package com.aengine.network.support;

import com.aengine.network.netty.IConnection;
import com.google.protobuf.Message;

/**
 */
public abstract class IoSession {

	protected int protocol;

    protected final IConnection conn;

    public abstract String getIdenty();
    
    protected IoSession(IConnection conn) {
        this.conn = conn;
    }

    public abstract void send(Message message);

    public void close() {
        conn.close();
    }
}
