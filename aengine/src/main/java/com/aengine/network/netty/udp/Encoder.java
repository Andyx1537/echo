package com.aengine.network.netty.udp;

import com.aengine.network.netty.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 */
public class Encoder extends MessageToMessageEncoder<Packet> {
	
	private static Logger log = LoggerFactory.getLogger(Encoder.class);
    @Override
    protected void encode(ChannelHandlerContext ctx, Packet msg, List<Object> out) throws Exception {
        ByteBuf byteBuf = ctx.alloc().buffer();
        byteBuf.writeByte(msg.getHead());
        byteBuf.writeInt(msg.getId());
        byteBuf.writeShort(msg.getBytes().length + 4);
        byteBuf.writeInt(msg.getCmd());
        byteBuf.writeBytes(msg.getBytes());
        // TODO 检测包大小
        if (msg.getBytes().length + 4 >= 512) {
        	log.warn("package length to long ,this package length:" + msg.getBytes().length + 4 + ", msg id :" + msg.getCmd());
        }
        DatagramPacket packet = new DatagramPacket(byteBuf, msg.getAddress());
        out.add(packet);
    }
}
