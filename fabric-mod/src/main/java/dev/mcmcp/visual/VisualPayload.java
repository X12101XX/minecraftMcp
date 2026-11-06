package dev.mcmcp.visual;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Versioned, bounded display-only data; never accepts client world edits. */
public record VisualPayload(String session, String dimension, String id, long sequence, Kind kind,
		int duration, BlockPos min, BlockPos max, String style, int offset, int total, int processed,
		List<Entry> entries) implements CustomPacketPayload {
	public static final int MAX_ENTRIES = 128;
	public static final int MAX_BLOCKS = 8192;
	public static final Type<VisualPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("minecraft-mcp", "visual_v1"));
	private static final StreamCodec<io.netty.buffer.ByteBuf, BlockState> STATE = ByteBufCodecs.fromCodec(BlockState.CODEC);
	public static final StreamCodec<RegistryFriendlyByteBuf, VisualPayload> CODEC = StreamCodec.ofMember(VisualPayload::encode, VisualPayload::decode);

	public enum Kind { RANGE, PREVIEW, PROGRESS, CLEAR, RESET, NOTICE }
	public record Entry(BlockPos pos, BlockState state) {}

	public VisualPayload {
		entries = List.copyOf(entries);
		if (entries.size() > MAX_ENTRIES || total < 0 || total > 262144 || processed < 0 || processed > total
				|| offset < 0 || offset > MAX_BLOCKS || duration < 0 || duration > 1200
				|| (kind == Kind.PREVIEW && (total > MAX_BLOCKS || offset + entries.size() > total))) {
			throw new IllegalArgumentException("invalid display payload bounds");
		}
		if (kind == Kind.RANGE) {
			long sx = (long) max.getX() - min.getX() + 1;
			long sy = (long) max.getY() - min.getY() + 1;
			long sz = (long) max.getZ() - min.getZ() + 1;
			if (sx < 1 || sy < 1 || sz < 1 || sx > 262144 || sy > 262144 || sz > 262144 || sx * sy * sz > 262144) {
				throw new IllegalArgumentException("invalid display range");
			}
		}
	}

	private void encode(RegistryFriendlyByteBuf buf) {
		buf.writeUtf(session, 128); buf.writeUtf(dimension, 256); buf.writeUtf(id, 128);
		buf.writeVarLong(sequence); buf.writeEnum(kind); buf.writeVarInt(duration);
		buf.writeBlockPos(min); buf.writeBlockPos(max); buf.writeUtf(style, 64);
		buf.writeVarInt(offset); buf.writeVarInt(total); buf.writeVarInt(processed); buf.writeVarInt(entries.size());
		for (Entry entry : entries) { buf.writeBlockPos(entry.pos); STATE.encode(buf, entry.state); }
	}

	private static VisualPayload decode(RegistryFriendlyByteBuf buf) {
		String session = buf.readUtf(128), dimension = buf.readUtf(256), id = buf.readUtf(128);
		long sequence = buf.readVarLong(); Kind kind = buf.readEnum(Kind.class); int duration = buf.readVarInt();
		BlockPos min = buf.readBlockPos(), max = buf.readBlockPos(); String style = buf.readUtf(64);
		int offset = buf.readVarInt(), total = buf.readVarInt(), processed = buf.readVarInt(), count = buf.readVarInt();
		if (count < 0 || count > MAX_ENTRIES) throw new DecoderException("display page exceeds entry limit");
		List<Entry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) entries.add(new Entry(buf.readBlockPos(), STATE.decode(buf)));
		try { return new VisualPayload(session, dimension, id, sequence, kind, duration, min, max, style, offset, total, processed, entries); }
		catch (IllegalArgumentException e) { throw new DecoderException(e); }
	}

	@Override public Type<VisualPayload> type() { return TYPE; }
}
