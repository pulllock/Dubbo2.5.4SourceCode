package com.alibaba.dubbo.remoting.zookeeper;

import java.util.List;

/**
 * 节点监听器接口
 */
public interface ChildListener {

	/**
	 * 子节点变化回调方法
	 * @param path
	 * @param children
	 */
	void childChanged(String path, List<String> children);

}
