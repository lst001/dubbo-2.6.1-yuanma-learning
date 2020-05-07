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

package com.alibaba.dubbo.remoting.buffer;

import java.nio.ByteBuffer;

/**
 * 该类实现了ChannelBufferFactory，该类就是基于 字节数组 来创建缓冲区的工厂。
 *
 * 该类利用了单例模式，其中的方法比较简单，就是调用了ChannelBuffers中的静态方法，
 *
 * 调用的方法实际上还是使用了HeapChannelBuffer中创建缓冲区的方法。
 */
public class HeapChannelBufferFactory implements ChannelBufferFactory {

    /**
     * 单例
     */
    private static final HeapChannelBufferFactory INSTANCE = new HeapChannelBufferFactory();

    public HeapChannelBufferFactory() {
        super();
    }

    public static ChannelBufferFactory getInstance() {
        return INSTANCE;
    }

    public ChannelBuffer getBuffer(int capacity) {
        // 创建一个capacity容量的缓冲区
        return ChannelBuffers.buffer(capacity);
    }

    public ChannelBuffer getBuffer(byte[] array, int offset, int length) {
        return ChannelBuffers.wrappedBuffer(array, offset, length);
    }

    public ChannelBuffer getBuffer(ByteBuffer nioBuffer) {
        // 判断该缓冲区是否有字节数组支持
        if (nioBuffer.hasArray()) {
            // 使用
            return ChannelBuffers.wrappedBuffer(nioBuffer);
        }
        // 创建一个nioBuffer剩余容量的缓冲区
        ChannelBuffer buf = getBuffer(nioBuffer.remaining());
        // 记录下nioBuffer的位置
        int pos = nioBuffer.position();
        // 写入数据到buf
        buf.writeBytes(nioBuffer);
        // 把nioBuffer的位置重置到pos
        nioBuffer.position(pos);
        return buf;
    }

}
