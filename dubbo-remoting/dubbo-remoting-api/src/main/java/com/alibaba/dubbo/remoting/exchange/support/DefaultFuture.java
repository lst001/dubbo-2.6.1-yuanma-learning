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
package com.alibaba.dubbo.remoting.exchange.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DefaultFuture.
 * 该类实现了ResponseFuture接口，其中封装了处理响应的逻辑。
 * 你可以把DefaultFuture看成是一个中介，买房和卖房都通过这个中介进行沟通，
 * 中介拥有着买房者的信息request和卖房者的信息response，并且促成他们之间的买卖。
 * <p>
 * <p>
 * 属性 ：
 * 可以看到，该类的属性包含了request、response、channel三个实例，
 * 在该类中，把请求和响应通过唯一的id一一对应起来。做到异步处理返回结果时能给准确的返回给对应的请求。
 * 可以看到属性中有两个集合，注意是静态集合，分别是通道集合和future集合，
 * 也就是该类本身也是所有 DefaultFuture 的管理容器。
 */
public class DefaultFuture implements ResponseFuture {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFuture.class);

    /**
     * 通道集合
     */
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<Long, Channel>();

    /**
     * Future集合，key为请求编号
     */
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<Long, DefaultFuture>();

    /**
     * 开启一个后台线程进行扫描的逻辑写在了静态代码块里面，只开启一次。
     */
    static {
        // 开启一个后台扫描调用超时任务
        Thread th = new Thread(new RemotingInvocationTimeoutScan(), "DubboResponseTimeoutScanTimer");
        th.setDaemon(true);
        th.start();
    }

    // invoke id.
    /**
     * 请求编号
     */
    private final long id;

    /**
     * 通道
     */
    private final Channel channel;

    /**
     * 请求
     */
    private final Request request;
    /**
     * 超时
     */
    private final int timeout;
    /**
     * 锁
     */
    private final Lock lock = new ReentrantLock();

    /**
     * 完成情况，控制多线程的休眠与唤醒
     */
    private final Condition done = lock.newCondition();

    /**
     * 创建开始时间
     */
    private final long start = System.currentTimeMillis();
    /**
     * 发送请求时间(记录发送的时间)
     */
    private volatile long sent;
    /**
     * 响应结果（类似futureTask中的callable的call接口的返回V值）
     */
    private volatile Response response;
    /**
     * 回调
     */
    private volatile ResponseCallback callback;


    /**
     * 每一个DefaultFuture实例都跟每一个请求一一对应，
     * 被存入到集合中管理起来。
     *
     * @param channel
     * @param request
     * @param timeout
     */
    public DefaultFuture(Channel channel, Request request, int timeout) {
        this.channel = channel;
        this.request = request;
        // 设置请求编号
        this.id = request.getId();
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
        // put into waiting map.
        // 加入到等待集合中(两个静态管理的集合)
        FUTURES.put(id, this);
        CHANNELS.put(id, channel);
    }

    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    public static void sent(Channel channel, Request request) {
        DefaultFuture future = FUTURES.get(request.getId());
        if (future != null) {
            future.doSent();
        }
    }

    /**
     * 静态方法
     * <p>
     * 该方法是接收响应，也就是某个请求得到了响应，那么代表这次请求任务完成，
     * 所有需要把future从集合中移除。具体的接收响应结果在doReceived方法中实现。
     *
     * @param channel
     * @param response
     */
    public static void received(Channel channel, Response response) {
        try {
            // future集合中移除该请求的future，（响应id和请求id一一对应的）
            DefaultFuture future = FUTURES.remove(response.getId());
            if (future != null) {
                // 接收响应结果
                future.doReceived(response);
            } else {
                logger.warn("The timeout response finally returned at "
                        + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
                        + ", response " + response
                        + (channel == null ? "" : ", channel: " + channel.getLocalAddress()
                        + " -> " + channel.getRemoteAddress()));
            }
        } finally {
            // 通道集合移除该请求对应的通道，代表着这一次请求结束
            CHANNELS.remove(response.getId());
        }
    }

    public Object get() throws RemotingException {
        return get(timeout);
    }

    /**
     * 该方法是实现了ResponseFuture定义的方法，是获得该future对应的请求对应的响应结果，
     * 其实future、请求、响应都是一一对应的。其中如果还没得到响应，则会线程阻塞等待，
     * 等到有响应结果或者超时，才返回。返回的逻辑在returnFromResponse中实现。
     *
     * @param timeout
     * @return
     * @throws RemotingException
     */
    public Object get(int timeout) throws RemotingException {
        // 超时时间默认为1s
        if (timeout <= 0) {
            timeout = Constants.DEFAULT_TIMEOUT;
        }
        // 如果请求没有完成，也就是还没有响应返回(此时是需要等的)
        if (!isDone()) {
            long start = System.currentTimeMillis();
            // 获得锁
            lock.lock();
            try {
                // 轮询 等待请求是否完成
                while (!isDone()) {
                    // 线程阻塞等待
                    done.await(timeout, TimeUnit.MILLISECONDS);
                    // 如果请求完成或者超时，则结束，退出循环
                    if (isDone() || System.currentTimeMillis() - start > timeout) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                // 释放锁
                lock.unlock();
            }
            // 到这里，会分，完成和超时两种情况
            // 如果没有收到响应，则抛出超时的异常
            if (!isDone()) {
                throw new TimeoutException(sent > 0, channel, getTimeoutMessage(false));
            }
        }
        // 返回响应（方法）
        return returnFromResponse();
    }

    /**
     * 该方法是取消一个请求，可以直接关闭一个请求，也就是值创建一个响应来回应该请求，
     * 把response值设置到该请求对于到future中，做到了中断请求的作用。
     * 该方法跟closeChannel的区别是closeChannel中对response的状态设置了CHANNEL_INACTIVE，
     * 而cancel方法是中途被主动取消的，虽然有response值，但是并没有一个响应状态。
     */
    public void cancel() {
        // 创建一个取消请求的响应
        Response errorResult = new Response(id);
        errorResult.setErrorMessage("request future has been canceled.");
        response = errorResult;
        // 从集合中删除该请求
        FUTURES.remove(id);
        CHANNELS.remove(id);
    }

    public boolean isDone() {
        return response != null;
    }

    /**
     * 设置回调
     *
     * @param callback
     */
    public void setCallback(ResponseCallback callback) {
        if (isDone()) {
            invokeCallback(callback);
        } else {
            boolean isdone = false;
            lock.lock();
            try {
                if (!isDone()) {
                    this.callback = callback;
                } else {
                    isdone = true;
                }
            } finally {
                lock.unlock();
            }
            if (isdone) {
                invokeCallback(callback);
            }
        }
    }

    /**
     * 该方法是执行回调来处理响应结果。分为了三种情况：
     * <p>
     * 1 响应成功，那么执行完成后的逻辑。
     * 2 超时，会按照超时异常来处理
     * 3 其他，按照RuntimeException异常来处理
     *
     * @param c
     */
    private void invokeCallback(ResponseCallback c) {
        ResponseCallback callbackCopy = c;
        if (callbackCopy == null) {
            throw new NullPointerException("callback cannot be null.");
        }
        c = null;
        Response res = response;
        if (res == null) {
            throw new IllegalStateException("response cannot be null. url:" + channel.getUrl());
        }

        if (res.getStatus() == Response.OK) {
            try {
                callbackCopy.done(res.getResult());
            } catch (Exception e) {
                logger.error("callback invoke error .reasult:" + res.getResult() + ",url:" + channel.getUrl(), e);
            }
        } else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            try {
                TimeoutException te = new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage());
                callbackCopy.caught(te);
            } catch (Exception e) {
                logger.error("callback invoke error ,url:" + channel.getUrl(), e);
            }
        } else {
            try {
                RuntimeException re = new RuntimeException(res.getErrorMessage());
                callbackCopy.caught(re);
            } catch (Exception e) {
                logger.error("callback invoke error ,url:" + channel.getUrl(), e);
            }
        }
    }

    /**
     * 这代码跟invokeCallback方法中差不多，都是把响应分了三种情况。
     *
     * @return
     * @throws RemotingException
     */
    private Object returnFromResponse() throws RemotingException {
        Response res = response;
        if (res == null) {
            throw new IllegalStateException("response cannot be null");
        }
        // 如果正常返回，则返回响应结果
        if (res.getStatus() == Response.OK) {
            return res.getResult();
        }
        // 如果超时，则抛出超时异常
        if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {
            throw new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage());
        }
        // 其他 抛出RemotingException异常
        throw new RemotingException(channel, res.getErrorMessage());
    }

    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private long getStartTimestamp() {
        return start;
    }

    private void doSent() {
        sent = System.currentTimeMillis();
    }

    /**
     * 可以看到，当接收到响应后，会把等待的线程唤醒，然后执行回调来处理该响应结果。
     *
     * @param res
     */
    private void doReceived(Response res) {
        // 获得锁
        lock.lock();
        try {
            // 设置响应
            response = res;
            if (done != null) {
                // 唤醒等待
                done.signal();
            }
        } finally {
            // 释放锁
            lock.unlock();
        }
        if (callback != null) {
            // 执行回调
            invokeCallback(callback);
        }
    }

    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        return (sent > 0 ? "Waiting server-side response timeout" : "Sending request timeout in client-side")
                + (scan ? " by scan timer" : "") + ". start time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date())) + ","
                + (sent > 0 ? " client elapsed: " + (sent - start)
                + " ms, server elapsed: " + (nowTimestamp - sent)
                : " elapsed: " + (nowTimestamp - start)) + " ms, timeout: "
                + timeout + " ms, request: " + request + ", channel: " + channel.getLocalAddress()
                + " -> " + channel.getRemoteAddress();
    }

    /**
     * 该方法是扫描调用超时任务的线程，每次都会遍历future集合，
     * 检测请求是否超时了，如果超时则创建一个超时响应来回应该请求。
     */
    private static class RemotingInvocationTimeoutScan implements Runnable {

        public void run() {
            while (true) {
                try {
                    for (DefaultFuture future : FUTURES.values()) {
                        if (future == null || future.isDone()) {
                            // 已经完成，跳过扫描
                            continue;
                        }
                        // 超时
                        if (System.currentTimeMillis() - future.getStartTimestamp() > future.getTimeout()) {
                            // create exception response.创建一个超时的响应
                            Response timeoutResponse = new Response(future.getId());
                            // set timeout status.设置超时状态，是服务端侧超时还是客户端侧超时
                            timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
                            // 设置错误信息
                            timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
                            // handle response.接收创建的超时响应
                            DefaultFuture.received(future.getChannel(), timeoutResponse);
                        }
                    }
                    Thread.sleep(30);
                } catch (Throwable e) {
                    logger.error("Exception when scan the timeout invocation of remoting.", e);
                }
            }
        }
    }

}