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

/**
 * RPC Exception. (API, Prototype, ThreadSafe)
 *
 * @serial Don't change the class name and properties.
 * @export
 * @see com.alibaba.dubbo.rpc.Invoker#invoke(Invocation)
 * @since 1.0
 * <p>
 * 该类是rpc调用抛出的异常类，其中封装了五种通用的错误码。
 */
public final class RpcException extends RuntimeException {

    /**
     * 不知道异常
     */
    public static final int UNKNOWN_EXCEPTION = 0;

    /**
     * 网络异常
     */
    public static final int NETWORK_EXCEPTION = 1;

    /**
     * 超时异常
     */
    public static final int TIMEOUT_EXCEPTION = 2;

    /**
     * 基础异常
     */
    public static final int BIZ_EXCEPTION = 3;

    /**
     * 禁止访问异常
     */
    public static final int FORBIDDEN_EXCEPTION = 4;

    /**
     * 序列化异常
     */
    public static final int SERIALIZATION_EXCEPTION = 5;

    private static final long serialVersionUID = 7815426752583648734L;

    /**
     * RpcException cannot be extended, use error code for exception type to keep compatibility
     */
    private int code;

    public RpcException() {
        super();
    }

    public RpcException(String message, Throwable cause) {
        super(message, cause);
    }

    public RpcException(String message) {
        super(message);
    }

    public RpcException(Throwable cause) {
        super(cause);
    }

    public RpcException(int code) {
        super();
        this.code = code;
    }

    public RpcException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public RpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public RpcException(int code, Throwable cause) {
        super(cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public boolean isBiz() {
        return code == BIZ_EXCEPTION;
    }

    public boolean isForbidded() {
        return code == FORBIDDEN_EXCEPTION;
    }

    public boolean isTimeout() {
        return code == TIMEOUT_EXCEPTION;
    }

    public boolean isNetwork() {
        return code == NETWORK_EXCEPTION;
    }

    public boolean isSerialization() {
        return code == SERIALIZATION_EXCEPTION;
    }
}