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
package com.alibaba.dubbo.common.threadpool;

import java.util.concurrent.Executor;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * ThreadPool
 * 
 * @author william.liangf
 *
 * 提供了三种线程池实现：
 * 1. fixed，固定大小线程池，启动时建立，不关闭，一直持有。这是默认的线程池。
 * 2. cached，缓存线程池，空闲一分钟自动删除，需要时重建。
 * 3. limited，可伸缩线程池，但是线程池中的线程池数只会增长不会收缩。这样是为了
 * 避免收缩时突然来了大流量引起性能问题。
 */
@SPI("fixed")
public interface ThreadPool {
    
    /**
     * 线程池
     * 
     * @param url 线程参数
     * @return 线程池
     */
    @Adaptive({Constants.THREADPOOL_KEY})
    Executor getExecutor(URL url);

}