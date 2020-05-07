/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.remoting.exchange.codec;

import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.StreamUtils;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferOutputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;
import com.alibaba.dubbo.remoting.telnet.codec.TelnetCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 * <p>
 * 该类继承了TelnetCodec，是信息交换编解码器。
 * dubbo将一条消息分成了协议头和协议体，用来解决粘包拆包问题，
 * 但是头跟体在编解码上有区别，我们先来看看dubbo 的协议头的配置
 * <p>
 * <p>
 * 那么在编解码中，协议头是通过 Codec 编解码，
 * 而body部分是用Serialization序列化和反序列化的。
 * <p>
 * 可以看到 MAGIC是个固定的值，用来判断是不是dubbo协议的数据包，
 * 并且MAGIC_LOW和MAGIC_HIGH分别是MAGIC的低位和高位。
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    protected static final int HEADER_LENGTH = 16;
    // magic header.
    /**
     * MAGIC二进制：1101 1010    1011 1011，十进制：55995
     */
    protected static final short MAGIC = (short) 0xdabb;
    /**
     * Magic High，也就是0-7位：1101 1010
     */
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    /**
     * Magic Low  8-15位 ：1011 1011
     */
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];


    // message flag.
    /**
     * 128 二进制：1000 0000
     */
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    /**
     * 64 二进制：0100 0000
     */
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    /**
     * 32 二进制：0010 0000
     */
    protected static final byte FLAG_EVENT = (byte) 0x20;
    /**
     * 31 二进制：0001 1111
     */
    protected static final int SERIALIZATION_MASK = 0x1f;


    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }


    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        if (msg instanceof Request) {
            // 如果消息是Request类型，对请求消息编码
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            // 如果消息是Response类型，对响应消息编码
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            // 直接让父类( Telnet ) 处理，目前是 Telnet 命令的结果。
            super.encode(channel, buffer, msg);
        }
    }

    /**
     * 该方法就是解码前的一些核对过程，包括检测是否为dubbo协议，是否有拆包现象等，具体的解码在decodeBody方法。
     *
     * @param channel
     * @param buffer
     * @return
     * @throws IOException
     */
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int readable = buffer.readableBytes();
        // 读取前16字节的协议头数据，如果数据不满16字节，则读取全部
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        buffer.readBytes(header);
        // 解码
        return decode(channel, buffer, readable, header);
    }

    /**
     * @param channel
     * @param buffer
     * @param readable
     * @param header
     * @return
     * @throws IOException
     */
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        // 核对魔数（该数字固定）
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            // 将 buffer 完全复制到 `header` 数组中
            if (header.length < readable) {
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            return super.decode(channel, buffer, readable, header);
        }

        // check length.
        // Header 长度不够，返回需要更多的输入，解决拆包现象
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.
        int len = Bytes.bytes2int(header, 12);
        // 检查信息头长度
        checkPayload(channel, len);

        int tt = len + HEADER_LENGTH;
        // 总长度不够，返回需要更多的输入，解决拆包现象
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            // 对body反序列化
            return decodeBody(channel, is, header);
        } finally {
            // 如果不可用
            if (is.available() > 0) {
                try {
                    // 打印错误日志
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    // 跳过未读完的流
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        // 用并运算符
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);

        // 反序列化
        Serialization s = CodecSupport.getSerialization(channel.getUrl(), proto);
        ObjectInput in = s.deserialize(channel.getUrl(), is);

        // get request id.
        // 获得请求id
        long id = Bytes.bytes2long(header, 4);
        // 如果第16位为0，则说明是响应
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            // 如果第18位不是0，则说明是心跳事件
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            // 如果响应是成功的
            if (status == Response.OK) {
                try {
                    Object data;
                    if (res.isHeartbeat()) {
                        // 如果是心跳事件，则心跳事件的解码
                        data = decodeHeartbeatData(channel, in);
                    } else if (res.isEvent()) {
                        // 如果是事件，则事件的解码
                        data = decodeEventData(channel, in);
                    } else {
                        // 否则执行普通解码
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    // 重新设置响应结果
                    res.setResult(data);
                } catch (Throwable t) {
                    res.setStatus(Response.CLIENT_ERROR);
                    res.setErrorMessage(StringUtils.toString(t));
                }
            } else {
                res.setErrorMessage(in.readUTF());
            }
            return res;
        } else {
            // decode request.
            // 对请求类型解码
            Request req = new Request(id);
            // 设置版本号
            req.setVersion("2.0.0");
            // 如果第17位不为0，则是双向
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);
            // 如果18位不为0，则是心跳事件
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }
            try {
                Object data;
                if (req.isHeartbeat()) {
                    // 如果请求是心跳事件，则心跳事件解码
                    data = decodeHeartbeatData(channel, in);
                } else if (req.isEvent()) {
                    // 如果是事件，则事件解码
                    data = decodeEventData(channel, in);
                } else {
                    // 否则，用普通解码
                    data = decodeRequestData(channel, in);
                }
                // 把重新设置请求数据
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                // 设置是异常请求
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null)
            return null;
        Request req = future.getRequest();
        if (req == null)
            return null;
        return req.getData();
    }

    /**
     * 该方法是对Request类型的消息进行编码
     * 也可以看到前半部分逻辑是对协议头的编码，
     * 后面还有对body值的序列化。
     *
     * @param channel
     * @param buffer
     * @param req
     * @throws IOException
     */
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        Serialization serialization = getSerialization(channel);
        // header.
        // 创建16字节的字节数组
        byte[] header = new byte[HEADER_LENGTH];

        // set magic number.
        // 设置前16位数据，也就是设置header[0]和header[1]的数据为Magic High和Magic Low
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        // 16-23位为serialization编号，用到或运算1000 0000|serialization编号，
        // 例如serialization编号为0001 1111，则为0001 1111
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        // 继续上面的例子，0001 1111 | 0100 0000 = 0101 1111
        if (req.isTwoWay()) header[2] |= FLAG_TWOWAY;

        // 继续上面的例子，0101 1111|0010 0000 = 0111 1111 可以看到0011代表请求标记、双向、是事件，
        // 这样就设置了16、17、18位，后面19-23位是Serialization 编号
        if (req.isEvent()) header[2] |= FLAG_EVENT;

        // set request id.
        // 设置32-95位请求id
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        // 编码 `Request.data` 到 Body ，并写入到 Buffer
        int savedWriteIndex = buffer.writerIndex();
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);

        //会写入到 Buffer
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);

        // 对body数据序列化
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        // 如果该请求是事件
        if (req.isEvent()) {
            // 特殊事件编码
            encodeEventData(channel, out, req.getData());
        } else {
            // 正常请求编码
            encodeRequestData(channel, out, req.getData());
        }
        // 释放资源
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        int len = bos.writtenBytes();
        //检验消息长度
        checkPayload(channel, len);

        // 设置96-127位：Body值
        Bytes.int2bytes(len, header, 12);

        // write
        buffer.writerIndex(savedWriteIndex);

        // write header.
        // 把header写入到buffer
        buffer.writeBytes(header);

        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    /**
     * 该方法是对Response类型的消息进行编码，
     * 过程跟encodeRequest类似。
     *
     * @param channel
     * @param buffer
     * @param res
     * @throws IOException
     */
    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            Serialization serialization = getSerialization(channel);
            // header.
            // 创建16字节大小的字节数组
            byte[] header = new byte[HEADER_LENGTH];

            // set magic number.
            // 设置前0-15位为魔数
            Bytes.short2bytes(MAGIC, header);

            // set request and serialization flag.
            // 设置响应标志和序列化id
            header[2] = serialization.getContentTypeId();

            // 如果是心跳事件，则设置第18位为事件
            if (res.isHeartbeat()) header[2] |= FLAG_EVENT;

            // set response status.
            // 设置24-31位为状态码
            byte status = res.getStatus();
            header[3] = status;

            // set request id.
            // 设置32-95位为请求id
            Bytes.long2bytes(res.getId(), header, 4);

            // 写入数据
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            // 对body进行序列化
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    // 对心跳事件编码
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    // 对普通响应编码
                    encodeResponseData(channel, out, res.getResult());
                }
            } else out.writeUTF(res.getErrorMessage());
            // 释放
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            int len = bos.writtenBytes();
            checkPayload(channel, len);
            Bytes.int2bytes(len, header, 12);
            // write
            buffer.writerIndex(savedWriteIndex);
            buffer.writeBytes(header); // write header.
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            //如果在写入数据失败，则返回响应格式错误的返回码
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        // 发送响应
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    @Deprecated
    protected Object decodeHeartbeatData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

}
