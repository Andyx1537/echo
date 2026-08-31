package com.aengine.network.netty;

/**
 */
public interface ITcpClient {

    IConnection connect(String ip, int port);

    void register(IEventListener listener);

    void unregister(IEventListener listener);

    void setOpt(String opt, Object value);
}
