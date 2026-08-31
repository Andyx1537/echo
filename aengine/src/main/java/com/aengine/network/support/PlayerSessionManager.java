package com.aengine.network.support;

import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.IEventListener;
import com.aengine.network.netty.Packet;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 */
public abstract class PlayerSessionManager<K> implements IEventListener {

    private static final Logger log = LoggerFactory.getLogger(PlayerSessionManager.class);

    protected final ConcurrentMap<IConnection, PlayerSession<K>> c2p = new ConcurrentHashMap<>();

    protected final ConcurrentMap<PlayerSession<K>, IConnection> p2c = new ConcurrentHashMap<>();

    protected final ConcurrentMap<K, PlayerSession<K>> sessions = new ConcurrentHashMap<>();

    protected final ExecutorService pool;

    protected final PacketHandlerManager handlerManager;

    public PlayerSessionManager(ExecutorService pool, PacketHandlerManager handlerManager) {
        this.pool = pool;
        this.handlerManager = handlerManager;
    }

    @Override
    public void onConnect(IConnection conn) {
        PlayerSession<K> session = new PlayerSession<>(conn);
        c2p.put(conn, session);
        p2c.put(session, conn);
    }

    @Override
    public void onClose(IConnection conn) {
        PlayerSession<K> session = c2p.remove(conn);
        if (session != null) {
            p2c.remove(session);
            if (session.getIdentity() != null) {
                sessions.remove(session.getIdentity());//如果没有Key
                //破坏了结构
                offline(session.getIdentity());
            }
        }
    }

    /**
     * 离线处理
     *
     * @param identity
     */
    public abstract void offline(K identity);

    @Override
    public void onWritable(IConnection conn) {
    }

    public void onException(PlayerSession<K> session, HandleException e) {
        log.error("handle packet error", e);
    }

    @Override
    public void onReceive(IConnection conn, Packet packet) {
        PlayerSession<K> session = c2p.get(conn);
        if (session == null) {
            return;
        }
        pool.execute(new OrderedTask(session, packet) {
            @Override
            public void run() {
                try {
                    handlerManager.forward(getSession(), getPacket());
                } catch (Throwable t) {
                    onException(session, new HandleException(packet.getCmd(), t));
                }
            }
        });
    }

    @Override
    public void onFail(IConnection conn, Packet packet) {
    }

    public void setIdentity(K identity, PlayerSession<K> session) {
        session.setIdentity(identity);
        PlayerSession<K> old = sessions.remove(identity);
        if (old != null && !old.equals(session)) {
            old.setIdentity(null);
            old.close();
        }
        sessions.put(identity, session);
    }

    public PlayerSession<K> getPlayerSession(K identity) {
        return sessions.get(identity);
    }

    public void broadcast(Message message) {
        sessions.forEach((k, v) -> {
            v.send(message);
        });
    }

    public PacketHandlerManager getHandlerManager() {
        return handlerManager;
    }

    public void shutdown() {
        pool.shutdownNow();
        for (int i = 0; i < 10; i++) {
            if (!pool.isTerminated()) {
                try {
                    pool.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    @Deprecated
    public int getCurrentSessionSize() {
        return sessions.size();
    }
    /**
     * 获取当前的在线信息
     * @return 
     */
    public Set<K> getAllSessionKeys() {
        return new HashSet<>(sessions.keySet());
    }
}
