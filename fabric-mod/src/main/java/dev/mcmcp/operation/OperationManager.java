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
import dev.mcmcp.game.EntityAccess;
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
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
	public static final int MAX_UNDO_JOURNAL_ENTRIES = 1_048_576;
	public static final int MAX_CHUNKS = 64;
	public static final int MAX_PRELOAD_CHUNKS = 1_024;
	public static final int PRELOAD_TICKET_TIMEOUT_TICKS = 6_000;
	public static final int MAX_QUEUED = 32;
	public static final int MAX_ACTIVE_READS = 4;
	public static final int DEFAULT_WRITE_CANDIDATES_PER_TICK = 1;
	public static final long TICK_BUDGET_NANOS = 2_000_000L;
	private static final int MAX_IMMEDIATE_TASKS_PER_TICK = 128;
	private static final int MAX_ACTIVE_PRELOADS = 2;
	private static final int PRELOAD_TICKETS_PER_TICK = 64;
	private static final int PRELOAD_CHECKS_PER_TICK = 512;
	private static final int MAX_TRACKED_PRELOAD_CHUNKS = PreloadTicketLeases.DEFAULT_CAPACITY;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Holds the custom ticket type until the first server-thread use. */
	private static final class PreloadTicket {
		private static final TicketType TYPE =
				new TicketType(PRELOAD_TICKET_TIMEOUT_TICKS, TicketType.FLAG_LOADING);
	}

	@FunctionalInterface
	public interface ServerCall {
		JsonObject call() throws BridgeException;
	}

	private final GameAccess access;
	private final String sessionId;
	private final CursorCodec cursors;
	private final boolean allowWrites;
	private final int writeCandidatesPerTick;
	private final VisualEvents visuals;
	private final BlockingQueue<Runnable> immediate = new ArrayBlockingQueue<>(128);
	private final List<Operation> pendingReads = new ArrayList<>();
	private final List<Operation> pendingWrites = new ArrayList<>();
	private final List<Operation> activeReads = new ArrayList<>();
	private final List<Operation> pendingLoads = new ArrayList<>();
	private final List<Operation> activeLoads = new ArrayList<>();
	private Operation activeWrite;
	private final Map<String, Operation> operations = new ConcurrentHashMap<>();
	private final Map<ServerLevel, PreloadTicketLeases> preloadLeases = new IdentityHashMap<>();
	private final Map<String, IdempotencyRecord> idempotency = new ConcurrentHashMap<>();
	private int reservedUndoEntries;
	private boolean shuttingDown;
	private long workTicks;
	private long workNanosTotal;
	private long workNanosMax;
	private long lastWorkNanos;
	private int lastWriteCandidates;
	private int lastReadVoxels;
	private volatile long lastTickNanos = -1L;

	public OperationManager(GameAccess access, String sessionId, CursorCodec cursors, boolean allowWrites) {
		this(access, sessionId, cursors, allowWrites, DEFAULT_WRITE_CANDIDATES_PER_TICK);
	}

	public OperationManager(GameAccess access, String sessionId, CursorCodec cursors, boolean allowWrites,
			int writeCandidatesPerTick) {
		this.access = access;
		this.sessionId = sessionId;
		this.cursors = cursors;
		this.allowWrites = allowWrites;
		this.writeCandidatesPerTick = Math.max(1, writeCandidatesPerTick);
		this.visuals = new VisualEvents(access.server(), sessionId);
	}

	public String sessionId() {
		return sessionId;
	}

	/** Milliseconds since the last server-tick scheduler pass, or -1 if none has run yet. */
	public long tickAgeMillis() {
		long last = lastTickNanos;
		return last < 0 ? -1 : (System.nanoTime() - last) / 1_000_000L;
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

	public JsonObject getEntity(JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "entity_uuid"), "arguments");
		requireSession(args);
		String dimension = Json.string(args, "dimension", true, null);
		String entityUuid = Json.string(args, "entity_uuid", true, null);
		JsonObject result = EntityAccess.getEntity(access, dimension, entityUuid);
		result.addProperty("ok", true);
		result.addProperty("world_session_id", sessionId);
		return result;
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

	public JsonObject getBiomeOrLight(String tool, JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "pos"), "arguments");
		requireSession(args);
		String dimension = Json.string(args, "dimension", true, null);
		int[] pos = Json.position(args, "pos", true);
		ServerLevel level = access.level(dimension);
		access.requireInBounds(level, pos[0], pos[1], pos[2]);
		access.requireChunk(level, pos[0], pos[1], pos[2]);
		BlockPos blockPos = new BlockPos(pos[0], pos[1], pos[2]);
		JsonObject facts = switch (tool) {
			case "get_biome" -> access.biomeFacts(level, blockPos);
			case "get_light" -> access.lightFacts(level, blockPos);
			default -> throw BridgeException.of("UNKNOWN_TOOL", "unknown tool", null);
		};
		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		root.addProperty("world_session_id", sessionId);
		root.addProperty("dimension", dimension);
		root.add("pos", Json.pos(pos[0], pos[1], pos[2]));
		facts.entrySet().forEach(entry -> root.add(entry.getKey(), entry.getValue()));
		root.addProperty("observed_tick", access.tick());
		visuals.notice(tool);
		visuals.range(dimension, "block", pos[0], pos[1], pos[2], pos[0], pos[1], pos[2], "read", 30);
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
		if (pendingReads.size() + pendingWrites.size() + pendingLoads.size() >= MAX_QUEUED) {
			JsonObject details = new JsonObject();
			int queued = pendingReads.size() + pendingWrites.size() + pendingLoads.size();
			details.addProperty("queued", queued);
			details.addProperty("limit", MAX_QUEUED);
			throw BridgeException.of("QUEUE_FULL", "operation queue is full", details);
		}

		Operation op = switch (tool) {
			case "get_region" -> createRegion(args, requestId);
			case "get_terrain" -> createTerrain(args, requestId);
			case "preload_chunks" -> createPreload(args, requestId);
			case "set_block" -> createSet(args, requestId, false);
			case "remove_block" -> createSet(args, requestId, true);
			case "fill_region" -> createFill(args, requestId, false);
			case "replace_blocks" -> createFill(args, requestId, true);
			case "undo_operation" -> createUndo(args, requestId);
			case "batch_set_blocks", "validate_batch_states" -> createBatch(tool, args, requestId);
			default -> throw BridgeException.of("UNKNOWN_TOOL", "unknown tool", null);
		};
		if (op.isWrite()) {
			if (op.total > MAX_UNDO_JOURNAL_ENTRIES - reservedUndoEntries) {
				throw BridgeException.of("RESOURCE_EXHAUSTED",
						"undo journal capacity is reserved by other operations", null);
			}
			op.reservedUndoEntries = op.total;
			reservedUndoEntries += op.total;
		}
		visuals.notice(tool);
		operations.put(op.id, op);
		idempotency.put(requestId, new IdempotencyRecord(canonical, op.id));
		if (op.isLoad()) {
			pendingLoads.add(op);
		} else if (op.isRead()) {
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

	public JsonObject getOperationByRequestId(JsonObject args) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "request_id"), "arguments");
		requireSession(args);
		String requestId = requireRequestId(args);
		IdempotencyRecord record = idempotency.get(requestId);
		Operation op = record == null ? null : operations.get(record.operationId);
		if (op == null) {
			JsonObject details = new JsonObject();
			details.addProperty("request_id", requestId);
			throw BridgeException.of("OPERATION_NOT_FOUND", "request was not accepted or has expired", details);
		}
		visuals.notice("get_operation_by_request_id");
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
			pendingLoads.remove(op);
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
		if (!op.isRead()) {
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
		lastTickNanos = tickStart;
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
		long preloadDeadline = start + TICK_BUDGET_NANOS / 4;
		long readDeadline = start + TICK_BUDGET_NANOS / 2;
		promote(tick);
		Iterator<Operation> loadIterator = activeLoads.iterator();
		while (loadIterator.hasNext()) {
			Operation op = loadIterator.next();
			if (op.cancelRequested) {
				cancelPreload(op, tick);
				loadIterator.remove();
				continue;
			}
			if (op.state() == Operation.State.RUNNING) {
				try {
					if (processPreload(op, tick, preloadDeadline)) {
						loadIterator.remove();
					}
				} catch (RuntimeException e) {
					JsonObject details = new JsonObject();
					details.addProperty("exception", e.getClass().getSimpleName());
					failPreload(op, tick, "INTERNAL_ERROR",
							"preload operation failed; tickets for acquired chunks are released", details);
					loadIterator.remove();
				}
			}
		}
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
				try {
					processWrite(writing, tick, deadline);
				} catch (RuntimeException e) {
					JsonObject details = new JsonObject();
					details.addProperty("exception", e.getClass().getSimpleName());
					failTask(writing, tick, "INTERNAL_ERROR",
							"write operation failed; completed changes are not rolled back", details);
				}
			}
			writes = writing.processed - processedBefore;
			visuals.progress(writing.dimension, writing.id, writing.processed, writing.total, writing.state().json());
			if (writing.isTerminal()) {
				visuals.clear(writing.dimension, writing.id);
				activeWrite = null;
			}
		}
		cleanupExpired();
		prunePreloadTickets(tick);
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
		while (activeLoads.size() < MAX_ACTIVE_PRELOADS && !pendingLoads.isEmpty()) {
			Operation op = pendingLoads.removeFirst();
			if (op.cancelRequested) {
				op.finish(tick, Operation.State.CANCELLED, null, null, null);
				continue;
			}
			op.markStarted(tick);
			activeLoads.add(op);
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

	private boolean processPreload(Operation op, int tick, long deadline) {
		ServerLevel level = op.preloadLevel;
		if (level == null) {
			try {
				level = access.level(op.dimension);
			} catch (BridgeException e) {
				failPreload(op, tick, e.code, e.getMessage(), e.details);
				return true;
			}
			op.preloadLevel = level;
		}
		if (op.cancelRequested) {
			cancelPreload(op, level, tick);
			return true;
		}
		if (op.startedTick >= 0 && tick - op.startedTick >= PRELOAD_TICKET_TIMEOUT_TICKS) {
			JsonObject details = new JsonObject();
			details.addProperty("dimension", op.dimension);
			details.addProperty("requested_chunks", op.total);
			details.addProperty("loaded_chunks", op.succeeded);
			details.addProperty("timeout_ticks", PRELOAD_TICKET_TIMEOUT_TICKS);
			failPreload(op, level, tick, "CHUNK_LOAD_TIMEOUT",
					"chunks did not finish loading before the preload timeout", details);
			return true;
		}
		int spanX = (op.maxX >> 4) - (op.minX >> 4) + 1;
		int total = op.total;
		if (op.cursor < total) {
			int work = 0;
			while (op.cursor < total) {
				if (op.cancelRequested) {
					cancelPreload(op, level, tick);
					return true;
				}
				if (work > 0 && (work >= PRELOAD_TICKETS_PER_TICK || System.nanoTime() >= deadline)) {
					break;
				}
				int index = op.cursor;
				int chunkX = (op.minX >> 4) + index % spanX;
				int chunkZ = (op.minZ >> 4) + index / spanX;
				long packed = ChunkPos.pack(chunkX, chunkZ);
				PreloadTicketLeases leases = leasesFor(level);
				if (!leases.acquire(packed, tick)) {
					JsonObject details = new JsonObject();
					details.addProperty("tracked_chunks", leases.size());
					details.addProperty("limit", MAX_TRACKED_PRELOAD_CHUNKS);
					failPreload(op, level, tick, "RESOURCE_EXHAUSTED",
							"preload ticket capacity is exhausted", details);
					return true;
				}
				try {
					level.getChunkSource().addTicketWithRadius(PreloadTicket.TYPE,
							new ChunkPos(chunkX, chunkZ), 0);
				} catch (RuntimeException e) {
					if (leases.release(packed, false)) {
						removePreloadTicket(level, chunkX, chunkZ);
					}
					JsonObject details = new JsonObject();
					details.addProperty("dimension", op.dimension);
					details.addProperty("chunk_x", chunkX);
					details.addProperty("chunk_z", chunkZ);
					details.addProperty("exception", e.getClass().getSimpleName());
					failPreload(op, level, tick, "INTERNAL_ERROR", "could not add a chunk ticket", details);
					return true;
				}
				op.cursor++;
				work++;
			}
			return false;
		}
		if (op.preloadCheckCursor == 0) {
			op.preloadPassLoaded = 0;
		}
		int work = 0;
		while (op.preloadCheckCursor < total) {
			if (op.cancelRequested) {
				cancelPreload(op, level, tick);
				return true;
			}
			if (work > 0 && (work >= PRELOAD_CHECKS_PER_TICK || System.nanoTime() >= deadline)) {
				break;
			}
			int index = op.preloadCheckCursor++;
			int chunkX = (op.minX >> 4) + index % spanX;
			int chunkZ = (op.minZ >> 4) + index / spanX;
			if (level.getChunkSource().hasChunk(chunkX, chunkZ)
					&& level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
				op.preloadPassLoaded++;
			}
			work++;
		}
		if (op.preloadCheckCursor >= total) {
			op.processed = op.preloadPassLoaded;
			op.succeeded = op.preloadPassLoaded;
			if (op.preloadPassLoaded == total) {
				releasePreloadTickets(op, level, true);
				op.finish(tick, Operation.State.SUCCEEDED, null, null, null);
				return true;
			}
			op.preloadCheckCursor = 0;
		}
		return false;
	}

	private PreloadTicketLeases leasesFor(ServerLevel level) {
		return preloadLeases.computeIfAbsent(level, ignored -> new PreloadTicketLeases(
				MAX_TRACKED_PRELOAD_CHUNKS, PRELOAD_TICKET_TIMEOUT_TICKS + 200));
	}

	private void releasePreloadTickets(Operation op, ServerLevel level, boolean retain) {
		PreloadTicketLeases leases = preloadLeases.get(level);
		if (leases == null) {
			return;
		}
		int spanX = (op.maxX >> 4) - (op.minX >> 4) + 1;
		int acquired = Math.min(op.cursor, op.total);
		for (int index = 0; index < acquired; index++) {
			int chunkX = (op.minX >> 4) + index % spanX;
			int chunkZ = (op.minZ >> 4) + index / spanX;
			if (leases.release(ChunkPos.pack(chunkX, chunkZ), retain)) {
				removePreloadTicket(level, chunkX, chunkZ);
			}
		}
		if (leases.isEmpty()) {
			preloadLeases.remove(level);
		}
	}

	private void cancelPreload(Operation op, ServerLevel level, int tick) {
		if (level != null) {
			releasePreloadTickets(op, level, false);
		}
		op.finish(tick, Operation.State.CANCELLED, null, null, null);
	}

	private void cancelPreload(Operation op, int tick) {
		ServerLevel level = op.preloadLevel != null ? op.preloadLevel : preloadLevelFor(op);
		if (level == null) {
			try {
				level = access.level(op.dimension);
			} catch (BridgeException ignored) {
			}
		}
		cancelPreload(op, level, tick);
	}

	private ServerLevel preloadLevelFor(Operation op) {
		for (ServerLevel level : preloadLeases.keySet()) {
			if (GameAccess.dimensionId(level).equals(op.dimension)) {
				return level;
			}
		}
		return null;
	}

	private void failPreload(Operation op, ServerLevel level, int tick, String code, String message,
			JsonObject details) {
		if (level != null) {
			releasePreloadTickets(op, level, false);
		}
		failTask(op, tick, code, message, details);
	}

	private void failPreload(Operation op, int tick, String code, String message, JsonObject details) {
		ServerLevel level = op.preloadLevel != null ? op.preloadLevel : preloadLevelFor(op);
		if (level == null) {
			try {
				level = access.level(op.dimension);
			} catch (BridgeException ignored) {
			}
		}
		failPreload(op, level, tick, code, message, details);
	}

	private static void removePreloadTicket(ServerLevel level, int chunkX, int chunkZ) {
		try {
			level.getChunkSource().removeTicketWithRadius(PreloadTicket.TYPE,
					new ChunkPos(chunkX, chunkZ), 0);
		} catch (RuntimeException e) {
			MinecraftMcpMod.LOGGER.warn("Could not remove a preload ticket at chunk {},{}", chunkX, chunkZ, e);
		}
	}

	private void prunePreloadTickets(int tick) {
		Iterator<Map.Entry<ServerLevel, PreloadTicketLeases>> iterator = preloadLeases.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ServerLevel, PreloadTicketLeases> entry = iterator.next();
			for (long packed : entry.getValue().prune(tick)) {
				removePreloadTicket(entry.getKey(), ChunkPos.getX(packed), ChunkPos.getZ(packed));
			}
			if (entry.getValue().isEmpty()) {
				iterator.remove();
			}
		}
	}

	private void releaseAllPreloadTickets() {
		for (Map.Entry<ServerLevel, PreloadTicketLeases> entry : preloadLeases.entrySet()) {
			for (long packed : entry.getValue().drain()) {
				removePreloadTicket(entry.getKey(), ChunkPos.getX(packed), ChunkPos.getZ(packed));
			}
		}
		preloadLeases.clear();
	}

	private boolean prepareWrite(Operation op, int tick, long deadline) {
		if (op.prepared) {
			return true;
		}
		if (!op.tool.equals("batch_set_blocks") && !op.tool.equals("validate_batch_states")) {
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
		if (op.tool.equals("validate_batch_states")) {
			op.finish(tick, Operation.State.SUCCEEDED, null, null, null);
			return true;
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
		if (op.tool.equals("undo_operation")) {
			return processUndo(op, tick, deadline);
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
			if (work > 0 && (work >= writeCandidatesPerTick || System.nanoTime() >= deadline)) {
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
				if (op.errorCapacityExhausted()) {
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
			WriteResult result = applyWrite(op, level, pos, current, target);
			op.cursor++;
			work++;
			if (result.after != null && !current.equals(result.after)) {
				op.changed++;
			}
			if (result.failure != null) {
				coordinateFailure(op, index, x, y, z, "WRITE_OUTCOME_UNKNOWN",
						"engine write or post-write observation failed; effects may be unknown",
						result.failure, true);
				finishWrite(op, tick, op.errorsOverflow ? "RESOURCE_EXHAUSTED" : "WRITE_OUTCOME_UNKNOWN");
				return true;
			}
			if (!result.accepted) {
				coordinateFailure(op, index, x, y, z, "WRITE_REJECTED",
						"engine rejected the block update", null, false);
				if (!op.onErrorContinue || op.errorCapacityExhausted()) {
					finishWrite(op, tick, op.errorCapacityExhausted() ? "RESOURCE_EXHAUSTED" : "WRITE_REJECTED");
					return true;
				}
				continue;
			}
			if (current.equals(result.after)) op.unchanged++;
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

	private record WriteResult(boolean accepted, BlockState after, JsonObject failure, boolean unsafe) {}

	/** Captures the observed final state even when the engine throws or rejects the write. */
	private WriteResult applyWrite(Operation op, ServerLevel level, BlockPos pos,
			BlockState before, BlockState target) {
		boolean unsafe = before.hasBlockEntity() || target.hasBlockEntity();
		boolean accepted = false;
		boolean attempted = false;
		BlockState after = null;
		JsonObject failure = null;
		try {
			unsafe = unsafe || level.getBlockEntity(pos) != null;
			attempted = true;
			accepted = level.setBlock(pos, target, Block.UPDATE_ALL);
		} catch (RuntimeException e) {
			failure = new JsonObject();
			failure.addProperty("exception", e.getClass().getSimpleName());
		}
		if (attempted) {
			try {
				if (!access.chunkLoaded(level, pos.getX(), pos.getZ())) {
					throw new IllegalStateException("target chunk unloaded after write");
				}
				after = level.getBlockState(pos);
				unsafe = unsafe || after.hasBlockEntity() || level.getBlockEntity(pos) != null;
			} catch (RuntimeException e) {
				if (failure == null) {
					failure = new JsonObject();
					failure.addProperty("exception", e.getClass().getSimpleName());
				}
			}
			boolean unknown = failure != null;
			op.recordUndo(new Operation.UndoEntry(pos.getX(), pos.getY(), pos.getZ(),
					before, after, unsafe || unknown, unknown));
		}
		return new WriteResult(accepted, after, failure, unsafe);
	}

	private boolean processUndo(Operation op, int tick, long deadline) {
		if (op.cursor >= op.total) {
			finishWrite(op, tick, null);
			return true;
		}
		ServerLevel level;
		try {
			level = access.level(op.dimension);
		} catch (BridgeException e) {
			failTask(op, tick, e);
			return true;
		}
		int index = op.cursor++;
		Operation.UndoEntry entry = op.undoSource.undoJournal.get(op.total - 1 - index);
		int x = entry.x(), y = entry.y(), z = entry.z();
		String failure = null;
		String message = null;
		JsonObject details = null;
		boolean unknown = false;
		try {
			access.requireInBounds(level, x, y, z);
		} catch (BridgeException e) {
			coordinateFailure(op, index, x, y, z, e.code, e.getMessage(), e.details, false);
			finishWrite(op, tick, e.code);
			return true;
		}
		if (entry.unsafeToRestore()) {
			failure = "UNDO_UNSAFE";
			message = "source write involved a block entity or has an unknown outcome";
		} else if (!access.chunkLoaded(level, x, z)) {
			failure = "CHUNK_NOT_LOADED";
			message = "target chunk is not loaded";
		} else {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState current = level.getBlockState(pos);
			if (!entry.matchesAfter(current)) {
				failure = "PRECONDITION_FAILED";
				message = "current block state does not match the recorded post-write state";
				details = new JsonObject();
				details.add("expected", GameAccess.stateJson(entry.after()));
				details.add("actual", GameAccess.stateJson(current));
			} else if (current.hasBlockEntity() || entry.before().hasBlockEntity()
					|| level.getBlockEntity(pos) != null) {
				failure = "UNDO_UNSAFE";
				message = "block entity data cannot be restored";
			} else if (current.equals(entry.before())) {
				op.unchanged++;
				op.succeeded++;
				op.processed++;
			} else {
				WriteResult result = applyWrite(op, level, pos, current, entry.before());
				if (result.after != null && !current.equals(result.after)) op.changed++;
				if (result.failure != null) {
					failure = "WRITE_OUTCOME_UNKNOWN";
					message = "engine write or post-write observation failed; effects may be unknown";
					details = result.failure;
					unknown = true;
				} else if (!result.accepted) {
					failure = "WRITE_REJECTED";
					message = "engine rejected the block update";
				} else if (result.unsafe) {
					failure = "UNDO_UNSAFE";
					message = "the restore produced block entity data that cannot be rolled back";
				} else if (!entry.before().equals(result.after)) {
					failure = "UNDO_RESULT_MISMATCH";
					message = "world update did not leave the requested restored block state";
					details = new JsonObject();
					details.add("expected", GameAccess.stateJson(entry.before()));
					details.add("actual", GameAccess.stateJson(result.after));
				} else {
					if (current.equals(result.after)) op.unchanged++;
					op.succeeded++;
					op.processed++;
				}
			}
		}
		if (failure != null) {
			coordinateFailure(op, index, x, y, z, failure, message, details, unknown);
			if (!op.onErrorContinue || op.errorCapacityExhausted() || unknown || failure.equals("CHUNK_NOT_LOADED")) {
				finishWrite(op, tick, op.errorCapacityExhausted() ? "RESOURCE_EXHAUSTED" : failure);
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
			if (op.isTerminal() && op.terminalExpiresAtMillis() < now && !isUndoSourceInUse(op)) {
				reservedUndoEntries -= op.reservedUndoEntries;
				iterator.remove();
				IdempotencyRecord record = idempotency.get(op.requestId);
				if (record != null && record.operationId.equals(op.id)) {
					idempotency.remove(op.requestId);
				}
			}
		}
	}

	private boolean isUndoSourceInUse(Operation source) {
		if (activeWrite != null && !activeWrite.isTerminal() && activeWrite.undoSource == source) {
			return true;
		}
		for (Operation pending : pendingWrites) {
			if (pending.undoSource == source) return true;
		}
		return false;
	}

	public void shutdown() {
		shuttingDown = true;
		releaseAllPreloadTickets();
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
		for (Operation op : pendingLoads) {
			op.finish(tick, Operation.State.FAILED, "WORLD_SESSION_ENDED", "world session ended", null);
		}
		for (Operation op : activeLoads) {
			op.finish(tick, Operation.State.FAILED, "WORLD_SESSION_ENDED", "world session ended", null);
		}
		if (activeWrite != null) {
			activeWrite.finish(tick, Operation.State.FAILED, "WORLD_SESSION_ENDED", "world session ended", null);
		}
		pendingReads.clear();
		pendingWrites.clear();
		activeReads.clear();
		pendingLoads.clear();
		activeLoads.clear();
		activeWrite = null;
		immediate.clear();
		operations.clear();
		idempotency.clear();
		reservedUndoEntries = 0;
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
			case "undo_operation" -> {
				if (!copy.has("on_error")) {
					copy.addProperty("on_error", "stop");
				}
			}
			case "batch_set_blocks", "validate_batch_states" -> {
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

	private Operation createPreload(JsonObject args, String requestId) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "min", "max", "request_id"), "arguments");
		String dimension = Json.string(args, "dimension", true, null);
		int[] min = Json.position(args, "min", true);
		int[] max = Json.position(args, "max", true);
		if (min[0] > max[0] || min[1] > max[1] || min[2] > max[2]) {
			throw BridgeException.invalid("min must not be greater than max on any axis");
		}
		long chunks = ((long) (max[0] >> 4) - (min[0] >> 4) + 1)
				* ((long) (max[2] >> 4) - (min[2] >> 4) + 1);
		if (chunks > MAX_PRELOAD_CHUNKS) {
			JsonObject details = new JsonObject();
			details.addProperty("chunks", chunks);
			details.addProperty("limit", MAX_PRELOAD_CHUNKS);
			throw BridgeException.of("LIMIT_EXCEEDED", "requested box touches too many chunks", details);
		}
		ServerLevel level = access.level(dimension);
		access.requireInBounds(level, min[0], min[1], min[2]);
		access.requireInBounds(level, max[0], max[1], max[2]);
		Operation op = new Operation(newId(), requestId, "preload_chunks", "load", dimension, sessionId,
				(int) chunks, false, min[0], min[1], min[2], max[0], max[1], max[2], 0, "auto");
		op.preloadLevel = level;
		return op;
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

	private Operation createBatch(String tool, JsonObject args, String requestId) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "blocks", "on_error", "request_id"),
				"arguments");
		String dimension = Json.string(args, "dimension", true, null);
		boolean onErrorContinue = parseOnError(args);
		List<JsonObject> blocks = Json.objectArray(args, "blocks");
		int limit = MAX_BATCH_ENTRIES;
		if (blocks.isEmpty() || blocks.size() > limit) {
			throw BridgeException.invalid("blocks must contain between 1 and " + limit + " entries");
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
		Operation op = new Operation(newId(), requestId, tool, "write", dimension, sessionId,
				n, onErrorContinue, xs[0], ys[0], zs[0], xs[0], ys[0], zs[0], 0, "auto");
		op.batchX = xs;
		op.batchY = ys;
		op.batchZ = zs;
		op.batchBlocks = normalizedBlocks;
		op.batchTargets = new BlockState[n];
		op.batchExpected = new BlockState[n];
		return op;
	}

	private Operation createUndo(JsonObject args, String requestId) throws BridgeException {
		Json.only(args, Set.of("world_session_id", "dimension", "operation_id", "on_error", "request_id"),
				"arguments");
		String dimension = Json.string(args, "dimension", true, null);
		boolean onErrorContinue = parseOnError(args);
		Operation source = requireOperation(args);
		if (!source.isWrite()) {
			throw BridgeException.of("OPERATION_KIND_MISMATCH", "source operation is not a write", null);
		}
		if (!source.isTerminal()) {
			throw BridgeException.of("OPERATION_NOT_READY", "source operation is not terminal", null);
		}
		if (!source.dimension.equals(dimension)) {
			throw BridgeException.invalid("dimension must match the source operation");
		}
		access.level(dimension);
		Operation op = new Operation(newId(), requestId, "undo_operation", "write", dimension, sessionId,
				source.undoJournal.size(), onErrorContinue, source.minX, source.minY, source.minZ,
				source.maxX, source.maxY, source.maxZ, 0, "auto");
		op.undoSource = source;
		op.undoSourceId = source.id;
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

	private JsonObject limitsJson() {
		JsonObject limits = new JsonObject();
		limits.addProperty("query_voxels", MAX_QUERY_VOXELS);
		limits.addProperty("write_voxels", MAX_WRITE_VOXELS);
		limits.addProperty("batch_entries", MAX_BATCH_ENTRIES);
		limits.addProperty("undo_journal_entries", MAX_UNDO_JOURNAL_ENTRIES);
		limits.addProperty("chunks_per_operation", MAX_CHUNKS);
		limits.addProperty("preload_chunks_per_operation", MAX_PRELOAD_CHUNKS);
		limits.addProperty("preload_ticket_timeout_ticks", PRELOAD_TICKET_TIMEOUT_TICKS);
		limits.addProperty("preload_tracked_chunks", MAX_TRACKED_PRELOAD_CHUNKS);
		limits.addProperty("queued_operations", MAX_QUEUED);
		limits.addProperty("request_bytes", 2 * 1024 * 1024);
		limits.addProperty("page_bytes", RegionSnapshot.MAX_PAGE_BYTES);
		limits.addProperty("terminal_ttl_seconds", 300);
		limits.addProperty("tick_budget_ms", 2);
		limits.addProperty("write_candidates_per_tick", writeCandidatesPerTick);
		return limits;
	}

	private record IdempotencyRecord(String canonical, String operationId) {
	}

	private static final Set<String> SUPPORTED_TOOLS = Set.of(
			"get_world_info", "get_player_position", "get_entity", "get_block", "get_biome", "get_light", "get_region", "get_terrain",
			"preload_chunks",
			"set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks", "undo_operation",
			"get_operation", "get_operation_by_request_id", "cancel_operation", "get_operation_errors", "get_query_result",
			"request_screenshot", "get_screenshot_status", "get_screenshot", "cancel_screenshot");

	private static final Set<String> ASYNC_TOOLS = Set.of(
			"get_region", "get_terrain", "preload_chunks", "set_block", "remove_block", "fill_region",
			"replace_blocks", "batch_set_blocks", "validate_batch_states", "undo_operation");

	// Preload can generate and persist chunks, so read-only bridges reject it even though its
	// operation kind is "load" rather than "write".
	private static final Set<String> WRITE_TOOLS = Set.of(
			"set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks", "validate_batch_states", "undo_operation",
			"preload_chunks");
}
