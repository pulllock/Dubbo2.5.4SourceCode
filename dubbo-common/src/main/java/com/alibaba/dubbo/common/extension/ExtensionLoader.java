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
package com.alibaba.dubbo.common.extension;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

/**
 * Dubbo使用的扩展点获取。<p>
 * <ul>
 * <li>自动注入关联扩展点。</li>
 * <li>自动Wrap上扩展点的Wrap类。</li>
 * <li>缺省获得的的扩展点是一个Adaptive Instance。
 * </ul>
 * 扩展点加载器，查找，校验，加载扩展点
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">JDK5.0的自动发现机制实现</a>
 * 
 * @author william.liangf
 * @author ding.lid
 *
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);
    //
    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";
    
    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");
    /**
     * ExtensionLoader实例的缓存
     * 每个扩展点都会有一个ExtensionLoader缓存
     * 比如Protocol就会有一个，key是com.alibaba.dubbo.rpc.Protocol
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();
    /**
     * 扩展点的具体实现的实例
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================
    /**
     * 当前扩展点的类型，也就是注解了@SPI的扩展点接口
     */
    private final Class<?> type;
    /**
     * 扩展点ExtesionFactory的adaptive对象。
     * dubbo的这个框架是自包含的，例如这个框架自身实现的代码ExtesionFactory也使用了扩展点的能力。
     */
    private final ExtensionFactory objectFactory;
    /**
     * 扩展类和扩展名的映射关系缓存
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();
    /**
     * 用来缓存当前扩展点所有的实现类的Class
     * 在map中key是配置文件中的key，value是对应的实现类
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String,Class<?>>>();

    /**
     * 拓展名与@Active的映射
     */
    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();
    /**
     * 自适应实现类的Class缓存
     * 注解了@Adaptive的类
     */

    private volatile Class<?> cachedAdaptiveClass = null;

    /**
     * 缓存了扩展点具体实现的实例，key是扩展点的名字，
     * 比如DubboProtocol，key是dubbo，value是DubboProtocol的实例
     * Hodler中有具体实现的实例
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    /**
     * 没有指定扩展点名字时，默认使用的名字,（注解上会有个默认名字）
     */
    private String cachedDefaultName;
    /**
     * 用来缓存自适应扩展实现的实例
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    private volatile Throwable createAdaptiveInstanceError;

    /**
     * 包装类的Class缓存
     */
    private Set<Class<?>> cachedWrapperClasses;
    
    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    /**
     * 判断是否添加了@SPI注解
     */
    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    /**
     * 每一个ExtensionLoader实例仅负责加载特定的SPI扩展的实现
     * @param type 某个扩展点的类型
     * @param <T>
     * @return
     *
     * 1. 各种校验
     * 2. 从缓存中获取指定类型的ExtensionLoader实例
     * 3. 如果缓存中不存在，就新建个ExtensionLoader实例，并放到缓存中
     * 4. 返回ExtensionLoader实例
     */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        // 扩展点类型不能为空
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        // 扩展点类型只能是接口类型的
        if(!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        // 没有添加@SPI注解
        if(!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type + 
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }
        // 先从缓存中获取指定类型的ExtensionLoader
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        // 缓存中不存在
        if (loader == null) {
            /**
             * 创建一个新的ExtensionLoader实例，放到缓存中去
             * 对于每一个扩展，dubbo中只有个对应的ExtensionLoader实例
             */
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    /**
     * 实例化ExtensionLoader
     * @param type
     * 1. 将当前类型赋值给当前ExtensionLoader实例的type字段
     * 2. 为当前实例创建一个Extension工厂
     */
    private ExtensionLoader(Class<?> type) {
        this.type = type;
        /**
         * 对于扩展类型是ExtensionFactory的，设置为null
         * getAdaptiveExtension方法获取一个运行时自适应的扩展类型
         * 每个Extension只能有一个@Adaptive类型的实现，如果么有，dubbo会自动生成一个类
         * objectFactory是一个ExtensionFactory类型的属性，主要用于加载扩展的实现
         */

        objectFactory = (
                type == ExtensionFactory.class ?
                        null :
                        ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension()
        );
    }
    
    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to <pre>
     *     getActivateExtension(url, key, null);
     * </pre>
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to <pre>
     *     getActivateExtension(url, values, null);
     * </pre>
     *
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     * @param url url
     * @param values extension point names
     * @return extension list which are activated
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to <pre>
     *     getActivateExtension(url, url.getParameter(key).split(","), null);
     * </pre>
     *
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     * 根据条件获取当前扩展可自动激活的实现
     * @see com.alibaba.dubbo.common.extension.Activate
     * @param url url
     * @param values extension point names
     * @param group group
     * @return extension list which are activated
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        if (! names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            getExtensionClasses();
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Activate activate = entry.getValue();
                if (isMatchGroup(group, activate.group())) {
                    T ext = getExtension(name);
                    if (! names.contains(name)
                            && ! names.contains(Constants.REMOVE_VALUE_PREFIX + name) 
                            && isActive(activate, url)) {
                        exts.add(ext);
                    }
                }
            }
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i ++) {
        	String name = names.get(i);
            if (! name.startsWith(Constants.REMOVE_VALUE_PREFIX)
            		&& ! names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
            	if (Constants.DEFAULT_KEY.equals(name)) {
            		if (usrs.size() > 0) {
	            		exts.addAll(0, usrs);
	            		usrs.clear();
            		}
            	} else {
	            	T ext = getExtension(name);
	            	usrs.add(ext);
            	}
            }
        }
        if (usrs.size() > 0) {
        	exts.addAll(usrs);
        }
        return exts;
    }
    
    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean isActive(Activate activate, URL url) {
        String[] keys = activate.value();
        if (keys == null || keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 返回扩展点实例，如果没有指定的扩展点或是还没加载（即实例化）则返回<code>null</code>。注意：此方法不会触发扩展点的加载。
     * <p />
     * 一般应该调用{@link #getExtension(String)}方法获得扩展，这个方法会触发扩展点加载。
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * 返回已经加载的扩展点的名字。
     * <p />
     * 一般应该调用{@link #getSupportedExtensions()}方法获得扩展，这个方法会返回所有的扩展点。
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * 返回指定名字的扩展。如果指定名字的扩展不存在，则抛异常 {@link IllegalStateException}.
     * 根据名称获取当前扩展的指定实现
     * @param name
     * @return
     *
     * 1. 校验
     * 2. 如果name是true，就获取默认扩展点的实现实例
     * 3. 从缓存中获取扩展点实现实例
     * 4. 如果缓存中不存在，就根据name创建具体的扩展点实现实例
     * 5. 返回name对应的具体扩展点实现的实例
     */
	@SuppressWarnings("unchecked")
	public T getExtension(String name) {
		if (name == null || name.length() == 0)
		    throw new IllegalArgumentException("Extension name == null");
		// 如果扩展名称是true，获取默认实现
		if ("true".equals(name)) {
		    return getDefaultExtension();
		}
		// 从缓存获取
		Holder<Object> holder = cachedInstances.get(name);
		if (holder == null) {
		    cachedInstances.putIfAbsent(name, new Holder<Object>());
		    holder = cachedInstances.get(name);
		}
		Object instance = holder.get();
		if (instance == null) {
		    synchronized (holder) {
	            instance = holder.get();
	            if (instance == null) {
	                // 缓存不存在，创建实例
	                instance = createExtension(name);
	                // 加入缓存
	                holder.set(instance);
	            }
	        }
		}
		return (T) instance;
	}
	
	/**
	 * 返回缺省的扩展，如果没有设置则返回<code>null</code>。 
	 */
	public T getDefaultExtension() {
	    //走一遍加载扩展实现的流程
	    getExtensionClasses();
	    //默认名字会在SPI注解中有
        if(null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        //根据默认名字获取扩展
        return getExtension(cachedDefaultName);
	}

	public boolean hasExtension(String name) {
	    if (name == null || name.length() == 0)
	        throw new IllegalArgumentException("Extension name == null");
	    try {
	        return getExtensionClass(name) != null;
	    } catch (Throwable t) {
	        return false;
	    }
	}
    
	public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }
    
	/**
	 * 返回缺省的扩展点名，如果没有设置缺省则返回<code>null</code>。 
	 */
	public String getDefaultExtensionName() {
	    getExtensionClasses();
	    return cachedDefaultName;
	}

    /**
     * 编程方式添加新扩展点。
     *
     * @param name 扩展点名
     * @param clazz 扩展点类
     * @throws IllegalStateException 要添加扩展点名已经存在。
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if(!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if(clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if(!clazz.isAnnotationPresent(Adaptive.class)) {
            if(StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if(cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        }
        else {
            if(cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * 编程方式添加替换已有扩展点。
     *
     * @param name 扩展点名
     * @param clazz 扩展点类
     * @throws IllegalStateException 要添加扩展点名已经存在。
     * @deprecated 不推荐应用使用，一般只在测试时可以使用
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if(!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if(clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if(!clazz.isAnnotationPresent(Adaptive.class)) {
            if(StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if(!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        }
        else {
            if(cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * 获取当前扩展的自适应实现
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        // 先从自适应实例缓存中查找实例对象
        Object instance = cachedAdaptiveInstance.get();
        // 缓存中不存在
        if (instance == null) {
            if(createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    // 获取锁之后再检查一次缓存中是不是已经存在
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            // 缓存中没有，就创建新的AdaptiveExtension实例
                            instance = createAdaptiveExtension();
                            // 新实例加入缓存
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            }
            else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if(i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i ++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * 根据指定的名字创建扩展点的实现的实例
     * @param name
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        /**
         * getExtensionClasses加载当前扩展点的所有实现
         * 比如：
         * 我们在使用ExtensionLoader.getExtensionLoader(Protocol.class)
         * 获取Protocol的ExtensionLoader的时候，就已经设置了当前ExtensionLoader
         * 的类型是Protocol的，所以这里获取的时候就是Protocol的所有实现。
         *
         * 获取到所有的实现之后，getExtensionClasses()返回的是Map<String, Class<?>>
         */
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            /**
             * 从缓存中获取已经创建的扩展点的实现的实例
             * 如果还没有，就根据Class通过反射来创建具体的实例，
             * 并放到缓存中去
             */
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, (T) clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            /**
             * 向实例中注入依赖的扩展
             * 如果一个扩展点A依赖了其他的扩展点B，并且有setter方法
             * 就会执行将扩展点B注入扩展点A的操作
             */
            injectExtension(instance);
            /**
             * 如果扩展点有包装类，将扩展点进行包装
             * 包装后如果也依赖了其他扩展点，也需要注入其他扩展点
             */
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && wrapperClasses.size() > 0) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }
    /**
     * 扩展点自动注入
     */
    private T injectExtension(T instance) {
        try {
            // 在获取第一个扩展点的ExtensionLoader的实例的时候，objectFactory就被实例化了，是AdaptiveExtensionFactory
            if (objectFactory != null) {
                // 遍历要注入的实例的方法
                for (Method method : instance.getClass().getMethods()) {
                    // 只处理set方法，比如setA，就是要把A注入到instance中
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                        // set方法参数类型
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                            // setter方法对应的属性名，也就是扩展点接口名称
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            /**
                             * objectFactory是AdaptiveExtensionFactory实例
                             * 比如这里的pt是com.alibaba.dubbo.rpc.Protocol，property是protocol
                             * objectFactory就会根据这两个参数去获取Protocol对应的扩展实现的实例
                             */
                            Object object = objectFactory.getExtension(pt, property);
                            // 获取到了setter方法的参数的实现，可以进行注入
                            if (object != null) {
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }
    
	private Class<?> getExtensionClass(String name) {
	    if (type == null)
	        throw new IllegalArgumentException("Extension type == null");
	    if (name == null)
	        throw new IllegalArgumentException("Extension name == null");
	    Class<?> clazz = getExtensionClasses().get(name);
	    if (clazz == null)
	        throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
	    return clazz;
	}

    /**
     * 加载当前扩展点的所有实现的class，并缓存
     * @return
     */
	private Map<String, Class<?>> getExtensionClasses() {
        /**
         * 先从缓存中获取，不存在的话就调用loadExtensionClasses进行加载
         * cachedClasses缓存中存储了当前扩展点所有的实现类
         */
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    /**
                     * 如果没有加载Extension的实现，进行扫描加载，完成后缓存起来
                     * 每个扩展点，其实现的加载只会执行一次
                     * 例如，如果Protocol的某个具体实现加载出错了，没有放到缓存中去
                     * 后面再使用，也不会再进行加载了。
                     */
                    classes = loadExtensionClasses();
                    // 缓存起来
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
	}

    // 此方法已经getExtensionClasses方法同步过。
    /**
     * 加载扩展点的实现
     */
    private Map<String, Class<?>> loadExtensionClasses() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if(defaultAnnotation != null) {
            // 当前扩展点的默认实现名字，如果有的话进行缓存
            String value = defaultAnnotation.value();
            if(value != null && (value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                if(names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                if(names.length == 1) cachedDefaultName = names[0];
            }
        }

        // 从配置文件中加载扩展实现类
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        // 从META-INF/dubbo/internal目录下加载
        loadFile(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        // 从META-INF/dubbo/目录下加载
        loadFile(extensionClasses, DUBBO_DIRECTORY);
        // 从META-INF/services/下加载
        loadFile(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /**
     * 加载具体的扩展实现
     * 解析配置文件
     * 获取扩展点实现对应的名称和实现类
     * 进行分类处理和缓存
     *
     * 此方法执行完成之后：
     * 当前Extension类型如果是AdaptiveExtension类型，就会赋值给cachedAdaptiveClass，只能有一个
     * 当前Extension类型如果是Wrapper实现类型，就会赋值给cachedWrapperClass，无顺序
     * 当前Extension类型如果是实现自动激活类型，就会赋值给cachedActivesClass，无序，map
     * cachedName也会被赋值，扩展点实现类对应的名称
     *
     * @param extensionClasses
     * @param dir
     */
    private void loadFile(Map<String, Class<?>> extensionClasses, String dir) {
        // 配置文件的名称
        String fileName = dir + type.getName();
        try {
            Enumeration<java.net.URL> urls;
            // 获取类加载器
            ClassLoader classLoader = findClassLoader();
            // 获取对应配置文件名的所有的文件
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                // 遍历文件进行处理
                while (urls.hasMoreElements()) {
                    // 配置文件路径
                    java.net.URL url = urls.nextElement();
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream(), "utf-8"));
                        try {
                            String line = null;
                            // 每次处理一行
                            while ((line = reader.readLine()) != null) {
                                // #号以后的为注释
                                final int ci = line.indexOf('#');
                                if (ci >= 0) line = line.substring(0, ci);
                                line = line.trim();
                                if (line.length() > 0) {
                                    try {
                                        String name = null;
                                        // =号之前的为扩展名字，后面的为扩展类实现的全限定名
                                        int i = line.indexOf('=');
                                        if (i > 0) {
                                            name = line.substring(0, i).trim();
                                            line = line.substring(i + 1).trim();
                                        }
                                        if (line.length() > 0) {
                                            // 加载扩展类的实现
                                            Class<?> clazz = Class.forName(line, true, classLoader);
                                            // 查看类型是否匹配
                                            if (! type.isAssignableFrom(clazz)) {
                                                throw new IllegalStateException("Error when load extension class(interface: " +
                                                        type + ", class line: " + clazz.getName() + "), class " 
                                                        + clazz.getName() + "is not subtype of interface.");
                                            }
                                            // @Adaptice类型的
                                            // 如果实现类是@Adaptive类型的，就不会放入extensionClasses，cachedClass缓存
                                            // 而是放到cachedAdaptiveClass中缓存
                                            if (clazz.isAnnotationPresent(Adaptive.class)) {
                                                if(cachedAdaptiveClass == null) {
                                                    cachedAdaptiveClass = clazz;
                                                } else if (! cachedAdaptiveClass.equals(clazz)) {
                                                    throw new IllegalStateException("More than 1 adaptive class found: "
                                                            + cachedAdaptiveClass.getClass().getName()
                                                            + ", " + clazz.getClass().getName());
                                                }
                                            } else {// 不是@Adaptive类型的
                                                try {// 判断是否是wrapper类型
                                                    // Wrapper类型的肯定会有一个构造方法，方法参数是扩展的接口类型
                                                    clazz.getConstructor(type);
                                                    Set<Class<?>> wrappers = cachedWrapperClasses;
                                                    if (wrappers == null) {
                                                        cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                                                        wrappers = cachedWrapperClasses;
                                                    }
                                                    wrappers.add(clazz);
                                                } catch (NoSuchMethodException e) {// 不是wrapper类型
                                                    clazz.getConstructor();
                                                    if (name == null || name.length() == 0) {
                                                        name = findAnnotationName(clazz);
                                                        if (name == null || name.length() == 0) {
                                                            if (clazz.getSimpleName().length() > type.getSimpleName().length()
                                                                    && clazz.getSimpleName().endsWith(type.getSimpleName())) {
                                                                name = clazz.getSimpleName().substring(0, clazz.getSimpleName().length() - type.getSimpleName().length()).toLowerCase();
                                                            } else {
                                                                throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + url);
                                                            }
                                                        }
                                                    }
                                                    // 有可能配置了多个名字
                                                    String[] names = NAME_SEPARATOR.split(name);
                                                    if (names != null && names.length > 0) {
                                                        // 是否是Active类型的
                                                        Activate activate = clazz.getAnnotation(Activate.class);
                                                        if (activate != null) {
                                                            cachedActivates.put(names[0], activate);
                                                        }
                                                        for (String n : names) {
                                                            if (! cachedNames.containsKey(clazz)) {
                                                                // 放入Extension实现类与名称映射的缓存中去，每个class只对应第一个名称有效
                                                                cachedNames.put(clazz, n);
                                                            }
                                                            Class<?> c = extensionClasses.get(n);
                                                            if (c == null) {
                                                                // 放入到extensionClasses缓存中去，多个name可能对应一份extensionClasses
                                                                extensionClasses.put(n, clazz);
                                                            } else if (c != clazz) {
                                                                throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + url + ", cause: " + t.getMessage(), t);
                                        exceptions.put(line, e);
                                    }
                                }
                            } // end of while read lines
                        } finally {
                            reader.close();
                        }
                    } catch (Throwable t) {
                        logger.error("Exception when load extension class(interface: " +
                                            type + ", class file: " + url + ") in " + url, t);
                    }
                } // end of while urls
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }
    
    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    /**
     * 创建自适应扩展实现的实例
     * @return
     */
    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            /**
             * 先通过getAdaptiveExtensionClass获取自适应扩展类的Class
             * 然后通过反射获取实例
             * 最后如果自适应扩展依赖了其他的扩展点，就进行扩展点注入
             */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extenstion " + type + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 获取自适应扩展类
     * @return
     */
    private Class<?> getAdaptiveExtensionClass() {
        /**
         * getExtensionClasses加载当前扩展点的所有实现
         * 比如：
         * 我们在使用ExtensionLoader.getExtensionLoader(Protocol.class)
         * 获取Protocol的ExtensionLoader的时候，就已经设置了当前ExtensionLoader
         * 的类型是Protocol的，所以这里获取的时候就是Protocol的所有实现。
         *
         * 获取到所有的实现之后，getExtensionClasses()返回的是Map<String, Class<?>>
         *
         * 另外需要说的是，如果扩展点的实现注解了类级别的@Adaptive注解，
         * 这些实现的Class加载完后会赋值给cachedAdaptiveClass缓存。如果扩展点的实现
         * 是包装类，这些实现的Class加载完后会放到cachedWrapperClasses缓存中。
         * 其他的正常的扩展点的实现都会放到Map<String, Class<?>>中返回。
         *
         * 目前只有AdaptiveExtensionFactory和AdaptiveCompiler两个实现类是被注解了@Adaptive
         * 也就是说这两个就是自适应扩展，如果要加载ExtensionFactory和Compiler的自适应扩展
         * 不需要使用自动生成代码，而是直接使用两个实现类就可以了。
         * 其他的扩展点如果想要获取自适应扩展实现，就需要继续往下走，使用生成的Xxx$Adaptive代码。
         *
         * 一个扩展点有且只有一个自适应扩展点，要么是内置的两个AdaptiveExtensionFactory和AdaptiveCompiler，
         * 要么是生成的Xxx$Adaptive
         */
        getExtensionClasses();
        /**
         * 自适应扩展实现，在上面一步加载的时候，就会被加载缓存起来
         * 只会执行一次，后面再获取的时候，就是获取缓存起来的这个。
         */
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        // 没有缓存自适应扩展实现，就动态创建一个
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 创建自适应扩展的类
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {
        /**
         * 根据具体的接口来生成自适应扩展类的代码
         * 比如Protocol就会生成Protocol$Adaptive为名字的类的代码
         */
        String code = createAdaptiveExtensionClassCode();
        // 获取类加载器
        ClassLoader classLoader = findClassLoader();
        // 获取Compiler的自适应扩展，获取到的是AdaptiveCompiler实例
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 如果我们没有指定名字，默认使用javassist
        return compiler.compile(code, classLoader);
    }

    /**
     * 生成自适应扩展的代码
     * @return
     */
    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuidler = new StringBuilder();
        // 获取到接口的所有方法
        Method[] methods = type.getMethods();
        // 遍历所有的方法，查看是否有方法注解了@Adaptive
        boolean hasAdaptiveAnnotation = false;
        for(Method m : methods) {
            if(m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // 完全没有方法注解@Adaptive，则不需要生成自适应扩展类代码
        if(! hasAdaptiveAnnotation)
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");
        // 生成package
        codeBuidler.append("package " + type.getPackage().getName() + ";");
        // 生成import
        codeBuidler.append("\nimport " + ExtensionLoader.class.getName() + ";");
        // 生成类的名字，就是Xxxx$Adaptive，并且实现了当前类型的接口
        codeBuidler.append("\npublic class " + type.getSimpleName() + "$Adpative" + " implements " + type.getCanonicalName() + " {");

        // 遍历所有的方法进行处理
        for (Method method : methods) {
            Class<?> rt = method.getReturnType();
            Class<?>[] pts = method.getParameterTypes();
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            // 如果一个方法没有注解@Adaptive，说明该方法不需要自适应扩展生成，直接生成抛出不支持的异常代码
            if (adaptiveAnnotation == null) {
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                // 找到URL类型参数位置
                int urlTypeIndex = -1;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // 有类型为URL的参数，需要校验url不能为null
                if (urlTypeIndex != -1) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                                    urlTypeIndex);
                    code.append(s);
                    
                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex); 
                    code.append(s);
                }
                // 参数中没有URL类型的
                else {
                    String attribMethod = null;

                    /**
                     * 如果参数中没有URL类型的，需要遍历每个参数对象中的所有方法
                     * 找到参数对象中的public URL getUrl() {return url;}方法
                     *
                     * 这个也可以获取到URL
                     */
                    LBL_PTS:
                    for (int i = 0; i < pts.length; ++i) {
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    /**
                     * 如果找不到URL类型的参数或者参数对象中没有获取URL的方法，直接抛异常
                     *
                     * URL对于dubbo来说相当于一个总线，所有的参数传递，都是在url中进行的
                     */
                    if(attribMethod == null) {
                        throw new IllegalStateException("fail to create adative class for interface " + type.getName()
                        		+ ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }
                    
                    // Null point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                                    urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                                    urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    s = String.format("%s url = arg%d.%s();",URL.class.getName(), urlTypeIndex, attribMethod); 
                    code.append(s);
                }
                // 获取@Adaptive("xxxx,yyyy")中配置的value
                String[] value = adaptiveAnnotation.value();
                // 没有设置Key，则使用“扩展点接口名的点分隔 作为Key，具体可参考@Adaptive注解中的注释说明
                if(value.length == 0) {
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if(Character.isUpperCase(charArray[i])) {
                            if(i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        }
                        else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[] {sb.toString()};
                }
                
                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        s = String.format("\nString methodName = arg%d.getMethodName();", i); 
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                // 组装extName
                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                for (int i = value.length - 1; i >= 0; --i) {
                    if(i == value.length - 1) {
                        if(null != defaultExtName) {
                            if(!"protocol".equals(value[i]))
                                if (hasInvocation) 
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        }
                        else {
                            if(!"protocol".equals(value[i]))
                                if (hasInvocation) 
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    }
                    else {
                        if(!"protocol".equals(value[i]))
                            if (hasInvocation) 
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                String s = String.format("\nif(extName == null) " +
                		"throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);
                
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);
                
                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }

                s = String.format("extension.%s(", method.getName());
                code.append(s);
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            }
            
            codeBuidler.append("\npublic " + rt.getCanonicalName() + " " + method.getName() + "(");
            for (int i = 0; i < pts.length; i ++) {
                if (i > 0) {
                    codeBuidler.append(", ");
                }
                codeBuidler.append(pts[i].getCanonicalName());
                codeBuidler.append(" ");
                codeBuidler.append("arg" + i);
            }
            codeBuidler.append(")");
            if (ets.length > 0) {
                codeBuidler.append(" throws ");
                for (int i = 0; i < ets.length; i ++) {
                    if (i > 0) {
                        codeBuidler.append(", ");
                    }
                    codeBuidler.append(ets[i].getCanonicalName());
                }
            }
            codeBuidler.append(" {");
            codeBuidler.append(code.toString());
            codeBuidler.append("\n}");
        }
        codeBuidler.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuidler.toString());
        }
        return codeBuidler.toString();
    }

    private static ClassLoader findClassLoader() {
        return  ExtensionLoader.class.getClassLoader();
    }
    
    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }
    
}