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
package com.alibaba.dubbo.remoting.zookeeper.support;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 该类实现了ZookeeperClient接口，是客户端的抽象类，它实现了一些公共逻辑，
 * 把具体的doClose、createPersistent等方法抽象出来，留给子类来实现。
 *
 * @param <TargetChildListener>
 */
public abstract class AbstractZookeeperClient<TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);

    /**
     * url对象
     */
    private final URL url;

    /**
     * 状态监听器集合
     */
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();

    /**
     * 客户端监听器集合
     */
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();

    /**
     * 是否关闭
     */
    private volatile boolean closed = false;

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    public URL getUrl() {
        return url;
    }

    /**
     * 该方法是创建客户端的方法，其中createEphemeral和createPersistent方法都被抽象出来。
     *
     * @param path
     * @param ephemeral
     */
    public void create(String path, boolean ephemeral) {
        //  获得/的位置
        int i = path.lastIndexOf('/');
        if (i > 0) {
            String parentPath = path.substring(0, i);
            if (!checkExists(parentPath)) {
                // 创建客户端
                create(parentPath, false);
            }
        }
        // 如果是临时节点
        if (ephemeral) {
            // 创建临时节点
            createEphemeral(path);
        } else {
            // 递归创建节点
            createPersistent(path);
        }
    }

    /**
     * 该方法就是增加状态监听器。
     *
     * @param listener
     */
    public void addStateListener(StateListener listener) {
        // 状态监听器加入集合
        stateListeners.add(listener);
    }

    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    public List<String> addChildListener(String path, final ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners == null) {
            childListeners.putIfAbsent(path, new ConcurrentHashMap<ChildListener, TargetChildListener>());
            listeners = childListeners.get(path);
        }
        TargetChildListener targetListener = listeners.get(listener);

        if (targetListener == null) {
            listeners.putIfAbsent(listener, createTargetChildListener(path, listener));
            targetListener = listeners.get(listener);
        }
        return addTargetChildListener(path, targetListener);
    }

    public void removeChildListener(String path, ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    /**
     * 该方法是关闭客户端，其中doClose方法也被抽象出。
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            // 关闭  ,doClose方法也被抽象出来
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * 关闭客户端
     * 抽象方法留给子类实现逻辑(以下方法都是被抽象的，由它的两个子类来实现)
     */
    protected abstract void doClose();

    /**
     * 递归创建节点
     *
     * @param path
     */
    protected abstract void createPersistent(String path);

    /**
     * 创建临时节点
     *
     * @param path
     */
    protected abstract void createEphemeral(String path);

    /**
     * 检测该节点是否存在
     *
     * @param path
     * @return
     */
    protected abstract boolean checkExists(String path);

    /**
     * 创建子节点监听器(path,listener)
     *
     * @param path
     * @param listener
     * @return
     */
    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    /**
     * 为子节点添加监听器
     * @param path
     * @param listener
     * @return
     */
    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    /**
     * 移除子节点监听器
     * @param path
     * @param listener
     */
    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

}
