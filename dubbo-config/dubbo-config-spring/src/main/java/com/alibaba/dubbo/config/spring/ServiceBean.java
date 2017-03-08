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
package com.alibaba.dubbo.config.spring;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.AbstractApplicationContext;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ModuleConfig;
import com.alibaba.dubbo.config.MonitorConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory;

/**
 * ServiceFactoryBean
 * 继承ServiceConfig类，对应service标签
 * 实现了ApplicationListener，表明这是一个监听器，Spring Bean初始化完成后，会由Spring调用监听器的onApplicationEvent方法，dubbo就在此方法中发布服务
 * 实现了ApplicationContextAware接口，可以获取ApplicationContext
 * 实现了BeanNameAware接口，可以获得到bean自身的id
 * 实现了InitializingBean接口，可以让bean在初始化完成之后做一些自定义的操作，容器在为bean设置了属性之后，会调用afterPropertiesSet方法
 * 实现了DisposableBean接口，容器在销毁该 Bean 之前，将调用该接口的 destroy() 方法
 * 上面实现这几个接口之后，代码和Spring框架耦合度增加，不推荐使用
 *
 * 对于配置了delay属性的Bean在afterPropertiesSet方法被调用的时候就会开始暴露服务。
 * 没有配置delay属性的Bean会在bean实例化结束以后在onApplicationEvent方法中暴露服务。
 * @author william.liangf
 * @export
 */
public class ServiceBean<T> extends ServiceConfig<T> implements InitializingBean, DisposableBean, ApplicationContextAware, ApplicationListener, BeanNameAware {

	private static final long serialVersionUID = 213195494150089726L;
    //Spring的应用上下文
    private static transient ApplicationContext SPRING_CONTEXT;
    //Spring的应用上下文
	private transient ApplicationContext applicationContext;

    private transient String beanName;

    private transient boolean supportedApplicationListener;
    
	public ServiceBean() {
        super();
    }

    public ServiceBean(Service service) {
        super(service);
    }

    /**
     * 获取应用上下文的方法
     * @return
     */
    public static ApplicationContext getSpringContext() {
	    return SPRING_CONTEXT;
	}

    /**
     * 实现了ApplicationContextAware接口，Spring初始化时候，会通过该方法将ApplicationContext对象注入
     * @param applicationContext
     */
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
		//给Spring扩展点工厂类设置应用上下文
		SpringExtensionFactory.addApplicationContext(applicationContext);
		if (applicationContext != null) {
		    SPRING_CONTEXT = applicationContext;
		    try {
	            Method method = applicationContext.getClass().getMethod("addApplicationListener", new Class<?>[]{ApplicationListener.class}); // 兼容Spring2.0.1
	            method.invoke(applicationContext, new Object[] {this});
	            supportedApplicationListener = true;
	        } catch (Throwable t) {
                if (applicationContext instanceof AbstractApplicationContext) {
    	            try {
    	                Method method = AbstractApplicationContext.class.getDeclaredMethod("addListener", new Class<?>[]{ApplicationListener.class}); // 兼容Spring2.0.1
                        if (! method.isAccessible()) {
                            method.setAccessible(true);
                        }
    	                method.invoke(applicationContext, new Object[] {this});
                        supportedApplicationListener = true;
    	            } catch (Throwable t2) {
    	            }
	            }
	        }
		}
	}

    public void setBeanName(String name) {
        this.beanName = name;
    }

    //实现了ApplicationListener接口，Spring在Bean初始化以后（finishRefresh()）会调用该方法
    //dubbo在此方法中发布服务
    public void onApplicationEvent(ApplicationEvent event) {
	    //只监听容器刷新事件ContextRefreshedEvent
        if (ContextRefreshedEvent.class.getName().equals(event.getClass().getName())) {
        	if (isDelay() && ! isExported() && ! isUnexported()) {
                if (logger.isInfoEnabled()) {
                    logger.info("The service ready on spring started. service: " + getInterface());
                }
                //父类ServiceConfig中实现的方法
                export();
            }
        }
    }
    
    private boolean isDelay() {
        Integer delay = getDelay();
        ProviderConfig provider = getProvider();
        if (delay == null && provider != null) {
            delay = provider.getDelay();
        }
        return supportedApplicationListener && (delay == null || delay.intValue() == -1);
    }

    //实现了InitializingBean接口，Spring会在Bean实例化过程中的设置完属性之后调用该方法
    @SuppressWarnings({ "unchecked", "deprecation" })
	public void afterPropertiesSet() throws Exception {
        if (getProvider() == null) {
            Map<String, ProviderConfig> providerConfigMap = applicationContext == null ? null  : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProviderConfig.class, false, false);
            if (providerConfigMap != null && providerConfigMap.size() > 0) {
                Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null  : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
                if ((protocolConfigMap == null || protocolConfigMap.size() == 0)
                        && providerConfigMap.size() > 1) { // 兼容旧版本
                    List<ProviderConfig> providerConfigs = new ArrayList<ProviderConfig>();
                    for (ProviderConfig config : providerConfigMap.values()) {
                        if (config.isDefault() != null && config.isDefault().booleanValue()) {
                            providerConfigs.add(config);
                        }
                    }
                    if (providerConfigs.size() > 0) {
                        setProviders(providerConfigs);
                    }
                } else {
                    ProviderConfig providerConfig = null;
                    for (ProviderConfig config : providerConfigMap.values()) {
                        if (config.isDefault() == null || config.isDefault().booleanValue()) {
                            if (providerConfig != null) {
                                throw new IllegalStateException("Duplicate provider configs: " + providerConfig + " and " + config);
                            }
                            providerConfig = config;
                        }
                    }
                    if (providerConfig != null) {
                        setProvider(providerConfig);
                    }
                }
            }
        }
        if (getApplication() == null
                && (getProvider() == null || getProvider().getApplication() == null)) {
            Map<String, ApplicationConfig> applicationConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ApplicationConfig.class, false, false);
            if (applicationConfigMap != null && applicationConfigMap.size() > 0) {
                ApplicationConfig applicationConfig = null;
                for (ApplicationConfig config : applicationConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        if (applicationConfig != null) {
                            throw new IllegalStateException("Duplicate application configs: " + applicationConfig + " and " + config);
                        }
                        applicationConfig = config;
                    }
                }
                if (applicationConfig != null) {
                    setApplication(applicationConfig);
                }
            }
        }
        if (getModule() == null
                && (getProvider() == null || getProvider().getModule() == null)) {
            Map<String, ModuleConfig> moduleConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ModuleConfig.class, false, false);
            if (moduleConfigMap != null && moduleConfigMap.size() > 0) {
                ModuleConfig moduleConfig = null;
                for (ModuleConfig config : moduleConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        if (moduleConfig != null) {
                            throw new IllegalStateException("Duplicate module configs: " + moduleConfig + " and " + config);
                        }
                        moduleConfig = config;
                    }
                }
                if (moduleConfig != null) {
                    setModule(moduleConfig);
                }
            }
        }
        if ((getRegistries() == null || getRegistries().size() == 0)
                && (getProvider() == null || getProvider().getRegistries() == null || getProvider().getRegistries().size() == 0)
                && (getApplication() == null || getApplication().getRegistries() == null || getApplication().getRegistries().size() == 0)) {
            Map<String, RegistryConfig> registryConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, RegistryConfig.class, false, false);
            if (registryConfigMap != null && registryConfigMap.size() > 0) {
                List<RegistryConfig> registryConfigs = new ArrayList<RegistryConfig>();
                for (RegistryConfig config : registryConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        registryConfigs.add(config);
                    }
                }
                if (registryConfigs != null && registryConfigs.size() > 0) {
                    super.setRegistries(registryConfigs);
                }
            }
        }
        if (getMonitor() == null
                && (getProvider() == null || getProvider().getMonitor() == null)
                && (getApplication() == null || getApplication().getMonitor() == null)) {
            Map<String, MonitorConfig> monitorConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MonitorConfig.class, false, false);
            if (monitorConfigMap != null && monitorConfigMap.size() > 0) {
                MonitorConfig monitorConfig = null;
                for (MonitorConfig config : monitorConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        if (monitorConfig != null) {
                            throw new IllegalStateException("Duplicate monitor configs: " + monitorConfig + " and " + config);
                        }
                        monitorConfig = config;
                    }
                }
                if (monitorConfig != null) {
                    setMonitor(monitorConfig);
                }
            }
        }
        if ((getProtocols() == null || getProtocols().size() == 0)
                && (getProvider() == null || getProvider().getProtocols() == null || getProvider().getProtocols().size() == 0)) {
            Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null  : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
            if (protocolConfigMap != null && protocolConfigMap.size() > 0) {
                List<ProtocolConfig> protocolConfigs = new ArrayList<ProtocolConfig>();
                for (ProtocolConfig config : protocolConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        protocolConfigs.add(config);
                    }
                }
                if (protocolConfigs != null && protocolConfigs.size() > 0) {
                    super.setProtocols(protocolConfigs);
                }
            }
        }
        if (getPath() == null || getPath().length() == 0) {
            if (beanName != null && beanName.length() > 0 
                    && getInterface() != null && getInterface().length() > 0
                    && beanName.startsWith(getInterface())) {
                setPath(beanName);
            }
        }
        if (! isDelay()) {
            export();
        }
    }

    public void destroy() throws Exception {
        unexport();
    }

}