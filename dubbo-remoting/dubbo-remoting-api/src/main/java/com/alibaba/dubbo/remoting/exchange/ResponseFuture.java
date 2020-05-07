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
package com.alibaba.dubbo.remoting.exchange;

import com.alibaba.dubbo.remoting.RemotingException;

/**
 * Future. (API/SPI, Prototype, ThreadSafe)
 *
 * @see com.alibaba.dubbo.remoting.exchange.ExchangeChannel#request(Object)
 * @see com.alibaba.dubbo.remoting.exchange.ExchangeChannel#request(Object, int)
 * <p>
 * <p>
 * 该接口是响应future接口，该接口的设计意图跟java.util.concurrent.Future很类似。
 * 发送出去的消息，泼出去的水，只有等到对方(响应方)主动响应才能得到结果，
 * 但是请求方需要去主动回去该请求的结果，就显得有些艰难，所有产生了这样一个接口，
 * 它能够获取任务执行结果、可以核对请求消息是否被响应，还能设置回调来支持异步。
 */
public interface ResponseFuture {

    /**
     * get result.
     *
     * @return result.
     */
    Object get() throws RemotingException;

    /**
     * get result with the specified timeout.
     *
     * @param timeoutInMillis timeout.
     * @return result.
     */
    Object get(int timeoutInMillis) throws RemotingException;

    /**
     * set callback.
     *
     * @param callback
     */
    void setCallback(ResponseCallback callback);

    /**
     * check is done.
     *
     * @return done or not.
     */
    boolean isDone();

}