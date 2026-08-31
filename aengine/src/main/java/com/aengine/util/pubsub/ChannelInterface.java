package com.aengine.util.pubsub;

import java.util.List;

/**
 * 频道解释器
 * 
 *
 */
public interface ChannelInterface {

	/**
	 * 
	 * @return
	 */
	public PubServiceEnum getServiceType();

	/**
	 * 频道消息条数限制
	 * 
	 * @return
	 */
	public int getChannelListSize();

	/**
	 * 频道信息
	 * 
	 * @return
	 */
	public String getChannel();

	/**
	 * 频道存活时长,缓存时间(秒)
	 * 
	 * @return
	 */
	public int getChannelCacheTime();

	/**
	 * 频道KeyList条数限制
	 * 
	 * @return
	 */
	public int getChannelKeySize();

	/**
	 * 订阅成功反馈
	 */
	public void onSuccess(List<String> messages);

	public static void sendMessage(String channel, String message, String key) {
		// Redis.send
		PubSubService.send(channel, message, key);
	}

	public static void sendMessageWithHead(String channel, String message, String key, String headInfo) {
		PubSubService.send(channel, message, key, headInfo);
	}

	public static String getKey(String channel, String... params) {
		StringBuilder stf = new StringBuilder(channel);
		for (String param : params) {
			stf.append("_" + param);
		}
		return stf.toString();
	}

	public static String getKeyWithOrderDes(String channel , String... params) {
		if (params.length == 0)
			return channel;
		if (params.length == 1)
			return channel + "_" + params[0];
		StringBuilder stf = new StringBuilder(channel);
		for(int i = 0 ; i < params.length -1;i++) {
			for(int j = 0 ; j < params.length -1 - i ; j++) {
				if (params[j].compareTo(params[j+1]) < 0) {
					String temp = params[j];
					params[j] = params[j+1];
					params[j+1] = temp;
				}
			}
		}
		for (String param : params) {
			stf.append("_" + param);
		}
		return stf.toString();
	}
	
	public static String getKeyWithOrderAsc(String channel , String... params) {
		if (params.length == 0)
			return channel;
		if (params.length == 1)
			return channel + "_" + params[0];
		StringBuilder stf = new StringBuilder(channel);
		for(int i = 0 ; i < params.length -1;i++) {
			for(int j = 0 ; j < params.length -1 - i ; j++) {
				if (params[j].compareTo(params[j+1]) > 0) {
					String temp = params[j];
					params[j] = params[j+1];
					params[j+1] = temp;
				}
			}
		}
		for (String param : params) {
			stf.append("_" + param);
		}
		return stf.toString();
	}
}
