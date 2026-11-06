package dev.mcmcp.visual;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** All sending and player access happens on the server thread. HTTP only records notice bits. */
public final class VisualEvents {
	private static final String[] TOOLS = {"get_world_info", "get_player_position", "get_block", "get_region", "get_terrain", "set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks", "get_operation", "cancel_operation", "get_operation_errors", "get_query_result"};
	private final MinecraftServer server;
	private final String session;
	private final AtomicLong notices = new AtomicLong();
	private final AtomicReference<String> lastFailure = new AtomicReference<>();
	private long sequence;
	private PendingPreview pending;

	public VisualEvents(MinecraftServer server, String session) { this.server = server; this.session = session; }
	public static void registerPayload() { PayloadTypeRegistry.clientboundPlay().register(VisualPayload.TYPE, VisualPayload.CODEC); }

	/** Repeated polls coalesce per tool/Tick; distinct tool kinds are retained in a fixed 14-bit set. */
	public void notice(String tool) {
		for (int i = 0; i < TOOLS.length; i++) if (TOOLS[i].equals(tool)) {
			long bit = 1L << i; notices.getAndUpdate(value -> value | bit); return;
		}
	}

	public void failure(String code) { lastFailure.set(code); }

	public void tick() {
		long bits = notices.getAndSet(0);
		for (int i = 0; i < TOOLS.length; i++) if ((bits & (1L << i)) != 0) {
			send(message("", "tool", VisualPayload.Kind.NOTICE, 60, BlockPos.ZERO, BlockPos.ZERO, TOOLS[i], 0, 0, 0, List.of()));
		}
		String failure = lastFailure.getAndSet(null);
		if (failure != null) send(message("", "error", VisualPayload.Kind.NOTICE, 60, BlockPos.ZERO, BlockPos.ZERO, "failed " + failure, 0, 0, 0, List.of()));
		if (pending == null) return;
		PendingPreview p = pending;
		for (int page = 0; page < 4 && p.offset < p.targets.length; page++) {
			int end = Math.min(p.targets.length, p.offset + VisualPayload.MAX_ENTRIES);
			List<VisualPayload.Entry> entries = new ArrayList<>(end - p.offset);
			for (int i = p.offset; i < end; i++) entries.add(new VisualPayload.Entry(new BlockPos(p.xs[i], p.ys[i], p.zs[i]), p.targets[i]));
			send(message(p.dimension, p.id, VisualPayload.Kind.PREVIEW, 200, BlockPos.ZERO, BlockPos.ZERO, "preview", p.offset, p.targets.length, 0, entries));
			p.offset = end;
		}
		if (p.offset == p.targets.length) pending = null;
	}

	public void range(String dimension, String id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String style, int durationTicks) {
		if (!hasViewer(dimension)) return;
		send(message(dimension, id, VisualPayload.Kind.RANGE, durationTicks, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), style, 0, 0, 0, List.of()));
	}

	public void preview(String dimension, String operationId, int[] xs, int[] ys, int[] zs, BlockState[] targets) {
		if (targets.length < 1 || targets.length > VisualPayload.MAX_BLOCKS || xs.length != targets.length || ys.length != targets.length || zs.length != targets.length) throw new IllegalArgumentException("invalid preview plan");
		if (hasViewer(dimension)) pending = new PendingPreview(dimension, operationId, xs, ys, zs, targets);
	}

	public void progress(String dimension, String operationId, int processed, int total, String state) {
		if (!hasViewer(dimension)) return;
		send(message(dimension, operationId, VisualPayload.Kind.PROGRESS, 200, BlockPos.ZERO, BlockPos.ZERO, state, 0, total, processed, List.of()));
	}

	public void clear(String dimension, String operationId) {
		if (pending != null && pending.id.equals(operationId)) pending = null;
		if (hasViewer(dimension)) send(message(dimension, operationId, VisualPayload.Kind.CLEAR, 0, BlockPos.ZERO, BlockPos.ZERO, "", 0, 0, 0, List.of()));
	}

	public void shutdown() {
		pending = null; notices.set(0);
		send(message("", "", VisualPayload.Kind.RESET, 0, BlockPos.ZERO, BlockPos.ZERO, "", 0, 0, 0, List.of()));
	}

	private VisualPayload message(String dimension, String id, VisualPayload.Kind kind, int duration, BlockPos min, BlockPos max, String style, int offset, int total, int processed, List<VisualPayload.Entry> entries) {
		return new VisualPayload(session, dimension, id, ++sequence, kind, duration, min, max, style, offset, total, processed, entries);
	}
	private boolean hasViewer(String dimension) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) if (player.level().dimension().identifier().toString().equals(dimension) && ServerPlayNetworking.canSend(player, VisualPayload.TYPE)) return true;
		return false;
	}
	private void send(VisualPayload payload) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) if ((payload.dimension().isEmpty() || player.level().dimension().identifier().toString().equals(payload.dimension())) && ServerPlayNetworking.canSend(player, VisualPayload.TYPE)) ServerPlayNetworking.send(player, payload);
	}
	private static final class PendingPreview {
		final String dimension, id;
		final int[] xs, ys, zs;
		final BlockState[] targets;
		int offset;
		PendingPreview(String dimension, String id, int[] xs, int[] ys, int[] zs, BlockState[] targets) { this.dimension = dimension; this.id = id; this.xs = xs; this.ys = ys; this.zs = zs; this.targets = targets; }
	}
}
