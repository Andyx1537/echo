package com.aengine.network.netty.udp;

import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.Packet;
import com.aengine.network.netty.tcp.NettyConnection;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
@ChannelHandler.Sharable
public class ServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ServerHandler.class);

    private final UdpServer udpServer;

    public ServerHandler(UdpServer udpServer) {
    	this.udpServer = udpServer;
    }

    private NettyConnection conn = null;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        conn = new NettyConnection(ctx.channel(), 200);
    }

	private static byte[] dummy = new byte[]{0};
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        Packet packet = (Packet)msg;
	    if ((packet.getHead() & Packet.HEAD_ACK) == Packet.HEAD_ACK) {
	        int id = packet.getId();
	        udpServer.cancelRetryTask(id);
		    return;
	    } else if ((packet.getHead() & Packet.HEAD_NEED_ACK) == Packet.HEAD_NEED_ACK) {
	    	Packet ack = new Packet((byte)(Packet.HEAD_UDP | Packet.HEAD_ACK), packet.getCmd(), dummy);
	    	ack.setId(packet.getId());
	    	ack.setAddress(packet.getAddress());
	    	conn.write(ack);
	    }
        udpServer.getEventDispatcher().onReceive(conn, packet);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        super.channelWritabilityChanged(ctx);
        if (ctx.channel().isWritable()) {
            if (log.isDebugEnabled())
                log.debug("connection["+conn+"] is available, flush the queue of connection");
            if (conn != null)
                conn.flush();
        }
    }

    IConnection getConn() {
        return conn;
    }
}
