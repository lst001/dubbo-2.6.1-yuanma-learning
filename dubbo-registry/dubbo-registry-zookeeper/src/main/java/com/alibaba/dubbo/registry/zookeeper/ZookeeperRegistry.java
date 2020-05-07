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
package com.alibaba.dubbo.registry.zookeeper;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter;
import com.alibaba.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ZookeeperRegistry
 */
public class ZookeeperRegistry extends FailbackRegistry {

    // 日志记录
    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    // 默认的zookeeper端口
    private final static int DEFAULT_ZOOKEEPER_PORT = 2181;

    // 默认zookeeper根节点
    private final static String DEFAULT_ROOT = "dubbo";

    // zookeeper根节点
    private final String root;


    // 服务接口集合
    private final Set<String> anyServices = new ConcurrentHashSet<String>();


    // 监听器集合
    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<URL, ConcurrentMap<NotifyListener, ChildListener>>();


    // zookeeper客户端实例
    private final ZookeeperClient zkClient;


    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        // 获得url携带的分组配置，并且作为zookeeper的根节点
        String group = url.getParameter(Constants.GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(Constants.PATH_SEPARATOR)) {
            group = Constants.PATH_SEPARATOR + group;
        }
        this.root = group;
        // 创建zookeeper client
        zkClient = zookeeperTransporter.connect(url);
        // 添加状态监听器，当状态为重连的时候调用恢复方法
        zkClient.addStateListener(new StateListener() {
            public void stateChanged(int state) {
                if (state == RECONNECTED) {
                    try {
                        // 恢复
                        recover();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        });
    }

    static String appendDefaultPort(String address) {
        if (address != null && address.length() > 0) {
            int i = address.indexOf(':');
            // 如果地址本身没有端口，则使用默认端口2181
            if (i < 0) {
                return address + ":" + DEFAULT_ZOOKEEPER_PORT;
            } else if (Integer.parseInt(address.substring(i + 1)) == 0) {
                return address.substring(0, i + 1) + DEFAULT_ZOOKEEPER_PORT;
            }
        }
        return address;
    }

    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doRegister(URL url) {
        try {
            // 创建URL节点，也就是URL层的节点
            zkClient.create(toUrlPath(url), url.getParameter(Constants.DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doUnregister(URL url) {
        try {
            // 删除节点
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            // 处理所有Service层发起的订阅，例如监控中心的订阅
            if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                // 获得根目录
                String root = toRootPath();
                // 获得url对应的监听器集合
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                // 不存在就创建监听器集合
                if (listeners == null) {
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                    listeners = zkListeners.get(url);
                }
                // 获得节点监听器
                ChildListener zkListener = listeners.get(listener);
                if (zkListener == null) {
                    // 如果该节点监听器为空，则创建
                    listeners.putIfAbsent(listener, new ChildListener() {
                        public void childChanged(String parentPath, List<String> currentChilds) {
                            // 遍历现有的节点，如果现有的服务集合中没有该节点，
                            // 则加入该节点，然后订阅该节点
                            for (String child : currentChilds) {
                                // 解码
                                child = URL.decode(child);
                                if (!anyServices.contains(child)) {
                                    anyServices.add(child);
                                    subscribe(url.setPath(child).addParameters(Constants.INTERFACE_KEY, child,
                                            Constants.CHECK_KEY, String.valueOf(false)), listener);
                                }
                            }
                        }
                    });
                    // 重新获取，为了保证一致性
                    zkListener = listeners.get(listener);
                }
                // 创建service节点，该节点为持久节点
                zkClient.create(root, false);
                // 向zookeeper的service节点发起订阅，获得Service接口全名数组
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (services != null && !services.isEmpty()) {
                    for (String service : services) {
                        // 遍历Service接口全名数组
                        service = URL.decode(service);
                        anyServices.add(service);
                        // 发起该service层的订阅
                        subscribe(url.setPath(service).addParameters(Constants.INTERFACE_KEY, service,
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else {
                // 处理指定 Service 层的发起订阅，例如服务消费者的订阅
                List<URL> urls = new ArrayList<URL>();
                // 遍历分类数组
                for (String path : toCategoriesPath(url)) {
                    // 获得监听器集合
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    // 如果没有则创建
                    if (listeners == null) {
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                        listeners = zkListeners.get(url);
                    }
                    // 获得节点监听器
                    ChildListener zkListener = listeners.get(listener);
                    if (zkListener == null) {
                        listeners.putIfAbsent(listener, new ChildListener() {
                            public void childChanged(String parentPath, List<String> currentChilds) {
                                // 通知服务变化 回调NotifyListener
                                ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds));
                            }
                        });
                        // 重新获取节点监听器，保证一致性
                        zkListener = listeners.get(listener);
                    }
                    // 创建type节点，该节点为持久节点
                    zkClient.create(path, false);
                    // 向zookeeper的type节点发起订阅
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    if (children != null) {
                        // 加入到自子节点数据数组
                        urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                //拿到服务提供者列表 连接
                // url 是服务消费者的列表
                // urls 是服务提供者的列表

                // 通知数据变化
                notify(url, listener, urls);
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doUnsubscribe(URL url, NotifyListener listener) {
        // 获得监听器集合
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            // 获得子节点的监听器
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                zkClient.removeChildListener(toUrlPath(url), zkListener);
            }
        }
    }

    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<String>();
            // 遍历分组类别
            for (String path : toCategoriesPath(url)) {
                // 获得子节点
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            // 获得 providers 中，和 consumer 匹配的 URL 数组
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    private String toRootDir() {
        if (root.equals(Constants.PATH_SEPARATOR)) {
            return root;
        }
        return root + Constants.PATH_SEPARATOR;
    }

    private String toRootPath() {
        return root;
    }

    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        // 如果是包括所有服务，则返回根节点
        if (Constants.ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    private String[] toCategoriesPath(URL url) {
        String[] categories;
        // 如果url携带的分类配置为*，则创建包括所有分类的数组
        if (Constants.ANY_VALUE.equals(url.getParameter(Constants.CATEGORY_KEY))) {
            categories = new String[]{Constants.PROVIDERS_CATEGORY, Constants.CONSUMERS_CATEGORY,
                    Constants.ROUTERS_CATEGORY, Constants.CONFIGURATORS_CATEGORY};
        } else {
            // 返回url携带的分类配置 Root + Service + Type
            categories = url.getParameter(Constants.CATEGORY_KEY, new String[]{Constants.DEFAULT_CATEGORY});
        }
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            // 加上服务路径
            paths[i] = toServicePath(url) + Constants.PATH_SEPARATOR + categories[i];
        }
        // Root + Service + Type
        return paths;
    }

    private String toCategoryPath(URL url) {
        return toServicePath(url) + Constants.PATH_SEPARATOR + url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
    }

    private String toUrlPath(URL url) {
        //该方法是获得URL路径，拼接规则是Root + Service + Type + URL
        return toCategoryPath(url) + Constants.PATH_SEPARATOR + URL.encode(url.toFullString());
    }

    // 获得 providers 中，和 consumer 匹配的 URL 数组
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<URL>();
        if (providers != null && !providers.isEmpty()) {
            // 遍历服务提供者
            for (String provider : providers) {
                // 解码
                provider = URL.decode(provider);
                if (provider.contains("://")) {
                    // 把服务转化成url的形式
                    URL url = URL.valueOf(provider);
                    // 判断是否匹配，如果匹配， 则加入到集合中
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    // 增加了若不存在匹配，则创建 empty:// 的 URL返回。通过这样的方式，可以处理类似服务提供者为空的情况。
    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        // 返回和服务消费者匹配的服务提供者url
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        // 如果不存在，则创建`empty://` 的 URL返回
        if (urls == null || urls.isEmpty()) {
            int i = path.lastIndexOf('/');
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = consumer.setProtocol(Constants.EMPTY_PROTOCOL).addParameter(Constants.CATEGORY_KEY, category);
            urls.add(empty);
        }
        return urls;
    }

}