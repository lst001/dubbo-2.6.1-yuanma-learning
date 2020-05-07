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
package com.alibaba.dubbo.rpc.filter;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcStatus;

/**
 * LimitInvokerFilter(Hystrix)
 *
 * 该类时对于每个服务的每个方法的 最大可并行调用数量限制 的过滤器，它是在服务消费者侧的过滤。
 */
@Activate(group = Constants.CONSUMER, value = Constants.ACTIVES_KEY)
public class ActiveLimitFilter implements Filter {

    /**
     * 该类只有这一个方法。该过滤器是用来限制调用数量，先进行调用数量的检测，
     * 如果没有到达最大的调用数量，则先调用后面的调用链，如果在后面的调用链失败，则记录相关时间
     * 如果成功也记录相关时间和调用次数
     * @param invoker    service
     * @param invocation invocation.
     * @return
     * @throws RpcException
     */
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 获得url对象
        URL url = invoker.getUrl();
        // 获得方法名称
        String methodName = invocation.getMethodName();
        // 获得并发调用数（单个服务的单个方法），默认为0
        int max = invoker.getUrl().getMethodParameter(methodName, Constants.ACTIVES_KEY, 0);
        // 通过方法名来获得对应的状态
        RpcStatus count = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName());
        if (max > 0) {
            // 获得该方法调用的超时次数
            long timeout = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.TIMEOUT_KEY, 0);
            // 获得系统时间
            long start = System.currentTimeMillis();
            long remain = timeout;
            // 获得该方法的调用数量
            int active = count.getActive();
            // 如果活跃数量大于等于最大的并发调用数量
            if (active >= max) {
                synchronized (count) {
                    // 当活跃数量大于等于最大的并发调用数量时一直循环
                    while ((active = count.getActive()) >= max) {
                        try {
                            // 等待超时时间
                            count.wait(remain);
                        } catch (InterruptedException e) {
                        }
                        long elapsed = System.currentTimeMillis() - start;
                        remain = timeout - elapsed;
                        // 如果累计时间大于超时时间，则抛出异常
                        if (remain <= 0) {
                            throw new RpcException("Waiting concurrent invoke timeout in client-side for service:  "
                                    + invoker.getInterface().getName() + ", method: "
                                    + invocation.getMethodName() + ", elapsed: " + elapsed
                                    + ", timeout: " + timeout + ". concurrent invokes: " + active
                                    + ". max concurrent invoke limit: " + max);
                        }
                    }
                }
            }
        }
        try {
            // 获得系统时间作为开始时间
            long begin = System.currentTimeMillis();
            // 开始计数
            RpcStatus.beginCount(url, methodName);
            try {
                // 调用后面的调用链，如果没有抛出异常，则算成功
                Result result = invoker.invoke(invocation);
                // 结束计数，记录时间
                // 成功 记录相关时间和调用次数
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, true);
                return result;
            } catch (RuntimeException t) {
                // 失败 记录相关时间和调用次数
                RpcStatus.endCount(url, methodName, System.currentTimeMillis() - begin, false);
                throw t;
            }
        } finally {
            if (max > 0) {
                synchronized (count) {
                    // 唤醒count
                    count.notify();
                }
            }
        }
    }

}