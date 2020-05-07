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
package com.alibaba.dubbo.remoting.exchange.support.header;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Transporters;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ExchangeHandler;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Exchanger;
import com.alibaba.dubbo.remoting.transport.DecodeHandler;

/**
 * DefaultMessenger
 * <p>
 * 该类继承了Exchanger接口，是Exchanger接口的默认实现，实现了Exchanger接口定义的两个方法，
 * 分别调用的是Transporters的连接和绑定方法，再利用这这两个方法返回的客户端和服务端实例来
 * 创建信息交换的客户端和服务端。
 */
public class HeaderExchanger implements Exchanger {

    public static final String NAME = "header";

    public ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
        // 用传输层连接返回的client 创建对应的信息交换客户端，默认开启心跳检测
        return new HeaderExchangeClient(Transporters.connect(url, new DecodeHandler(new HeaderExchangeHandler(handler))), true);
    }

    public ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        // 用传输层绑定返回的server创建对应的信息交换服务端

        // 这里也是分成两步,下面先分析bind方法,该方法就是开启Netty4服务器监听请求
        // 1) bind方法
        // 2) new HeaderExchangeServer(Server server)
        return new HeaderExchangeServer(Transporters.bind(url, new DecodeHandler(new HeaderExchangeHandler(handler))));
    }

}