package com.aengine.network.netty.udp;

import com.aengine.network.netty.Packet;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 */
@ChannelHandler.Sharable
public class OutboundHandler extends ChannelOutboundHandlerAdapter{

	private final UdpServer udpServer;

	public OutboundHandler(UdpServer udpServer) {
		this.udpServer = udpServer;
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		Packet packet = (Packet) msg;
		if ((packet.getHead() & Packet.HEAD_NEED_ACK) == Packet.HEAD_NEED_ACK && packet.getRetry() == 0) {
			udpServer.checkAndMakeRetryTask(packet);
		}
		super.write(ctx, msg, promise);
	}
}
