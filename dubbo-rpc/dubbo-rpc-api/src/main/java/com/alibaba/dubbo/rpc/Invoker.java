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
package com.alibaba.dubbo.rpc;

import com.alibaba.dubbo.common.Node;

/**
 * Invoker. (API/SPI, Prototype, ThreadSafe)
 *
 * @see com.alibaba.dubbo.rpc.Protocol#refer(Class, com.alibaba.dubbo.common.URL)
 * @see com.alibaba.dubbo.rpc.InvokerListener
 * @see com.alibaba.dubbo.rpc.protocol.AbstractInvoker
 * <p>
 * 该接口是实体域，它是dubbo的核心模型，其他模型都向它靠拢，或者转化成它，它代表了一个可执行体，
 *
 * 可以向它发起invoke调用，这个有可能是一个本地的实现，也可能是一个远程的实现，也可能是一个集群的实现。
 * 它代表了一次调用
 */
public interface Invoker<T> extends Node {

    /**
     * get service interface.
     * 获得服务接口
     *
     * @return service interface.
     */
    Class<T> getInterface();

    /**
     * invoke.
     * 调用下一个会话域
     *
     * @param invocation
     * @return result
     * @throws RpcException
     */
    Result invoke(Invocation invocation) throws RpcException;

}