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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL statistics. (API, Cached, ThreadSafe)
 *
 * @see com.alibaba.dubbo.rpc.filter.ActiveLimitFilter
 * @see com.alibaba.dubbo.rpc.filter.ExecuteLimitFilter
 * @see com.alibaba.dubbo.rpc.cluster.loadbalance.LeastActiveLoadBalance
 * <p>
 * 该类是rpc的一些状态监控，其中封装了许多的计数器，用来记录rpc调用的状态。
 */
public class RpcStatus {

    /**
     * uri对应的状态集合，key为uri，value为RpcStatus对象
     */
    private static final ConcurrentMap<String, RpcStatus> SERVICE_STATISTICS = new ConcurrentHashMap<String, RpcStatus>();

    /**
     * method对应的状态集合，key是uri，第二个key是方法名methodName
     * 调用method的状态集合
     */
    private static final ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> METHOD_STATISTICS = new ConcurrentHashMap<String, ConcurrentMap<String, RpcStatus>>();
    /**
     * 已经没用了
     */
    private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<String, Object>();
    /**
     * 活跃状态
     */
    private final AtomicInteger active = new AtomicInteger();
    /**
     * 总的数量
     */
    private final AtomicLong total = new AtomicLong();
    /**
     * 失败的个数
     */
    private final AtomicInteger failed = new AtomicInteger();
    /**
     * 总调用时长
     */
    private final AtomicLong totalElapsed = new AtomicLong();
    /**
     * 总调用失败时长
     */
    private final AtomicLong failedElapsed = new AtomicLong();
    /**
     * 最大调用时长
     */
    private final AtomicLong maxElapsed = new AtomicLong();
    /**
     * 最大调用失败时长
     */
    private final AtomicLong failedMaxElapsed = new AtomicLong();
    /**
     * 最大调用成功时长
     */
    private final AtomicLong succeededMaxElapsed = new AtomicLong();

    /**
     * Semaphore used to control concurrency limit set by `executes`
     * <p>
     * 信号量用来控制`execution`设置的并发限制（共享锁）
     */
    private volatile Semaphore executesLimit;

    /**
     * 用来控制`execution`设置的许可证
     */
    private volatile int executesPermits;

    private RpcStatus() {
    }

    /**
     * @param url
     * @return status
     */
    public static RpcStatus getStatus(URL url) {
        String uri = url.toIdentityString();
        RpcStatus status = SERVICE_STATISTICS.get(uri);
        if (status == null) {
            SERVICE_STATISTICS.putIfAbsent(uri, new RpcStatus());
            status = SERVICE_STATISTICS.get(uri);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url) {
        String uri = url.toIdentityString();
        SERVICE_STATISTICS.remove(uri);
    }

    /**
     * @param url
     * @param methodName
     * @return status
     */
    public static RpcStatus getStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map == null) {
            METHOD_STATISTICS.putIfAbsent(uri, new ConcurrentHashMap<String, RpcStatus>());
            map = METHOD_STATISTICS.get(uri);
        }
        RpcStatus status = map.get(methodName);
        if (status == null) {
            map.putIfAbsent(methodName, new RpcStatus());
            status = map.get(methodName);
        }
        return status;
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map != null) {
            map.remove(methodName);
        }
    }

    /**
     * 开始计数
     *
     * @param url
     */
    public static void beginCount(URL url, String methodName) {
        // 对该url对应对活跃计数器加一
        beginCount(getStatus(url));
        // 对该方法对活跃计数器加一
        beginCount(getStatus(url, methodName));
    }

    /**
     * 该方法是增加计数
     * 以原子方式加1
     *
     * @param status
     */
    private static void beginCount(RpcStatus status) {
        status.active.incrementAndGet();
    }

    /**
     * @param url
     * @param elapsed
     * @param succeeded
     */
    public static void endCount(URL url, String methodName, long elapsed, boolean succeeded) {
        // url对应的状态中计数器减一
        endCount(getStatus(url), elapsed, succeeded);
        // 方法对应的状态中计数器减一
        endCount(getStatus(url, methodName), elapsed, succeeded);
    }

    /**
     * 该方法是计数器减少。
     *
     * @param status
     * @param elapsed
     * @param succeeded
     */
    private static void endCount(RpcStatus status, long elapsed, boolean succeeded) {
        // 活跃计数器减一
        status.active.decrementAndGet();
        // 总计数器加1
        status.total.incrementAndGet();
        // 总调用时长加上调用时长
        status.totalElapsed.addAndGet(elapsed);
        // 如果最大调用时长小于elapsed，则设置最大调用时长
        if (status.maxElapsed.get() < elapsed) {
            status.maxElapsed.set(elapsed);
        }
        // 如果rpc调用成功
        if (succeeded) {
            // 如果成最大调用成功时长小于elapsed，则设置最大调用成功时长
            if (status.succeededMaxElapsed.get() < elapsed) {
                status.succeededMaxElapsed.set(elapsed);
            }
        } else {
            // 失败计数器加一
            status.failed.incrementAndGet();
            // 失败的过期数加上elapsed
            status.failedElapsed.addAndGet(elapsed);
            // 总调用失败时长小于elapsed，则设置总调用失败时长
            if (status.failedMaxElapsed.get() < elapsed) {
                status.failedMaxElapsed.set(elapsed);
            }
        }
    }

    /**
     * set value.
     *
     * @param key
     * @param value
     */
    public void set(String key, Object value) {
        values.put(key, value);
    }

    /**
     * get value.
     *
     * @param key
     * @return value
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * get active.
     *
     * @return active
     */
    public int getActive() {
        return active.get();
    }

    /**
     * get total.
     *
     * @return total
     */
    public long getTotal() {
        return total.longValue();
    }

    /**
     * get total elapsed.
     *
     * @return total elapsed
     */
    public long getTotalElapsed() {
        return totalElapsed.get();
    }

    /**
     * get average elapsed.
     *
     * @return average elapsed
     */
    public long getAverageElapsed() {
        long total = getTotal();
        if (total == 0) {
            return 0;
        }
        return getTotalElapsed() / total;
    }

    /**
     * get max elapsed.
     *
     * @return max elapsed
     */
    public long getMaxElapsed() {
        return maxElapsed.get();
    }

    /**
     * get failed.
     *
     * @return failed
     */
    public int getFailed() {
        return failed.get();
    }

    /**
     * get failed elapsed.
     *
     * @return failed elapsed
     */
    public long getFailedElapsed() {
        return failedElapsed.get();
    }

    /**
     * get failed average elapsed.
     *
     * @return failed average elapsed
     */
    public long getFailedAverageElapsed() {
        long failed = getFailed();
        if (failed == 0) {
            return 0;
        }
        return getFailedElapsed() / failed;
    }

    /**
     * get failed max elapsed.
     *
     * @return failed max elapsed
     */
    public long getFailedMaxElapsed() {
        return failedMaxElapsed.get();
    }

    /**
     * get succeeded.
     *
     * @return succeeded
     */
    public long getSucceeded() {
        return getTotal() - getFailed();
    }

    /**
     * get succeeded elapsed.
     *
     * @return succeeded elapsed
     */
    public long getSucceededElapsed() {
        return getTotalElapsed() - getFailedElapsed();
    }

    /**
     * get succeeded average elapsed.
     *
     * @return succeeded average elapsed
     */
    public long getSucceededAverageElapsed() {
        long succeeded = getSucceeded();
        if (succeeded == 0) {
            return 0;
        }
        return getSucceededElapsed() / succeeded;
    }

    /**
     * get succeeded max elapsed.
     *
     * @return succeeded max elapsed.
     */
    public long getSucceededMaxElapsed() {
        return succeededMaxElapsed.get();
    }

    /**
     * Calculate average TPS (Transaction per second).
     *
     * @return tps
     */
    public long getAverageTps() {
        if (getTotalElapsed() >= 1000L) {
            return getTotal() / (getTotalElapsed() / 1000L);
        }
        return getTotal();
    }

    /**
     * Get the semaphore for thread number. Semaphore's permits is decided by {@link Constants#EXECUTES_KEY}
     *
     * @param maxThreadNum value of {@link Constants#EXECUTES_KEY}
     * @return thread number semaphore
     */
    public Semaphore getSemaphore(int maxThreadNum) {
        if (maxThreadNum <= 0) {
            return null;
        }

        if (executesLimit == null || executesPermits != maxThreadNum) {
            synchronized (this) {
                if (executesLimit == null || executesPermits != maxThreadNum) {
                    executesLimit = new Semaphore(maxThreadNum);
                    executesPermits = maxThreadNum;
                }
            }
        }

        return executesLimit;
    }
}