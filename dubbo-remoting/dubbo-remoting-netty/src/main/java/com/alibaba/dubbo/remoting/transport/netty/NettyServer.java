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
package com.alibaba.dubbo.remoting.transport.netty;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ExecutorUtil;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.transport.AbstractServer;
import com.alibaba.dubbo.remoting.transport.dispatcher.ChannelHandlers;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NettyServer
 * 该类继承了AbstractServer，实现了Server，是基于netty3实现的服务器类。
 */
public class NettyServer extends AbstractServer implements Server {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    /**
     * 连接该服务器的通道集合
     * <ip:port, channel>
     */
    private Map<String, Channel> channels;

    /**
     * 服务器引导类对象
     */
    private ServerBootstrap bootstrap;

    /**
     * 通道
     */
    private org.jboss.netty.channel.Channel channel;

    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        super(url, ChannelHandlers.wrap(handler, ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME)));
    }

    /**
     * 该方法是创建服务器，并且打开服务器。同样创建服务器的方式跟正常的用netty创建服务器方式一样，
     * 只是新加了编码器和解码器。还有一个注意点就是这里ServerBootstrap 的可选项。
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {
        // 设置日志工厂
        NettyHelper.setNettyLoggerFactory();
        // 创建线程池
        ExecutorService boss = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerBoss", true));
        ExecutorService worker = Executors.newCachedThreadPool(new NamedThreadFactory("NettyServerWorker", true));
        // 新建通道工厂
        ChannelFactory channelFactory = new NioServerSocketChannelFactory(boss, worker, getUrl().getPositiveParameter(Constants.IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS));
        // 新建服务引导类对象
        bootstrap = new ServerBootstrap(channelFactory);

        // 新建通道处理器
        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);
        // 获得通道集合
        channels = nettyHandler.getChannels();
        // https://issues.jboss.org/browse/NETTY-365
        // https://issues.jboss.org/browse/NETTY-379
        // final Timer timer = new HashedWheelTimer(new NamedThreadFactory("NettyIdleTimer", true));
        // 禁用nagle算法，将数据立即发送出去。纳格算法是以减少封包传送量来增进TCP/IP网络的效能

        // 设置管道工厂
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            /**
             * 获得通道
             * @return
             */
            public ChannelPipeline getPipeline() {
                // 新建编解码器
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                // 获得通道
                ChannelPipeline pipeline = Channels.pipeline();
                /*int idleTimeout = getIdleTimeout();
                if (idleTimeout > 10000) {
                    pipeline.addLast("timer", new IdleStateHandler(timer, idleTimeout / 1000, 0, 0));
                }*/
                // 设置解码器
                pipeline.addLast("decoder", adapter.getDecoder());
                // 设置编码器
                pipeline.addLast("encoder", adapter.getEncoder());
                // 设置通道处理器
                pipeline.addLast("handler", nettyHandler);
                // 返回通道
                return pipeline;
            }
        });

        // bind 绑定地址，也就是启用服务器
        channel = bootstrap.bind(getBindAddress());
    }

    /**
     * 该方法是关闭服务器。
     * @throws Throwable
     */
    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.关闭通道
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            // 获得所有连接该服务器的通道集合
            Collection<com.alibaba.dubbo.remoting.Channel> channels = getChannels();
            if (channels != null && !channels.isEmpty()) {
                // 遍历通道集合
                for (com.alibaba.dubbo.remoting.Channel channel : channels) {
                    try {
                        // 关闭通道连接
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (bootstrap != null) {
                // release external resource. 回收资源
                bootstrap.releaseExternalResources();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (channels != null) {
                // 清空集合
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    /**
     * 该方法是返回连接该服务器的通道集合，并且用了HashSet保存，不会重复。
     * @return
     */
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new HashSet<Channel>();
        for (Channel channel : this.channels.values()) {
            if (channel.isConnected()) {
                chs.add(channel);
            } else {
                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
            }
        }
        return chs;
    }

    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    public boolean isBound() {
        return channel.isBound();
    }

}