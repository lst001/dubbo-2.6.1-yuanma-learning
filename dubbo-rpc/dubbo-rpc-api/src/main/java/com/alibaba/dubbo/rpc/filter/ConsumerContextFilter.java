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
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;

/**
 *
 * 该过滤器做的是在当前的RpcContext中记录本地调用的一次状态信息。
 *
 * 可以看到RpcContext记录了一次调用状态信息，然后先调用后面的调用链，再回来把附加值设置到RpcContext中。
 * 然后返回RpcContext，再清空，这样是因为后面的调用链中的附加值对前面的调用链是不可见的。
 *
 *
 * ConsumerContextInvokerFilter
 */
@Activate(group = Constants.CONSUMER, order = -10000)
public class ConsumerContextFilter implements Filter {

    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        // 设置rpc上下文
        RpcContext.getContext()
                .setInvoker(invoker)
                .setInvocation(invocation)
                .setLocalAddress(NetUtils.getLocalHost(), 0)
                .setRemoteAddress(invoker.getUrl().getHost(),
                        invoker.getUrl().getPort());
        // 如果该会话域是rpc会话域
        if (invocation instanceof RpcInvocation) {
            // 设置实体域
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        try {
            // 调用下个调用链
            return invoker.invoke(invocation);
        } finally {
            // 清空附加值
            RpcContext.getContext().clearAttachments();
        }
    }

}