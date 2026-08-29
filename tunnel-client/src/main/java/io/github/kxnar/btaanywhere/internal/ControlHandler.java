package io.github.kxnar.btaanywhere.internal;

import com.google.gson.JsonObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

final class ControlHandler extends SimpleChannelInboundHandler<JsonObject> {
	private final NettyTunnelSession session;
	private final long generation;

	ControlHandler(NettyTunnelSession session, long generation) {
		this.session = session;
		this.generation = generation;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext context, JsonObject message) {
		session.handleControl(message, generation);
	}

	@Override
	public void channelInactive(ChannelHandlerContext context) {
		session.onDisconnected(generation, new IllegalStateException("relay control stream closed"));
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
		context.close();
		session.onDisconnected(generation, cause);
	}
}
