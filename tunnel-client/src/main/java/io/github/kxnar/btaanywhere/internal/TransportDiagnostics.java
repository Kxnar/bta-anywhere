package io.github.kxnar.btaanywhere.internal;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QLogConfiguration;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Opt-in numeric sampling and native qlog; qlog may include endpoint metadata. */
final class TransportDiagnostics extends ChannelDuplexHandler {
	private long readBytes;
	private long pendingBytes;
	private long writtenBytes;

	/** Packet metadata only; opt-in captures can be large and include peer addresses. */
	static QLogConfiguration qlogConfiguration() {
		String directory = System.getProperty("bta.qlogDirectory");
		if (directory == null) { return null; }
		Path root = Path.of(directory).toAbsolutePath();
		if (!Files.isDirectory(root)) {
			throw new IllegalArgumentException("bta.qlogDirectory must be an existing directory");
		}
		// Native qlog requires a nonexistent path. Separate files also preserve
		// old connections when a diagnostic run reconnects with an empty local CID.
		return new QLogConfiguration(root.resolve("connection-" + UUID.randomUUID() + ".qlog").toString(),
			"BTA Anywhere tunnel", "Opt-in native QUIC packet and recovery events");
	}

	static void start(QuicChannel channel) {
		if (Boolean.getBoolean("bta.transportProfile")) {
			sampleConnection(channel, System.nanoTime() + 500_000_000L);
		}
	}

	private static void emit(JsonObject row) {
		row.addProperty("java_nanos", System.nanoTime());
		System.err.println("BTA_DIAGNOSTIC " + row);
	}

	private static void sampleConnection(QuicChannel channel, long due) {
		channel.eventLoop().schedule(() -> {
			if (!channel.isActive()) { return; }
			long lag = Math.max(0, System.nanoTime() - due) / 1000;
			channel.collectPathStats(0).addListener(future -> {
				if (future.isSuccess()) {
					var stats = (io.netty.incubator.codec.quic.QuicConnectionPathStats) future.getNow();
					JsonObject row = new JsonObject();
					row.addProperty("kind", "java_connection");
					row.addProperty("loop_delay_us", lag);
					row.addProperty("rtt_native", stats.rtt());
					row.addProperty("cwnd", stats.cwnd());
					row.addProperty("sent", stats.sent());
					row.addProperty("lost", stats.lost());
					row.addProperty("retrans", stats.retrans());
					row.addProperty("stream_retrans_bytes", stats.streamRetransBytes());
					row.addProperty("sent_bytes", stats.sentBytes());
					row.addProperty("recv_bytes", stats.recvBytes());
					emit(row);
				}
			});
			sampleConnection(channel, System.nanoTime() + 500_000_000L);
		}, 500, TimeUnit.MILLISECONDS);
	}

	@Override
	public void handlerAdded(ChannelHandlerContext context) {
		sampleStream(context);
	}

	private void sampleStream(ChannelHandlerContext context) {
		context.executor().schedule(() -> {
			if (!context.channel().isActive()) { return; }
			QuicStreamChannel stream = (QuicStreamChannel) context.channel();
			JsonObject row = new JsonObject();
			row.addProperty("kind", "java_stream");
			row.addProperty("stream", stream.streamId());
			row.addProperty("capacity", stream.bytesBeforeUnwritable());
			row.addProperty("writable", stream.isWritable());
			row.addProperty("pending_write_bytes", pendingBytes);
			row.addProperty("read_bytes", readBytes);
			row.addProperty("written_bytes", writtenBytes);
			emit(row);
			sampleStream(context);
		}, 500, TimeUnit.MILLISECONDS);
	}

	@Override
	public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
		if (message instanceof ByteBuf buffer) { readBytes += buffer.readableBytes(); }
		super.channelRead(context, message);
	}

	@Override
	public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
		int size = message instanceof ByteBuf buffer ? buffer.readableBytes() : 0;
		pendingBytes += size;
		promise.addListener(future -> {
			pendingBytes -= size;
			if (future.isSuccess()) { writtenBytes += size; }
		});
		super.write(context, message, promise);
	}
}
