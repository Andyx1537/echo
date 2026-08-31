package com.aengine.network.netty.websocket;

import com.aengine.network.netty.AbstractSocketServer;
import com.aengine.network.netty.ICheckSum;
import com.aengine.network.support.PlayerSessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;

/**
 */
public class WebSocketServer extends AbstractSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    private final EventLoopGroup bossGroup;

    private final EventLoopGroup workerGroup;

    private final ServerBootstrap bootstrap;

    private int upLimit = 2048;

    private int downLimit = 5120;

    private int idle = 30;

    private int sendQueueSize = 30;

	private final SslContext sslContext;

	private final ICheckSum upCheckSum;

	private final ICheckSum downCheckSum;

	private ServerHandler handler;

	public WebSocketServer(ICheckSum upCheckSum, ICheckSum downCheckSum, int threads, String sslKey, String sslPass) throws Exception{
		bossGroup = new NioEventLoopGroup();
		workerGroup = new NioEventLoopGroup(threads);
		bootstrap = new ServerBootstrap();
		bootstrap.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.option(ChannelOption.SO_BACKLOG, 5)
				.childOption(ChannelOption.TCP_NODELAY, true);
		if (sslKey != null) {
			KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
			KeyStore keyStore = KeyStore.getInstance("JKS");
			keyStore.load(new FileInputStream(sslKey), sslPass.toCharArray());
			keyManagerFactory.init(keyStore,sslPass.toCharArray());
			sslContext = SslContextBuilder.forServer(keyManagerFactory).build();
		} else {
			sslContext = null;
		}
		this.upCheckSum = upCheckSum;
		this.downCheckSum = downCheckSum;
	}


    public WebSocketServer(int threads, String sslKey, String sslPass) throws Exception{
		this(null, null, threads, sslKey, sslPass);
    }

	public WebSocketServer(int threads) throws Exception{
		this(threads, null, null);
	}

    @Override
    public void bind(String ip, int port) {
        InetSocketAddress address = new InetSocketAddress(ip, port);
        // 复用同一 handler，避免重复 bind 累积空闲检查线程
        if (handler == null)
            handler = new ServerHandler(idle, sendQueueSize, dispatcher);
        final ServerHandler handler = this.handler;
        bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
	            if (sslContext != null) {
		            SSLEngine engine = sslContext.newEngine(ch.alloc());
		            engine.setUseClientMode(false);
		            ch.pipeline().addLast("ssl", new SslHandler(engine));
	            }
                ch.pipeline().addLast("http-codec", new HttpServerCodec())
                        .addLast("aggregator", new HttpObjectAggregator(65536))
                        .addLast("websocket", new WebSocketServerProtocolHandler("/", null, true))
                        .addLast("index-page", new WebSocketIndexPageHandler("/"))
                        .addLast("decoder", new Decoder(upCheckSum, upLimit))
                        .addLast("server-handler", handler)
                        .addLast("encoder", new Encoder(downCheckSum, downLimit));
            }
        });
        try {
            bootstrap.bind(address).sync();
        } catch (InterruptedException e) {
            log.error("bind "+ip+":"+port+" failed", e);
            shutdown();
        }
    }

    @Override
    public void start() {
    }

    @Override
    public void shutdown() {
        if (handler != null)
            handler.stopIdleChecker();
        try {
            bossGroup.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            log.error("shutdown boss group failed", e);
        }
        try {
            workerGroup.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            log.error("shutdown worker group failed", e);
        }
	    PlayerSessionManager<?> sessionManager = (PlayerSessionManager<?>) dispatcher.getListener(PlayerSessionManager.class);
	    if (sessionManager != null) {
		    sessionManager.shutdown();
	    }
    }

    @Override
    public void setOpt(String opt, Object value) {
        switch(opt) {
            case "UPSTREAM_LIMIT":
                this.upLimit = (int)value;
                return;
            case "DOWNSTREAM_LIMIT":
                this.downLimit = (int)value;
                return;
            case "IDLE_SEC":
                this.idle = (int)value;
                return;
            case "SEND_QUEUE_SIZE":
                this.sendQueueSize = (int)value;
                return;
            default:
                break;
        }
        bootstrap.childOption(ChannelOption.valueOf(opt), value);
    }
}
