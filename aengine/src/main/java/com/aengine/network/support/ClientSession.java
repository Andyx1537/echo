/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.network.support;

import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.Packet;
import com.aengine.util.NetworkUtil;
import com.aengine.util.ProtobufUtil;
import com.google.protobuf.Message;

import java.nio.charset.Charset;

/**
 * 客户端会话缓存
 *
 * @param
 */
public class ClientSession extends IoSession {

    public ClientSession(IConnection conn) {
        super(conn);
    }

    @Override
    public void send(Message message) {
        int cmd = NetworkUtil.getMessageID(message.getClass());
        byte[] bytes = null;
        switch (protocol) {
            case Packet.PROTOCOL_PROTOBUF:
                bytes = message.toByteArray();
                break;
            case Packet.PROTOCOL_JSON:
                bytes = ProtobufUtil.toJson(message).getBytes(Charset.forName("UTF-8"));
                break;
        }
        HandlerStatistic.stats(message.getClass(), 0, bytes.length);
        conn.write(new Packet(Packet.HEAD_TCP, cmd, bytes));
    }

    @Override
    public String getIdenty() {
        return "";
    }

    
}
