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
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;

/**
 * 失败转移，当出现失败，重试其它服务器，通常用于读操作，但重试会带来更长延迟。 
 * 
 * <a href="http://en.wikipedia.org/wiki/Failover">Failover</a>
 * 
 * @author william.liangf
 */
public class FailoverCluster implements Cluster {

    public final static String NAME = "failover";

    /**
     * join其实是在服务消费者初始化阶段进行的
     * 只是实例化一个FailoverClusterInvoker实例
     * 实际使用的时候是在消费者进行远程调用的时候，
     * 会首先调用Directory的list方法找到Invoker列表，
     * 然后通过LoadBalance从Invoker列表中选择一个Invoker，
     * 最后FailoverClusterInvoker将调用选中的Invoker进行实际调用。
     * @param directory
     * @param <T>
     * @return
     * @throws RpcException
     */
    public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
        // 创建并返回FailoverClusterInvoker实例
        return new FailoverClusterInvoker<T>(directory);
    }

}