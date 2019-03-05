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

import java.util.ArrayList;
import java.util.List;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.support.RpcUtils;

/**
 * AbstractClusterInvoker
 * 
 * @author william.liangf
 * @author chao.liuc
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger                logger                            = LoggerFactory
                                                                                         .getLogger(AbstractClusterInvoker.class);
    protected final Directory<T>               directory;

    protected final boolean                    availablecheck;
    
    private volatile boolean                   destroyed = false;

    private volatile Invoker<T>                stickyInvoker                     = null;

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }
    
    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null)
            throw new IllegalArgumentException("service directory == null");
        
        this.directory = directory ;
        //sticky 需要检测 avaliablecheck 
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK) ;
    }

    public Class<T> getInterface() {
        return directory.getInterface();
    }

    public URL getUrl() {
        return directory.getUrl();
    }

    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    public void destroy() {
        directory.destroy();
        destroyed = true;
    }

    /**
     * 使用loadbalance选择invoker.</br>
     * a)先lb选择，如果在selected列表中 或者 不可用且做检验时，进入下一步(重选),否则直接返回</br>
     * b)重选验证规则：selected > available .保证重选出的结果尽量不在select中，并且是可用的 
     * 
     * @param availablecheck 如果设置true，在选择的时候先选invoker.available == true
     * @param selected 已选过的invoker.注意：输入保证不重复
     * 
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.size() == 0)
            return null;
        String methodName = invocation == null ? "" : invocation.getMethodName();

        /**
         * 粘滞连接用于有状态服务，尽可能让客户端总是向同一提供者发起调用，除非该提供者挂了，再连另一台。
         * 具体可以查看文档
         */
        boolean sticky = invokers.get(0).getUrl().getMethodParameter(methodName,Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY) ;
        {
            // ignore overloaded method
            /**
             * 查看invokers中是否包含stickyInvoker，
             * 如果不包含说明stickyInvoker代表的服务提供者挂了，
             * 需要置空
             */
            if ( stickyInvoker != null && !invokers.contains(stickyInvoker) ){
                stickyInvoker = null;
            }
            //ignore cucurrent problem
            if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))){
                if (availablecheck && stickyInvoker.isAvailable()){
                    return stickyInvoker;
                }
            }
        }
        // 到这里，说明stickyInvoker为空或者不可用，调用doselect选择Invoker
        Invoker<T> invoker = doselect(loadbalance, invocation, invokers, selected);
        
        if (sticky){
            stickyInvoker = invoker;
        }
        return invoker;
    }
    
    private Invoker<T> doselect(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.size() == 0)
            return null;
        if (invokers.size() == 1)
            return invokers.get(0);
        // 如果只有两个invoker，退化成轮循
        if (invokers.size() == 2 && selected != null && selected.size() > 0) {
            return selected.get(0) == invokers.get(0) ? invokers.get(1) : invokers.get(0);
        }

        /**
         * loadbalance默认为RandomLoadBalance
         * 通过具体的负载均衡组件选择Invoker
         */
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);
        
        /**
         * 如果selected中包含负载均衡选择出的Invoker，
         * 或者Invoker无法经过可用性检查，
         * 就会进行重选
         */
        if( (selected != null && selected.contains(invoker))
                ||(!invoker.isAvailable() && getUrl()!=null && availablecheck)){
            try{
                // 重选
                Invoker<T> rinvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if(rinvoker != null){
                    invoker =  rinvoker;
                }else{
                    // 看下第一次选的位置，如果不是最后，选+1位置.
                    int index = invokers.indexOf(invoker);
                    try{
                        // 最后在避免碰撞
                        invoker = index <invokers.size()-1?invokers.get(index+1) :invoker;
                    }catch (Exception e) {
                        logger.warn(e.getMessage()+" may because invokers list dynamic change, ignore.",e);
                    }
                }
            }catch (Throwable t){
                logger.error("clustor relselect fail reason is :"+t.getMessage() +" if can not slove ,you can set cluster.availablecheck=false in url",t);
            }
        }
        return invoker;
    } 
    
    /**
     * 重选，先从非selected的列表中选择，没有在从selected列表中选择.
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected
     * @return
     * @throws RpcException
     */
    private Invoker<T> reselect(LoadBalance loadbalance,Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected ,boolean availablecheck)
            throws RpcException {
        
        // 预先分配一个，这个列表是一定会用到的.
        List<Invoker<T>> reselectInvokers = new ArrayList<Invoker<T>>(invokers.size()>1?(invokers.size()-1):invokers.size());

        // 需要检查可用性
        if( availablecheck ){
            for(Invoker<T> invoker : invokers){
                if(invoker.isAvailable()){
                    // selected中不包含当前invoker，就添加到reselectInvokers中去
                    if(selected ==null || !selected.contains(invoker)){
                        reselectInvokers.add(invoker);
                    }
                }
            }
            // reselectInvoker不为空，通过负载均衡进行选择
            if(reselectInvokers.size()>0){
                return  loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }else{
            // 不需要检查可用性
            for(Invoker<T> invoker : invokers){
                if(selected ==null || !selected.contains(invoker)){
                    reselectInvokers.add(invoker);
                }
            }
            if(reselectInvokers.size()>0){
                return  loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        /**
         * 走到这里的话，说明reselectInvokers集合为空
         * 从selected中查找一个可用的invoker，添加到reselectInvokers中
         */
        {
            if(selected != null){
                for(Invoker<T> invoker : selected){
                    if((invoker.isAvailable()) //优先选available 
                            && !reselectInvokers.contains(invoker)){
                        reselectInvokers.add(invoker);
                    }
                }
            }
            // 进行选择
            if(reselectInvokers.size()>0){
                return  loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        return null;
    }
    
    public Result invoke(final Invocation invocation) throws RpcException {

        checkWhetherDestroyed();

        LoadBalance loadbalance;

        // 先获取所有的Invoker列表，是从Directory中获取的
        List<Invoker<T>> invokers = list(invocation);
        if (invokers != null && invokers.size() > 0) {
            // 加载LoadBalance，默认是random
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(invocation.getMethodName(),Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        } else {
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }
        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);
        // doInvoke子类实现
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {

        if(destroyed){
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }
    
    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (invokers == null || invokers.size() == 0) {
            throw new RpcException("Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName() 
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress() 
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;
    
    protected  List<Invoker<T>> list(Invocation invocation) throws RpcException {
    	List<Invoker<T>> invokers = directory.list(invocation);
    	return invokers;
    }
}