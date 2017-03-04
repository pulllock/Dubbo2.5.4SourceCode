/*
 * Copyright 1999-2012 Alibaba Group.
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
package com.alibaba.dubbo.common.extension.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.ExtensionFactory;
import com.alibaba.dubbo.common.extension.ExtensionLoader;

/**
 * AdaptiveExtensionFactory
 *  ExtensionFactory的实现，有@Adaptive注解，是自适应扩展实现
 *  每个扩展点做多只能有一个自适应实现，如果所有的实现中都没注释@Adaptive注解，那么dubbo会动态生成一个自适应的实现类
 *  对所有的ExtensionFactory调用的地方实际上是对AdaptiveExtensionFactory的调用
 * @author william.liangf
 */
@Adaptive
public class AdaptiveExtensionFactory implements ExtensionFactory {
    
    private final List<ExtensionFactory> factories;
    
    public AdaptiveExtensionFactory() {
        ExtensionLoader<ExtensionFactory> loader = ExtensionLoader.getExtensionLoader(ExtensionFactory.class);
        List<ExtensionFactory> list = new ArrayList<ExtensionFactory>();
        for (String name : loader.getSupportedExtensions()) {
            //保存所有ExtensionFactory的实现
            list.add(loader.getExtension(name));
        }
        factories = Collections.unmodifiableList(list);
    }

    public <T> T getExtension(Class<T> type, String name) {
        //依次遍历各个ExtensionFactory实现的getExtension方法
        //找到Extension后立即返回，没找到返回null
        for (ExtensionFactory factory : factories) {
            T extension = factory.getExtension(type, name);
            if (extension != null) {
                return extension;
            }
        }
        return null;
    }

}
