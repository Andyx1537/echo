package com.aengine.network.netty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 */
public abstract class AbstractConnection implements IConnection {

    private static final Logger log = LoggerFactory.getLogger(AbstractConnection.class);

    private final LinkedList<Packet> queue = new LinkedList<>();

    private long idle;

    private final int sendQueueSize;

    private volatile long lastReadTime = System.currentTimeMillis();

    private final AtomicBoolean locked = new AtomicBoolean(false);

    protected AbstractConnection(int sendQueueSize) {
        this.sendQueueSize = sendQueueSize;
    }

    @Override
    public boolean isIdle() {
        // idle <= 0 表示禁用空闲检查（如客户端不应因自身空闲被断开）
        if (idle <= 0) {
            return false;
        }
        return System.currentTimeMillis() - lastReadTime > idle;
    }

    @Override
    public void setIdle(long millis) {
        this.idle = millis;
    }

    protected abstract void writeAndFlush(Packet packet);

    @Override
    public void write(Packet packet) {
        if (!isActive()) {
            if (log.isDebugEnabled()) {
                log.debug("send packet to inactive connection");
            }
            return;
        }
        synchronized (queue) {
            if (queue.size() == 0) {
                if (isWritable()) {
                    writeAndFlush(packet);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("send packet to un-writable connection");
                    }
                    queue.addLast(packet);
                }
                return;
            }
            if (queue.size() > sendQueueSize) {
                log.error("too many packet in queue of connection,queue size : " + queue.size());
                log.error("packetInChannel");
                for (int i = 0; i < queue.size(); i++) {
                    log.error("packet :" + queue.get(i));
                }

                close();
                return;
            }
            queue.addLast(packet);
        }
    }

    @Override
    public void flush() {
        synchronized (queue) {
            while (queue.size() > 0) {
                if (!isActive() || !isWritable()) {
                    break;
                }
                writeAndFlush(queue.removeFirst());
            }
        }
    }

    @Override
    public void updateReadTime() {
        lastReadTime = System.currentTimeMillis();
    }

    /**
     * 锁定该连接，用来表明当前已有线程在处理该连接的网络包（基于 CAS，线程安全）
     *
     * @return true加锁成功，false加锁失败
     */
    public boolean tryLock() {
        return locked.compareAndSet(false, true);
    }

    /**
     * 解锁连接
     */
    public void unlock() {
        locked.set(false);
    }

}
