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
package com.alibaba.dubbo.remoting.transport.netty4;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.Codec2;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.IOException;
import java.util.List;

/**
 * NettyCodecAdapter.
 * 该类是基于netty4的编解码器。
 * 属性跟基于netty3实现的编解码一样。
 */
final class NettyCodecAdapter {

    /**
     * 编码器
     */
    private final ChannelHandler encoder = new InternalEncoder();

    /**
     * 解码器
     */
    private final ChannelHandler decoder = new InternalDecoder();

    /**
     * 编解码器
     */
    private final Codec2 codec;

    /**
     * url对象
     */
    private final URL url;

    /**
     * 通道处理器
     */
    private final com.alibaba.dubbo.remoting.ChannelHandler handler;

    public NettyCodecAdapter(Codec2 codec, URL url, com.alibaba.dubbo.remoting.ChannelHandler handler) {
        this.codec = codec;
        this.url = url;
        this.handler = handler;
    }

    public ChannelHandler getEncoder() {
        return encoder;
    }

    public ChannelHandler getDecoder() {
        return decoder;
    }


    /**
     * 该内部类是编码器的抽象，主要的编码还是调用了codec.encode。
     */
    private class InternalEncoder extends MessageToByteEncoder {


        protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {

            // 创建缓冲区
            com.alibaba.dubbo.remoting.buffer.ChannelBuffer buffer = new NettyBackedChannelBuffer(out);
            // 获得通道
            Channel ch = ctx.channel();
            // 获得netty通道
            NettyChannel channel = NettyChannel.getOrAddChannel(ch, url, handler);
            try {
                // 编码
                codec.encode(channel, buffer, msg);
            } finally {
                // 检测通道是否活跃
                NettyChannel.removeChannelIfDisconnected(ch);
            }
        }
    }


    /**
     * 该内部类是解码器的抽象类，其中关键的是调用了codec.decode。
     */
    private class InternalDecoder extends ByteToMessageDecoder {

        protected void decode(ChannelHandlerContext ctx, ByteBuf input, List<Object> out) throws Exception {

            // 创建缓冲区
            ChannelBuffer message = new NettyBackedChannelBuffer(input);
            // 获得通道
            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);

            Object msg;

            int saveReaderIndex;

            try {
                // decode object.
                do {
                    // 记录读索引
                    saveReaderIndex = message.readerIndex();
                    try {
                        msg = codec.decode(channel, message);
                    } catch (IOException e) {
                        throw e;
                    }
                    // 拆包
                    if (msg == Codec2.DecodeResult.NEED_MORE_INPUT) {
                        message.readerIndex(saveReaderIndex);
                        break;
                    } else {
                        //is it possible to go here ?
                        if (saveReaderIndex == message.readerIndex()) {
                            throw new IOException("Decode without read data.");
                        }
                        // 读取数据
                        if (msg != null) {
                            out.add(msg);
                        }
                    }
                } while (message.readable());
            } finally {
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
            }
        }
    }
}