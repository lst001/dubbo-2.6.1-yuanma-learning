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

import java.io.IOException;
import java.io.OutputStream;

/**
 * 该类继承了OutputStream
 * <p>
 * 该类中包装了一个缓冲区对象和startIndex，startIndex是记录开始写入的索引。
 */
public class ChannelBufferOutputStream extends OutputStream {

    /**
     * 缓冲区
     */
    private final ChannelBuffer buffer;

    /**
     * 记录开始写入的索引
     */
    private final int startIndex;

    public ChannelBufferOutputStream(ChannelBuffer buffer) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        this.buffer = buffer;
        // 把开始写入数据的索引记录下来
        startIndex = buffer.writerIndex();
    }

    /**
     * 该方法是返回写入了多少数据。
     *
     * @return
     */
    public int writtenBytes() {
        return buffer.writerIndex() - startIndex;
    }


    /**
     * 该类里面还有write方法，都是调用了buffer.writeBytes。
     *
     * @param b
     * @param off
     * @param len
     * @throws IOException
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return;
        }

        buffer.writeBytes(b, off, len);
    }

    @Override
    public void write(byte[] b) throws IOException {
        buffer.writeBytes(b);
    }

    @Override
    public void write(int b) throws IOException {
        buffer.writeByte((byte) b);
    }

    public ChannelBuffer buffer() {
        return buffer;
    }
}
