package com.aengine.network.netty;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * network 模块单元测试：校验和（CRC16/CRC32/MD5）与 Packet 包结构。
 */
class NetworkTest {

    @Test
    void crc16Length() {
        ICheckSum checksum = new CRC16CheckSum();
        assertThat(checksum.length()).isEqualTo(2);
        assertThat(checksum.checksum("hello".getBytes())).hasSize(2);
    }

    @Test
    void crc32MatchesKnownValue() {
        ICheckSum checksum = new CRC32CheckSum();
        assertThat(checksum.length()).isEqualTo(4);
        byte[] result = checksum.checksum("123456789".getBytes());
        // CRC32("123456789") = 0xCBF43926
        assertThat(result).containsExactly(
                (byte) 0xCB, (byte) 0xF4, (byte) 0x39, (byte) 0x26);
    }

    @Test
    void checksumIsDeterministic() {
        ICheckSum md5 = new MD5CheckSum();
        byte[] a = md5.checksum("payload".getBytes());
        byte[] b = md5.checksum("payload".getBytes());
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(16);
    }

    @Test
    void packetCarriesHeaderAndPayload() {
        byte[] payload = {1, 2, 3};
        Packet packet = new Packet(Packet.HEAD_TCP, 1001, payload);
        assertThat(packet.getHead()).isEqualTo(Packet.HEAD_TCP);
        assertThat(packet.getCmd()).isEqualTo(1001);
        assertThat(packet.getBytes()).isEqualTo(payload);

        packet.setId(42);
        assertThat(packet.getId()).isEqualTo(42);

        packet.retry();
        assertThat(packet.getRetry()).isEqualTo(1);
    }

    @Test
    void packetNextIdIncrements() {
        int first = Packet.nextId();
        int second = Packet.nextId();
        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void packetWithSessionId() {
        Packet packet = new Packet(Packet.HEAD_UDP, (short) 7, 2002, new byte[0]);
        assertThat(packet.getSid()).isEqualTo((short) 7);
        assertThat(packet.getCmd()).isEqualTo(2002);
    }
}
