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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExchangeServerImpl
 * <p>
 * 该类实现了ExchangeServer接口，是基于协议头的信息交换服务器实现类，
 * HeaderExchangeServer是Server的装饰器，每个实现方法都会调用server的方法。
 * <p>
 * 该类里面的很多实现跟HeaderExchangeClient差不多，包括心跳检测等逻辑。
 */
public class HeaderExchangeServer implements ExchangeServer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 信息交换服务器中的线程池
     */
    private final ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(1,
            new NamedThreadFactory(
                    "dubbo-remoting-server-heartbeat",
                    true));
    /**
     * 服务器（被装饰者 nettyServer对象）
     */
    private final Server server;

    // heartbeat timer
    /**
     * 心跳定时器
     */
    private ScheduledFuture<?> heatbeatTimer;


    // heartbeat timeout (ms), default value is 0 , won't execute a heartbeat.
    /**
     * 心跳周期
     */
    private int heartbeat;

    /**
     * 心跳超时时间
     */
    private int heartbeatTimeout;

    /**
     * 信息交换服务器是否关闭
     */
    private AtomicBoolean closed = new AtomicBoolean(false);


    /**
     * 步骤2)new HeaderExchangeServer(Server server)
     * 构造函数就是对属性的设置,心跳的机制以及默认值都跟HeaderExchangeClient中的一模一样
     *
     * @param server
     */
    public HeaderExchangeServer(Server server) {
        if (server == null) {
            throw new IllegalArgumentException("server == null");
        }
        this.server = server;
        //获得心跳周期配置,如果没有配置,默认设置为0
        this.heartbeat = server.getUrl().getParameter(Constants.HEARTBEAT_KEY, 0);
        // 获得心跳超时配置,默认是心跳周期的三倍
        this.heartbeatTimeout = server.getUrl().getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, heartbeat * 3);
        // 如果心跳超时时间小于心跳周期的两倍,则抛出异常
        if (heartbeatTimeout < heartbeat * 2) {
            throw new IllegalStateException("heartbeatTimeout < heartbeatInterval * 2");
        }
        // 开始心跳
        startHeatbeatTimer();
    }

    public Server getServer() {
        return server;
    }

    public boolean isClosed() {
        return server.isClosed();
    }

    /**
     * 该方法是检测服务器是否还运行，只要有一个客户端连接着，就算服务器运行着。
     *
     * @return
     */
    private boolean isRunning() {
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            if (DefaultFuture.hasFuture(channel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 两个close方法，第二个close方法是优雅的关闭，有一定的延时来让一些响应或者操作做完。
     * 关闭分两个步骤，第一个就是关闭信息交换服务器中的线程池和心跳检测，然后才是关闭服务器。
     */
    public void close() {
        // 关闭线程池和心跳检测
        doClose();
        // 关闭服务器
        server.close();
    }

    public void close(final int timeout) {
        // 开始关闭
        startClose();
        if (timeout > 0) {
            final long max = (long) timeout;
            final long start = System.currentTimeMillis();
            if (getUrl().getParameter(Constants.CHANNEL_SEND_READONLYEVENT_KEY, true)) {
                // 发送 READONLY_EVENT事件给所有连接该服务器的客户端，表示 Server 不可读了。
                sendChannelReadOnlyEvent();
            }
            // 当服务器还在运行，并且没有超时，睡眠，也就是等待timeout左右时间在进行关闭
            while (HeaderExchangeServer.this.isRunning()
                    && System.currentTimeMillis() - start < max) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        // 关闭线程池和心跳检测
        doClose();
        // 延迟关闭
        server.close(timeout);
    }

    @Override
    public void startClose() {
        server.startClose();
    }

    /**
     * 在关闭服务器中有一个操作就是发送事件READONLY_EVENT，告诉客户端该服务器不可读了，
     * 就是该方法实现的，逐个通知连接的客户端该事件。
     */
    private void sendChannelReadOnlyEvent() {
        // 创建一个READONLY_EVENT事件的请求
        Request request = new Request();
        request.setEvent(Request.READONLY_EVENT);
        // 不需要响应
        request.setTwoWay(false);
        request.setVersion(Version.getVersion());

        Collection<Channel> channels = getChannels();
        // 遍历连接的通道，进行通知
        for (Channel channel : channels) {
            try {
                // 通过通道还连接着，则发送通知
                if (channel.isConnected())
                    channel.send(request, getUrl().getParameter(Constants.CHANNEL_READONLYEVENT_SENT_KEY, true));
            } catch (RemotingException e) {
                logger.warn("send connot write messge error.", e);
            }
        }
    }

    /**
     * 关闭线程池和心跳检测
     */
    private void doClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 停止心跳检测
        stopHeartbeatTimer();
        try {
            // 关闭线程池
            scheduled.shutdown();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * 该方法是返回连接该服务器信息交换通道集合。
     * 逻辑就是先获得通道集合，在根据通道来创建信息交换通道，然后返回信息通道集合。
     *
     * @return
     */
    public Collection<ExchangeChannel> getExchangeChannels() {
        Collection<ExchangeChannel> exchangeChannels = new ArrayList<ExchangeChannel>();
        // 获得连接该服务器通道集合
        Collection<Channel> channels = server.getChannels();
        if (channels != null && !channels.isEmpty()) {
            for (Channel channel : channels) {
                // 遍历通道集合，为每个通道都创建信息交换通道，并且加入信息交换通道集合
                exchangeChannels.add(HeaderExchangeChannel.getOrAddChannel(channel));
            }
        }
        return exchangeChannels;
    }

    public ExchangeChannel getExchangeChannel(InetSocketAddress remoteAddress) {
        Channel channel = server.getChannel(remoteAddress);
        return HeaderExchangeChannel.getOrAddChannel(channel);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Collection<Channel> getChannels() {
        return (Collection) getExchangeChannels();
    }

    public Channel getChannel(InetSocketAddress remoteAddress) {
        return getExchangeChannel(remoteAddress);
    }

    public boolean isBound() {
        return server.isBound();
    }

    public InetSocketAddress getLocalAddress() {
        return server.getLocalAddress();
    }

    public URL getUrl() {
        return server.getUrl();
    }

    public ChannelHandler getChannelHandler() {
        return server.getChannelHandler();
    }

    /**
     * 该方法就是重置属性，重置后，重新开始心跳，设置心跳属性的机制跟构造函数一样。
     *
     * @param url
     */
    public void reset(URL url) {
        // 重置属性
        server.reset(url);
        try {
            // 重置的逻辑跟构造函数一样设置
            if (url.hasParameter(Constants.HEARTBEAT_KEY)
                    || url.hasParameter(Constants.HEARTBEAT_TIMEOUT_KEY)) {
                int h = url.getParameter(Constants.HEARTBEAT_KEY, heartbeat);
                int t = url.getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, h * 3);
                if (t < h * 2) {
                    throw new IllegalStateException("heartbeatTimeout < heartbeatInterval * 2");
                }
                if (h != heartbeat || t != heartbeatTimeout) {
                    heartbeat = h;
                    heartbeatTimeout = t;
                    // 重新开始心跳
                    startHeatbeatTimer();
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Deprecated
    public void reset(com.alibaba.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    public void send(Object message) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message);
    }

    public void send(Object message, boolean sent) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message, sent);
    }

    /**
     * 该方法是开始心跳,跟HeaderExchangeClient类中的开始心跳方法唯一区别是获得的通道不一样,
     * 客户端跟通道是一一对应的,所有只要对一个通道进行心跳检测,而服务端跟通道是一对多的关系,
     * 所有需要对该服务器连接的所有通道进行心跳检测
     */
    private void startHeatbeatTimer() {
        // 先停止现有的心跳检测
        stopHeartbeatTimer();
        if (heartbeat > 0) {
            // 创建心跳定时器
            heatbeatTimer = scheduled.scheduleWithFixedDelay(
                    new HeartBeatTask(new HeartBeatTask.ChannelProvider() {
                        public Collection<Channel> getChannels() {
                            // 返回一个不可修改的连接该服务器的信息交换通道集合
                            return Collections.unmodifiableCollection(
                                    HeaderExchangeServer.this.getChannels());
                        }
                    }, heartbeat, heartbeatTimeout),
                    heartbeat, heartbeat, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 该方法是停止当前的心跳检测。
     */
    private void stopHeartbeatTimer() {
        try {
            // 取消定时器
            ScheduledFuture<?> timer = heatbeatTimer;
            if (timer != null && !timer.isCancelled()) {
                // 取消大量已排队任务，用于回收空间
                timer.cancel(true);
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        } finally {
            heatbeatTimer = null;
        }
    }

}