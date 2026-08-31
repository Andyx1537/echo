package com.aengine.network.support;

import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.IEventListener;
import com.aengine.network.netty.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 */
public class TransientSessionListener implements IEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransientSessionListener.class);

    private final ExecutorService pool;

    private final PacketHandlerManager handlerManager;

    public TransientSessionListener(ExecutorService pool, PacketHandlerManager manager) {
        this.pool = pool;
        this.handlerManager = manager;
    }

    @Override
    public void onConnect(IConnection conn) {

    }

    @Override
    public void onClose(IConnection conn) {

    }

    @Override
    public void onWritable(IConnection conn) {
    }

    public void onException(TransientSession session, Throwable t) {
        log.error("handle packet error", t);
    }

    @Override
    public void onReceive(IConnection conn, Packet packet) {
        TransientSession session = new TransientSession(conn, packet.getAddress());
        pool.submit(() -> {
            try {
                handlerManager.forward(session, packet);
            } catch (Exception e) {
                onException(session, e);
            }
        });
    }

    @Override
    public void onFail(IConnection conn, Packet packet) {
        TransientSession session = new TransientSession(conn, packet.getAddress());
        pool.submit(() -> {
            handlerManager.onUdpFail(session, packet);
        });
    }

    public void shutdown() {
        pool.shutdownNow();
        for (int i = 0; i < 10; i++) {
            if (!pool.isTerminated())
                try {
                    pool.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                }
        }
    }
}
