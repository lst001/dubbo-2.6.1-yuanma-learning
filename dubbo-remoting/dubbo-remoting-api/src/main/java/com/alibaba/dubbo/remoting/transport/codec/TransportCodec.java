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
package com.alibaba.dubbo.remoting.transport.codec;

import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferOutputStream;
import com.alibaba.dubbo.remoting.transport.AbstractCodec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * TransportCodec
 * 该类是传输编解码器，使用 Serialization 进行序列化/反序列化，直接编解码。
 */
public class TransportCodec extends AbstractCodec {

    public void encode(Channel channel, ChannelBuffer buffer, Object message) throws IOException {
        // 获得序列化的 ObjectOutput 对象
        OutputStream output = new ChannelBufferOutputStream(buffer);
        ObjectOutput objectOutput = getSerialization(channel).serialize(channel.getUrl(), output);
        // 写入 ObjectOutput
        encodeData(channel, objectOutput, message);
        objectOutput.flushBuffer();
        // 释放
        if (objectOutput instanceof Cleanable) {
            ((Cleanable) objectOutput).cleanup();
        }
    }

    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        // 获得反序列化的 ObjectInput 对象
        InputStream input = new ChannelBufferInputStream(buffer);
        // 读取 ObjectInput
        ObjectInput objectInput = getSerialization(channel).deserialize(channel.getUrl(), input);
        Object object = decodeData(channel, objectInput);
        // 释放
        if (objectInput instanceof Cleanable) {
            ((Cleanable) objectInput).cleanup();
        }
        return object;
    }

    protected void encodeData(Channel channel, ObjectOutput output, Object message) throws IOException {
        encodeData(output, message);
    }

    protected Object decodeData(Channel channel, ObjectInput input) throws IOException {
        return decodeData(input);
    }

    protected void encodeData(ObjectOutput output, Object message) throws IOException {
        output.writeObject(message);
    }

    protected Object decodeData(ObjectInput input) throws IOException {
        try {
            return input.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("ClassNotFoundException: " + StringUtils.toString(e));
        }
    }
}