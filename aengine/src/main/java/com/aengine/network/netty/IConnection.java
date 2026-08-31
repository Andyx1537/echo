package com.aengine.network.netty;

/**
 */
public interface IConnection {

    boolean isActive();

    boolean isWritable();

    boolean isIdle();

    void setIdle(long millis);

    void close();

    void write(Packet packet);

    void flush();

    String localAddress();

    String remoteAddress();

    void updateReadTime();
}
