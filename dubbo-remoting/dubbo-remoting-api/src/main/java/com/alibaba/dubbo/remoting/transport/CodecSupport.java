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

package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Serialization;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 该类是编解码工具类，提供查询 Serialization 的功能。
 * 该类中缓存了所有的序列化对象和序列化扩展名。可以从中拿到Serialization。
 */
public class CodecSupport {


    private static final Logger logger = LoggerFactory.getLogger(CodecSupport.class);
    /**
     * 序列化对象集合 key为序列化类型编号
     */
    private static Map<Byte, Serialization> ID_SERIALIZATION_MAP = new HashMap<Byte, Serialization>();

    /**
     * 序列化扩展名集合 key为序列化类型编号 value为序列化扩展名
     */
    private static Map<Byte, String> ID_SERIALIZATIONNAME_MAP = new HashMap<Byte, String>();

    static {
        // 利用dubbo 的SPI机制获得序列化扩展名
        Set<String> supportedExtensions = ExtensionLoader.getExtensionLoader(Serialization.class).getSupportedExtensions();
        for (String name : supportedExtensions) {
            // 获得相应扩展名的序列化实现
            Serialization serialization = ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(name);
            byte idByte = serialization.getContentTypeId();
            if (ID_SERIALIZATION_MAP.containsKey(idByte)) {
                logger.error("Serialization extension " + serialization.getClass().getName()
                        + " has duplicate id to Serialization extension "
                        + ID_SERIALIZATION_MAP.get(idByte).getClass().getName()
                        + ", ignore this Serialization extension");
                continue;
            }
            // 缓存序列化实现
            ID_SERIALIZATION_MAP.put(idByte, serialization);
            // 缓存序列化编号和扩展名
            ID_SERIALIZATIONNAME_MAP.put(idByte, name);
        }
    }

    private CodecSupport() {
    }

    public static Serialization getSerializationById(Byte id) {
        return ID_SERIALIZATION_MAP.get(id);
    }

    public static Serialization getSerialization(URL url) {
        return ExtensionLoader.getExtensionLoader(Serialization.class).getExtension(
                url.getParameter(Constants.SERIALIZATION_KEY, Constants.DEFAULT_REMOTING_SERIALIZATION));
    }

    public static Serialization getSerialization(URL url, Byte id) throws IOException {
        Serialization serialization = getSerializationById(id);
        String serializationName = url.getParameter(Constants.SERIALIZATION_KEY, Constants.DEFAULT_REMOTING_SERIALIZATION);
        // Check if "serialization id" passed from network matches the id on this side(only take effect for JDK serialization), for security purpose.
        if (serialization == null
                || ((id == 3 || id == 7 || id == 4) && !(serializationName.equals(ID_SERIALIZATIONNAME_MAP.get(id))))) {
            throw new IOException("Unexpected serialization id:" + id + " received from network, please check if the peer send the right id.");
        }
        return serialization;
    }

}
