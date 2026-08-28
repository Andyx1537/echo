package com.echo.gateway;

import com.aengine.network.support.PlayerSession;
import com.echo.proto.system.Heartbeat_9001;
import com.echo.proto.system.HeartbeatAck_9002;
import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 心跳 Handler 单测：回 {@link HeartbeatAck_9002}（含服务端时间）。
 */
@ExtendWith(MockitoExtension.class)
class HeartbeatHandlerTest {

    @Mock
    private PlayerSession<Long> session;

    @Test
    void onHeartbeatRepliesAck() {
        long before = System.currentTimeMillis();
        new HeartbeatHandler().onHeartbeat(session,
                Heartbeat_9001.newBuilder().setClientTime(123L).build());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(session).send(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(HeartbeatAck_9002.class);
        HeartbeatAck_9002 ack = (HeartbeatAck_9002) captor.getValue();
        assertThat(ack.getServerTime()).isGreaterThanOrEqualTo(before);
    }
}
