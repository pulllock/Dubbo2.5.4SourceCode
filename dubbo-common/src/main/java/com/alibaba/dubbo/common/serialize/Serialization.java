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
package com.alibaba.dubbo.common.serialize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * Serialization. (SPI, Singleton, ThreadSafe)
 * 
 * @author ding.lid
 * @author william.liangf
 */
@SPI("hessian2")
public interface Serialization {

    /**
     * get content type id
     * 获取内容类型id
     * @return content type id
     */
    byte getContentTypeId();

    /**
     * get content type
     * 获取内容类型
     * @return content type
     */
    String getContentType();

    /**
     * create serializer
     * 创建ObjectOutput对象，序列化输出到OutputStream
     * @param url 
     * @param output
     * @return serializer
     * @throws IOException
     */
    @Adaptive
    ObjectOutput serialize(URL url, OutputStream output) throws IOException;

    /**
     * create deserializer
     * 创建ObjectInput对象，从InputStream反序列化
     * @param url 
     * @param input
     * @return deserializer
     * @throws IOException
     */
    @Adaptive
    ObjectInput deserialize(URL url, InputStream input) throws IOException;

}