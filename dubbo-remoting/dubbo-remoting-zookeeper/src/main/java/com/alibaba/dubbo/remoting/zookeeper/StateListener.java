package com.alibaba.dubbo.remoting.zookeeper;

/**
 * 状态监听器接口
 */
public interface StateListener {

	/**
	 * 已断开
	 */
	int DISCONNECTED = 0;

	/**
	 * 已连接
	 */
	int CONNECTED = 1;

	/**
	 * 已重连
	 */
	int RECONNECTED = 2;

	/**
	 * 状态变更回调方法
	 * @param connected
	 */
	void stateChanged(int connected);

}
