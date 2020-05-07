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
package com.alibaba.dubbo.remoting.telnet.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.telnet.TelnetHandler;
import com.alibaba.dubbo.remoting.transport.ChannelHandlerAdapter;

/**
 * 该类继承了ChannelHandlerAdapter，实现了TelnetHandler接口，是TelnetHandler的适配器类，
 * 负责在接收到HeaderExchangeHandler发来的telnet命令后分发给对应的TelnetHandler实现类去实现，并且返回命令结果。
 * <p>
 * 该类只实现了telnet方法，其中的逻辑还是比较清晰，就是根据对应的命令去让对应的实现类产生命令结果
 */
public class TelnetHandlerAdapter extends ChannelHandlerAdapter implements TelnetHandler {

    /**
     * 扩展加载器
     */
    private final ExtensionLoader<TelnetHandler> extensionLoader = ExtensionLoader.getExtensionLoader(TelnetHandler.class);

    public String telnet(Channel channel, String message) throws RemotingException {
        // 获得提示键配置，用于nc获取信息时不显示提示符
        String prompt = channel.getUrl().getParameterAndDecoded(Constants.PROMPT_KEY, Constants.DEFAULT_PROMPT);
        boolean noprompt = message.contains("--no-prompt");
        message = message.replace("--no-prompt", "");
        StringBuilder buf = new StringBuilder();

        // 删除头尾空白符的字符串
        message = message.trim();
        String command;
        // 获得命令
        if (message.length() > 0) {
            int i = message.indexOf(' ');
            if (i > 0) {
                // 获得命令
                command = message.substring(0, i).trim();
                // 获得参数
                message = message.substring(i + 1).trim();
            } else {
                command = message;
                message = "";
            }
        } else {
            command = "";
        }
        if (command.length() > 0) {
            // 如果有该命令的扩展实现类
            if (extensionLoader.hasExtension(command)) {
                try {
                    // 执行相应命令的实现类的telnet
                    String result = extensionLoader.getExtension(command).telnet(channel, message);
                    if (result == null) {
                        return null;
                    }
                    // 返回结果
                    buf.append(result);
                } catch (Throwable t) {
                    buf.append(t.getMessage());
                }
            } else {
                buf.append("Unsupported command: ");
                buf.append(command);
            }
        }
        if (buf.length() > 0) {
            buf.append("\r\n");
        }
        // 添加 telnet 提示语
        if (prompt != null && prompt.length() > 0 && !noprompt) {
            buf.append(prompt);
        }
        return buf.toString();
    }

}
