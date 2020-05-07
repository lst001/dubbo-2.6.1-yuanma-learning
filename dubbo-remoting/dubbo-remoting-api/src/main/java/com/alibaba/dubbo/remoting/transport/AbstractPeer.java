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
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Endpoint;
import com.alibaba.dubbo.remoting.RemotingException;

/**
 * AbstractPeer
 */
public abstract class AbstractPeer implements Endpoint, ChannelHandler {

    //用于装饰模式
    private final ChannelHandler handler;

    //该端的Url参数
    private volatile URL url;

    //是否正在关闭
    // closing closed means the process is being closed and close is finished
    private volatile boolean closing;


    //是否关闭完成
    private volatile boolean closed;

    //装饰模式，这样做可以对装饰对象灵活的增强功能 (只是维护了通道的正在关闭和关闭完成两个状态)
    public AbstractPeer(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    /**
     * 1 sent值为true，等待消息发出，消息发送失败将抛出异常
     * 2 sent值为false，不等待消息发出，将消息放入 IO 队列，即刻返回。
     * @param message
     * @throws RemotingException
     */
    public void send(Object message) throws RemotingException {

        // url中sent的配置项
        send(message, url.getParameter(Constants.SENT_KEY, false));
    }

    public void close() {
        closed = true;
    }

    public void close(int timeout) {
        close();
    }

    public void startClose() {
        if (isClosed()) {
            return;
        }
        closing = true;
    }

    public URL getUrl() {
        return url;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        this.url = url;
    }

    public ChannelHandler getChannelHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }

    /**
     * @return ChannelHandler
     */
    @Deprecated
    public ChannelHandler getHandler() {
        return getDelegateHandler();
    }

    /**
     * Return the final handler (which may have been wrapped). This method should be distinguished with getChannelHandler() method
     *
     * @return ChannelHandler
     */
    public ChannelHandler getDelegateHandler() {
        return handler;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isClosing() {
        return closing && !closed;
    }


    /**
     * 以下是ChannelHandler的接口
     * @param ch
     * @throws RemotingException
     */
    public void connected(Channel ch) throws RemotingException {
        if (closed) {
            return;
        }
        handler.connected(ch);
    }

    public void disconnected(Channel ch) throws RemotingException {
        handler.disconnected(ch);
    }

    public void sent(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        handler.sent(ch, msg);
    }

    public void received(Channel ch, Object msg) throws RemotingException {
        if (closed) {
            return;
        }
        handler.received(ch, msg);
    }

    public void caught(Channel ch, Throwable ex) throws RemotingException {
        handler.caught(ch, ex);
    }
}