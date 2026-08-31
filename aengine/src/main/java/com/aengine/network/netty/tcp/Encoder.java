package com.aengine.network.netty.tcp;

import com.aengine.network.netty.ICheckSum;
import com.aengine.network.netty.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.Attribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编码器
 *
 */
public class Encoder extends MessageToByteEncoder<Packet> {

    private static final Logger log = LoggerFactory.getLogger(Encoder.class);

    private final int limit;

    private final ICheckSum checkSum;

    public Encoder(int limit) {
    	this(null, limit);
    }

    public Encoder(ICheckSum checkSum, int limit) {
    	this.limit = limit;
    	this.checkSum = checkSum;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet packet, ByteBuf buf) throws Exception {
	    if (packet.getBytes().length > limit && log.isWarnEnabled())
		    log.warn("packet size[" + packet.getBytes().length + "] is over limit[" + limit + "]"+" Cmd:"+packet.getCmd());
	    if((short)(packet.getBytes().length + 4) < 0) {
		    log.error("packet size[" + packet.getBytes().length + "] is over limit[" + limit + "]"+" Cmd:"+packet.getCmd());
	    }
    	if (checkSum == null) {
		    buf.writeByte(packet.getHead());
		    buf.writeShort(packet.getBytes().length + 4);
		    buf.writeInt(packet.getCmd());
		    buf.writeBytes(packet.getBytes());
	    } else {
		    buf.writeByte(packet.getHead());
		    int size = 2 + 2 + 4 + packet.getBytes().length;
		    ByteBuf temp = Unpooled.buffer(size, size);
		    temp.writeShort(getSid(ctx));
		    temp.writeShort(packet.getBytes().length + 4);
		    temp.writeInt(packet.getCmd());
		    temp.writeBytes(packet.getBytes());
		    byte[] check = checkSum.checksum(temp.array());
		    buf.writeBytes(check);
		    buf.writeBytes(temp);
		    temp.release();
	    }
    }

    private short getSid(ChannelHandlerContext ctx) {
	    Attribute<Short> attr = ctx.channel().attr(NettyConnection.SEND_SID);
	    if (attr.get() == null) {
	    	attr.set((short)1);
	    	return 1;
	    }
	    short sid = (short)(attr.get() + 1);
	    if (sid == Short.MAX_VALUE) {
	    	attr.set((short)0);
	    } else {
	    	attr.set(sid);
	    }
	    return sid;
    }
}
