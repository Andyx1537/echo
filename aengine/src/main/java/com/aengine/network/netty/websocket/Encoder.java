package com.aengine.network.netty.websocket;

import com.aengine.network.netty.ICheckSum;
import com.aengine.network.netty.Packet;
import com.aengine.network.netty.tcp.NettyConnection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.Attribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编码器
 *
 */
public class Encoder extends ChannelOutboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Encoder.class);

    private final int limit;

    private final ICheckSum checkSum;

    public Encoder(ICheckSum checkSum, int limit) {
    	this.checkSum = checkSum;
        this.limit = limit;
    }

	public Encoder(int limit) {
        this(null, limit);
	}

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof Packet) {
            Packet packet = (Packet) msg;
	        if (packet.getBytes().length > limit && log.isWarnEnabled())
		        log.warn("cmd["+packet.getCmd()+"],packet size["+packet.getBytes().length+"] is over limit["+limit+"]");
	        if((short)(packet.getBytes().length + 4) < 0) {
		        log.error("packet size[" + packet.getBytes().length + "] is over limit[" + limit + "]");
	        }
	        if (checkSum == null) {
		        int size = 7 + packet.getBytes().length;
		        ByteBuf buf = ctx.alloc().buffer(size);
		        try {
			        buf.writeByte(packet.getHead());
			        buf.writeShort(packet.getBytes().length + 4);
			        buf.writeInt(packet.getCmd());
			        buf.writeBytes(packet.getBytes());
			        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(buf);
			        ctx.writeAndFlush(frame, promise);
			        return;
		        } catch (Exception e) {
			        buf.release();
			        promise.setFailure(e);
			        return;
		        }
	        } else {
		        int size = 7 + packet.getBytes().length + checkSum.length();
		        ByteBuf buf = ctx.alloc().buffer(size);
		        try {
			        buf.writeByte(packet.getHead());
			        size = 2 + 2 + 4 + packet.getBytes().length;
			        ByteBuf temp = Unpooled.buffer(size, size);
			        temp.writeShort(getSid(ctx));
			        temp.writeShort(packet.getBytes().length + 4);
			        temp.writeInt(packet.getCmd());
			        temp.writeBytes(packet.getBytes());
			        byte[] check = checkSum.checksum(temp.array());
			        buf.writeBytes(check);
			        buf.writeBytes(temp);
			        temp.release();
			        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(buf);
			        ctx.writeAndFlush(frame, promise);
			        return;
		        } catch (Exception e) {
			        buf.release();
			        promise.setFailure(e);
			        return;
		        }
	        }
        }
        // 非 Packet 消息（如 WebSocket 握手的 101 响应、Close 帧等）必须透传原始 promise，
        // 否则 netty 的握手处理器无法得知 101 写完成，会在默认 10s 后判定握手超时并断开连接。
        ctx.write(msg, promise);
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
