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
package com.alibaba.dubbo.registry.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;

/**
 * RegistryProtocol
 * 
 * @author william.liangf
 * @author chao.liuc
 */
public class RegistryProtocol implements Protocol {

    private Cluster cluster;
    
    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }
    
    private Protocol protocol;
    
    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    private RegistryFactory registryFactory;
    
    public void setRegistryFactory(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    private ProxyFactory proxyFactory;
    
    public void setProxyFactory(ProxyFactory proxyFactory) {
        this.proxyFactory = proxyFactory;
    }

    public int getDefaultPort() {
        return 9090;
    }
    
    private static RegistryProtocol INSTANCE;

    public RegistryProtocol() {
        INSTANCE = this;
    }
    
    public static RegistryProtocol getRegistryProtocol() {
        if (INSTANCE == null) {
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(Constants.REGISTRY_PROTOCOL); // load
        }
        return INSTANCE;
    }
    
    private final Map<URL, NotifyListener> overrideListeners = new ConcurrentHashMap<URL, NotifyListener>();
    
    public Map<URL, NotifyListener> getOverrideListeners() {
		return overrideListeners;
	}

    /**
     * 用于解决rmi重复暴露端口冲突的问题，已经暴露过的服务不再重新暴露
     * providerurl <--> exporter
     */
    private final Map<String, ExporterChangeableWrapper<?>> bounds = new ConcurrentHashMap<String, ExporterChangeableWrapper<?>>();
    
    private final static Logger logger = LoggerFactory.getLogger(RegistryProtocol.class);
    
    public <T> Exporter<T> export(final Invoker<T> originInvoker) throws RpcException {
        //export invoker
        // 导出服务，本地启动服务，但不包括向注册中心注册服务
        final ExporterChangeableWrapper<T> exporter = doLocalExport(originInvoker);
        //registry provider
        /**
         * 获取Registry的实现类，由于我们配置的注册中心是zookeeper，url中的registry=zookeeper
         * 所以这里我们获取到的实现类是ZookeeperRegistry
         */
        final Registry registry = getRegistry(originInvoker);
        // 获取已经注册的服务提供者的URL，就是dubbo://开头的
        final URL registedProviderUrl = getRegistedProviderUrl(originInvoker);
        /**
         * 向注册中心注册服务提供者，就是自己，我们这就是写zookeeper结点
         * ZookeeperRegistry继承了FailbackRegistry，
         * FailbackRegistry继承了AbstractRegistry
         */
        registry.register(registedProviderUrl);
        // 订阅override数据
        // FIXME 提供者订阅时，会影响同一JVM即暴露服务，又引用同一服务的的场景，因为subscribed以服务名为缓存的key，导致订阅信息覆盖。
        // 获取订阅的URL，就是以provider://开头的
        final URL overrideSubscribeUrl = getSubscribedOverrideUrl(registedProviderUrl);
        // 创建override监听器
        final OverrideListener overrideSubscribeListener = new OverrideListener(overrideSubscribeUrl);
        overrideListeners.put(overrideSubscribeUrl, overrideSubscribeListener);
        // 向注册中心订阅override数据
        registry.subscribe(overrideSubscribeUrl, overrideSubscribeListener);
        // 保证每次export都返回一个新的exporter实例
        return new Exporter<T>() {
            public Invoker<T> getInvoker() {
                return exporter.getInvoker();
            }
            public void unexport() {
            	try {
            		exporter.unexport();
            	} catch (Throwable t) {
                	logger.warn(t.getMessage(), t);
                }
                try {
                	registry.unregister(registedProviderUrl);
                } catch (Throwable t) {
                	logger.warn(t.getMessage(), t);
                }
                try {
                	overrideListeners.remove(overrideSubscribeUrl);
                	registry.unsubscribe(overrideSubscribeUrl, overrideSubscribeListener);
                } catch (Throwable t) {
                	logger.warn(t.getMessage(), t);
                }
            }
        };
    }
    
    @SuppressWarnings("unchecked")
    private <T> ExporterChangeableWrapper<T>  doLocalExport(final Invoker<T> originInvoker){
        // 获取key，该key用于缓存导出的服务，具体可看bounds上的注释，key是provider的url
        String key = getCacheKey(originInvoker);
        // 先从缓存中获取，不存在就导出添加到缓存中
        ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null) {
            synchronized (bounds) {
                exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
                if (exporter == null) {
                    /**
                     * 先创建了一个Invoker代理类，里面持有原来的Invoker(registry://开头的)
                     * 还持有服务提供者的URL（dubbo://开头的）
                     */
                    final Invoker<?> invokerDelegete = new InvokerDelegete<T>(originInvoker, getProviderUrl(originInvoker));
                    /**
                     * 先使用具体协议的protocol进行导出服务，我们这里是DubboProtocol
                     * 然后将导出的Exporter和原来的Invoker封装成ExporterChangeableWrapper实例返回
                     */
                    exporter = new ExporterChangeableWrapper<T>((Exporter<T>)protocol.export(invokerDelegete), originInvoker);
                    // 已经暴露的服务添加到缓存中
                    bounds.put(key, exporter);
                }
            }
        }
        return (ExporterChangeableWrapper<T>) exporter;
    }
    
    /**
     * 对修改了url的invoker重新export
     * @param originInvoker
     * @param newInvokerUrl
     */
    @SuppressWarnings("unchecked")
    private <T> void doChangeLocalExport(final Invoker<T> originInvoker, URL newInvokerUrl){
        String key = getCacheKey(originInvoker);
        final ExporterChangeableWrapper<T> exporter = (ExporterChangeableWrapper<T>) bounds.get(key);
        if (exporter == null){
            logger.warn(new IllegalStateException("error state, exporter should not be null"));
            return ;//不存在是异常场景 直接返回 
        } else {
            final Invoker<T> invokerDelegete = new InvokerDelegete<T>(originInvoker, newInvokerUrl);
            exporter.setExporter(protocol.export(invokerDelegete));
        }
    }

    /**
     * 根据invoker的地址获取registry实例
     * @param originInvoker
     * @return
     */
    private Registry getRegistry(final Invoker<?> originInvoker){
        // 获取registryUrl，就是以registry://开头的
        URL registryUrl = originInvoker.getUrl();
        // 获取url中的registry属性，我们这里用的是zookeeper
        if (Constants.REGISTRY_PROTOCOL.equals(registryUrl.getProtocol())) {
            String protocol = registryUrl.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_DIRECTORY);
            registryUrl = registryUrl.setProtocol(protocol).removeParameter(Constants.REGISTRY_KEY);
        }
        // registryFactory也是spi生成的代码，这里最后获得是ZookeeperRegistry
        return registryFactory.getRegistry(registryUrl);
    }

    /**
     * 返回注册到注册中心的URL，对URL参数进行一次过滤
     * @param originInvoker
     * @return
     */
    private URL getRegistedProviderUrl(final Invoker<?> originInvoker){
        URL providerUrl = getProviderUrl(originInvoker);
        // 注册中心看到的地址
        final URL registedProviderUrl = providerUrl.removeParameters(getFilteredKeys(providerUrl)).removeParameter(Constants.MONITOR_KEY);
        return registedProviderUrl;
    }
    
    private URL getSubscribedOverrideUrl(URL registedProviderUrl){
    	return registedProviderUrl.setProtocol(Constants.PROVIDER_PROTOCOL)
                .addParameters(Constants.CATEGORY_KEY, Constants.CONFIGURATORS_CATEGORY, 
                        Constants.CHECK_KEY, String.valueOf(false));
    }

    /**
     * 通过invoker的url 获取 providerUrl的地址
     * @param origininvoker
     * @return
     */
    private URL getProviderUrl(final Invoker<?> origininvoker){
        String export = origininvoker.getUrl().getParameterAndDecoded(Constants.EXPORT_KEY);
        if (export == null || export.length() == 0) {
            throw new IllegalArgumentException("The registry export url is null! registry: " + origininvoker.getUrl());
        }
        
        URL providerUrl = URL.valueOf(export);
        return providerUrl;
    }

    /**
     * 获取invoker在bounds中缓存的key
     * @param originInvoker
     * @return
     */
    private String getCacheKey(final Invoker<?> originInvoker){
        URL providerUrl = getProviderUrl(originInvoker);
        String key = providerUrl.removeParameters("dynamic", "enabled").toFullString();
        return key;
    }
    
    @SuppressWarnings("unchecked")
	public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        // 从url中得到protocol，我们这里是zookeeper，然后删除url中的registry属性
        url = url.setProtocol(url.getParameter(Constants.REGISTRY_KEY, Constants.DEFAULT_REGISTRY)).removeParameter(Constants.REGISTRY_KEY);
        /**
         * 获取Registry的实现类，由于我们配置的注册中心是zookeeper
         * 所以这里我们获取到的实现类是ZookeeperRegistry
         */
        Registry registry = registryFactory.getRegistry(url);
        if (RegistryService.class.equals(type)) {
        	return proxyFactory.getInvoker((T) registry, type, url);
        }

        // group="a,b" or group="*"
        // 配置了group
        Map<String, String> qs = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        String group = qs.get(Constants.GROUP_KEY);
        if (group != null && group.length() > 0 ) {
            if ( ( Constants.COMMA_SPLIT_PATTERN.split( group ) ).length > 1
                    || "*".equals( group ) ) {
                return doRefer( getMergeableCluster(), registry, type, url );
            }
        }
        // 引用服务
        return doRefer(cluster, registry, type, url);
    }
    
    private Cluster getMergeableCluster() {
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension("mergeable");
    }
    
    private <T> Invoker<T> doRefer(Cluster cluster, Registry registry, Class<T> type, URL url) {
        /**
         * 实例化一个RegistryDirectory，Directory类似一个List，
         * 内部持有多个Invoker的列表。
         * RegistryDirectory是动态的，他实现了监听接口，里面的Invoker是可以变化的。
         * 还有一个StaticDirectory是静态的，他内部的Invoker是不可变的。
         */
        RegistryDirectory<T> directory = new RegistryDirectory<T>(type, url);
        directory.setRegistry(registry);
        directory.setProtocol(protocol);
        // 获取消费者服务的URL，以consumer://开头
        URL subscribeUrl = new URL(Constants.CONSUMER_PROTOCOL, NetUtils.getLocalHost(), 0, type.getName(), directory.getUrl().getParameters());
        if (! Constants.ANY_VALUE.equals(url.getServiceInterface())
                && url.getParameter(Constants.REGISTER_KEY, true)) {
            // 注册到注册中心去
            registry.register(subscribeUrl.addParameters(Constants.CATEGORY_KEY, Constants.CONSUMERS_CATEGORY,
                    Constants.CHECK_KEY, String.valueOf(false)));
        }
        // 订阅providers、configurators、routers等结点
        // 发起服务提供者、路由规则、配置规则的数据订阅
        directory.subscribe(subscribeUrl.addParameter(Constants.CATEGORY_KEY, 
                Constants.PROVIDERS_CATEGORY 
                + "," + Constants.CONFIGURATORS_CATEGORY 
                + "," + Constants.ROUTERS_CATEGORY));
        // 一个注册中心可能有多个服务提供者，这里需要将多个提供者合并为一个Invoker
        return cluster.join(directory);
    }

    //过滤URL中不需要输出的参数(以点号开头的)
    private static String[] getFilteredKeys(URL url) {
        Map<String, String> params = url.getParameters();
        if (params != null && !params.isEmpty()) {
            List<String> filteredKeys = new ArrayList<String>();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (entry != null && entry.getKey() != null && entry.getKey().startsWith(Constants.HIDE_KEY_PREFIX)) {
                    filteredKeys.add(entry.getKey());
                }
            }
            return filteredKeys.toArray(new String[filteredKeys.size()]);
        } else {
            return new String[] {};
        }
    }
    
    public void destroy() {
        List<Exporter<?>> exporters = new ArrayList<Exporter<?>>(bounds.values());
        for(Exporter<?> exporter :exporters){
            exporter.unexport();
        }
        bounds.clear();
    }
    
    
    /*重新export 1.protocol中的exporter destory问题 
     *1.要求registryprotocol返回的exporter可以正常destroy
     *2.notify后不需要重新向注册中心注册 
     *3.export 方法传入的invoker最好能一直作为exporter的invoker.
     */
    private class OverrideListener implements NotifyListener {
    	
    	private volatile List<Configurator> configurators;
    	
    	private final URL subscribeUrl;

		public OverrideListener(URL subscribeUrl) {
			this.subscribeUrl = subscribeUrl;
		}

		/*
         *  provider 端可识别的override url只有这两种.
         *  override://0.0.0.0/serviceName?timeout=10
         *  override://0.0.0.0/?timeout=10
         */
        public void notify(List<URL> urls) {
        	List<URL> result = null;
        	for (URL url : urls) {
        		URL overrideUrl = url;
        		if (url.getParameter(Constants.CATEGORY_KEY) == null
        				&& Constants.OVERRIDE_PROTOCOL.equals(url.getProtocol())) {
        			// 兼容旧版本
        			overrideUrl = url.addParameter(Constants.CATEGORY_KEY, Constants.CONFIGURATORS_CATEGORY);
        		}
        		if (! UrlUtils.isMatch(subscribeUrl, overrideUrl)) {
        			if (result == null) {
        				result = new ArrayList<URL>(urls);
        			}
        			result.remove(url);
        			logger.warn("Subsribe category=configurator, but notifed non-configurator urls. may be registry bug. unexcepted url: " + url);
        		}
        	}
        	if (result != null) {
        		urls = result;
        	}
        	this.configurators = RegistryDirectory.toConfigurators(urls);
            List<ExporterChangeableWrapper<?>> exporters = new ArrayList<ExporterChangeableWrapper<?>>(bounds.values());
            for (ExporterChangeableWrapper<?> exporter : exporters){
                Invoker<?> invoker = exporter.getOriginInvoker();
                final Invoker<?> originInvoker ;
                if (invoker instanceof InvokerDelegete){
                    originInvoker = ((InvokerDelegete<?>)invoker).getInvoker();
                }else {
                    originInvoker = invoker;
                }
                
                URL originUrl = RegistryProtocol.this.getProviderUrl(originInvoker);
                URL newUrl = getNewInvokerUrl(originUrl, urls);
                
                if (! originUrl.equals(newUrl)){
                    RegistryProtocol.this.doChangeLocalExport(originInvoker, newUrl);
                }
            }
        }
        
        private URL getNewInvokerUrl(URL url, List<URL> urls){
        	List<Configurator> localConfigurators = this.configurators; // local reference
            // 合并override参数
            if (localConfigurators != null && localConfigurators.size() > 0) {
                for (Configurator configurator : localConfigurators) {
                    url = configurator.configure(url);
                }
            }
            return url;
        }
    }
    
    public static class InvokerDelegete<T> extends InvokerWrapper<T>{
        private final Invoker<T> invoker;
        /**
         * @param invoker 
         * @param url invoker.getUrl返回此值
         */
        public InvokerDelegete(Invoker<T> invoker, URL url){
            super(invoker, url);
            this.invoker = invoker;
        }
        public Invoker<T> getInvoker(){
            if (invoker instanceof InvokerDelegete){
                return ((InvokerDelegete<T>)invoker).getInvoker();
            } else {
                return invoker;
            }
        }
    }
    
    /**
     * exporter代理,建立返回的exporter与protocol export出的exporter的对应关系，在override时可以进行关系修改.
     * 
     * @author chao.liuc
     *
     * @param <T>
     */
    private class ExporterChangeableWrapper<T> implements Exporter<T>{
    	
        private Exporter<T> exporter;
        
        private final Invoker<T> originInvoker;

        public ExporterChangeableWrapper(Exporter<T> exporter, Invoker<T> originInvoker){
            this.exporter = exporter;
            this.originInvoker = originInvoker;
        }
        
        public Invoker<T> getOriginInvoker() {
            return originInvoker;
        }

        public Invoker<T> getInvoker() {
            return exporter.getInvoker();
        }
        
        public void setExporter(Exporter<T> exporter){
            this.exporter = exporter;
        }

        public void unexport() {
            String key = getCacheKey(this.originInvoker);
            bounds.remove(key);
            exporter.unexport();
        }
    }
}