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

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.support.AbstractZookeeperClient;

import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkStateListener;
import org.I0Itec.zkclient.exception.ZkNoNodeException;
import org.I0Itec.zkclient.exception.ZkNodeExistsException;
import org.apache.zookeeper.Watcher.Event.KeeperState;

import java.util.List;

/**
 * 该类继承了AbstractZookeeperClient，是zk客户端的实现类。
 * <p>
 * 该类有两个属性，其中client就是核心所在，几乎所有方法都调用了client的方法。
 */
public class ZkclientZookeeperClient extends AbstractZookeeperClient<IZkChildListener> {

    /**
     * zk客户端包装类
     */
    private final ZkClientWrapper client;

    /**
     * 连接状态
     */
    private volatile KeeperState state = KeeperState.SyncConnected;

    /**
     * 该方法是构造方法，同时在里面也做了创建客户端和启动客户端的操作。
     * <p>
     * 其他方法都是实现了父类抽象的方法，并且调用的是client方法（zkClient）
     *
     * @param url
     */
    public ZkclientZookeeperClient(URL url) {
        super(url);
        // 新建一个zkclient包装类
        client = new ZkClientWrapper(url.getBackupAddress(), 30000);
        // 增加状态监听
        client.addListener(new IZkStateListener() {

            public void handleStateChanged(KeeperState state) throws Exception {
                ZkclientZookeeperClient.this.state = state;
                // 如果状态变为了断开连接
                if (state == KeeperState.Disconnected) {
                    // 则修改状态
                    stateChanged(StateListener.DISCONNECTED);
                } else if (state == KeeperState.SyncConnected) {
                    stateChanged(StateListener.CONNECTED);
                }
            }

            public void handleNewSession() throws Exception {
                // 状态变为重连
                stateChanged(StateListener.RECONNECTED);
            }
        });
        // 启动客户端
        client.start();
    }

    /**
     * 该方法是递归场景节点，调用的就是client.createPersistent(path)。
     *
     * @param path
     */
    public void createPersistent(String path) {
        try {
            // 递归创建节点
            client.createPersistent(path);
        } catch (ZkNodeExistsException e) {
        }
    }

    public void createEphemeral(String path) {
        try {
            client.createEphemeral(path);
        } catch (ZkNodeExistsException e) {
        }
    }

    public void delete(String path) {
        try {
            client.delete(path);
        } catch (ZkNoNodeException e) {
        }
    }

    public List<String> getChildren(String path) {
        try {
            return client.getChildren(path);
        } catch (ZkNoNodeException e) {
            return null;
        }
    }

    public boolean checkExists(String path) {
        try {
            return client.exists(path);
        } catch (Throwable t) {
        }
        return false;
    }

    public boolean isConnected() {
        return state == KeeperState.SyncConnected;
    }

    public void doClose() {
        client.close();
    }

    public IZkChildListener createTargetChildListener(String path, final ChildListener listener) {
        return new IZkChildListener() {
            public void handleChildChange(String parentPath, List<String> currentChilds)
                    throws Exception {
                listener.childChanged(parentPath, currentChilds);
            }
        };
    }

    public List<String> addTargetChildListener(String path, final IZkChildListener listener) {
        return client.subscribeChildChanges(path, listener);
    }

    public void removeTargetChildListener(String path, IZkChildListener listener) {
        client.unsubscribeChildChanges(path, listener);
    }

}
