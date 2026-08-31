package com.aengine.network.support;

import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.Packet;
import com.aengine.util.NetworkUtil;
import com.aengine.util.ProtobufUtil;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 */
public class TransientSession extends IoSession {

    private final Logger log = LoggerFactory.getLogger(TransientSession.class);

    private final InetSocketAddress address;

    private final String uuid = "yaowanTransientSessionUnique";//此处理解为,所有server to server 间的信息传输,当使用到消息处理逻辑并同步入redis时,均需争抢同一把锁
    //以保证该操作的安全性

    public TransientSession(IConnection conn, InetSocketAddress address) {
        super(conn);
        this.address = address;
    }

    @Override
    public void send(Message message) {
        int cmd = NetworkUtil.getMessageID(message.getClass());
        Packet packet = new Packet(Packet.HEAD_UDP, cmd, message.toByteArray());
        packet.setAddress(address);
        conn.write(packet);
        if (log.isDebugEnabled()) {
            log.debug("send to " + address + ":" + message.getClass().getSimpleName() + ":" + ProtobufUtil.toText(message));
        }
    }

    public void sendMessageNeedAck(Message message) {
        int cmd = NetworkUtil.getMessageID(message.getClass());
        Packet packet = new Packet((byte) (Packet.HEAD_UDP | Packet.HEAD_NEED_ACK), cmd, message.toByteArray());
        packet.setAddress(address);
        packet.setId(Packet.nextId());
        conn.write(packet);
        if (log.isDebugEnabled()) {
            log.debug("send to " + address + ":" + message.getClass().getSimpleName());
        }
    }

    @Override
    public String getIdenty() {
        return uuid;
    }

}
