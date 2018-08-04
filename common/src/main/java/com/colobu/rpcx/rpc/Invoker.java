package com.colobu.rpcx.rpc;

import com.colobu.rpcx.rpc.impl.RpcInvocation;

import java.lang.reflect.Method;


/**
 * Created by goodjava@qq.com.
 */
public interface Invoker<T> extends Node {

    Class<T> getInterface();

    Result invoke(RpcInvocation invocation) throws RpcException;

    void setMethod(Method method);

    void setInterface(Class clazz);

}
