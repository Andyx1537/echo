package com.aengine.network.netty.tcp;

import com.aengine.network.netty.EventDispatcher;
import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.Packet;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 */
@ChannelHandler.Sharable
public class ServerHandler extends ChannelInboundHandlerAdapter{

    private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);

    private final long idle;

    private final int sendQueueSize;

    private final EventDispatcher dispatcher;

    private final ConcurrentMap<Channel, NettyConnection> ref = new ConcurrentHashMap<>();

    private final Thread idleChecker;

    protected ServerHandler(long idle, int sendQueueSize, EventDispatcher dispatcher) {
        this.idle = idle;
        this.sendQueueSize = sendQueueSize;
        this.dispatcher = dispatcher;
        // idle <= 0 表示禁用空闲检查，无需启动监视线程
        if (idle > 0) {
            idleChecker = new Thread(() -> {
                for (; ; ) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    ref.forEach((k, v) -> {
                        if (v.isIdle())
                            v.close();
                    });
                }
            }, "connection-idle-checker");
            idleChecker.setDaemon(true);
            idleChecker.start();
        } else {
            idleChecker = null;
        }
    }

    /**
     * 停止空闲检查线程（由 server.shutdown() 调用）
     */
    void stopIdleChecker() {
        if (idleChecker != null)
            idleChecker.interrupt();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        if (log.isDebugEnabled())
            log.debug("["+ctx.channel().remoteAddress()+"] connected");
	    NettyConnection conn = new NettyConnection(ctx.channel(), sendQueueSize);
        conn.setIdle(idle * 1000);
        dispatcher.onConnect(conn);
        ref.put(ctx.channel(), conn);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        if (log.isDebugEnabled())
            log.debug("["+ctx.channel().remoteAddress()+"] disconnected");
        IConnection conn = ref.remove(ctx.channel());
        if (conn != null)
            dispatcher.onClose(conn);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Packet packet = (Packet)msg;
	    NettyConnection conn = ref.get(ctx.channel());
        if (conn != null) {
            dispatcher.onReceive(conn, packet);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
        if (ctx.channel().isWritable()) {
	        NettyConnection conn = ref.get(ctx.channel());
            if (log.isDebugEnabled())
                log.debug("connection["+conn+"] is available, flush the queue of connection");
            if (conn != null)
                conn.flush();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("handle packet from ["+ctx.channel().id().asLongText()+"] failed!", cause);
	    NettyConnection conn = ref.get(ctx.channel());
        if (conn != null) {
            conn.close();
        } else {
            log.error("connection["+ctx.channel().id().asLongText()+"] not found in manager");
            ctx.channel().close();
        }
    }

    protected NettyConnection get(Channel ch) {
        return ref.get(ch);
    }
}
