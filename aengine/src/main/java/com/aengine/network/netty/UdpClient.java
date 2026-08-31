package com.aengine.network.netty;

import java.io.IOException;
import java.net.*;

/**
 */
public class UdpClient {
    private static DatagramSocket sendSocket;

    public static void init(int port, InetAddress ip) throws SocketException {
        sendSocket = new DatagramSocket(port, ip);
    }


    public static void send(byte[] data, InetAddress ip, int port) throws IOException {
        DatagramPacket sendPacket = new DatagramPacket(data, data.length, ip,
                port);
        sendSocket.send(sendPacket);
    }

    public static void send(byte[] data, InetSocketAddress address) throws IOException {
        DatagramPacket sendPacket = new DatagramPacket(data, data.length, address);
        sendSocket.send(sendPacket);
    }

    public static void destroy() {
        if (sendSocket != null)
            sendSocket.close();
    }
}
