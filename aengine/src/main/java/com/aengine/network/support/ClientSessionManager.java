/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.network.support;

import com.aengine.network.netty.AbstractConnection;
import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.IEventListener;
import com.aengine.network.netty.Packet;
import com.aengine.util.thread.OrderedRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 *
 */
public class ClientSessionManager implements IEventListener {

    protected final ConcurrentHashMap<IConnection, ClientSession> ic2c = new ConcurrentHashMap<>();

    protected final ConcurrentHashMap<ClientSession, IConnection> c2ic = new ConcurrentHashMap<>();

//    protected final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();
    protected final ExecutorService pool;

    protected final PacketHandlerManager handlerManager;

    private static Logger log = LoggerFactory.getLogger(ClientSessionManager.class);

    public ClientSessionManager(ExecutorService pool, PacketHandlerManager handlerManager) {
        this.pool = pool;
        this.handlerManager = handlerManager;
    }

    @Override
    public void onConnect(IConnection conn) {
        ClientSession session = new ClientSession(conn);
//        log.warn("onConnect :" + conn);
        ic2c.put(conn, session);
        c2ic.put(session, conn);
    }

    @Override
    public void onClose(IConnection conn) {
//        log.warn("onClose :" + conn);
        ClientSession session = ic2c.remove(conn);
        if (session != null) {
            c2ic.remove(session);
//            if (session.getIdentity() != null) {
//                sessions.remove(session.getIdentity());//如果没有Key
//            }
        }
    }

    @Override
    public void onWritable(IConnection conn) {
    }

    public void onException(ClientSession session, HandleException e) {
//        log.error("handle packet error", e);
    }

    @Override
    public void onReceive(IConnection conn, Packet packet) {
        ClientSession session = ic2c.get(conn);
        if (session == null) {
            return;
        }
        pool.execute(new OrderedRunnable() {
            @Override
            public void run() {
                try {
                    handlerManager.forward(session, packet);
                } catch (Throwable t) {
                    onException(session, new HandleException(packet.getCmd(), t));
                }
            }

            @Override
            public void unlock() {
                ((AbstractConnection) packet.getConnection()).unlock();
            }

            @Override
            public boolean tryLock() {
                return ((AbstractConnection) packet.getConnection()).tryLock();
            }

            @Override
            public int getIdentity() {
                return session.hashCode();
            }
        });
    }

    @Override
    public void onFail(IConnection conn, Packet packet) {

    }

//    public void setIdentity(K identity, PlayerSession<K> session) {
//        session.setIdentity(identity);
//        if (sessions.containsKey(identity)) {
//            PlayerSession<K> old = sessions.remove(identity);
//            old.setIdentity(null);
//            old.close();
//        }
//        sessions.put(identity, session);
//    }
//    public PlayerSession<K> getPlayerSession(K identity) {
//        return sessions.get(identity);
//    }
    public ClientSession getClientSessionByConn(IConnection conn) {
        return ic2c.get(conn);
    }
}
