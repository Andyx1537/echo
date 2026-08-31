package com.aengine.network.netty.tcp;

import com.aengine.network.netty.ICheckSum;
import com.aengine.network.netty.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.Attribute;

import java.util.List;

/**
 * 解码器
 *
 */
public class Decoder extends ByteToMessageDecoder {
    private final int limit;

    private final ICheckSum checkSum;

    public Decoder(int limit) {
    	this(null, limit);
    }

    public Decoder(ICheckSum checkSum, int limit) {
    	this.limit = limit;
    	this.checkSum = checkSum;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    	if (checkSum == null) {
		    if (in.readableBytes() < 7)
			    return;
		    in.markReaderIndex();
		    byte head = in.readByte();
		    short length = in.readShort();
		    if (length <= 0 || length > limit)
			    throw new IllegalArgumentException();
		    int cmd = in.readInt();
		    if (in.readableBytes() < length - 4) {
			    in.resetReaderIndex();
			    return;
		    }
		    byte[] bytes = new byte[length - 4];
		    in.readBytes(bytes);
		    out.add(new Packet(head, cmd, bytes));
	    } else {
		    if (in.readableBytes() < 7 + checkSum.length())
			    return;
		    in.markReaderIndex();
		    byte head = in.readByte();

		    byte[] orig = new byte[checkSum.length()];
		    in.readBytes(orig);
		    short sid = in.readShort();
		    if (!checkSid(ctx, sid))
			    throw new IllegalArgumentException();

		    short length = in.readShort();
		    if (length <= 0 || length > limit)
			    throw new IllegalArgumentException();
		    int cmd = in.readInt();
		    if (in.readableBytes() < length - 4) {
			    in.resetReaderIndex();
			    return;
		    }
		    byte[] bytes = new byte[length - 4];
		    in.readBytes(bytes);

		    byte[] check = new byte[2 + 2 + length];
		    in.resetReaderIndex();
		    in.skipBytes(1 + checkSum.length());
		    in.readBytes(check);

		    byte[] compare = checkSum.checksum(check);
		    for (int i = 0; i < orig.length; i++) {
			    if (orig[i] != compare[i]) {
				    throw new IllegalArgumentException();
			    }
		    }
		    out.add(new Packet(head, sid, cmd, bytes));
	    }
    }

    private boolean checkSid(ChannelHandlerContext ctx, short sid) {
	    Attribute<Short> attr = ctx.channel().attr(NettyConnection.RECV_SID);
	    if (attr.get() == null) {
	    	attr.set((short)1);
		    return sid == 1;
	    }
	    if (sid != attr.get() + 1)
	    	return false;
	    if (sid == Short.MAX_VALUE)
		    attr.set((short)0);
	    else
		    attr.set(sid);
	    return true;
    }
}
