package com.aengine.network.support;

import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.Packet;
import com.aengine.util.NetworkUtil;
import com.aengine.util.ProtobufUtil;
import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;

/**
 */
public class PlayerSession<K> extends IoSession {

    private static final Logger log = LoggerFactory.getLogger(PlayerSession.class);

    private K identity;

    public PlayerSession(IConnection conn) {
        super(conn);
    }

    public void setIdentity(K identity) {
        this.identity = identity;
    }

    public K getIdentity() {
        return identity;
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
        if (log.isDebugEnabled()) {
            log.debug("send to " + identity + ":" + message.getClass().getSimpleName() + "," + ProtobufUtil.toText(message));
        }
    }

    public void send(int messageId, byte[] message) {
        conn.write(new Packet(Packet.HEAD_TCP, messageId, message));
    }

    public void setIdle(long idle) {
        conn.setIdle(idle);
    }

    public String getRemoteAddress() {
        return conn.remoteAddress();
    }

    @Override
    public String getIdenty() {
        return identity == null ? null : identity.toString();
    }

}
