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
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractClient;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * NettyClient.
 * <p>
 * 该类继承了AbstractClient，是基于netty3实现的客户端类。
 */
public class NettyClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(NettyClient.class);

    // ChannelFactory's closure has a DirectMemory leak, using static to avoid
    // https://issues.jboss.org/browse/NETTY-424
    /**
     * 通道工厂，用static来避免直接缓存区的一个OOM问题
     * ChannelFactory用了static修饰，为了避免netty3中会有直接缓冲内存泄漏的现象
     */
    private static final ChannelFactory channelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(new NamedThreadFactory("NettyClientBoss", true)),
            Executors.newCachedThreadPool(new NamedThreadFactory("NettyClientWorker", true)),
            Constants.DEFAULT_IO_THREADS);

    /**
     * 客户端引导对象
     */
    private ClientBootstrap bootstrap;

    /**
     * volatile, please copy reference to use
     * 通道
     */
    private volatile Channel channel;

    public NettyClient(final URL url, final ChannelHandler handler) throws RemotingException {
        super(url, wrapChannelHandler(url, handler));
    }

    /**
     * 该方法是创建客户端，并且打开，其中的逻辑就是用netty3的客户端引导类来创建一个客户端，
     * nettyClient启动
     *
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {
        // 设置日志工厂
        NettyHelper.setNettyLoggerFactory();
        // 实例化客户端引导类
        bootstrap = new ClientBootstrap(channelFactory);

        // config
        // @see org.jboss.netty.channel.socket.SocketChannelConfig

        // 配置选择项
        bootstrap.setOption("keepAlive", true);
        bootstrap.setOption("tcpNoDelay", true);
        bootstrap.setOption("connectTimeoutMillis", getTimeout());
        // 创建通道处理器
        final NettyHandler nettyHandler = new NettyHandler(getUrl(), this);
        // 设置责任链路
        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            /**
             * 获得通道
             * @return
             */
            public ChannelPipeline getPipeline() {
                // 新建编解码
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyClient.this);
                // 获得管道
                ChannelPipeline pipeline = Channels.pipeline();
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
    }

    /**
     * 该方法是客户端连接服务器的方法。其中调用了bootstrap.connect。
     * 后面的逻辑是用来检测是否连接，最后如果未连接，则会取消该连接任务。
     *
     * @throws Throwable
     */
    protected void doConnect() throws Throwable {
        long start = System.currentTimeMillis();
        // 用引导类连接
        ChannelFuture future = bootstrap.connect(getConnectAddress());
        try {
            // 在超时时间内是否连接完成
            boolean ret = future.awaitUninterruptibly(getConnectTimeout(), TimeUnit.MILLISECONDS);

            if (ret && future.isSuccess()) {
                // 获得通道
                Channel newChannel = future.getChannel();
                // 异步修改此通道
                newChannel.setInterestOps(Channel.OP_READ_WRITE);
                try {
                    //Close old channel 关闭旧的通道
                    Channel oldChannel = NettyClient.this.channel; // copy reference
                    if (oldChannel != null) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close old netty channel " + oldChannel + " on create new netty channel " + newChannel);
                            }
                            // 关闭
                            oldChannel.close();
                        } finally {
                            // 移除通道
                            NettyChannel.removeChannelIfDisconnected(oldChannel);
                        }
                    }
                } finally {
                    // 如果客户端关闭
                    if (NettyClient.this.isClosed()) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close new netty channel " + newChannel + ", because the client closed.");
                            }
                            // 关闭通道
                            newChannel.close();
                        } finally {
                            NettyClient.this.channel = null;
                            NettyChannel.removeChannelIfDisconnected(newChannel);
                        }
                    } else {
                        NettyClient.this.channel = newChannel;
                    }
                }
            } else if (future.getCause() != null) {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + ", error message is:" + future.getCause().getMessage(), future.getCause());
            } else {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + " client-side timeout "
                        + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start) + "ms) from netty client "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion());
            }
        } finally {
            // 如果客户端没有连接
            if (!isConnected()) {
                // 取消future
                future.cancel();
            }
        }
    }

    @Override
    protected void doDisConnect() throws Throwable {
        try {
            NettyChannel.removeChannelIfDisconnected(channel);
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }
    }

    /**
     * 在这里不能关闭是因为channelFactory 是静态属性，被多个 NettyClient 共用。
     * 所以不能释放资源。
     *
     * @throws Throwable
     */
    @Override
    protected void doClose() throws Throwable {
        /*try {
            bootstrap.releaseExternalResources();
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }*/
    }

    @Override
    protected com.alibaba.dubbo.remoting.Channel getChannel() {
        Channel c = channel;
        if (c == null || !c.isConnected())
            return null;
        return NettyChannel.getOrAddChannel(c, getUrl(), this);
    }

}