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
package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Codec2;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * AbstractCodec
 */
public abstract class AbstractCodec implements Codec2 {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCodec.class);

    /**
     * 检验消息长度。
     * @param channel
     * @param size
     * @throws IOException
     */
    protected static void checkPayload(Channel channel, long size) throws IOException {
        // 默认长度
        int payload = Constants.DEFAULT_PAYLOAD;
        if (channel != null && channel.getUrl() != null) {
            // 优先从url中获得消息长度配置，如果没有则用默认长度
            payload = channel.getUrl().getParameter(Constants.PAYLOAD_KEY, Constants.DEFAULT_PAYLOAD);
        }
        // 如果消息长度过长，则报错
        if (payload > 0 && size > payload) {
            ExceedPayloadLimitException e = new ExceedPayloadLimitException("Data length too large: " + size + ", max payload: " + payload + ", channel: " + channel);
            logger.error(e);
            throw e;
        }
    }

    /**
     * 获得序列化对象。
     * @param channel
     * @return
     */
    protected Serialization getSerialization(Channel channel) {
        return CodecSupport.getSerialization(channel.getUrl());
    }

    /**
     *判断是否为客户端侧的通道。
     * @param channel
     * @return
     */
    protected boolean isClientSide(Channel channel) {
        // 获得是side对应的value
        String side = (String) channel.getAttribute(Constants.SIDE_KEY);
        if ("client".equals(side)) {
            return true;
        } else if ("server".equals(side)) {
            return false;
        } else {
            InetSocketAddress address = channel.getRemoteAddress();
            URL url = channel.getUrl();
            // 判断url的主机地址是否和远程地址一样，如果是，则判断为client，如果不是，则判断为server
            boolean client = url.getPort() == address.getPort()
                    && NetUtils.filterLocalHost(url.getIp()).equals(
                    NetUtils.filterLocalHost(address.getAddress()
                            .getHostAddress()));
            // 把value设置进去
            channel.setAttribute(Constants.SIDE_KEY, client ? "client"
                    : "server");
            return client;
        }
    }

    /**
     * 否为服务端侧的通道。
     * @param channel
     * @return
     */
    protected boolean isServerSide(Channel channel) {
        return !isClientSide(channel);
    }

}
