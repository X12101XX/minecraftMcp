package dev.mcmcp.operation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.mcmcp.MinecraftMcpMod;
import dev.mcmcp.bridge.BridgeException;
import dev.mcmcp.bridge.CursorCodec;
import dev.mcmcp.bridge.Json;
import dev.mcmcp.game.GameAccess;
import dev.mcmcp.query.BlockStateKey;
import dev.mcmcp.query.RegionSnapshot;
import dev.mcmcp.query.TerrainFact;
import dev.mcmcp.query.TerrainSnapshot;
import dev.mcmcp.visual.VisualEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Owns the accepted-operation lifecycle. Submission and all world-touching work run on the server
 * thread; only immutable DTO reads are served directly to HTTP threads.
 */
public final class OperationManager {
	public static final int MAX_QUERY_VOXELS = 65_536;
	public static final int MAX_WRITE_VOXELS = 262_144;
	public static final int MAX_BATCH_ENTRIES = 8_192;
	public static final int MAX_CHUNKS = 64;
	public static final int MAX_QUEUED = 32;
	public static final int MAX_ACTIVE_READS = 4;
	public static final int WRITE_CANDIDATES_PER_TICK = 1;
	public static final long TICK_BUDGET_NANOS = 2_000_000L;
	private static final int MAX_IMMEDIATE_TASKS_PER_TICK = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	@FunctionalInterface
	public interface ServerCall {
		JsonObject call() throws BridgeException;
	}

	private final GameAccess access;
	private final String sessionId;
	private final CursorCodec cursors;
	private final boolean allowWrites;
	private final VisualEvents visuals;
	private final BlockingQueue<Runnable> immediate = new ArrayBlockingQueue<>(128);
	private final List<Operation> pendingReads = new ArrayList<>();
	private final List<Operation> pendingWrites = new ArrayList<>();
	private final List<Operation> activeReads = new ArrayList<>();
	private Operation activeWrite;
	private final Map<String, Operation> operations = new ConcurrentHashMap<>();
	private final Map<String, IdempotencyRecord> idempotency = new ConcurrentHashMap<>();
	private boolean shuttingDown;
	private long workTicks;
	private long workNanosTotal;
	private long workNanosMax;
	private long lastWorkNanos;
	private int lastWriteCandidates;
	private int lastReadVoxels;

	public OperationManager(GameAccess access, String sessionId, CursorCodec cursors, boolean allowWrites) {
		this.access = access;
		this.sessionId = sessionId;
		this.cursors = cursors;
		this.allowWrites = allowWrites;
		this.visuals = new VisualEvents(access.server(), sessionId);
	}

	public String sessionId() {
		return sessionId;
	}

	public void reportError(String code) {
		visuals.failure(code);
	}

	public CompletableFuture<JsonObject> callOnServer(ServerCall call) throws BridgeException {
		if (shuttingDown) {
			throw BridgeException.of("WORLD_SESSION_ENDED", "the world session is shutting down", null);
		}
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		boolean accepted = immediate.offer(() -> {
			try {
				future.complete(call.call());
			} catch (BridgeException e) {
				future.completeExceptionally(e);
			} catch (Throwable t) {
				MinecraftMcpMod.LOGGER.error("Unhandled bridge request failure", t);
				future.completeExceptionally(new BridgeException("INTERNAL_ERROR", 500,
						"internal bridge error", false, null));
			}
		});
		if (!accepted) {
			throw BridgeException.of("QUEUE_FULL", "server-side request queue is full", null);
		}
		return future;
	}

	public JsonObject worldInfo(JsonObject args) throws BridgeException {
		Json.only(args, Set.of(), "arguments");
		visuals.notice("get_world_info");
		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		root.addProperty("ready", true);
		root.addProperty("world_session_id", sessionId);
		root.add("dimensions", access.dimensionsJson());
		root.add("players", access.playersJson());
		root.add("limits", limitsJson());
		JsonArray tools = new JsonArray();
		for (String tool : SUPPORTED_TOOLS) {
			tools.add(tool);
		}
		root.add("supported_tools", tools);
		return root;
	}

	public JsonObject playerPosition(JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "player"), "arguments");
		requireSession(args);
		String selector = Json.string(args, "player", false, null);
		ServerPlayer player = access.findPlayer(selector);
		visuals.notice("get_player_position");
		ServerLevel level = player.level();
		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		root.addProperty("world_session_id", sessionId);
		root.addProperty("player_uuid", player.getUUID().toString());
		root.addProperty("name", player.getGameProfile().name());
		root.addProperty("dimension", GameAccess.dimensionId(level));
		JsonObject position = new JsonObject();
		position.addProperty("x", player.getX());
		position.addProperty("y", player.getY());
		position.addProperty("z", player.getZ());
		root.add("position", position);
		root.add("block_pos", Json.pos(player.blockPosition().getX(), player.blockPosition().getY(),
				player.blockPosition().getZ()));
		root.addProperty("yaw", player.getYRot());
		root.addProperty("pitch", player.getXRot());
		root.addProperty("observed_tick", access.tick());
		return root;
	}

	public JsonObject getBlock(JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "pos"), "arguments");
		requireSession(args);
		String dimension = Json.string(args, "dimension", true, null);
		int[] pos = Json.position(args, "pos", true);
		ServerLevel level = access.level(dimension);
		access.requireInBounds(level, pos[0], pos[1], pos[2]);
		access.requireChunk(level, pos[0], pos[1], pos[2]);
		BlockPos blockPos = new BlockPos(pos[0], pos[1], pos[2]);
		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		root.addProperty("world_session_id", sessionId);
		root.addProperty("dimension", dimension);
		root.add("pos", Json.pos(pos[0], pos[1], pos[2]));
		JsonObject facts = access.blockFacts(level, blockPos);
		visuals.notice("get_block");
		visuals.range(dimension, "block", pos[0], pos[1], pos[2], pos[0], pos[1], pos[2], "read", 30);
		facts.entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue()));
		root.addProperty("observed_tick", access.tick());
		return root;
	}

	public JsonObject acceptAsync(String tool, JsonObject args) throws BridgeException {
		if (!ASYNC_TOOLS.contains(tool)) {
			throw BridgeException.of("UNKNOWN_TOOL", "unknown tool", null);
		}
		if (tool.startsWith("get_") && tool.equals("get_world_info")) {
			throw BridgeException.of("UNKNOWN_TOOL", "use the info route", null);
		}
		requireSession(args);
		boolean write = WRITE_TOOLS.contains(tool);
		if (write && !allowWrites) {
			throw BridgeException.of("FORBIDDEN", "bridge is configured read-only", null);
		}
		String requestId = requireRequestId(args);
		JsonObject normalized = normalize(tool, args);
		String canonical = tool + "|" + Json.canonical(normalized);
		IdempotencyRecord existing = idempotency.get(requestId);
		if (existing != null) {
			Operation original = operations.get(existing.operationId);
			if (original == null) {
				idempotency.remove(requestId);
			} else if (!existing.canonical.equals(canonical)) {
				JsonObject details = new JsonObject();
				details.addProperty("request_id", requestId);
				throw BridgeException.of("IDEMPOTENCY_CONFLICT",
						"request_id was already used with different arguments", details);
			} else {
				return original.envelope();
			}
		}
		if (pendingReads.size() + pendingWrites.size() >= MAX_QUEUED) {
			JsonObject details = new JsonObject();
			details.addProperty("queued", pendingReads.size() + pendingWrites.size());
			details.addProperty("limit", MAX_QUEUED);
			throw BridgeException.of("QUEUE_FULL", "operation queue is full", details);
		}

		Operation op = switch (tool) {
			case "get_region" -> createRegion(args, requestId);
			case "get_terrain" -> createTerrain(args, requestId);
			case "set_block" -> createSet(args, requestId, false);
			case "remove_block" -> createSet(args, requestId, true);
			case "fill_region" -> createFill(args, requestId, false);
			case "replace_blocks" -> createFill(args, requestId, true);
			case "batch_set_blocks" -> createBatch(args, requestId);
			default -> throw BridgeException.of("UNKNOWN_TOOL", "unknown tool", null);
		};
		visuals.notice(tool);
		operations.put(op.id, op);
		idempotency.put(requestId, new IdempotencyRecord(canonical, op.id));
		if (op.isRead()) {
			visuals.range(op.dimension, op.id, op.minX, op.minY, op.minZ, op.maxX, op.maxY, op.maxZ, "read", 30);
			pendingReads.add(op);
		} else {
			pendingWrites.add(op);
		}
		return op.envelope();
	}

	public JsonObject getOperation(JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "operation_id"), "arguments");
		requireSession(args);
		Operation op = requireOperation(args);
		visuals.notice("get_operation");
		return op.envelope();
	}

	public JsonObject cancelOperation(JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "operation_id"), "arguments");
		requireSession(args);
		Operation op = requireOperation(args);
		visuals.notice("cancel_operation");
		boolean requested;
		if (op.isTerminal()) {
			requested = op.snapshotJson().get("state").getAsString().equals("cancelled");
		} else {
			op.cancelRequested = true;
			pendingReads.remove(op);
			pendingWrites.remove(op);
			requested = true;
			if (op.state() == Operation.State.QUEUED) {
				op.finish(access.tick(), Operation.State.CANCELLED, null, null, null);
				visuals.clear(op.dimension, op.id);
				visuals.progress(op.dimension, op.id, op.processed, op.total, "cancelled");
			}
			// A running operation observes the flag at the next work-unit boundary.
		}
		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		root.addProperty("cancel_requested", requested);
		root.add("operation", op.snapshotJson());
		return root;
	}

	public JsonObject getErrors(JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "operation_id", "cursor"), "arguments");
		requireSession(args);
		Operation op = requireOperation(args);
		visuals.notice("get_operation_errors");
		String cursor = Json.string(args, "cursor", false, null);
		if (!op.isTerminal()) {
			throw BridgeException.of("OPERATION_NOT_READY", "operation has not reached a terminal state", null);
		}
		int offset = 0;
		if (cursor != null) {
			offset = cursors.decode(cursor, op.id, "errors");
		}
		List<OperationError> errors = op.errorSnapshot();
		if (errors.isEmpty()) {
			if (offset != 0) {
				throw new BridgeException("INVALID_CURSOR", 400, "cursor offset is outside the result", false, null);
			}
			JsonObject root = new JsonObject();
			root.addProperty("ok", true);
			root.addProperty("world_session_id", sessionId);
			root.addProperty("operation_id", op.id);
			root.addProperty("error_count", 0);
			root.add("errors", new JsonArray());
			root.add("next_cursor", JsonNull.INSTANCE);
			root.addProperty("expires_at", op.terminalExpiresAt());
			return root;
		}
		if (offset < 0 || offset >= errors.size()) {
			throw new BridgeException("INVALID_CURSOR", 400, "cursor offset is outside the result", false, null);
		}
		int total = errors.size();
		int low = 1;
		int high = total - offset;
		int best = 0;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			JsonObject candidate = errorPage(op, errors, offset, mid, "x".repeat(200));
			if (Json.GSON.toJson(candidate).getBytes(StandardCharsets.UTF_8).length <= RegionSnapshot.MAX_PAGE_BYTES - 4096) {
				best = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		if (best == 0) {
			throw BridgeException.of("RESULT_TOO_LARGE", "one error record cannot fit into a page", null);
		}
		while (best > 0) {
			int next = offset + best;
			JsonObject page = errorPage(op, errors, offset, best,
					next >= total ? "x".repeat(200) : cursors.encode(op.id, "errors", next));
			page.add("next_cursor", next >= total ? JsonNull.INSTANCE
					: new JsonPrimitive(cursors.encode(op.id, "errors", next)));
			if (Json.GSON.toJson(page).getBytes(StandardCharsets.UTF_8).length <= RegionSnapshot.MAX_PAGE_BYTES) {
				return page;
			}
			best--;
		}
		throw BridgeException.of("RESULT_TOO_LARGE", "one error record cannot fit into a page", null);
	}

	private JsonObject errorPage(Operation op, List<OperationError> errors, int offset, int count,
			String cursorForOffset) {
		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		root.addProperty("world_session_id", sessionId);
		root.addProperty("operation_id", op.id);
		root.addProperty("error_count", errors.size());
		JsonArray array = new JsonArray(count);
		for (int i = 0; i < count; i++) {
			array.add(errors.get(offset + i).toJson());
		}
		root.add("errors", array);
		root.addProperty("next_cursor", cursorForOffset);
		root.addProperty("expires_at", op.terminalExpiresAt());
		return root;
	}

	public JsonObject getQueryResult(JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "operation_id", "cursor"), "arguments");
		requireSession(args);
		Operation op = requireOperation(args);
		visuals.notice("get_query_result");
		String cursor = Json.string(args, "cursor", false, null);
		if (!op.isTerminal()) {
			throw BridgeException.of("OPERATION_NOT_READY", "operation has not reached a terminal state", null);
		}
		if (op.isWrite()) {
			throw BridgeException.of("OPERATION_KIND_MISMATCH", "operation is not a query", null);
		}
		if (op.state() != Operation.State.SUCCEEDED) {
			throw BridgeException.of("RESULT_UNAVAILABLE", "failed or cancelled query has no result pages", null);
		}
		int offset = cursor == null ? 0 : cursors.decode(cursor, op.id, "result");
		String expiresAt = op.terminalExpiresAt();
		if (op.regionResult() != null) {
			return op.regionResult().page(offset, op.id, sessionId, op.dimension, expiresAt,
					next -> cursors.encode(op.id, "result", next)).json();
		}
		TerrainSnapshot terrain = op.terrainResult();
		if (terrain != null) {
			return terrain.page(offset, op.id, sessionId, op.dimension, expiresAt,
					next -> cursors.encode(op.id, "result", next)).json();
		}
		throw BridgeException.of("RESULT_UNAVAILABLE", "query result is unavailable", null);
	}

	public void tick() {
		if (shuttingDown) {
			return;
		}
		long tickStart = System.nanoTime();
		int writes = 0;
		int tickReadsBefore = 0;
		for (Operation read : activeReads) {
			tickReadsBefore += read.processed;
		}
		int tick = access.tick();
		visuals.tick();
		for (int i = 0; i < MAX_IMMEDIATE_TASKS_PER_TICK; i++) {
			Runnable task = immediate.poll();
			if (task == null) {
				break;
			}
			try {
				task.run();
			} catch (Throwable t) {
				MinecraftMcpMod.LOGGER.error("Immediate bridge task failed", t);
			}
		}
		long start = System.nanoTime();
		long deadline = start + TICK_BUDGET_NANOS;
		long readDeadline = start + TICK_BUDGET_NANOS / 2;
		promote(tick);
		Iterator<Operation> iterator = activeReads.iterator();
		while (iterator.hasNext()) {
			Operation op = iterator.next();
			if (op.cancelRequested) {
				op.finish(tick, Operation.State.CANCELLED, null, null, null);
				visuals.clear(op.dimension, op.id);
				visuals.progress(op.dimension, op.id, op.processed, op.total, "cancelled");
				iterator.remove();
				continue;
			}
			if (op.regionResult() == null && op.terrainResult() == null && op.state() == Operation.State.RUNNING) {
				boolean finished = op.tool.equals("get_region") ? processRegion(op, tick, readDeadline)
						: processTerrain(op, tick, readDeadline);
				if (finished) {
					iterator.remove();
				}
			}
		}
		if (activeWrite != null) {
			Operation writing = activeWrite;
			int processedBefore = writing.processed;
			if (writing.cancelRequested) {
				writing.finish(tick, Operation.State.CANCELLED, null, null, null);
			} else {
				processWrite(writing, tick, deadline);
			}
			writes = writing.processed - processedBefore;
			visuals.progress(writing.dimension, writing.id, writing.processed, writing.total, writing.state().json());
			if (writing.isTerminal()) {
				visuals.clear(writing.dimension, writing.id);
				activeWrite = null;
			}
		}
		cleanupExpired();
		long elapsed = System.nanoTime() - tickStart;
		int reads = 0;
		for (Operation read : activeReads) {
			reads += read.processed;
		}
		reads -= tickReadsBefore;
		if (reads < 0) {
			reads = 0;
		}
		lastWorkNanos = elapsed;
		lastWriteCandidates = writes;
		lastReadVoxels = reads;
		if (writes > 0 || reads > 0) {
			workTicks++;
			workNanosTotal += elapsed;
			workNanosMax = Math.max(workNanosMax, elapsed);
			if (workTicks % 200 == 0) {
				MinecraftMcpMod.LOGGER.info(
						"MCP scheduler: ticks={} avg_work_ms={} max_work_ms={} last_work_ms={} last_writes={} last_reads={}",
						workTicks, String.format(java.util.Locale.ROOT, "%.2f", workNanosTotal / 1_000_000.0 / workTicks),
						String.format(java.util.Locale.ROOT, "%.2f", workNanosMax / 1_000_000.0),
						String.format(java.util.Locale.ROOT, "%.2f", lastWorkNanos / 1_000_000.0),
						writes, reads);
			}
		}
	}

	private void promote(int tick) {
		while (activeReads.size() < MAX_ACTIVE_READS && !pendingReads.isEmpty()) {
			Operation op = pendingReads.removeFirst();
			if (op.cancelRequested) {
				op.finish(tick, Operation.State.CANCELLED, null, null, null);
				continue;
			}
			op.markStarted(tick);
			activeReads.add(op);
		}
		if (activeWrite == null && !pendingWrites.isEmpty()) {
			Operation op = pendingWrites.removeFirst();
			if (op.cancelRequested) {
				op.finish(tick, Operation.State.CANCELLED, null, null, null);
			} else {
				op.markStarted(tick);
				activeWrite = op;
			}
		}
	}

	private boolean processRegion(Operation op, int tick, long deadline) {
		ServerLevel level;
		try {
			level = access.level(op.dimension);
		} catch (BridgeException e) {
			failTask(op, tick, e);
			return true;
		}
		int sizeX = op.maxX - op.minX + 1;
		int sizeZ = op.maxZ - op.minZ + 1;
		int sizeY = op.maxY - op.minY + 1;
		if (op.indices == null) {
			op.palette = new ArrayList<>();
			op.paletteIndex = new LinkedHashMap<>();
			op.indices = new int[op.total];
		}
		int lastChunkX = Integer.MIN_VALUE;
		int lastChunkZ = Integer.MIN_VALUE;
		boolean lastLoaded = false;
		int stride = sizeX * sizeZ;
		int work = 0;
		while (op.cursor < op.total) {
			if (work > 0 && (work >= 1024 || System.nanoTime() >= deadline)) {
				break;
			}
			int index = op.cursor;
			int x = op.minX + index % sizeX;
			int z = op.minZ + (index / sizeX) % sizeZ;
			int y = op.minY + index / stride;
			int chunkX = x >> 4;
			int chunkZ = z >> 4;
			if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
				lastChunkX = chunkX;
				lastChunkZ = chunkZ;
				lastLoaded = access.chunkLoaded(level, x, z);
			}
			if (!lastLoaded) {
				JsonObject details = new JsonObject();
				details.addProperty("dimension", op.dimension);
				details.addProperty("chunk_x", chunkX);
				details.addProperty("chunk_z", chunkZ);
				op.addError(new OperationError(op.errors.size(), index, x, y, z,
						"CHUNK_NOT_LOADED", "chunk unloaded during the scan; no partial region was published",
						false, false, details));
				op.failed++;
				op.processed++;
				failTask(op, tick, "CHUNK_NOT_LOADED", "chunk unloaded during the scan", details);
				return true;
			}
			BlockState state = level.getBlockState(new BlockPos(x, y, z));
			BlockStateKey key = GameAccess.stateKey(state);
			Integer paletteIndex = op.paletteIndex.get(key);
			if (paletteIndex == null) {
				paletteIndex = op.palette.size();
				op.palette.add(key);
				op.paletteIndex.put(key, paletteIndex);
			}
			op.indices[index] = paletteIndex;
			op.cursor++;
			op.processed++;
			op.succeeded++;
			work++;
		}
		if (op.cursor >= op.total) {
			op.setRegionResult(new RegionSnapshot(op.minX, op.minY, op.minZ, sizeX, sizeY, sizeZ,
					op.palette, op.indices, op.encoding, op.startedTick, tick));
			op.finish(tick, Operation.State.SUCCEEDED, null, null, null);
			return true;
		}
		return false;
	}

	private boolean processTerrain(Operation op, int tick, long deadline) {
		ServerLevel level;
		try {
			level = access.level(op.dimension);
		} catch (BridgeException e) {
			failTask(op, tick, e);
			return true;
		}
		int sizeX = op.maxX - op.minX + 1;
		int sizeZ = op.maxZ - op.minZ + 1;
		int sizeY = op.maxY - op.minY + 1;
		int stride = sizeX * sizeZ;
		if (op.terrainFacts == null) {
			op.terrainFacts = new TerrainFact[op.total];
		}
		int lastChunkX = Integer.MIN_VALUE;
		int lastChunkZ = Integer.MIN_VALUE;
		boolean lastLoaded = false;
		int work = 0;
		while (op.cursor < op.total) {
			if (work > 0 && (work >= 512 || System.nanoTime() >= deadline)) {
				break;
			}
			int index = op.cursor;
			int x = op.minX + index % sizeX;
			int z = op.minZ + (index / sizeX) % sizeZ;
			int y = op.minY + index / stride;
			int chunkX = x >> 4;
			int chunkZ = z >> 4;
			if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
				lastChunkX = chunkX;
				lastChunkZ = chunkZ;
				lastLoaded = access.chunkLoaded(level, x, z);
			}
			if (!lastLoaded) {
				JsonObject details = new JsonObject();
				details.addProperty("dimension", op.dimension);
				details.addProperty("chunk_x", chunkX);
				details.addProperty("chunk_z", chunkZ);
				op.addError(new OperationError(op.errors.size(), index, x, y, z,
						"CHUNK_NOT_LOADED", "chunk unloaded during the scan; no partial terrain was published",
						false, false, details));
				op.failed++;
				op.processed++;
				failTask(op, tick, "CHUNK_NOT_LOADED", "chunk unloaded during the scan", details);
				return true;
			}
			int column = (z - op.minZ) * sizeX + (x - op.minX);
			op.terrainFacts[column * sizeY + (y - op.minY)] = access.terrainFact(level,
					new BlockPos(x, y, z));
			op.cursor++;
			op.processed++;
			op.succeeded++;
			work++;
		}
		if (op.cursor >= op.total) {
			op.setTerrainResult(new TerrainSnapshot(op.minX, op.minY, op.minZ, op.maxX, op.maxY, op.maxZ,
					op.clearanceHeight, op.terrainFacts, level.dimensionType().minY(),
					level.dimensionType().minY() + level.dimensionType().height(),
					op.startedTick, tick));
			op.finish(tick, Operation.State.SUCCEEDED, null, null, null);
			return true;
		}
		return false;
	}

	private boolean prepareWrite(Operation op, int tick, long deadline) {
		if (op.prepared) {
			return true;
		}
		if (!op.tool.equals("batch_set_blocks")) {
			op.prepared = true;
			return true;
		}
		int end = Math.min(op.total, op.prepareIndex + 512);
		boolean invalid = false;
		int initialIndex = op.prepareIndex;
		while (op.prepareIndex < end && (op.prepareIndex == initialIndex || System.nanoTime() < deadline)) {
			int i = op.prepareIndex;
			JsonObject item = op.batchBlocks.get(i).getAsJsonObject();
			try {
				JsonObject stateJson = Json.object(item.get("block_state"), "block_state");
				op.batchTargets[i] = access.parseBlockState(stateJson, false);
				if (item.has("expected_state") && !item.get("expected_state").isJsonNull()) {
					op.batchExpected[i] = access.parseBlockState(
							Json.object(item.get("expected_state"), "expected_state"), true);
				}
			} catch (BridgeException e) {
				invalid = true;
				if (!op.addError(new OperationError(op.errors.size(), i, op.batchX[i], op.batchY[i], op.batchZ[i],
						e.code, e.getMessage(), false, false, e.details))) {
					op.failed++;
					op.processed++;
					failTask(op, tick, "RESOURCE_EXHAUSTED", "operation error log capacity was exhausted", null);
					return false;
				}
				op.failed++;
				op.processed++;
			}
			op.prepareIndex++;
		}
		if (op.prepareIndex >= op.total) {
			if (invalid || op.failed > 0) {
				op.processed = op.failed;
				failTask(op, tick, "INVALID_BLOCK_STATE",
						"batch was rejected before any write because at least one state is invalid", null);
				return false;
			}
			op.prepared = true;
		}
		return true;
	}

	private boolean processWrite(Operation op, int tick, long deadline) {
		if (!prepareWrite(op, tick, deadline)) {
			return true;
		}
		if (!op.prepared) {
			return false;
		}
		if (op.batchTargets != null) {
			if (op.previewStartedTick < 0) {
				visuals.preview(op.dimension, op.id, op.batchX, op.batchY, op.batchZ, op.batchTargets);
				op.previewStartedTick = tick;
				return false;
			}
			if (tick - op.previewStartedTick < 20) {
				return false;
			}
		}
		ServerLevel level;
		try {
			level = access.level(op.dimension);
		} catch (BridgeException e) {
			failTask(op, tick, e);
			return true;
		}
		int sizeX = op.maxX - op.minX + 1;
		int sizeZ = op.maxZ - op.minZ + 1;
		int stride = sizeX * sizeZ;
		int work = 0;
		while (op.cursor < op.total) {
			if (op.cancelRequested) {
				op.finish(tick, Operation.State.CANCELLED, null, null, null);
				return true;
			}
			if (work > 0 && (work >= WRITE_CANDIDATES_PER_TICK || System.nanoTime() >= deadline)) {
				break;
			}
			int index = op.cursor;
			int x;
			int y;
			int z;
			if (op.tool.equals("batch_set_blocks")) {
				x = op.batchX[index];
				y = op.batchY[index];
				z = op.batchZ[index];
			} else if (op.tool.equals("set_block") || op.tool.equals("remove_block")) {
				x = op.minX;
				y = op.minY;
				z = op.minZ;
			} else {
				x = op.minX + index % sizeX;
				z = op.minZ + (index / sizeX) % sizeZ;
				y = op.minY + index / stride;
			}
			BlockState target = op.tool.equals("batch_set_blocks") ? op.batchTargets[index] : op.targetState;
			BlockState expected = op.tool.equals("batch_set_blocks") ? op.batchExpected[index] : op.expectedState;
			if (!access.chunkLoaded(level, x, z)) {
				JsonObject details = new JsonObject();
				details.addProperty("dimension", op.dimension);
				details.addProperty("chunk_x", x >> 4);
				details.addProperty("chunk_z", z >> 4);
				coordinateFailure(op, index, x, y, z, "CHUNK_NOT_LOADED",
						"chunk is not loaded; this bridge never loads chunks", details, false);
				finishWrite(op, tick, op.errorsOverflow ? "RESOURCE_EXHAUSTED" : "CHUNK_NOT_LOADED");
				return true;
			}
			BlockPos pos = new BlockPos(x, y, z);
			BlockState current = level.getBlockState(pos);
			if (op.tool.equals("replace_blocks") && !GameAccess.matchesFilter(current, op.filter)) {
				op.skipped++;
				op.processed++;
				op.cursor++;
				visuals.range(op.dimension, op.id + "-step", x, y, z, x, y, z, "unchanged", 12);
				work++;
				continue;
			}
			if (expected != null && !current.equals(expected)) {
				JsonObject details = new JsonObject();
				details.addProperty("expected", Json.GSON.toJson(GameAccess.stateJson(expected)));
				details.addProperty("actual", Json.GSON.toJson(GameAccess.stateJson(current)));
				coordinateFailure(op, index, x, y, z, "PRECONDITION_FAILED",
						"current block state does not match expected_state", details, false);
				op.cursor++;
				work++;
				if (!op.onErrorContinue) {
					finishWrite(op, tick, op.errorsOverflow ? "RESOURCE_EXHAUSTED" : "PRECONDITION_FAILED");
					return true;
				}
				if (op.errorsOverflow) {
					finishWrite(op, tick, "RESOURCE_EXHAUSTED");
					return true;
				}
				continue;
			}
			if (current.equals(target)) {
				op.unchanged++;
				op.succeeded++;
				op.processed++;
				op.cursor++;
				visuals.range(op.dimension, op.id + "-step", x, y, z, x, y, z, "unchanged", 12);
				work++;
				continue;
			}
			boolean changed;
			try {
				changed = level.setBlock(pos, target, Block.UPDATE_ALL);
			} catch (RuntimeException e) {
				JsonObject details = new JsonObject();
				details.addProperty("exception", e.getClass().getSimpleName());
				coordinateFailure(op, index, x, y, z, "WRITE_OUTCOME_UNKNOWN",
						"engine threw while applying the update; side effects are unknown", details, true);
				finishWrite(op, tick, op.errorsOverflow ? "RESOURCE_EXHAUSTED" : "WRITE_OUTCOME_UNKNOWN");
				return true;
			}
			op.cursor++;
			work++;
			if (!changed) {
				coordinateFailure(op, index, x, y, z, "WRITE_REJECTED",
						"engine rejected the block update", null, false);
				if (!op.onErrorContinue) {
					finishWrite(op, tick, op.errorsOverflow ? "RESOURCE_EXHAUSTED" : "WRITE_REJECTED");
					return true;
				}
				if (op.errorsOverflow) {
					finishWrite(op, tick, "RESOURCE_EXHAUSTED");
					return true;
				}
				continue;
			}
			op.changed++;
			op.succeeded++;
			op.processed++;
			visuals.range(op.dimension, op.id + "-step", x, y, z, x, y, z, target.isAir() ? "remove" : "place", 12);
			if (op.errorCapacityExhausted()) {
				finishWrite(op, tick, "RESOURCE_EXHAUSTED");
				return true;
			}
		}
		if (op.cursor >= op.total) {
			finishWrite(op, tick, null);
			return true;
		}
		return false;
	}

	private void coordinateFailure(Operation op, int candidateIndex, int x, int y, int z,
			String code, String message, JsonObject details, boolean effectUnknown) {
		op.addError(new OperationError(op.errors.size(), candidateIndex, x, y, z, code, message,
				false, effectUnknown, details));
		op.failed++;
		op.processed++;
		visuals.range(op.dimension, op.id + "-step", x, y, z, x, y, z, "error", 30);
	}

	private void finishWrite(Operation op, int tick, String failureCode) {
		if (op.failed > 0) {
			String code = op.changed > 0 && failureCode == null ? "PARTIAL_FAILURE" : failureCode;
			JsonObject details = new JsonObject();
			details.addProperty("changed", op.changed);
			details.addProperty("failed", op.failed);
			op.finish(tick, Operation.State.FAILED, code == null ? "PARTIAL_FAILURE" : code,
					"some coordinates failed; completed changes are not rolled back", details);
		} else {
			op.finish(tick, Operation.State.SUCCEEDED, null, null, null);
		}
	}

	private void failTask(Operation op, int tick, BridgeException e) {
		failTask(op, tick, e.code, e.getMessage(), e.details);
	}

	private void failTask(Operation op, int tick, String code, String message, JsonObject details) {
		if (!code.equals("INVALID_BLOCK_STATE") && op.errors.isEmpty()) {
			op.addError(new OperationError(0, null, null, null, null, code, message, false, false,
					details == null ? new JsonObject() : details));
		}
		op.finish(tick, Operation.State.FAILED, code, message, details);
	}

	private void cleanupExpired() {
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<String, Operation>> iterator = operations.entrySet().iterator();
		while (iterator.hasNext()) {
			Operation op = iterator.next().getValue();
			if (op.isTerminal() && op.terminalExpiresAtMillis() < now) {
				iterator.remove();
				IdempotencyRecord record = idempotency.get(op.requestId);
				if (record != null && record.operationId.equals(op.id)) {
					idempotency.remove(op.requestId);
				}
			}
		}
	}

	public void shutdown() {
		shuttingDown = true;
		visuals.shutdown();
		int tick = access.tick();
		for (Operation op : pendingReads) {
			op.finish(tick, Operation.State.FAILED, "WORLD_SESSION_ENDED", "world session ended", null);
		}
		for (Operation op : pendingWrites) {
			op.finish(tick, Operation.State.FAILED, "WORLD_SESSION_ENDED", "world session ended", null);
		}
		for (Operation op : activeReads) {
			op.finish(tick, Operation.State.FAILED, "WORLD_SESSION_ENDED", "world session ended", null);
		}
		if (activeWrite != null) {
			activeWrite.finish(tick, Operation.State.FAILED, "WORLD_SESSION_ENDED", "world session ended", null);
		}
		pendingReads.clear();
		pendingWrites.clear();
		activeReads.clear();
		activeWrite = null;
		immediate.clear();
		operations.clear();
		idempotency.clear();
	}

	private void requireSession(JsonObject args) throws BridgeException {
		String provided = Json.string(args, "world_session_id", true, null);
		if (!sessionId.equals(provided)) {
			JsonObject details = new JsonObject();
			details.addProperty("provided", provided);
			throw BridgeException.of("WORLD_SESSION_MISMATCH",
					"world_session_id does not match the active world; obtain a new one from get_world_info",
					details);
		}
	}

	private Operation requireOperation(JsonObject args) throws BridgeException {
		String operationId = Json.string(args, "operation_id", true, null);
		Operation op = operations.get(operationId);
		if (op == null) {
			JsonObject details = new JsonObject();
			details.addProperty("operation_id", operationId);
			throw BridgeException.of("OPERATION_NOT_FOUND", "operation does not exist or has expired", details);
		}
		return op;
	}

	private String requireRequestId(JsonObject args) throws BridgeException {
		String value = Json.string(args, "request_id", true, null);
		try {
			UUID uuid = UUID.fromString(value);
			if (!uuid.toString().equals(value)) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException e) {
			throw BridgeException.invalid("request_id must be a lowercase canonical UUID");
		}
		return value;
	}

	private JsonObject normalize(String tool, JsonObject args) {
		JsonObject copy = args.deepCopy();
		copy.remove("request_id");
		switch (tool) {
			case "get_region" -> {
				if (!copy.has("encoding")) {
					copy.addProperty("encoding", "auto");
				}
			}
			case "get_terrain" -> {
				if (!copy.has("clearance_height")) {
					copy.addProperty("clearance_height", 2);
				}
			}
			case "set_block", "fill_region", "replace_blocks" -> {
				normalizeStateObject(copy, "block_state");
				if (!copy.has("on_error")) {
					copy.addProperty("on_error", "stop");
				}
			}
			case "remove_block" -> {
				if (!copy.has("on_error")) {
					copy.addProperty("on_error", "stop");
				}
			}
			case "batch_set_blocks" -> {
				if (copy.has("blocks") && copy.get("blocks").isJsonArray()) {
					for (JsonElement element : copy.getAsJsonArray("blocks")) {
						if (element.isJsonObject()) {
							normalizeStateObject(element.getAsJsonObject(), "block_state");
						}
					}
				}
				if (!copy.has("on_error")) {
					copy.addProperty("on_error", "stop");
				}
			}
			default -> {
			}
		}
		return copy;
	}

	private static void normalizeStateObject(JsonObject owner, String field) {
		if (owner.has(field) && owner.get(field).isJsonObject()) {
			JsonObject state = owner.getAsJsonObject(field);
			if (!state.has("properties")) {
				state.add("properties", new JsonObject());
			}
		}
	}

	private Operation createRegion(JsonObject args, String requestId) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "min", "max", "encoding", "request_id"),
				"arguments");
		String dimension = Json.string(args, "dimension", true, null);
		int[] min = Json.position(args, "min", true);
		int[] max = Json.position(args, "max", true);
		String encoding = Json.string(args, "encoding", false, "auto");
		if (!Set.of("auto", "palette_indices", "palette_rle").contains(encoding)) {
			throw BridgeException.invalid("encoding must be auto, palette_indices, or palette_rle");
		}
		ServerLevel level = access.level(dimension);
		validateRange(level, min, max, MAX_QUERY_VOXELS);
		checkChunks(level, min, max);
		int volume = (int) volume(min, max);
		Operation op = new Operation(newId(), requestId, "get_region", "read", dimension, sessionId,
				volume, false, min[0], min[1], min[2], max[0], max[1], max[2], 0, encoding);
		return op;
	}

	private Operation createTerrain(JsonObject args, String requestId) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "min", "max", "clearance_height", "request_id"),
				"arguments");
		String dimension = Json.string(args, "dimension", true, null);
		int[] min = Json.position(args, "min", true);
		int[] max = Json.position(args, "max", true);
		int clearance = Json.integer(args, "clearance_height", false, 2);
		if (clearance < 1 || clearance > 64) {
			throw BridgeException.invalid("clearance_height must be between 1 and 64");
		}
		ServerLevel level = access.level(dimension);
		validateRange(level, min, max, MAX_QUERY_VOXELS);
		checkChunks(level, min, max);
		int volume = (int) volume(min, max);
		return new Operation(newId(), requestId, "get_terrain", "read", dimension, sessionId,
				volume, false, min[0], min[1], min[2], max[0], max[1], max[2], clearance, "auto");
	}

	private Operation createSet(JsonObject args, String requestId, boolean remove) throws BridgeException {
		Set<String> fields = remove
				? Set.of("world_session_id", "dimension", "pos", "expected_state", "on_error", "request_id")
				: Set.of("world_session_id", "dimension", "pos", "block_state", "expected_state", "on_error",
						"request_id");
		Json.only(args, fields, "arguments");
		String dimension = Json.string(args, "dimension", true, null);
		int[] pos = Json.position(args, "pos", true);
		boolean onErrorContinue = parseOnError(args);
		ServerLevel level = access.level(dimension);
		access.requireInBounds(level, pos[0], pos[1], pos[2]);
		access.requireChunk(level, pos[0], pos[1], pos[2]);
		BlockState target;
		if (remove) {
			target = Blocks.AIR.defaultBlockState();
		} else {
			target = access.parseBlockState(Json.object(args.get("block_state"), "block_state"), false);
		}
		BlockState expected = null;
		if (args.has("expected_state") && !args.get("expected_state").isJsonNull()) {
			expected = access.parseBlockState(Json.object(args.get("expected_state"), "expected_state"), true);
		}
		Operation op = new Operation(newId(), requestId, remove ? "remove_block" : "set_block", "write",
				dimension, sessionId, 1, onErrorContinue,
				pos[0], pos[1], pos[2], pos[0], pos[1], pos[2], 0, "auto");
		op.targetState = target;
		op.expectedState = expected;
		return op;
	}

	private Operation createFill(JsonObject args, String requestId, boolean replace) throws BridgeException {
		Set<String> fields = replace
				? Set.of("world_session_id", "dimension", "min", "max", "filter", "block_state",
						"expected_state", "on_error", "request_id")
				: Set.of("world_session_id", "dimension", "min", "max", "block_state", "expected_state",
						"on_error", "request_id");
		Json.only(args, fields, "arguments");
		String dimension = Json.string(args, "dimension", true, null);
		int[] min = Json.position(args, "min", true);
		int[] max = Json.position(args, "max", true);
		boolean onErrorContinue = parseOnError(args);
		ServerLevel level = access.level(dimension);
		validateRange(level, min, max, MAX_WRITE_VOXELS);
		checkChunks(level, min, max);
		BlockState target = access.parseBlockState(Json.object(args.get("block_state"), "block_state"), false);
		BlockState expected = null;
		if (args.has("expected_state") && !args.get("expected_state").isJsonNull()) {
			expected = access.parseBlockState(Json.object(args.get("expected_state"), "expected_state"), true);
		}
		Operation op = new Operation(newId(), requestId, replace ? "replace_blocks" : "fill_region", "write",
				dimension, sessionId, (int) volume(min, max), onErrorContinue,
				min[0], min[1], min[2], max[0], max[1], max[2], 0, "auto");
		op.targetState = target;
		op.expectedState = expected;
		if (replace) {
			op.filter = access.parseFilter(Json.object(args.get("filter"), "filter"));
		}
		return op;
	}

	private Operation createBatch(JsonObject args, String requestId) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "blocks", "on_error", "request_id"),
				"arguments");
		String dimension = Json.string(args, "dimension", true, null);
		boolean onErrorContinue = parseOnError(args);
		List<JsonObject> blocks = Json.objectArray(args, "blocks");
		if (blocks.isEmpty() || blocks.size() > MAX_BATCH_ENTRIES) {
			throw BridgeException.invalid("blocks must contain between 1 and " + MAX_BATCH_ENTRIES + " entries");
		}
		ServerLevel level = access.level(dimension);
		int n = blocks.size();
		int[] xs = new int[n];
		int[] ys = new int[n];
		int[] zs = new int[n];
		Set<String> positions = new HashSet<>();
		Set<Long> chunks = new HashSet<>();
		JsonArray normalizedBlocks = new JsonArray(n);
		for (int i = 0; i < n; i++) {
			JsonObject item = blocks.get(i);
			Json.only(item, Set.of("pos", "block_state", "expected_state"), "blocks[" + i + "]");
			int[] pos = Json.position(item, "pos", true);
			Json.object(item.get("block_state"), "blocks[" + i + "].block_state");
			xs[i] = pos[0];
			ys[i] = pos[1];
			zs[i] = pos[2];
			access.requireInBounds(level, pos[0], pos[1], pos[2]);
			String key = pos[0] + "," + pos[1] + "," + pos[2];
			if (!positions.add(key)) {
				JsonObject details = Json.pos(pos[0], pos[1], pos[2]);
				throw BridgeException.of("DUPLICATE_POSITION", "batch contains a duplicate coordinate", details);
			}
			chunks.add((((long) (pos[0] >> 4)) << 32) | ((pos[2] >> 4) & 0xffffffffL));
			if (chunks.size() > MAX_CHUNKS) {
				throw BridgeException.of("LIMIT_EXCEEDED", "batch touches more than " + MAX_CHUNKS + " chunks", null);
			}
			if (!access.chunkLoaded(level, pos[0], pos[2])) {
				JsonObject details = Json.pos(pos[0], pos[1], pos[2]);
				details.addProperty("dimension", dimension);
				details.addProperty("chunk_x", pos[0] >> 4);
				details.addProperty("chunk_z", pos[2] >> 4);
				throw BridgeException.of("CHUNK_NOT_LOADED", "batch target chunk is not loaded", details);
			}
			normalizedBlocks.add(item);
		}
		Operation op = new Operation(newId(), requestId, "batch_set_blocks", "write", dimension, sessionId,
				n, onErrorContinue, xs[0], ys[0], zs[0], xs[0], ys[0], zs[0], 0, "auto");
		op.batchX = xs;
		op.batchY = ys;
		op.batchZ = zs;
		op.batchBlocks = normalizedBlocks;
		op.batchTargets = new BlockState[n];
		op.batchExpected = new BlockState[n];
		return op;
	}

	private static boolean parseOnError(JsonObject args) throws BridgeException {
		String value = Json.string(args, "on_error", false, "stop");
		if (!value.equals("stop") && !value.equals("continue")) {
			throw BridgeException.invalid("on_error must be stop or continue");
		}
		return value.equals("continue");
	}

	private void validateRange(ServerLevel level, int[] min, int[] max, int limit) throws BridgeException {
		if (min[0] > max[0] || min[1] > max[1] || min[2] > max[2]) {
			throw BridgeException.invalid("min must not be greater than max on any axis");
		}
		long volume = volume(min, max);
		if (volume > limit) {
			JsonObject details = new JsonObject();
			details.addProperty("volume", volume);
			details.addProperty("limit", limit);
			throw BridgeException.of("LIMIT_EXCEEDED", "requested volume exceeds the limit", details);
		}
		access.requireInBounds(level, min[0], min[1], min[2]);
		access.requireInBounds(level, max[0], max[1], max[2]);
		long chunks = (long) ((max[0] >> 4) - (min[0] >> 4) + 1)
				* ((max[2] >> 4) - (min[2] >> 4) + 1);
		if (chunks > MAX_CHUNKS) {
			JsonObject details = new JsonObject();
			details.addProperty("chunks", chunks);
			details.addProperty("limit", MAX_CHUNKS);
			throw BridgeException.of("LIMIT_EXCEEDED", "requested region touches too many chunks", details);
		}
	}

	private void checkChunks(ServerLevel level, int[] min, int[] max) throws BridgeException {
		for (int cx = min[0] >> 4; cx <= max[0] >> 4; cx++) {
			for (int cz = min[2] >> 4; cz <= max[2] >> 4; cz++) {
				if (!level.getChunkSource().hasChunk(cx, cz)
						|| level.getChunkSource().getChunkNow(cx, cz) == null) {
					JsonObject details = new JsonObject();
					details.addProperty("dimension", GameAccess.dimensionId(level));
					details.addProperty("chunk_x", cx);
					details.addProperty("chunk_z", cz);
					throw BridgeException.of("CHUNK_NOT_LOADED",
							"requested region includes a chunk that is not loaded", details);
				}
			}
		}
	}

	private static long volume(int[] min, int[] max) {
		return (long) (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
	}

	private String newId() {
		byte[] bytes = new byte[12];
		RANDOM.nextBytes(bytes);
		return "op-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static JsonObject limitsJson() {
		JsonObject limits = new JsonObject();
		limits.addProperty("query_voxels", MAX_QUERY_VOXELS);
		limits.addProperty("write_voxels", MAX_WRITE_VOXELS);
		limits.addProperty("batch_entries", MAX_BATCH_ENTRIES);
		limits.addProperty("chunks_per_operation", MAX_CHUNKS);
		limits.addProperty("queued_operations", MAX_QUEUED);
		limits.addProperty("request_bytes", 2 * 1024 * 1024);
		limits.addProperty("page_bytes", RegionSnapshot.MAX_PAGE_BYTES);
		limits.addProperty("terminal_ttl_seconds", 300);
		limits.addProperty("tick_budget_ms", 2);
		limits.addProperty("write_candidates_per_tick", WRITE_CANDIDATES_PER_TICK);
		return limits;
	}

	private record IdempotencyRecord(String canonical, String operationId) {
	}

	private static final Set<String> SUPPORTED_TOOLS = Set.of(
			"get_world_info", "get_player_position", "get_block", "get_region", "get_terrain",
			"set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks",
			"get_operation", "cancel_operation", "get_operation_errors", "get_query_result");

	private static final Set<String> ASYNC_TOOLS = Set.of(
			"get_region", "get_terrain", "set_block", "remove_block", "fill_region",
			"replace_blocks", "batch_set_blocks");

	private static final Set<String> WRITE_TOOLS = Set.of(
			"set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks");
}
