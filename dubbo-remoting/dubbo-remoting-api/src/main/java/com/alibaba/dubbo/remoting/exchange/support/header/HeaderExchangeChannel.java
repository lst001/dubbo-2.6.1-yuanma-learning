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
package com.alibaba.dubbo.remoting.exchange.support.header;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;

import java.net.InetSocketAddress;

/**
 * ExchangeReceiver
 * 该类实现了ExchangeChannel，是基于 协议头 的信息交换通道。
 * <p>
 * <p>
 * 这个类的属性是因为该类中有channel属性，
 * 也就是说HeaderExchangeChannel是Channel的装饰器，每个实现方法都会调用channel的方法。
 */
final class HeaderExchangeChannel implements ExchangeChannel {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeChannel.class);

    /**
     * 通道的key值
     */
    private static final String CHANNEL_KEY = HeaderExchangeChannel.class.getName() + ".CHANNEL";

    /**
     * 通道
     */
    private final Channel channel;

    /**
     * 是否关闭
     */
    private volatile boolean closed = false;

    HeaderExchangeChannel(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel == null");
        }
        this.channel = channel;
    }

    /**
     * 该静态方法做了HeaderExchangeChannel的创建和销毁，
     * 并且生命周期随channel销毁而销毁。
     *
     * @param ch
     * @return
     */
    static HeaderExchangeChannel getOrAddChannel(Channel ch) {
        if (ch == null) {
            return null;
        }
        // 获得通道中的HeaderExchangeChannel
        HeaderExchangeChannel ret = (HeaderExchangeChannel) ch.getAttribute(CHANNEL_KEY);
        if (ret == null) {
            // 创建一个HeaderExchangeChannel实例
            ret = new HeaderExchangeChannel(ch);
            // 如果通道连接
            if (ch.isConnected()) {
                // 加入属性值
                ch.setAttribute(CHANNEL_KEY, ret);
            }
        }
        return ret;
    }

    static void removeChannelIfDisconnected(Channel ch) {
        // 如果通道断开连接
        if (ch != null && !ch.isConnected()) {
            // 移除属性值
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    public void send(Object message) throws RemotingException {
        send(message, getUrl().getParameter(Constants.SENT_KEY, false));
    }

    /**
     * 该方法是在channel的send方法上加上了request和response模型(包装message)，
     * 最后再调用channel.send，起到了装饰器的作用（装饰nettyChannel）。
     * @param message
     * @param sent    already sent to socket?
     * @throws RemotingException
     */
    public void send(Object message, boolean sent) throws RemotingException {
        // 如果通道关闭，抛出异常
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The channel " + this + " is closed!");
        }
        // 判断消息的类型
        if (message instanceof Request
                || message instanceof Response
                || message instanceof String) {
            // 发送消息
            channel.send(message, sent);

        } else {
            // 新建一个request实例
            Request request = new Request();
            // 设置信息的版本
            request.setVersion("2.0.0");
            // 该请求不需要响应
            request.setTwoWay(false);
            // 把消息传入
            request.setData(message);
            // 发送消息
            channel.send(request, sent);
        }
    }

    public ResponseFuture request(Object request) throws RemotingException {
        return request(request, channel.getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT));
    }

    /**
     * 该方法是请求方法，用Request模型把请求内容装饰起来，
     * 然后发送一个Request类型的消息，并且返回DefaultFuture实例。
     * @param request
     * @param timeout
     * @return
     * @throws RemotingException
     */
    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        // 如果通道关闭，则抛出异常
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send request " + request + ", cause: The channel " + this + " is closed!");
        }
        // create request.创建请求
        Request req = new Request();
        // 设置版本号
        req.setVersion("2.0.0");
        // 设置需要响应
        req.setTwoWay(true);
        // 把请求数据传入
        req.setData(request);
        // 创建DefaultFuture对象，可以从future中主动获得请求对应的响应信息
        DefaultFuture future = new DefaultFuture(channel, req, timeout);
        try {
            // 发送请求消息
            channel.send(req);
        } catch (RemotingException e) {
            future.cancel();
            throw e;
        }
        return future;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        try {
            channel.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    // graceful close
    public void close(int timeout) {
        if (closed) {
            return;
        }
        closed = true;
        if (timeout > 0) {
            long start = System.currentTimeMillis();
            while (DefaultFuture.hasFuture(channel)
                    && System.currentTimeMillis() - start < timeout) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        close();
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    public URL getUrl() {
        return channel.getUrl();
    }

    public boolean isConnected() {
        return channel.isConnected();
    }

    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    public ExchangeHandler getExchangeHandler() {
        return (ExchangeHandler) channel.getChannelHandler();
    }

    public Object getAttribute(String key) {
        return channel.getAttribute(key);
    }

    public void setAttribute(String key, Object value) {
        channel.setAttribute(key, value);
    }

    public void removeAttribute(String key) {
        channel.removeAttribute(key);
    }

    public boolean hasAttribute(String key) {
        return channel.hasAttribute(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((channel == null) ? 0 : channel.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        HeaderExchangeChannel other = (HeaderExchangeChannel) obj;
        if (channel == null) {
            if (other.channel != null) return false;
        } else if (!channel.equals(other.channel)) return false;
        return true;
    }

    @Override
    public String toString() {
        return channel.toString();
    }

}