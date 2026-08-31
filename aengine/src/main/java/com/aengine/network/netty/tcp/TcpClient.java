package com.aengine.network.netty.tcp;

import com.aengine.network.netty.AbstractTcpClient;
import com.aengine.network.netty.ICheckSum;
import com.aengine.network.netty.IConnection;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class TcpClient extends AbstractTcpClient {

    private static final Logger log = LoggerFactory.getLogger(TcpClient.class);

    private final EventLoopGroup worker;

    private final Bootstrap bootstrap;

    private int upLimit = 2048;

    private int downLimit = 5120;

    // 客户端不应因自身空闲被断开：idle <= 0 表示禁用空闲检查
    private int idle = 0;

    private int sendQueueSize = 30;

    private final ServerHandler handler;

    public TcpClient() {
    	this(null, null);
    }

    public TcpClient(ICheckSum upCheckSum, ICheckSum downCheckSum) {
        worker = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(worker).channel(NioSocketChannel.class);

        handler = new ServerHandler(idle, sendQueueSize, dispatcher);

        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast("decoder", new Decoder(upCheckSum, upLimit))
                        .addLast("server-handler", handler)
                        .addLast("encoder", new Encoder(downCheckSum, downLimit));
            }
        });
    }

    @Override
    public IConnection connect(String ip, int port) {
        try {
            ChannelFuture future = bootstrap.connect(ip, port).sync();
            //这里返回时，handler中的回调并没有被调用，所以无法获得连接对象
            for (int i=0; i<100 ;i++) {
                IConnection conn = handler.get(future.channel());
                if (conn != null)
                    return conn;
                try {
                    Thread.sleep(10);
                } catch (Exception e) {
                    break;
                }
            }
            return null;
        } catch (Exception e) {
            log.error("connect to "+ip+":"+port+" failed", e);
            return null;
        }
    }

    @Override
    public void setOpt(String opt, Object value) {
        switch(opt) {
            case "UPSTREAM_LIMIT":
                this.upLimit = (int)value;
                break;
            case "DOWNSTREAM_LIMIT":
                this.downLimit = (int)value;
                break;
            case "IDLE_SEC":
                this.idle = (int)value;
                break;
            case "SEND_QUEUE_SIZE":
                this.sendQueueSize = (int)value;
                break;
            default:
                break;
        }
        bootstrap.option(ChannelOption.valueOf(opt), value);
    }
}
