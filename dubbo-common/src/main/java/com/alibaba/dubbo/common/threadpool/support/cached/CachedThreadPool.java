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
package com.alibaba.dubbo.common.threadpool.support.cached;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.threadpool.ThreadPool;
import com.alibaba.dubbo.common.threadpool.support.AbortPolicyWithReport;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;

/**
 * 此线程池可伸缩，线程空闲一分钟后回收，新请求重新创建线程，来源于：<code>Executors.newCachedThreadPool()</code>
 * 
 * @see java.util.concurrent.Executors#newCachedThreadPool()
 * @author william.liangf
 */
public class CachedThreadPool implements ThreadPool {

    public Executor getExecutor(URL url) {
        // 线程名字
        String name = url.getParameter(Constants.THREAD_NAME_KEY, Constants.DEFAULT_THREAD_NAME);
        // 核心线程数，默认0
        int cores = url.getParameter(Constants.CORE_THREADS_KEY, Constants.DEFAULT_CORE_THREADS);
        // 最大线程数，默认Integer.MAX_VALUE
        int threads = url.getParameter(Constants.THREADS_KEY, Integer.MAX_VALUE);
        // 队列数，默认0
        int queues = url.getParameter(Constants.QUEUES_KEY, Constants.DEFAULT_QUEUES);
        // 线程存活时间，默认1分钟
        int alive = url.getParameter(Constants.ALIVE_KEY, Constants.DEFAULT_ALIVE);
        return new ThreadPoolExecutor(cores, threads, alive, TimeUnit.MILLISECONDS, 
        		queues == 0 ? new SynchronousQueue<Runnable>() : 
        			(queues < 0 ? new LinkedBlockingQueue<Runnable>() 
        					: new LinkedBlockingQueue<Runnable>(queues)),
        		new NamedThreadFactory(name, true), new AbortPolicyWithReport(name, url));
    }

}