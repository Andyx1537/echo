package com.aengine.network.netty;

/**
 *
 */
public abstract class AbstractSocketServer implements ISocketServer{

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
