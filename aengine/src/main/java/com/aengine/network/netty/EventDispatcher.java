package com.aengine.network.netty;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 */
public class EventDispatcher implements IEventListener{

    private final ConcurrentMap<IEventListener, Boolean> listeners = new ConcurrentHashMap<>();

    @Override
    public void onConnect(IConnection conn) {
        for (IEventListener listener : listeners.keySet()) {
            listener.onConnect(conn);
        }
    }

    @Override
    public void onClose(IConnection conn) {
        for (IEventListener listener : listeners.keySet()) {
            listener.onClose(conn);
        }
    }

    @Override
    public void onWritable(IConnection conn) {
        for (IEventListener listener : listeners.keySet()) {
            listener.onWritable(conn);
        }
    }

    @Override
    public void onReceive(IConnection conn, Packet packet) {
        if (conn != null) {
            packet.setConn(conn);
            conn.updateReadTime();
        }
        for (IEventListener listener : listeners.keySet()) {
            listener.onReceive(conn, packet);
        }
    }

	@Override
	public void onFail(IConnection conn, Packet packet) {
		for (IEventListener listener : listeners.keySet()) {
			listener.onFail(conn, packet);
		}
	}

	protected void addListener(IEventListener listener) {
        listeners.putIfAbsent(listener, true);
    }

    protected void removeListener(IEventListener listener) {
        listeners.remove(listener);
    }

    public IEventListener getListener(Class<? extends IEventListener> clazz) {
    	for (IEventListener listener : listeners.keySet()) {
    		if (clazz.isAssignableFrom(listener.getClass())) {
    			return listener;
		    }
	    }
	    return null;
    }
}
