package com.aengine.network.netty.udp;

import com.aengine.network.netty.AbstractSocketServer;
import com.aengine.network.netty.EventDispatcher;
import com.aengine.network.netty.Packet;
import com.aengine.network.support.TransientSessionListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;

/**
 */
public class UdpServer extends AbstractSocketServer {

    private static final Logger log = LoggerFactory.getLogger(UdpServer.class);

    private final EventLoopGroup bossGroup;

    private final Bootstrap bootstrap;

    private ServerHandler handler;

    public UdpServer() {
        bossGroup = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
    }

    @Override
    public void bind(String ip, int port) {
        handler = new ServerHandler(this);
        final OutboundHandler outboundHandler = new OutboundHandler(this);
        bootstrap
                .group(bossGroup)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) throws Exception {
                        ch.pipeline().addLast("decoder", new Decoder())
                                .addLast("server-handler", handler)
                                .addLast("encoder", new Encoder())
                                .addLast("outbound-handler", outboundHandler);
                    }

                });
        try {
            bootstrap.bind(ip, port).sync();
        } catch (InterruptedException e) {
            log.error("bind " + ip + ":" + port + " failed", e);
            shutdown();
        }
    }

    @Override
    public void start() {
    }

    @Override
    public void shutdown() {
        try {
            bossGroup.shutdownGracefully().sync();
            scheduler.shutdown();
        } catch (InterruptedException e) {
            log.error("shutdown boss group failed", e);
        }
        TransientSessionListener transientSessionListener = (TransientSessionListener) dispatcher.getListener(TransientSessionListener.class);
        if (transientSessionListener != null) {
            transientSessionListener.shutdown();
        }
    }

    @Override
    public void setOpt(String opt, Object value) {
        bootstrap.option(ChannelOption.valueOf(opt), value);
    }

    public void send(Packet packet) {
        packet.setId(Packet.nextId());
        if (handler.getConn() != null)
            handler.getConn().write(packet);
    }

    private final Map<Integer, Future<?>> futures = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);

    void checkAndMakeRetryTask(Packet packet) {
        final Future<?>[] self = new Future<?>[1];
        Future<?> future = scheduler.scheduleAtFixedRate(() -> {
            packet.retry();
            if (packet.getRetry() > 10) {
                // 条件移除（避免误删被 ack 抢先替换的任务），并无条件取消自身，杜绝空转
                futures.remove(packet.getId(), self[0]);
                if (self[0] != null)
                    self[0].cancel(false);
                dispatcher.onFail(handler.getConn(), packet);
                return;
            }
            if (handler.getConn() != null)
                handler.getConn().write(packet);
        }, 1, 1, TimeUnit.SECONDS);
        self[0] = future;
        Future<?> old = futures.put(packet.getId(), future);
        if (old != null && !old.isCancelled())
            old.cancel(false);
    }

    void cancelRetryTask(int id) {
        Future<?> future = futures.remove(id);
        if (future != null && !future.isCancelled())
            future.cancel(false);
    }

    EventDispatcher getEventDispatcher() {
        return dispatcher;
    }
}
