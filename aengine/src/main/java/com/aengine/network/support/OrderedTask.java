package com.aengine.network.support;


import com.aengine.network.netty.AbstractConnection;
import com.aengine.network.netty.Packet;
import com.aengine.util.thread.OrderedRunnable;

/**
 */
public abstract class OrderedTask implements OrderedRunnable {

    private final Packet packet;

    private final PlayerSession<?> session;

    protected OrderedTask(PlayerSession<?> session, Packet packet) {
        this.packet = packet;
        this.session = session;
    }

    public Packet getPacket() {
        return packet;
    }

    public PlayerSession<?> getSession() {
        return session;
    }

    @Override
    public boolean tryLock() {
        return ((AbstractConnection) packet.getConnection()).tryLock();
    }

    @Override
    public void unlock() {
        ((AbstractConnection) packet.getConnection()).unlock();
    }

	@Override
	public int getIdentity() {
		return session.hashCode();
	}

	@Override
    public String toString() {
        return "cmd : " + packet.getCmd() + ",session identy : " + session.getIdentity();
    }
}
