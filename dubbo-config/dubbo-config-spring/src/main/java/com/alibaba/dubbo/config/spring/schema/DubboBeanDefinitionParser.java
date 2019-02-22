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
package com.alibaba.dubbo.config.spring.schema;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.ArgumentConfig;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.MethodConfig;
import com.alibaba.dubbo.config.MonitorConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.rpc.Protocol;

/**
 * AbstractBeanDefinitionParser
 * 
 * @author william.liangf
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {
    
    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
	
    private final Class<?> beanClass;
    
    private final boolean required;

    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    public BeanDefinition parse(Element element, ParserContext parserContext) {
        // 实际的解析过程
        return parse(element, parserContext, beanClass, required);
    }


    /**
     * 解析完xml中所有的标签，返回BeanDefinition
     * 然后Spring在finishRefresh();方法中开始调用dubbo的发布服务的方法，在ServiceBean的onApplicationEvent方法中export
     * export方法的实现在ServiceConfig中
     * 下面是Spring中的注释
     * //Last step: publish corresponding event.
     * finishRefresh();
     *
     */
    @SuppressWarnings("unchecked")
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);
        beanDefinition.setLazyInit(false);
        // 得到标签的id属性
        String id = element.getAttribute("id");
        // 标签没有id，并且required为true的时候，生成一个id。required为true表示必须要有id这个属性
        if ((id == null || id.length() == 0) && required) {
            // name属性
        	String generatedBeanName = element.getAttribute("name");
        	// 没name属性
        	if (generatedBeanName == null || generatedBeanName.length() == 0) {
        	    // name属性为空，如果是protocol标签，默认使用dubbo
                // <dubbo:protocol/>服务提供者协议配置
        	    if (ProtocolConfig.class.equals(beanClass)) {
        	        generatedBeanName = "dubbo";
        	    } else {
        	        // 其他的标签则使用interface属性作为名字
        	        generatedBeanName = element.getAttribute("interface");
        	    }
        	}
        	// 如果经过上面的步骤还没有名字，就使用类名字，
        	if (generatedBeanName == null || generatedBeanName.length() == 0) {
        		generatedBeanName = beanClass.getName();
        	}
        	// 经过上面的解析就会得到标签的id属性
            id = generatedBeanName; 
            int counter = 2;
            // 有同名的id的时候，在名字后面添加数字，保证id是唯一的
            while(parserContext.getRegistry().containsBeanDefinition(id)) {
                id = generatedBeanName + (counter ++);
            }
        }
        // bean的id不能重复
        if (id != null && id.length() > 0) {
            if (parserContext.getRegistry().containsBeanDefinition(id))  {
        		throw new IllegalStateException("Duplicate spring bean id " + id);
        	}
        	// 注册新的bean
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
            // 设置bean的id属性
            beanDefinition.getPropertyValues().addPropertyValue("id", id);
        }
        // 如果是protocol标签
        if (ProtocolConfig.class.equals(beanClass)) {
            // 获取所有的bean的name
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                // 根据name找到bean
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                // 找到已经解析过得protocol属性
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                // 如果存在protocol熟悉感你
                if (property != null) {
                    Object value = property.getValue();
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        // 会覆盖之前的属性值
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
        } else if (ServiceBean.class.equals(beanClass)) {
            String className = element.getAttribute("class");
            if(className != null && className.length() > 0) {
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                parseProperties(element.getChildNodes(), classDefinition);
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
        } else if (ProviderConfig.class.equals(beanClass)) {// <dubbo:provider/>标签，该标签为<dubbo:service>和<dubbo:protocol>标签的缺省值设置。
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        } else if (ConsumerConfig.class.equals(beanClass)) {// <dubbo:consumer/>标签，该标签为<dubbo:reference>标签的缺省值设置。
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }
        // 标签的属性集，也就是每个标签中定义的所有的属性，从类中的setter方法中获取
        Set<String> props = new HashSet<String>();
        ManagedMap parameters = null;
        // 标签对应类的所有方法进行遍历
        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            // 获取各种Config的set方法，比如ApplicationConfig
            // 方法修饰符是public
            // setter的参数只有一个
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {
                // setter方法的参数类型
                Class<?> type = setter.getParameterTypes()[0];
                /**
                 * name.substring(3, 4).toLowerCase() + name.substring(4)
                 * 比如name为setVersion，经过上面之后得到需要的v+ersion，即小写的version。
                 * camelToSplitName驼峰命名的转换成中横线“-”
                 */
                String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), "-");
                // 得到的标签属性添加到属性set中
                props.add(property);
                Method getter = null;
                try {
                    // 得到get方法
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        // 获取is方法
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                    }
                }
                // 属性没有get方法或者属性的get方法不是public，或者getter返回类型和setter的类型不一致。
                // 说明此属性不能被外部获得，不能在xml标签中配置
                if (getter == null 
                        || ! Modifier.isPublic(getter.getModifiers())
                        || ! type.equals(getter.getReturnType())) {
                    continue;
                }
                // 解析parameters属性
                if ("parameters".equals(property)) {
                    // 参数可能会有多个，并且是子标签
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);
                } else if ("methods".equals(property)) {
                    // 解析methods属性
                    // method标签<dubbo:method>该标签是service和reference的子标签，用于控制到方法级别。
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                } else if ("arguments".equals(property)) {
                    // 解析arguments属性
                    // argument标签<dubbo:argument>，是method标签的子标签
                    // 解析过程跟method类似
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    // 解析其他的标签属性
                    // 获取到属性的值
                    String value = element.getAttribute(property);
                    if (value != null) {
                    	value = value.trim();
                    	if (value.length() > 0) {
                    	    // registry属性，注册中心配置
                            // 注册中心配置为N/A
                    		if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                            	RegistryConfig registryConfig = new RegistryConfig();
                            	registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                            	beanDefinition.getPropertyValues().addPropertyValue(property, registryConfig);
                            } else if ("registry".equals(property) && value.indexOf(',') != -1) {
                    		    // registry属性，配置了多个注册中心，以逗号分割
                    			parseMultiRef("registries", value, beanDefinition, parserContext);
                            } else if ("provider".equals(property) && value.indexOf(',') != -1) {
                    		    // provider属性，配置了多个，以逗号分割
                            	parseMultiRef("providers", value, beanDefinition, parserContext);
                            } else if ("protocol".equals(property) && value.indexOf(',') != -1) {
                    		    // protocol属性，配置了多个
                                parseMultiRef("protocols", value, beanDefinition, parserContext);
                            } else {
                                Object reference;
                                // type是setter方法的参数类型
                                // 参数类型为基本数据类型
                                if (isPrimitive(type)) {
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // 兼容旧版本xsd中的default值
                                        value = null;
                                    }
                                    reference = value;
                                } else if ("protocol".equals(property) // protocol属性，只配置了一个值，多个值的在上面已经解析过了
                                        && ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value)// Protocol必须至少有一个实现类
                                        && (! parserContext.getRegistry().containsBeanDefinition(value)// 没有对应name的bean
                                                || ! ProtocolConfig.class.getName().equals(parserContext.getRegistry().getBeanDefinition(value).getBeanClassName()))) {// bean中只能有一个protocol类型的
                                    if ("dubbo:provider".equals(element.getTagName())) {
                                        logger.warn("Recommended replace <dubbo:provider protocol=\"" + value + "\" ... /> to <dubbo:protocol name=\"" + value + "\" ... />");
                                    }
                                    // 兼容旧版本配置
                                    ProtocolConfig protocol = new ProtocolConfig();
                                    protocol.setName(value);
                                    reference = protocol;
                                } else if ("monitor".equals(property) // monitor属性
                                        && (! parserContext.getRegistry().containsBeanDefinition(value)
                                                || ! MonitorConfig.class.getName().equals(parserContext.getRegistry().getBeanDefinition(value).getBeanClassName()))) {
                                    // 兼容旧版本配置
                                    reference = convertMonitor(value);
                                } else if ("onreturn".equals(property)) {// onreturn属性
                                    int index = value.lastIndexOf(".");
                                    String returnRef = value.substring(0, index);
                                    String returnMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(returnRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);
                                } else if ("onthrow".equals(property)) {// onthrow属性
                                    int index = value.lastIndexOf(".");
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(throwRef);
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
                                } else {
                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {// ref属性
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        // ref对应的bean必须为单例bean
                                        if (! refBean.isSingleton()) {
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value+ "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    reference = new RuntimeBeanReference(value);
                                }
		                        beanDefinition.getPropertyValues().addPropertyValue(property, reference);
                            }
                    	}
                    }
                }
            }
        }
        // 标签中配置的所有的属性
        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            // 标签中的属性
            Node node = attributes.item(i);
            // 标签中的属性的名字
            String name = node.getLocalName();
            // 经过前面解析之后，标签属性set中不包含当前解析的标签属性
            if (! props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                // 属性值
                String value = node.getNodeValue();
                // 添加到属性map中
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        // 返回一个BeanDefinition给Spring
        return beanDefinition;
    }

    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    
    protected static MonitorConfig convertMonitor(String monitor) {
        if (monitor == null || monitor.length() == 0) {
            return null;
        }
        if (GROUP_AND_VERION.matcher(monitor).matches()) {
            String group;
            String version;
            int i = monitor.indexOf(':');
            if (i > 0) {
                group = monitor.substring(0, i);
                version = monitor.substring(i + 1);
            } else {
                group = monitor;
                version = null;
            }
            MonitorConfig monitorConfig = new MonitorConfig();
            monitorConfig.setGroup(group);
            monitorConfig.setVersion(version);
            return monitorConfig;
        }
        return null;
    }
 
    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }
    
    @SuppressWarnings("unchecked")
	private static void parseMultiRef(String property, String value, RootBeanDefinition beanDefinition,
            ParserContext parserContext) {
    	String[] values = value.split("\\s*[,]+\\s*");
		ManagedList list = null;
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            if (v != null && v.length() > 0) {
            	if (list == null) {
                    list = new ManagedList();
                }
            	list.add(new RuntimeBeanReference(v));
            }
        }
        beanDefinition.getPropertyValues().addPropertyValue(property, list);
    }
    
    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if (tag.equals(node.getNodeName())
                            || tag.equals(node.getLocalName())) {
                        if (first) {
                            first = false;
                            String isDefault = element.getAttribute("default");
                            if (isDefault == null || isDefault.length() == 0) {
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                        if (subDefinition != null && ref != null && ref.length() > 0) {
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                        }
                    }
                }
            }
        }
    }

    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if ("property".equals(node.getNodeName())
                            || "property".equals(node.getLocalName())) {
                        String name = ((Element) node).getAttribute("name");
                        if (name != null && name.length() > 0) {
                            String value = ((Element) node).getAttribute("value");
                            String ref = ((Element) node).getAttribute("ref");
                            if (value != null && value.length() > 0) {
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);
                            } else if (ref != null && ref.length() > 0) {
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                            } else {
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedMap parameters = null;
            // 遍历每个parameter标签<dubbo:parameter>
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) {
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        // key属性
                        String key = ((Element) node).getAttribute("key");
                        // value属性
                        String value = ((Element) node).getAttribute("value");
                        // 有hide属性，在key前面添加一个点
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            key = Constants.HIDE_KEY_PREFIX + key;
                        }
                        // 放到map中去
                        parameters.put(key, new TypedStringValue(value, String.class));
                    }
                }
            }
            return parameters;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                              ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList methods = null;
            // 遍历每个method标签
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {
                        // name属性，方法名，不能为空
                        String methodName = element.getAttribute("name");
                        if (methodName == null || methodName.length() == 0) {
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        // 调用parse方法解析method标签
                        BeanDefinition methodBeanDefinition = parse(((Element) node),
                                parserContext, MethodConfig.class, false);
                        // 名字是父标签的id.methodName
                        String name = id + "." + methodName;
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                                methodBeanDefinition, name);
                        methods.add(methodBeanDefinitionHolder);
                    }
                }
            }
            // 循环完成之后，所有的method标签都已经被解析成BeanDefinition，并放到了ManagedList中保存
            // 如果存在method标签，就会放到methods属性下面
            if (methods != null) {
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                              ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList arguments = null;
            // 遍历每个argument标签
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {
                        // index属性
                        String argumentIndex = element.getAttribute("index");
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        BeanDefinition argumentBeanDefinition = parse(((Element) node),
                                parserContext, ArgumentConfig.class, false);
                        String name = id + "." + argumentIndex;
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                                argumentBeanDefinition, name);
                        arguments.add(argumentBeanDefinitionHolder);
                    }
                }
            }
            if (arguments != null) {
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
            }
        }
    }

}