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
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Client;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * DefaultMessageClient
 * <p>
 * 该类实现了ExchangeClient接口，是基于协议头的信息交互客户端类，同样它是Client、Channel的适配器。(装饰器)
 * 在该类的源码中可以看到所有的实现方法都是调用了client和channel属性的方法。
 * 该类主要的作用就是增加了心跳功能，为什么要增加心跳功能呢，对于长连接，一些拔网线等物理层的断开，
 * 会导致TCP的FIN消息来不及发送，对方收不到断开事件，那么就需要用到发送心跳包来检测连接是否断开。
 * consumer和provider断开，处理措施不一样，会分别做出重连和关闭通道的操作。
 * <p>
 * startHeartbeatTimer
 * stopHeartbeatTimer
 * 其他方法都是调用channel和client属性的方法。
 */
public class HeaderExchangeClient implements ExchangeClient {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeClient.class);

    /**
     * 定时器线程池
     */
    private static final ScheduledThreadPoolExecutor scheduled = new ScheduledThreadPoolExecutor(2, new NamedThreadFactory("dubbo-remoting-client-heartbeat", true));

    /**
     * 客户端(被装饰的client)
     */
    private final Client client;

    /**
     * 信息交换通道
     */
    private final ExchangeChannel channel;

    /**
     * 心跳定时器
     */
    private ScheduledFuture<?> heartbeatTimer;

    /**
     * 心跳周期，间隔多久发送心跳消息检测一次
     */
    private int heartbeat;

    /**
     * 心跳超时时间
     */
    private int heartbeatTimeout;

    /**
     * 构造函数就是对一些属性初始化设置，优先从url中获取。
     * 心跳超时时间小于心跳周期的两倍就抛出异常，意思就是至少重试两次心跳检测。
     *
     * @param client
     * @param needHeartbeat
     */
    public HeaderExchangeClient(Client client, boolean needHeartbeat) {
        if (client == null) {
            throw new IllegalArgumentException("client == null");
        }
        this.client = client;
        // 创建信息交换通道
        this.channel = new HeaderExchangeChannel(client);
        // 获得dubbo版本
        String dubbo = client.getUrl().getParameter(Constants.DUBBO_VERSION_KEY);
        //获得心跳周期配置，如果没有配置，并且dubbo是1.0版本的，则这只为1分钟，否则设置为0
        this.heartbeat = client.getUrl().getParameter(Constants.HEARTBEAT_KEY, dubbo != null && dubbo.startsWith("1.0.") ? Constants.DEFAULT_HEARTBEAT : 0);
        // 获得心跳超时配置，默认是心跳周期的三倍
        this.heartbeatTimeout = client.getUrl().getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, heartbeat * 3);
        // 如果心跳超时时间小于心跳周期的两倍，则抛出异常
        if (heartbeatTimeout < heartbeat * 2) {
            throw new IllegalStateException("heartbeatTimeout < heartbeatInterval * 2");
        }
        if (needHeartbeat) {
            // 开启心跳
            startHeartbeatTimer();
        }
    }

    public ResponseFuture request(Object request) throws RemotingException {
        return channel.request(request);
    }

    public URL getUrl() {
        return channel.getUrl();
    }

    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    public ResponseFuture request(Object request, int timeout) throws RemotingException {
        return channel.request(request, timeout);
    }

    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    public boolean isConnected() {
        return channel.isConnected();
    }

    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    public ExchangeHandler getExchangeHandler() {
        return channel.getExchangeHandler();
    }

    public void send(Object message) throws RemotingException {
        channel.send(message);
    }

    public void send(Object message, boolean sent) throws RemotingException {
        channel.send(message, sent);
    }

    public boolean isClosed() {
        return channel.isClosed();
    }

    public void close() {
        doClose();
        channel.close();
    }

    public void close(int timeout) {
        // Mark the client into the closure process
        startClose();
        doClose();
        channel.close(timeout);
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    public void reset(URL url) {
        client.reset(url);
    }

    @Deprecated
    public void reset(com.alibaba.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    public void reconnect() throws RemotingException {
        client.reconnect();
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

    /**
     * 该方法就是开启心跳。利用心跳定时器来做到定时检测心跳。
     * 因为这是信息交换客户端类，所有这里的只是返回包含HeaderExchangeClient对象的不可变列表，
     * 因为客户端跟channel是一一对应的，只有这一个该客户端本身的channel需要心跳。
     */
    private void startHeartbeatTimer() {
        stopHeartbeatTimer();
        if (heartbeat > 0) {
            heartbeatTimer = scheduled.scheduleWithFixedDelay(
                    new HeartBeatTask(new HeartBeatTask.ChannelProvider() {
                        public Collection<Channel> getChannels() {
                            return Collections.<Channel>singletonList(HeaderExchangeClient.this);
                        }
                    }, heartbeat, heartbeatTimeout),
                    heartbeat, heartbeat, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 该方法是停止现有心跳，也就是停止定时器，释放空间。
     */
    private void stopHeartbeatTimer() {
        if (heartbeatTimer != null && !heartbeatTimer.isCancelled()) {
            try {
                // 取消定时器
                heartbeatTimer.cancel(true);
                // 取消大量已排队任务，用于回收空间
                scheduled.purge();
            } catch (Throwable e) {
                if (logger.isWarnEnabled()) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        heartbeatTimer = null;
    }

    private void doClose() {
        stopHeartbeatTimer();
    }

    @Override
    public String toString() {
        return "HeaderExchangeClient [channel=" + channel + "]";
    }
}