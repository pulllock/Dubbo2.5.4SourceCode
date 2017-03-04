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
package com.alibaba.dubbo.common.extension;

/**
 * ExtensionFactory，扩展点对象生成工厂接口
 * 有@SPI注解，是一个扩展点
 * 主要有三个实现AdaptiveExtensionFactory，SpiExtensionFactory，SpringExtensionFactory
 * 不同的实现可以已不同的方式来完成对扩展点实现的加载
 * @author william.liangf
 * @export
 */
@SPI
public interface ExtensionFactory {

    /**
     * Get extension.
     * 
     * @param type object type.
     * @param name object name.
     * @return object instance.
     */
    <T> T getExtension(Class<T> type, String name);

}
