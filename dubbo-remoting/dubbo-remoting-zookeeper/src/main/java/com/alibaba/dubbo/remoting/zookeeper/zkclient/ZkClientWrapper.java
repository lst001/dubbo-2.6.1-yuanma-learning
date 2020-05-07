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
package com.alibaba.dubbo.remoting.zookeeper.zkclient;

import com.alibaba.dubbo.common.concurrent.ListenableFutureTask;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.Assert;

import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.ZkClient;
import org.apache.zookeeper.Watcher.Event.KeeperState;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Zkclient wrapper class that can monitor the state of the connection automatically after the connection is out of time
 * It is also consistent with the use of curator
 *
 * @date 2017/10/29
 *
 * 该类是zk客户端的包装类。
 */
public class ZkClientWrapper {

    Logger logger = LoggerFactory.getLogger(ZkClientWrapper.class);

    /**
     * 超时事件
     */
    private long timeout;

    /**
     * 真正的 ZkClient
     *
     * zk客户端
     */
    private ZkClient client;

    /**
     * 客户端状态
     */
    private volatile KeeperState state;

    /**
     * FutureTask的子类，会future返回ZkClient
     */
    private ListenableFutureTask<ZkClient> listenableFutureTask;

    /**
     * 是否开始
     */
    private volatile boolean started = false;


    /**
     * 设置了超时时间和客户端线程。
     * @param serverAddr
     * @param timeout
     */
    public ZkClientWrapper(final String serverAddr, long timeout) {
        this.timeout = timeout;
        listenableFutureTask = ListenableFutureTask.create(new Callable<ZkClient>() {
            @Override
            public ZkClient call() throws Exception {
                // 创建zk客户端
                return new ZkClient(serverAddr, Integer.MAX_VALUE);
            }
        });
    }

    /**
     * 该方法是客户端启动方法。
     */
    public void start() {
        // 如果客户端没有开启
        if (!started) {
            // 创建连接线程
            Thread connectThread = new Thread(listenableFutureTask);
            connectThread.setName("DubboZkclientConnector");
            connectThread.setDaemon(true);
            // 开启线程
            connectThread.start();
            try {
                // 获得zk客户端
                client = listenableFutureTask.get(timeout, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                logger.error("Timeout! zookeeper server can not be connected in : " + timeout + "ms!", t);
            }
            started = true;
        } else {
            logger.warn("Zkclient has already been started!");
        }
    }

    /**
     * 该方法是为客户端添加监听器。
     *
     * 其他方法都是对于 客户端是否还连接的检测。
     * @param listener
     */
    public void addListener(final IZkStateListener listener) {

        // 增加监听器(异步执行)
        listenableFutureTask.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    client = listenableFutureTask.get();
                    // 增加监听器
                    client.subscribeStateChanges(listener);
                } catch (InterruptedException e) {
                    logger.warn(Thread.currentThread().getName() + " was interrupted unexpectedly, which may cause unpredictable exception!");
                } catch (ExecutionException e) {
                    logger.error("Got an exception when trying to create zkclient instance, can not connect to zookeeper server, please check!", e);
                }
            }
        });
    }

    public boolean isConnected() {
        return client != null && state == KeeperState.SyncConnected;
    }

    public void createPersistent(String path) {
        Assert.notNull(client, new IllegalStateException("Zookeeper is not connected yet!"));
        client.createPersistent(path, true);
    }

    public void createEphemeral(String path) {
        Assert.notNull(client, new IllegalStateException("Zookeeper is not connected yet!"));
        client.createEphemeral(path);
    }

    public void delete(String path) {
        Assert.notNull(client, new IllegalStateException("Zookeeper is not connected yet!"));
        client.delete(path);
    }

    public List<String> getChildren(String path) {
        Assert.notNull(client, new IllegalStateException("Zookeeper is not connected yet!"));
        return client.getChildren(path);
    }

    public boolean exists(String path) {
        Assert.notNull(client, new IllegalStateException("Zookeeper is not connected yet!"));
        return client.exists(path);
    }

    public void close() {
        Assert.notNull(client, new IllegalStateException("Zookeeper is not connected yet!"));
        client.close();
    }

    public List<String> subscribeChildChanges(String path, final IZkChildListener listener) {
        Assert.notNull(client, new IllegalStateException("Zookeeper is not connected yet!"));
        return client.subscribeChildChanges(path, listener);
    }

    public void unsubscribeChildChanges(String path, IZkChildListener listener) {
        Assert.notNull(client, new IllegalStateException("Zookeeper is not connected yet!"));
        client.unsubscribeChildChanges(path, listener);
    }


}
