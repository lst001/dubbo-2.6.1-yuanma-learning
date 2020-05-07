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
package com.alibaba.dubbo.registry.dubbo;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.bytecode.Wrapper;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.registry.integration.RegistryDirectory;
import com.alibaba.dubbo.registry.support.AbstractRegistryFactory;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.cluster.Cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * DubboRegistryFactory
 *
 */
public class DubboRegistryFactory extends AbstractRegistryFactory {

    private Protocol protocol;
    private ProxyFactory proxyFactory;
    private Cluster cluster;

    private static URL getRegistryURL(URL url) {
        return url.setPath(RegistryService.class.getName())
                // 移除暴露服务和引用服务的参数
                .removeParameter(Constants.EXPORT_KEY).removeParameter(Constants.REFER_KEY)
                // 添加注册中心服务接口class值
                .addParameter(Constants.INTERFACE_KEY, RegistryService.class.getName())
                // 启用sticky 粘性连接，让客户端总是连接同一提供者
                .addParameter(Constants.CLUSTER_STICKY_KEY, "true")
                // 决定在创建客户端时建立连接
                .addParameter(Constants.LAZY_CONNECT_KEY, "true")
                // 不重连
                .addParameter(Constants.RECONNECT_KEY, "false")
                // 方法调用超时时间为10s
                .addParameterIfAbsent(Constants.TIMEOUT_KEY, "10000")
                // 每个客户端上一个接口的回调服务实例的限制为10000个
                .addParameterIfAbsent(Constants.CALLBACK_INSTANCES_LIMIT_KEY, "10000")
                // 注册中心连接超时时间10s
                .addParameterIfAbsent(Constants.CONNECT_TIMEOUT_KEY, "10000")
                // 添加方法级配置
                .addParameter(Constants.METHODS_KEY, StringUtils.join(new HashSet<String>(Arrays.asList(Wrapper.getWrapper(RegistryService.class).getDeclaredMethodNames())), ","))
                //.addParameter(Constants.STUB_KEY, RegistryServiceStub.class.getName())
                //.addParameter(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString()) //for event dispatch
                //.addParameter(Constants.ON_DISCONNECT_KEY, "disconnect")
                .addParameter("subscribe.1.callback", "true")
                .addParameter("unsubscribe.1.callback", "false");
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public Registry createRegistry(URL url) {
        url = getRegistryURL(url);
        List<URL> urls = new ArrayList<URL>();
        urls.add(url.removeParameter(Constants.BACKUP_KEY));
        String backup = url.getParameter(Constants.BACKUP_KEY);
        if (backup != null && backup.length() > 0) {
            // 分割备用地址
            String[] addresses = Constants.COMMA_SPLIT_PATTERN.split(backup);
            for (String address : addresses) {
                urls.add(url.setAddress(address));
            }
        }
        // 创建RegistryDirectory，里面有多个Registry的Invoker
        RegistryDirectory<RegistryService> directory = new RegistryDirectory<RegistryService>(RegistryService.class, url.addParameter(Constants.INTERFACE_KEY, RegistryService.class.getName()).addParameterAndEncoded(Constants.REFER_KEY, url.toParameterString()));
        // 将directory中的多个Invoker伪装成一个Invoker
        Invoker<RegistryService> registryInvoker = cluster.join(directory);
        // 代理
        RegistryService registryService = proxyFactory.getProxy(registryInvoker);
        // 创建注册中心对象
        DubboRegistry registry = new DubboRegistry(registryInvoker, registryService);
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // 通知监听器
        directory.notify(urls);
        // 订阅
        directory.subscribe(new URL(Constants.CONSUMER_PROTOCOL, NetUtils.getLocalHost(), 0, RegistryService.class.getName(), url.getParameters()));
        return registry;
    }
}