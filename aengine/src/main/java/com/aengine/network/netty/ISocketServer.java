package com.aengine.network.netty;

/**
 */
public interface ISocketServer {

    void bind(String ip, int port);

    void start();

    void shutdown();

    void register(IEventListener listener);

    void unregister(IEventListener listener);

	/**
	 * UPSTREAM_LIMIT       上行数据包最大字节数
	 * DOWNSTREAM_LIMIT     下行数据包最大字节数
	 * IDLE_SEC             空闲秒数（超过该时间将断开连接）
	 * SEND_QUEUE_SIZE      发送缓冲队列的大小（默认30）
	 *
	 * @param opt           参数选项
	 * @param value         参数的值
	 */
	void setOpt(String opt, Object value);
}
