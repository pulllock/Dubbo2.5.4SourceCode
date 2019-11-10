/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.protocol.dubbo;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.utils.AtomicPositiveInteger;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.ExchangeClient;
import com.alibaba.dubbo.remoting.exchange.ResponseFuture;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.RpcResult;
import com.alibaba.dubbo.rpc.protocol.AbstractInvoker;
import com.alibaba.dubbo.rpc.support.RpcUtils;

/**
 * DubboInvoker
 * 
 * @author william.liangf
 * @author chao.liuc
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {

    private final ExchangeClient[]      clients;

    /**
     * 使用的clients的位置
     */
    private final AtomicPositiveInteger index = new AtomicPositiveInteger();

    private final String                version;
    
    private final ReentrantLock     destroyLock = new ReentrantLock();
    
    private final Set<Invoker<?>> invokers;
    
    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients){
        this(serviceType, url, clients, null);
    }
    
    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers){
        super(serviceType, url, new String[] {Constants.INTERFACE_KEY, Constants.GROUP_KEY, Constants.TOKEN_KEY, Constants.TIMEOUT_KEY});
        this.clients = clients;
        // get version.
        this.version = url.getParameter(Constants.VERSION_KEY, "0.0.0");
        this.invokers = invokers; 
    }

    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {
        RpcInvocation inv = (RpcInvocation) invocation;
        // 获得方法名
        final String methodName = RpcUtils.getMethodName(invocation);
        // 获得path，服务名
        inv.setAttachment(Constants.PATH_KEY, getUrl().getPath());
        // 版本
        inv.setAttachment(Constants.VERSION_KEY, version);

        // ExchangeClient对象
        ExchangeClient currentClient;
        if (clients.length == 1) {
            currentClient = clients[0];
        } else {
            currentClient = clients[index.getAndIncrement() % clients.length];
        }
        try {
            // 异步配置
            boolean isAsync = RpcUtils.isAsync(getUrl(), invocation);
            // 单向通信配置
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
            // 超时时间
            int timeout = getUrl().getMethodParameter(methodName, Constants.TIMEOUT_KEY,Constants.DEFAULT_TIMEOUT);
            // 单向调用
            if (isOneway) {
            	boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
            	// 发送消息，不是请求
                currentClient.send(inv, isSent);
                // 设置future为null，无需FutureFilter异步回调
                RpcContext.getContext().setFuture(null);
                // 返回空的RpcResult
                return new RpcResult();
            } else if (isAsync) {
                // 异步调用
                // 发送请求，得到ResponseFuture
            	ResponseFuture future = currentClient.request(inv, timeout) ;
            	// 设置future，FutureFilter中异步回调
                RpcContext.getContext().setFuture(new FutureAdapter<Object>(future));
                // 暂时返回一个空RpcResult
                return new RpcResult();
            } else {
                // 同步调用，无需FutureFilter异步回调
            	RpcContext.getContext().setFuture(null);
            	// 发送请求，调用future的get阻塞等待结果
                return (Result) currentClient.request(inv, timeout).get();
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isAvailable() {
        if (!super.isAvailable())
            return false;
        for (ExchangeClient client : clients){
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)){
                //cannot write == not Available ?
                return true ;
            }
        }
        return false;
    }

    public void destroy() {
        //防止client被关闭多次.在connect per jvm的情况下，client.close方法会调用计数器-1，当计数器小于等于0的情况下，才真正关闭
        if (super.isDestroyed()){
            return ;
        } else {
            //dubbo check ,避免多次关闭
            destroyLock.lock();
            try{
                if (super.isDestroyed()){
                    return ;
                }
                super.destroy();
                if (invokers != null){
                    invokers.remove(this);
                }
                for (ExchangeClient client : clients) {
                    try {
                        client.close();
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }
                
            }finally {
                destroyLock.unlock();
            }
        }
    }
}