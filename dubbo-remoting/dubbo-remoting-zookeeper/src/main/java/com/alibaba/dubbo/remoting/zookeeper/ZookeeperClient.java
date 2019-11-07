package com.alibaba.dubbo.remoting.zookeeper;

import java.util.List;

import com.alibaba.dubbo.common.URL;

public interface ZookeeperClient {

	/**
	 * 创建节点
	 * @param path 节点路径
	 * @param ephemeral 是否临时节点
	 */
	void create(String path, boolean ephemeral);

	/**
	 * 删除节点
	 * @param path 节点路径
	 */
	void delete(String path);

	/**
	 * 获取子节点
	 * @param path
	 * @return
	 */
	List<String> getChildren(String path);

	/**
	 * 添加子节点监听器
	 * @param path
	 * @param listener
	 * @return
	 */
	List<String> addChildListener(String path, ChildListener listener);

	/**
	 * 移除子节点监听器
	 * @param path
	 * @param listener
	 */
	void removeChildListener(String path, ChildListener listener);

	/**
	 * 添加状态监听器
	 * @param listener
	 */
	void addStateListener(StateListener listener);

	/**
	 * 移除状态监听器
	 * @param listener
	 */
	void removeStateListener(StateListener listener);

	/**
	 * 是否连接
	 * @return
	 */
	boolean isConnected();

	/**
	 * 关闭
	 */
	void close();

	/**
	 * 获得注册中心url
	 * @return
	 */
	URL getUrl();

}
