package dev.mcmcp.operation;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.mcmcp.bridge.Json;
import dev.mcmcp.query.BlockStateKey;
import dev.mcmcp.query.RegionSnapshot;
import dev.mcmcp.query.TerrainSnapshot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Mutable only on the server thread; snapshot methods are synchronized for HTTP readers. */
public final class Operation {
	public static final long TERMINAL_TTL_MILLIS = 5 * 60 * 1000L;
	public static final int MAX_ERRORS = 65_536;

	public enum State {
		QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

		public String json() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		public boolean terminal() {
			return this == SUCCEEDED || this == FAILED || this == CANCELLED;
		}
	}

	public final String id;
	public final String requestId;
	public final String tool;
	public final String kind;
	public final String dimension;
	public final String worldSessionId;
	public final long createdAt;
	public final int total;
	public final boolean onErrorContinue;

	// Query plan
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;
	public final int clearanceHeight;
	public final String encoding;

	// Write plan
	public BlockState targetState;
	public BlockState expectedState;
	public BlockFilter filter;
	public JsonArray batchBlocks;
	public int[] batchX;
	public int[] batchY;
	public int[] batchZ;
	public BlockState[] batchTargets;
	public BlockState[] batchExpected;
	public int prepareIndex;
	public boolean prepared;
	public int previewStartedTick = -1;

	// Scan/execution cursor
	public int cursor;
	public List<BlockStateKey> palette;
	public Map<BlockStateKey, Integer> paletteIndex;
	public int[] indices;
	public dev.mcmcp.query.TerrainFact[] terrainFacts;

	public State state = State.QUEUED;
	public boolean cancelRequested;
	public int processed;
	public int succeeded;
	public int changed;
	public int unchanged;
	public int skipped;
	public int failed;
	public int startedTick = -1;
	public int finishedTick = -1;
	public long finishedAt;
	public JsonObject terminalError;
	public RegionSnapshot regionResult;
	public TerrainSnapshot terrainResult;
	public final List<OperationError> errors = new ArrayList<>();
	public boolean errorsOverflow;
	private final Object lock = new Object();

	public Operation(String id, String requestId, String tool, String kind, String dimension,
			String worldSessionId, int total, boolean onErrorContinue,
			int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
			int clearanceHeight, String encoding) {
		this.id = id;
		this.requestId = requestId;
		this.tool = tool;
		this.kind = kind;
		this.dimension = dimension;
		this.worldSessionId = worldSessionId;
		this.total = total;
		this.onErrorContinue = onErrorContinue;
		this.createdAt = System.currentTimeMillis();
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.clearanceHeight = clearanceHeight;
		this.encoding = encoding;
	}

	public boolean isWrite() {
		return "write".equals(kind);
	}

	public boolean isRead() {
		return "read".equals(kind);
	}

	public boolean isTerminal() {
		synchronized (lock) {
			return state.terminal();
		}
	}

	public State state() {
		synchronized (lock) {
			return state;
		}
	}

	public String terminalExpiresAt() {
		synchronized (lock) {
			if (!state.terminal() || finishedAt == 0) {
				return null;
			}
			return Instant.ofEpochMilli(finishedAt + TERMINAL_TTL_MILLIS).toString();
		}
	}

	public long terminalExpiresAtMillis() {
		synchronized (lock) {
			return state.terminal() ? finishedAt + TERMINAL_TTL_MILLIS : Long.MAX_VALUE;
		}
	}

	public boolean markStarted(int tick) {
		synchronized (lock) {
			if (state != State.QUEUED) {
				return false;
			}
			state = State.RUNNING;
			startedTick = tick;
			return true;
		}
	}

	public void finish(int tick, State result, String terminalCode, String terminalMessage,
			JsonObject terminalDetails) {
		synchronized (lock) {
			if (state.terminal()) {
				return;
			}
			state = result;
			finishedTick = tick;
			finishedAt = System.currentTimeMillis();
			if (result == State.FAILED) {
				terminalError = new JsonObject();
				terminalError.addProperty("code", terminalCode == null ? "INTERNAL_ERROR" : terminalCode);
				terminalError.addProperty("message",
						terminalMessage == null ? "operation failed" : terminalMessage);
				terminalError.addProperty("retryable", false);
				terminalError.add("details", terminalDetails == null ? new JsonObject() : terminalDetails);
			}
		}
	}

	public boolean addError(OperationError error) {
		synchronized (lock) {
			if (errors.size() >= MAX_ERRORS) {
				errorsOverflow = true;
				return false;
			}
			errors.add(error);
			return true;
		}
	}

	public boolean errorCapacityExhausted() {
		synchronized (lock) {
			return errors.size() >= MAX_ERRORS;
		}
	}

	public List<OperationError> errorSnapshot() {
		synchronized (lock) {
			return List.copyOf(errors);
		}
	}

	public JsonObject snapshotJson() {
		synchronized (lock) {
			JsonObject op = new JsonObject();
			op.addProperty("operation_id", id);
			op.addProperty("request_id", requestId);
			op.addProperty("world_session_id", worldSessionId);
			op.addProperty("dimension", dimension);
			op.addProperty("tool", tool);
			op.addProperty("kind", kind);
			op.addProperty("state", state.json());
			JsonObject counts = new JsonObject();
			counts.addProperty("total", total);
			counts.addProperty("processed", processed);
			counts.addProperty("succeeded", succeeded);
			counts.addProperty("changed", changed);
			counts.addProperty("unchanged", unchanged);
			counts.addProperty("skipped", skipped);
			counts.addProperty("failed", failed);
			op.add("counts", counts);
			op.addProperty("error_count", errors.size());
			op.addProperty("created_at", Instant.ofEpochMilli(createdAt).toString());
			op.addProperty("started_tick", startedTick < 0 ? null : startedTick);
			op.addProperty("finished_tick", finishedTick < 0 ? null : finishedTick);
			op.addProperty("expires_at", state.terminal() ? terminalExpiresAt() : null);
			op.addProperty("result_available", state == State.SUCCEEDED && isRead() && resultAvailable());
			op.add("terminal_error", terminalError == null ? JsonNull.INSTANCE : terminalError);
			return op;
		}
	}

	private boolean resultAvailable() {
		return (regionResult != null || terrainResult != null);
	}

	public void setRegionResult(RegionSnapshot result) {
		synchronized (lock) {
			this.regionResult = result;
		}
	}

	public void setTerrainResult(TerrainSnapshot result) {
		synchronized (lock) {
			this.terrainResult = result;
		}
	}

	public RegionSnapshot regionResult() {
		synchronized (lock) {
			return regionResult;
		}
	}

	public TerrainSnapshot terrainResult() {
		synchronized (lock) {
			return terrainResult;
		}
	}

	public JsonObject envelope() {
		JsonObject root = new JsonObject();
		root.addProperty("ok", true);
		root.add("operation", snapshotJson());
		return root;
	}

	public static final class BlockFilter {
		public final Block block;
		public final Map<String, String> properties;

		public BlockFilter(Block block, Map<String, String> properties) {
			this.block = block;
			this.properties = properties;
		}
	}
}
