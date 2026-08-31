package com.aengine.network.netty;

/**
 */
public interface IEventListener {

    void onConnect(IConnection conn);

    void onClose(IConnection conn);

    void onWritable(IConnection conn);

    void onReceive(IConnection conn, Packet packet);

    void onFail(IConnection conn, Packet packet);
}
