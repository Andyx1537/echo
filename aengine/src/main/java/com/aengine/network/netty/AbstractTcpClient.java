package com.aengine.network.netty;

/**
 */
public abstract class AbstractTcpClient implements ITcpClient{
    protected EventDispatcher dispatcher = new EventDispatcher();

    @Override
    public void register(IEventListener listener) {
        dispatcher.addListener(listener);
    }

    @Override
    public void unregister(IEventListener listener) {
        dispatcher.removeListener(listener);
    }
}
