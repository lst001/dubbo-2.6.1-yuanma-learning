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
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.fastjson.JSON;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Record access log for the service.
 * <p>
 * Logger key is <code><b>dubbo.accesslog</b></code>.
 * In order to configure access log appear in the specified appender only, additivity need to be configured in log4j's
 * config file, for example:
 * <code>
 * <pre>
 * &lt;logger name="<b>dubbo.accesslog</b>" <font color="red">additivity="false"</font>&gt;
 *    &lt;level value="info" /&gt;
 *    &lt;appender-ref ref="foo" /&gt;
 * &lt;/logger&gt;
 * </pre></code>
 *
 *
 *
 * 该过滤器是对记录日志的过滤器，它所做的工作就是把引用服务或者暴露服务的调用链信息写入到文件中。
 *
 * 日志消息先被放入日志集合，然后加入到日志队列，然后被放入到写入文件到任务中，最后进入文件。
 *
 *
 * 日志流向，日志先进入到是日志队列中的日志集合(logSet)，再进入logQueue，在进入logFuture，最后落地到文件。
 */
@Activate(group = Constants.PROVIDER, value = Constants.ACCESS_LOG_KEY)
public class AccessLogFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AccessLogFilter.class);

    /**
     * 日志访问名称，默认的日志访问名称
     */
    private static final String ACCESS_LOG_KEY = "dubbo.accesslog";

    /**
     * 日期格式
     */
    private static final String FILE_DATE_FORMAT = "yyyyMMdd";

    private static final String MESSAGE_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";

    /**
     * 日志队列大小
     */
    private static final int LOG_MAX_BUFFER = 5000;

    /**
     * 日志输出的频率
     */
    private static final long LOG_OUTPUT_INTERVAL = 5000;

    /**
     * 日志队列 key为访问日志的名称，value为该日志名称对应的日志集合
     */
    private final ConcurrentMap<String, Set<String>> logQueue = new ConcurrentHashMap<String, Set<String>>();

    /**
     * 日志线程池
     */
    private final ScheduledExecutorService logScheduled = Executors.newScheduledThreadPool(2, new NamedThreadFactory("Dubbo-Access-Log", true));

    /**
     * 日志记录任务
     */
    private volatile ScheduledFuture<?> logFuture = null;

    private void init() {
        // synchronized是一个重操作消耗性能，所有加上判空
        if (logFuture == null) {
            synchronized (logScheduled) {
                // 为了不重复初始化(双重判断)
                if (logFuture == null) {
                    // 创建日志记录任务
                    logFuture = logScheduled.scheduleWithFixedDelay(new LogTask(), LOG_OUTPUT_INTERVAL, LOG_OUTPUT_INTERVAL, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /**
     * 该方法是增加日志信息 到日志集合中。
     *
     * @param accesslog
     * @param logmessage
     */
    private void log(String accesslog, String logmessage) {
        init();
        Set<String> logSet = logQueue.get(accesslog);
        if (logSet == null) {
            logQueue.putIfAbsent(accesslog, new ConcurrentHashSet<String>());
            logSet = logQueue.get(accesslog);
        }
        if (logSet.size() < LOG_MAX_BUFFER) {
            logSet.add(logmessage);
        }
    }

    /**
     * 该方法是最重要的方法，从拼接了日志信息，把日志加入到集合，并且调用下一个调用链。
     *
     * @param invoker service
     * @param inv
     * @return
     * @throws RpcException
     */
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        try {
            // 获得日志名称
            String accesslog = invoker.getUrl().getParameter(Constants.ACCESS_LOG_KEY);
            if (ConfigUtils.isNotEmpty(accesslog)) {
                // 获得rpc上下文
                RpcContext context = RpcContext.getContext();
                // 获得调用的接口名称
                String serviceName = invoker.getInterface().getName();
                // 获得版本号
                String version = invoker.getUrl().getParameter(Constants.VERSION_KEY);
                // 获得组，是消费者侧还是生产者侧
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY);
                StringBuilder sn = new StringBuilder();
                sn.append("[").append(new SimpleDateFormat(MESSAGE_DATE_FORMAT).format(new Date())).append("] ").append(context.getRemoteHost()).append(":").append(context.getRemotePort())
                        .append(" -> ").append(context.getLocalHost()).append(":").append(context.getLocalPort())
                        .append(" - ");
                // 拼接组
                if (null != group && group.length() > 0) {
                    sn.append(group).append("/");
                }
                // 拼接服务名称
                sn.append(serviceName);
                // 拼接版本号
                if (null != version && version.length() > 0) {
                    sn.append(":").append(version);
                }
                sn.append(" ");
                // 拼接方法名
                sn.append(inv.getMethodName());
                sn.append("(");
                // 拼接参数类型
                Class<?>[] types = inv.getParameterTypes();
                // 拼接参数类型
                if (types != null && types.length > 0) {
                    boolean first = true;
                    for (Class<?> type : types) {
                        if (first) {
                            first = false;
                        } else {
                            sn.append(",");
                        }
                        sn.append(type.getName());
                    }
                }
                sn.append(") ");
                // 拼接参数
                Object[] args = inv.getArguments();
                if (args != null && args.length > 0) {
                    sn.append(JSON.toJSONString(args));
                }
                String msg = sn.toString();
                // 如果用默认的日志访问名称
                if (ConfigUtils.isDefault(accesslog)) {
                    LoggerFactory.getLogger(ACCESS_LOG_KEY + "." + invoker.getInterface().getName()).info(msg);
                } else {
                    // 把日志加入集合
                    log(accesslog, msg);
                }
            }
        } catch (Throwable t) {
            logger.warn("Exception in AcessLogFilter of service(" + invoker + " -> " + inv + ")", t);
        }
        return invoker.invoke(inv);
    }

    /**
     * 该内部类实现了Runnable，是把日志消息落地到文件到线程。
     */
    private class LogTask implements Runnable {
        public void run() {
            try {
                if (logQueue != null && logQueue.size() > 0) {
                    // 遍历日志队列
                    for (Map.Entry<String, Set<String>> entry : logQueue.entrySet()) {
                        try {
                            // 获得日志名称
                            String accesslog = entry.getKey();
                            // 获得日志集合
                            Set<String> logSet = entry.getValue();
                            // 如果文件不存在则创建文件
                            File file = new File(accesslog);
                            File dir = file.getParentFile();
                            if (null != dir && !dir.exists()) {
                                dir.mkdirs();
                            }
                            if (logger.isDebugEnabled()) {
                                logger.debug("Append log to " + accesslog);
                            }
                            if (file.exists()) {
                                // 获得现在的时间
                                String now = new SimpleDateFormat(FILE_DATE_FORMAT).format(new Date());
                                // 获得文件最后一次修改的时间
                                String last = new SimpleDateFormat(FILE_DATE_FORMAT).format(new Date(file.lastModified()));
                                // 如果文件最后一次修改的时间不等于现在的时间
                                if (!now.equals(last)) {
                                    // 获得重新生成文件名称
                                    File archive = new File(file.getAbsolutePath() + "." + last);
                                    // 因为都是file的绝对路径，所以没有进行移动文件，而是修改文件名
                                    file.renameTo(archive);
                                }
                            }
                            // 把日志集合中的日志写入到文件
                            FileWriter writer = new FileWriter(file, true);
                            try {
                                for (Iterator<String> iterator = logSet.iterator();
                                     iterator.hasNext();
                                     iterator.remove()) {
                                    writer.write(iterator.next());
                                    writer.write("\r\n");
                                }
                                writer.flush();
                            } finally {
                                writer.close();
                            }
                        } catch (Exception e) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

}