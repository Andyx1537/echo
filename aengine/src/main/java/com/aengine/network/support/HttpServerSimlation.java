/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aengine.network.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 *
 */
public class HttpServerSimlation implements Runnable {

    private final String ip;
    private final int port;

    private ExecutorService pool;
    private PacketHandlerManager handlerManager;

    private Logger log = LoggerFactory.getLogger(HttpServerSimlation.class);

    public HttpServerSimlation(String ip, int port, ExecutorService pool, PacketHandlerManager handlerManager) {
        this.ip = ip;
        this.port = port;
        this.pool = pool;
        this.handlerManager = handlerManager;
    }

    private boolean shutdown = false;

    @Override
    public void run() {
        ServerSocket serverSocket = null;
        try {
            InetSocketAddress address = new InetSocketAddress(ip, port);
            serverSocket = new ServerSocket();
            serverSocket.bind(address);
        } catch (Exception ex) {
            ex.printStackTrace();
            return;
        }
        String content = null;
        while (!shutdown) {
            try {

                Socket socket = null;
                while ((socket = serverSocket.accept()) != null) {
                    InputStreamReader bytesReader = new InputStreamReader(socket.getInputStream());
                    BufferedReader reader = new BufferedReader(bytesReader);
                    content = reader.readLine();
                    if (content == null) {
                        try {
                            if (!socket.isClosed() || socket.getInputStream() != null) {
                                socket.getInputStream().close();
                            }
                            socket.close();
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                        continue;
                    }
                    HashMap<String, String> contentMap = new HashMap<>();
                    String method = content.split(" ")[0];
                    String request = content.split(" ")[1];

                    String channelName = request.substring(request.indexOf("/") + 1);
                    if (channelName.contains("?")) {
                        channelName = channelName.substring(0, channelName.indexOf("?"));
                    }
                    String line = "";
                    int contentLength = 0;
                    while ((line = reader.readLine()) != null) {
                        if (line.length() <= 0) {
                            //读取头文件结束,
                            break;
                        }
                        if (line.startsWith("Content-Length")) {
                            contentLength = Integer.parseInt(line.split(":")[1].trim());
                        }
                    }

                    String requestInfo = null;
                    String requestMap = null;
                    if ("POST".equalsIgnoreCase(method)) {
                        char[] chars = new char[contentLength];
                        reader.read(chars);
                        requestInfo = new String(chars);
                    } else if ("GET".equalsIgnoreCase(method) && request.contains("?")) {
                        requestInfo = request.substring(request.indexOf("?") + 1);
                    }
                    if (requestInfo == null) {
                        //TODO log
                        break;
                    }
                    requestInfo = URLDecoder.decode(requestInfo);
                    String[] strsPairs = requestInfo.split("&");
                    for (String str : strsPairs) {
                        String[] strTem = str.split("=");
                        String key = strTem[0];
                        String value = "";
                        if (strTem.length == 2) {
                            value = strTem[1];
                        }
                        contentMap.put(key, value);
                    }

                    pool.submit(new HttpRequestHandler(socket, contentMap, channelName));
                }

                Thread.currentThread().sleep(100l);
            } catch (Exception ex) {
//                ex.printStackTrace();
                if (log.isWarnEnabled()) {
                    log.warn("receive http request error :" + content, ex);
                }
            }

        }
    }

    private class HttpRequestHandler implements Runnable {

        private Socket clientSocket;
        private Map<String, String> params;
        private String request;

        public HttpRequestHandler(Socket clientSocket, Map<String, String> params, String request) {
            this.clientSocket = clientSocket;
            this.params = params;
            this.request = request;
        }

        @Override
        public void run() {
            try {
                String response = handlerManager.handleHttpRequest(request, params);
                if (response != null) {

                    clientSocket.getOutputStream().write(createResponseString(response).getBytes(Charset.forName("UTF-8")));
                    clientSocket.getOutputStream().flush();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                try {
                    if (!clientSocket.isClosed() || clientSocket.getInputStream() != null) {
                        clientSocket.getInputStream().close();
                    }
                    clientSocket.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }

        }

    }

    private static final String createResponseString(String info) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK \n");
        sb.append("Content-Length:");
        sb.append(info.getBytes(Charset.forName("UTF-8")).length);
        sb.append("\n");
        sb.append("Content-Type: text/html;charset=utf8\n");
        sb.append("\n");
        sb.append(info);

        return sb.toString();
    }

}
