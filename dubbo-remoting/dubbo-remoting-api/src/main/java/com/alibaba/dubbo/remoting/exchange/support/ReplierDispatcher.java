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

import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ReplierDispatcher
 * 该类实现了Replier接口，是回复者调度器实现类。
 * <p>
 * 这是该类的两个属性，缓存了回复者集合和默认的回复者。
 */
public class ReplierDispatcher implements Replier<Object> {

    /**
     * 默认回复者
     */
    private final Replier<?> defaultReplier;

    /**
     * 回复者集合
     */
    private final Map<Class<?>, Replier<?>> repliers = new ConcurrentHashMap<Class<?>, Replier<?>>();

    public ReplierDispatcher() {
        this(null, null);
    }

    public ReplierDispatcher(Replier<?> defaultReplier) {
        this(defaultReplier, null);
    }

    public ReplierDispatcher(Replier<?> defaultReplier, Map<Class<?>, Replier<?>> repliers) {
        this.defaultReplier = defaultReplier;
        if (repliers != null && repliers.size() > 0) {
            this.repliers.putAll(repliers);
        }
    }

    public <T> ReplierDispatcher addReplier(Class<T> type, Replier<T> replier) {
        repliers.put(type, replier);
        return this;
    }

    public <T> ReplierDispatcher removeReplier(Class<T> type) {
        repliers.remove(type);
        return this;
    }

    /**
     * 从回复者集合中找到该类型的回复者，并且返回
     *
     * @param type
     * @return
     */
    private Replier<?> getReplier(Class<?> type) {
        for (Map.Entry<Class<?>, Replier<?>> entry : repliers.entrySet()) {
            if (entry.getKey().isAssignableFrom(type)) {
                return entry.getValue();
            }
        }
        if (defaultReplier != null) {
            return defaultReplier;
        }
        throw new IllegalStateException("Replier not found, Unsupported message object: " + type);
    }

    /**
     * 回复请求
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object reply(ExchangeChannel channel, Object request) throws RemotingException {
        return ((Replier) getReplier(request.getClass())).reply(channel, request);
    }

}