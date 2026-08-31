package com.aengine.network.netty.udp;

import com.aengine.network.netty.Packet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 */
public class Decoder extends MessageToMessageDecoder<DatagramPacket>{

    @Override
    protected void decode(ChannelHandlerContext ctx, DatagramPacket msg, List<Object> out) throws Exception {
    	if (msg.content().readableBytes() < 1 + 4 + 2 + 4)
    		return;
        byte head = msg.content().readByte();
        int id = msg.content().readInt();
        short length = msg.content().readShort();
        if (length <= 0)
        	return;
        int cmd = msg.content().readInt();
        if (cmd <= 0)
        	return;
        if (msg.content().readableBytes() != length - 4)
        	return;
        byte[] bytes = new byte[length - 4];
        msg.content().readBytes(bytes);
        Packet packet = new Packet(head, cmd, bytes);
        packet.setId(id);
        packet.setAddress(msg.sender());
        out.add(packet);
    }
}
