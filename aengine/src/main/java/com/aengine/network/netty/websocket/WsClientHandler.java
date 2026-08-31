package com.aengine.network.netty.websocket;

import com.aengine.network.netty.IConnection;
import com.aengine.network.netty.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.CharsetUtil;

/**
 */
public class WsClientHandler extends SimpleChannelInboundHandler<Object> {
  private final WebSocketClientHandshaker handshaker;
  private ChannelPromise handshakeFuture;

  public WsClientHandler(WebSocketClientHandshaker handshaker) {
    this.handshaker = handshaker;
  }

  public ChannelFuture handshakeFuture() {
    return handshakeFuture;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    handshakeFuture = ctx.newPromise();
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    System.out.println("客户端连接建立");
    // 在通道连接成功后发送握手连接
    handshaker.handshake(ctx.channel());
    super.channelActive(ctx);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
    Channel ch = ctx.channel();

    // 这里是第一次使用http连接成功的时候
    if (!handshaker.isHandshakeComplete()) {
      handshaker.finishHandshake(ch, (FullHttpResponse) msg);
      System.out.println("WebSocket Client connected!");
      handshakeFuture.setSuccess();
      return;
    }

    // 这里是第一次使用http连接失败的时候
    if (msg instanceof FullHttpResponse) {
      FullHttpResponse response = (FullHttpResponse) msg;
      throw new IllegalStateException(
              "Unexpected FullHttpResponse (getStatus=" + response.getStatus() + ", content=" + response.content()
                      .toString(CharsetUtil.UTF_8) + ')');
    }
    Packet packet = (Packet) msg;
//    IConnection conn = ref.get(ctx.channel());
//    if (conn != null) {
//      dispatcher.onReceive(conn, packet);
//    }
    // 这里是服务器与客户端进行通讯的
    WebSocketFrame frame = (WebSocketFrame) msg;
    if (frame instanceof TextWebSocketFrame) {
      TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
      System.out.println("客户端：接收到TextWebSocketFrame消息，消息内容是-- " + textFrame.text());
    } else if (frame instanceof BinaryWebSocketFrame) {
      System.out.println("客户端：接收到BinaryWebSocketFrame消息，消息内容是-- ");
      ByteBuf content = frame.content();
      byte[] result = new byte[content.readableBytes()];
      content.readBytes(result);
      for (byte b : result) {
        System.out.print(b);
        System.out.print(",");
      }
      System.out.println();
    } else if (frame instanceof PongWebSocketFrame) {
      System.out.println("WebSocket Client received pong");
    } else if (frame instanceof CloseWebSocketFrame) {
      System.out.println("WebSocket Client received closing");
      ch.close();
    }

  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable arg1) {
    System.out.println("异常发生");
    arg1.printStackTrace();
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    System.out.println("客户端连接断开");
    super.channelInactive(ctx);
  }
}
