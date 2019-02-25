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
package com.alibaba.dubbo.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.config.support.Parameter;
import com.alibaba.dubbo.monitor.MonitorFactory;
import com.alibaba.dubbo.monitor.MonitorService;
import com.alibaba.dubbo.registry.RegistryFactory;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.InvokerListener;
import com.alibaba.dubbo.rpc.ProxyFactory;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.support.MockInvoker;

/**
 * AbstractDefaultConfig
 * 
 * @author william.liangf
 * @export
 */
public abstract class AbstractInterfaceConfig extends AbstractMethodConfig {

    private static final long      serialVersionUID = -1559314110797223229L;

    // 服务接口的本地实现类名
    protected String               local;

    // 服务接口的本地实现类名
    protected String               stub;

    // 服务监控
    protected MonitorConfig        monitor;
    
    // 代理类型
    protected String               proxy;
    
    // 集群方式
    protected String               cluster;

    // 过滤器
    protected String               filter;
    
    // 监听器
    protected String               listener;

    // 负责人
    protected String               owner;

    // 连接数限制,0表示共享连接，否则为该服务独享连接数
    protected Integer              connections;
    
    // 连接数限制
    protected String               layer;
    
    // 应用信息
    protected ApplicationConfig    application;
    
    // 模块信息
    protected ModuleConfig         module;

    // 注册中心
    protected List<RegistryConfig> registries;
    
    // callback实例个数限制
    private Integer                callbacks;
    
    // 连接事件
    protected String              onconnect;
    
    // 断开事件
    protected String              ondisconnect;

    // 服务暴露或引用的scope,如果为local，则表示只在当前JVM内查找.
	private String scope;

    protected void checkRegistry() {
        // 兼容旧版本
        if (registries == null || registries.size() == 0) {
            // 获取注册中心地址
            String address = ConfigUtils.getProperty("dubbo.registry.address");
            if (address != null && address.length() > 0) {
                // 保存注册中心的list
                registries = new ArrayList<RegistryConfig>();
                String[] as = address.split("\\s*[|]+\\s*");
                for (String a : as) {
                    RegistryConfig registryConfig = new RegistryConfig();
                    registryConfig.setAddress(a);
                    registries.add(registryConfig);
                }
            }
        }
        if ((registries == null || registries.size() == 0)) {
            throw new IllegalStateException((getClass().getSimpleName().startsWith("Reference") 
                    ? "No such any registry to refer service in consumer " 
                        : "No such any registry to export service in provider ")
                                                    + NetUtils.getLocalHost()
                                                    + " use dubbo version "
                                                    + Version.getVersion()
                                                    + ", Please add <dubbo:registry address=\"...\" /> to your spring config. If you want unregister, please set <dubbo:service registry=\"N/A\" />");
        }
        // 添加属性
        for (RegistryConfig registryConfig : registries) {
            appendProperties(registryConfig);
        }
    }

    @SuppressWarnings("deprecation")
    protected void checkApplication() {
        // 兼容旧版本
        if (application == null) {
            String applicationName = ConfigUtils.getProperty("dubbo.application.name");
            if (applicationName != null && applicationName.length() > 0) {
                application = new ApplicationConfig();
            }
        }
        if (application == null) {
            throw new IllegalStateException(
                                            "No such application config! Please add <dubbo:application name=\"...\" /> to your spring config.");
        }
        // 填充各种Application的信息
        appendProperties(application);
        
        String wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_KEY);
        if (wait != null && wait.trim().length() > 0) {
            System.setProperty(Constants.SHUTDOWN_WAIT_KEY, wait.trim());
        } else {
            wait = ConfigUtils.getProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY);
            if (wait != null && wait.trim().length() > 0) {
                System.setProperty(Constants.SHUTDOWN_WAIT_SECONDS_KEY, wait.trim());
            }
        }
    }

    /**
     * 加载注册中心地址
     * 如果xml没有配置，就从dubbo.properties中获取
     * @param provider
     * @return
     */
    protected List<URL> loadRegistries(boolean provider) {
        // 检查注册中心配置，如果不存在抛异常；如果存在填充各种Registry属性
        checkRegistry();
        // 存放注册中心的URL
        List<URL> registryList = new ArrayList<URL>();
        if (registries != null && registries.size() > 0) {
            // 遍历注册中心配置
            for (RegistryConfig config : registries) {
                // 获取配置中的注册中心地址
                String address = config.getAddress();
                if (address == null || address.length() == 0) {
                    // 没配置，使用默认的0.0.0.0
                	address = Constants.ANYHOST_VALUE;
                }
                // 尝试从系统属性中获取
                String sysaddress = System.getProperty("dubbo.registry.address");
                if (sysaddress != null && sysaddress.length() > 0) {
                    address = sysaddress;
                }
                if (address != null && address.length() > 0 
                        && ! RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(address)) {
                    Map<String, String> map = new HashMap<String, String>();
                    // 配置的application中的属性取出来放到map中
                    appendParameters(map, application);
                    // 配置的registry中的属性取出来放到map中
                    appendParameters(map, config);
                    // 服务名
                    map.put("path", RegistryService.class.getName());
                    // dubbo的版本
                    map.put("dubbo", Version.getVersion());
                    // 时间戳
                    map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
                    // pid
                    if (ConfigUtils.getPid() > 0) {
                        map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
                    }
                    // map中不存在protocol，说明没有配置procotol
                    if (! map.containsKey("protocol")) {
                        // 查看有没有名为remote的Registry的实现类
                        if (ExtensionLoader.getExtensionLoader(RegistryFactory.class).hasExtension("remote")) {
                            map.put("protocol", "remote");
                        } else {
                            // 没有的话Protocol使用dubbo
                            map.put("protocol", "dubbo");
                        }
                    }
                    /**
                     * 解析生成url
                     * zookeeper://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=dubbo-provider&application.version=1.0&
                     * dubbo=2.5.3&environment=product&organization=china&owner=cheng.xi&pid=17268&timestamp=1488986530185
                     * addres可能配置了多个，所有要解析成一个URL列表
                     */
                    List<URL> urls = UrlUtils.parseURLs(address, map);
                    for (URL url : urls) {
                        // URL协议头设置为registry
                        url = url.addParameter(Constants.REGISTRY_KEY, url.getProtocol());
                        url = url.setProtocol(Constants.REGISTRY_PROTOCOL);
                        if ((provider && url.getParameter(Constants.REGISTER_KEY, true))
                                || (! provider && url.getParameter(Constants.SUBSCRIBE_KEY, true))) {
                            registryList.add(url);
                        }
                    }
                }
            }
        }
        /**
         * 返回的url为registry://127.0.0.1:2181/com.alibaba.dubbo.registry.RegistryService?application=dubbo-provider&application.version=1.0&dubbo=2.5.3&
         * environment=product&organization=china&owner=cheng.xi&pid=17268&registry=zookeeper&timestamp=1488986530185
         */
        return registryList;
    }
    
    protected URL loadMonitor(URL registryURL) {
        if (monitor == null) {
            String monitorAddress = ConfigUtils.getProperty("dubbo.monitor.address");
            String monitorProtocol = ConfigUtils.getProperty("dubbo.monitor.protocol");
            if (monitorAddress != null && monitorAddress.length() > 0
                    || monitorProtocol != null && monitorProtocol.length() > 0) {
                monitor = new MonitorConfig();
            } else {
                return null;
            }
        }
        appendProperties(monitor);
        Map<String, String> map = new HashMap<String, String>();
        map.put(Constants.INTERFACE_KEY, MonitorService.class.getName());
        map.put("dubbo", Version.getVersion());
        map.put(Constants.TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));
        if (ConfigUtils.getPid() > 0) {
            map.put(Constants.PID_KEY, String.valueOf(ConfigUtils.getPid()));
        }
        appendParameters(map, monitor);
        String address = monitor.getAddress();
        String sysaddress = System.getProperty("dubbo.monitor.address");
        if (sysaddress != null && sysaddress.length() > 0) {
            address = sysaddress;
        }
        if (ConfigUtils.isNotEmpty(address)) {
            if (! map.containsKey(Constants.PROTOCOL_KEY)) {
                if (ExtensionLoader.getExtensionLoader(MonitorFactory.class).hasExtension("logstat")) {
                    map.put(Constants.PROTOCOL_KEY, "logstat");
                } else {
                    map.put(Constants.PROTOCOL_KEY, "dubbo");
                }
            }
            return UrlUtils.parseURL(address, map);
        } else if (Constants.REGISTRY_PROTOCOL.equals(monitor.getProtocol()) && registryURL != null) {
            return registryURL.setProtocol("dubbo").addParameter(Constants.PROTOCOL_KEY, "registry").addParameterAndEncoded(Constants.REFER_KEY, StringUtils.toQueryString(map));
        }
        return null;
    }
    
    protected void checkInterfaceAndMethods(Class<?> interfaceClass, List<MethodConfig> methods) {
        // 接口不能为空
        if (interfaceClass == null) {
            throw new IllegalStateException("interface not allow null!");
        }
        // 检查接口类型必需为接口
        if(! interfaceClass.isInterface()) { 
            throw new IllegalStateException("The interface class " + interfaceClass + " is not a interface!");
        }
        // 检查方法是否在接口中存在
        if (methods != null && methods.size() > 0) {
            for (MethodConfig methodBean : methods) {
                String methodName = methodBean.getName();
                if (methodName == null || methodName.length() == 0) {
                    throw new IllegalStateException("<dubbo:method> name attribute is required! Please check: <dubbo:service interface=\"" + interfaceClass.getName() + "\" ... ><dubbo:method name=\"\" ... /></<dubbo:reference>");
                }
                boolean hasMethod = false;
                for (java.lang.reflect.Method method : interfaceClass.getMethods()) {
                    if (method.getName().equals(methodName)) {
                        hasMethod = true;
                        break;
                    }
                }
                if (!hasMethod) {
                    throw new IllegalStateException("The interface " + interfaceClass.getName()
                            + " not found method " + methodName);
                }
            }
        }
    }
    
    protected void checkStubAndMock(Class<?> interfaceClass) {
        if (ConfigUtils.isNotEmpty(local)) {
            Class<?> localClass = ConfigUtils.isDefault(local) ? ReflectUtils.forName(interfaceClass.getName() + "Local") : ReflectUtils.forName(local);
            if (! interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implemention class " + localClass.getName() + " not implement interface " + interfaceClass.getName());
            }
            try {
                ReflectUtils.findConstructor(localClass, interfaceClass);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "(" + interfaceClass.getName() + ")\" in local implemention class " + localClass.getName());
            }
        }
        if (ConfigUtils.isNotEmpty(stub)) {
            Class<?> localClass = ConfigUtils.isDefault(stub) ? ReflectUtils.forName(interfaceClass.getName() + "Stub") : ReflectUtils.forName(stub);
            if (! interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implemention class " + localClass.getName() + " not implement interface " + interfaceClass.getName());
            }
            try {
                ReflectUtils.findConstructor(localClass, interfaceClass);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("No such constructor \"public " + localClass.getSimpleName() + "(" + interfaceClass.getName() + ")\" in local implemention class " + localClass.getName());
            }
        }
        if (ConfigUtils.isNotEmpty(mock)) {
            if (mock.startsWith(Constants.RETURN_PREFIX)) {
                String value = mock.substring(Constants.RETURN_PREFIX.length());
                try {
                    MockInvoker.parseMockValue(value);
                } catch (Exception e) {
                    throw new IllegalStateException("Illegal mock json value in <dubbo:service ... mock=\"" + mock + "\" />");
                }
            } else {
                Class<?> mockClass = ConfigUtils.isDefault(mock) ? ReflectUtils.forName(interfaceClass.getName() + "Mock") : ReflectUtils.forName(mock);
                if (! interfaceClass.isAssignableFrom(mockClass)) {
                    throw new IllegalStateException("The mock implemention class " + mockClass.getName() + " not implement interface " + interfaceClass.getName());
                }
                try {
                    mockClass.getConstructor(new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException("No such empty constructor \"public " + mockClass.getSimpleName() + "()\" in mock implemention class " + mockClass.getName());
                }
            }
        }
    }

    /**
     * @deprecated Replace to <code>getStub()</code>
     * @return local
     */
    @Deprecated
    public String getLocal() {
        return local;
    }

    /**
     * @deprecated Replace to <code>setStub(String)</code>
     * @param local
     */
    @Deprecated
    public void setLocal(String local) {
        checkName("local", local);
        this.local = local;
    }

    /**
     * @deprecated Replace to <code>setStub(Boolean)</code>
     * @param local
     */
    @Deprecated
    public void setLocal(Boolean local) {
        if (local == null) {
            setLocal((String) null);
        } else {
            setLocal(String.valueOf(local));
        }
    }
    
    public String getStub() {
        return stub;
    }

    public void setStub(String stub) {
        checkName("stub", stub);
        this.stub = stub;
    }

    public void setStub(Boolean stub) {
        if (local == null) {
            setStub((String) null);
        } else {
            setStub(String.valueOf(stub));
        }
    }
    
    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        checkExtension(Cluster.class, "cluster", cluster);
        this.cluster = cluster;
    }

    public String getProxy() {
        return proxy;
    }
    
    public void setProxy(String proxy) {
        checkExtension(ProxyFactory.class, "proxy", proxy);
        this.proxy = proxy;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    @Parameter(key = Constants.REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return filter;
    }
    
    public void setFilter(String filter) {
        checkMultiExtension(Filter.class, "filter", filter);
        this.filter = filter;
    }

    @Parameter(key = Constants.INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        checkMultiExtension(InvokerListener.class, "listener", listener);
        return listener;
    }
    
    public void setListener(String listener) {
        this.listener = listener;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        checkNameHasSymbol("layer", layer);
        this.layer = layer;
    }

    public ApplicationConfig getApplication() {
        return application;
    }

    public void setApplication(ApplicationConfig application) {
        this.application = application;
    }

    public ModuleConfig getModule() {
        return module;
    }

    public void setModule(ModuleConfig module) {
        this.module = module;
    }

    public RegistryConfig getRegistry() {
        return registries == null || registries.size() == 0 ? null : registries.get(0);
    }

    public void setRegistry(RegistryConfig registry) {
        List<RegistryConfig> registries = new ArrayList<RegistryConfig>(1);
        registries.add(registry);
        this.registries = registries;
    }

    public List<RegistryConfig> getRegistries() {
        return registries;
    }

    @SuppressWarnings({ "unchecked" })
    public void setRegistries(List<? extends RegistryConfig> registries) {
        this.registries = (List<RegistryConfig>)registries;
    }

    public MonitorConfig getMonitor() {
        return monitor;
    }

    public void setMonitor(MonitorConfig monitor) {
        this.monitor = monitor;
    }

    public void setMonitor(String monitor) {
        this.monitor = new MonitorConfig(monitor);
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
    	checkMultiName("owner", owner);
        this.owner = owner;
    }

    public void setCallbacks(Integer callbacks) {
        this.callbacks = callbacks;
    }

    public Integer getCallbacks() {
        return callbacks;
    }

    public String getOnconnect() {
        return onconnect;
    }

    public void setOnconnect(String onconnect) {
        this.onconnect = onconnect;
    }

    public String getOndisconnect() {
        return ondisconnect;
    }

    public void setOndisconnect(String ondisconnect) {
        this.ondisconnect = ondisconnect;
    }

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

}