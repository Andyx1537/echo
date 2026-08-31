package com.aengine.network;

import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.IEventListener;
import com.aengine.network.netty.Packet;
import com.aengine.network.netty.websocket.WebSocketServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket 长连接手动测试 harness（不进 CI）。
 *
 * <p>用途：验证"握手完成后才 onConnect 并回连接成功响应"的修复，确认长连接不会在 10s 内被断开。</p>
 *
 * <p>运行方式（在 Aengine 目录下，JDK21）：</p>
 * <pre>
 * mvn -q test-compile \
 *   org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *   -Dexec.mainClass=com.aengine.network.WebSocketLongConnectionTest \
 *   -Dexec.classpathScope=test
 * </pre>
 *
 * <p>判定：收到服务端连接成功响应(cmd=1) 且 观察窗口(15s，已跨过 netty 默认 10s 握手超时) 内连接保持存活 → PASS。</p>
 */
public class WebSocketLongConnectionTest {

    private static final int PORT = 18080;
    private static final String PATH = "/";

    private static final byte HEAD = Packet.HEAD_TCP;
    private static final int CMD_CONNECT_SUCCESS = 1;
    private static final int CMD_HEARTBEAT = 2;

    private static final int OBSERVE_SECONDS = 15;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {
        log("=== WebSocket 长连接测试开始（观察 " + OBSERVE_SECONDS + "s，跨过 netty 默认 10s 握手超时）===");

        // ---------- 1. 启动引擎 WebSocket 服务端 ----------
        WebSocketServer server = new WebSocketServer(2);
        AtomicReference<IConnection> serverConn = new AtomicReference<>();
        server.register(new IEventListener() {
            @Override
            public void onConnect(IConnection conn) {
                serverConn.set(conn);
                log("server : onConnect（握手已完成）-> 回写连接成功响应 cmd=" + CMD_CONNECT_SUCCESS);
                conn.write(new Packet(HEAD, CMD_CONNECT_SUCCESS, "CONNECT_OK".getBytes()));
            }

            @Override
            public void onClose(IConnection conn) {
                log("server : onClose 连接关闭");
            }

            @Override
            public void onWritable(IConnection conn) {
            }

            @Override
            public void onReceive(IConnection conn, Packet packet) {
                log("server : 收到客户端包 cmd=" + packet.getCmd() + "（已刷新 lastReadTime）");
            }

            @Override
            public void onFail(IConnection conn, Packet packet) {
            }
        });
        server.bind("127.0.0.1", PORT);
        server.start();
        log("server : 已监听 127.0.0.1:" + PORT);

        // ---------- 2. 原生 netty WebSocket 客户端 ----------
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        CountDownLatch connectSuccess = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<Long> closedAt = new AtomicReference<>();
        long startNanos = System.nanoTime();

        URI uri = URI.create("ws://127.0.0.1:" + PORT + PATH);
        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), 1024 * 1024);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline()
                                .addLast(new HttpClientCodec())
                                .addLast(new HttpObjectAggregator(65536))
                                .addLast(new WebSocketClientProtocolHandler(handshaker))
                                .addLast(new ClientHandler(connectSuccess));
                    }
                });

        Channel channel = bootstrap.connect("127.0.0.1", PORT).sync().channel();
        channel.closeFuture().addListener(f -> {
            closed.set(true);
            closedAt.set((System.nanoTime() - startNanos) / 1_000_000);
            log("client : 连接断开，发生在连接后 " + closedAt.get() + "ms");
        });

        // ---------- 3. 观察窗口 ----------
        boolean got = connectSuccess.await(5, TimeUnit.SECONDS);
        log("client : 5s 内是否收到连接成功响应 = " + got);

        for (int s = 1; s <= OBSERVE_SECONDS; s++) {
            Thread.sleep(1000);
            if (closed.get()) {
                break;
            }
            if (s % 3 == 0) {
                log("client : 第 " + s + "s，连接存活=" + channel.isActive());
            }
        }

        boolean alive = channel.isActive() && !closed.get();
        log("=== 结果 ===");
        log("收到连接成功响应 : " + got);
        log("观察 " + OBSERVE_SECONDS + "s 后连接存活 : " + alive
                + (closedAt.get() != null ? "（断开于 " + closedAt.get() + "ms）" : ""));
        log((got && alive) ? ">>> PASS：长连接稳定，未在 10s 内断联" : ">>> FAIL：连接异常断开/未收到成功响应");

        // ---------- 4. 清理 ----------
        channel.close();
        group.shutdownGracefully();
        server.shutdown();
        System.exit((got && alive) ? 0 : 1);
    }

    /** 客户端处理：握手完成后启动心跳，并解析服务端下发的引擎包。 */
    private static class ClientHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {
        private final CountDownLatch connectSuccess;

        ClientHandler(CountDownLatch connectSuccess) {
            this.connectSuccess = connectSuccess;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                log("client : WebSocket 握手完成，启动每 4s 一次心跳");
                ctx.channel().eventLoop().scheduleAtFixedRate(
                        () -> ctx.channel().writeAndFlush(buildEnginePacket(CMD_HEARTBEAT, "ping")),
                        4, 4, TimeUnit.SECONDS);
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
            ByteBuf in = frame.content();
            if (in.readableBytes() < 7) {
                return;
            }
            byte head = in.readByte();
            short len = in.readShort();
            int cmd = in.readInt();
            byte[] body = new byte[Math.max(0, len - 4)];
            in.readBytes(body);
            log("client : 收到服务端包 cmd=" + cmd + " body=" + new String(body));
            if (cmd == CMD_CONNECT_SUCCESS) {
                connectSuccess.countDown();
            }
        }
    }

    /** 按引擎无校验和格式打包：[head:1][len=payload+4:2][cmd:4][payload]。 */
    private static BinaryWebSocketFrame buildEnginePacket(int cmd, String payload) {
        byte[] body = payload.getBytes();
        ByteBuf buf = Unpooled.buffer(7 + body.length);
        buf.writeByte(HEAD);
        buf.writeShort(body.length + 4);
        buf.writeInt(cmd);
        buf.writeBytes(body);
        return new BinaryWebSocketFrame(buf);
    }

    private static void log(String msg) {
        System.out.println("[" + TS.format(new Date()) + "] " + msg);
    }
}
