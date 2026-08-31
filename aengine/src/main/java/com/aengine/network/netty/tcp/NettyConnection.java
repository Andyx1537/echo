package com.aengine.network.netty.tcp;

import com.aengine.network.netty.AbstractConnection;
import com.aengine.network.netty.Packet;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 */
public class NettyConnection extends AbstractConnection {

	public static final AttributeKey<Short> RECV_SID = AttributeKey.valueOf("RECV_SID");

	public static final AttributeKey<Short> SEND_SID = AttributeKey.valueOf("SEND_SID");

    private final Channel channel;

    private final String localAddress;

    private final String remoteAddress;

    private boolean closed = false;

    public NettyConnection(Channel channel, int sendQueueSize) {
        super(sendQueueSize);
        this.channel = channel;
        this.localAddress = channel.localAddress() == null ? "" : channel.localAddress().toString();
	    this.remoteAddress = channel.remoteAddress() == null ? "" : channel.remoteAddress().toString();
    }


    @Override
    protected void writeAndFlush(Packet packet) {
    	/*
    	if ((packet.getHead() & Packet.HEAD_CLOSE) == Packet.HEAD_CLOSE) {
		    ChannelFuture future = channel.writeAndFlush(packet);
		    future.addListener(new GenericFutureListener<Future<? super Void>>() {
			    @Override
			    public void operationComplete(Future<? super Void> future) throws Exception {
			    	channel.close();
			    }
		    });
	    } else {
		    channel.writeAndFlush(packet);
	    }
	    */
        channel.writeAndFlush(packet);
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public boolean isWritable() {
        return channel.isWritable();
    }

    @Override
    public void close() {
    	/*
    	if (closed)
    		return;
    	closed = true;
    	write(new Packet((byte)(Packet.HEAD_TCP | Packet.HEAD_CLOSE), 0, new byte[]{0}));
    	*/
        if (channel.isActive()) {
	        channel.close();
        }
    }

    @Override
    public String localAddress() {
    	return localAddress;
    }

    @Override
    public String remoteAddress() {
    	return remoteAddress;
    }
}
