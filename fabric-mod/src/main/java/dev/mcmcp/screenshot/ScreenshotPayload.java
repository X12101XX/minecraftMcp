package dev.mcmcp.screenshot;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Dedicated screenshot channel; never carries world mutations or visual overlay data. */
public final class ScreenshotPayload {
	private ScreenshotPayload() {}
	public static final int CHUNK = 16384, MAX_IMAGE = 4 * 1024 * 1024;
	public enum Command { REQUEST, CANCEL }
	public enum Response { ERROR, DATA, TIMEOUT }
	public record Request(String session, String id, Command command) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minecraft-mcp", "capture_request_v1"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.ofMember(Request::encode, Request::decode);
		private void encode(RegistryFriendlyByteBuf buf) { buf.writeUtf(session, 128); buf.writeUtf(id, 128); buf.writeEnum(command); }
		private static Request decode(RegistryFriendlyByteBuf buf) { return new Request(buf.readUtf(128), buf.readUtf(128), buf.readEnum(Command.class)); }
		@Override public Type<Request> type() { return TYPE; }
	}
	public record Reply(String session, String id, Response response, int total, int offset, byte[] data) implements CustomPacketPayload {
		public static final Type<Reply> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minecraft-mcp", "capture_reply_v1"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = StreamCodec.ofMember(Reply::encode, Reply::decode);
		public Reply {
			if (data.length > CHUNK || total < 0 || total > MAX_IMAGE || offset < 0 || offset > total || data.length > total - offset || (response != Response.DATA && (total != 0 || offset != 0 || data.length != 0)) || (response == Response.DATA && (total == 0 || data.length == 0))) throw new IllegalArgumentException("invalid screenshot payload");
		}
		private void encode(RegistryFriendlyByteBuf buf) { buf.writeUtf(session, 128); buf.writeUtf(id, 128); buf.writeEnum(response); buf.writeVarInt(total); buf.writeVarInt(offset); buf.writeByteArray(data); }
		private static Reply decode(RegistryFriendlyByteBuf buf) {
			String session = buf.readUtf(128), id = buf.readUtf(128); Response response = buf.readEnum(Response.class);
			int total = buf.readVarInt(), offset = buf.readVarInt(); byte[] data = buf.readByteArray(CHUNK);
			try { return new Reply(session, id, response, total, offset, data); } catch (IllegalArgumentException e) { throw new DecoderException(e); }
		}
		@Override public Type<Reply> type() { return TYPE; }
	}
}
