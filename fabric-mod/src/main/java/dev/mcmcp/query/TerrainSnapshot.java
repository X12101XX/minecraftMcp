package dev.mcmcp.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.mcmcp.bridge.BridgeException;
import dev.mcmcp.bridge.Json;

import java.nio.charset.StandardCharsets;

/**
 * Immutable terrain result backed by sampled facts. Column analysis and page slicing are pure DTO
 * work; the world is never consulted after the scan completes.
 */
public final class TerrainSnapshot {
	private static final int PAGE_SAFETY_BYTES = 4096;

	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;
	public final int clearanceHeight;
	private final TerrainFact[] facts;
	private final int sizeY;
	private final int worldMinY;
	private final int worldMaxYExclusive;
	public final int startedTick;
	public final int finishedTick;

	public TerrainSnapshot(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
			int clearanceHeight, TerrainFact[] facts, int worldMinY, int worldMaxYExclusive,
			int startedTick, int finishedTick) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.clearanceHeight = clearanceHeight;
		this.facts = facts;
		this.sizeY = maxY - minY + 1;
		this.worldMinY = worldMinY;
		this.worldMaxYExclusive = worldMaxYExclusive;
		this.startedTick = startedTick;
		this.finishedTick = finishedTick;
		int expected = (maxX - minX + 1) * (maxZ - minZ + 1);
		if (facts.length != expected * sizeY) {
			throw new IllegalArgumentException("terrain facts do not match scan volume");
		}
	}

	public int columnCount() {
		return facts.length / sizeY;
	}

	public JsonObject columnJson(int index) {
		int sizeX = maxX - minX + 1;
		int x = minX + index % sizeX;
		int z = minZ + index / sizeX;
		TerrainFact[] column = new TerrainFact[sizeY];
		System.arraycopy(facts, index * sizeY, column, 0, sizeY);
		return new TerrainColumn(x, z, minY, maxY, worldMinY, worldMaxYExclusive, column)
				.toJson(clearanceHeight);
	}

	public record Page(JsonObject json, int nextOffset) {
	}

	public Page page(int offset, String operationId, String sessionId, String dimension,
			String expiresAt, java.util.function.IntFunction<String> cursorFactory) throws BridgeException {
		if (offset < 0 || offset >= columnCount()) {
			throw new BridgeException("INVALID_CURSOR", 400, "cursor offset is outside the result", false, null);
		}
		int low = 1;
		int high = columnCount() - offset;
		int best = 0;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			JsonObject candidate = build(offset, mid, operationId, sessionId, dimension, "x".repeat(200), expiresAt);
			int bytes = Json.GSON.toJson(candidate).getBytes(StandardCharsets.UTF_8).length;
			if (bytes <= RegionSnapshot.MAX_PAGE_BYTES - PAGE_SAFETY_BYTES) {
				best = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		if (best == 0) {
			throw new BridgeException("RESULT_TOO_LARGE", 400,
					"one terrain column cannot fit into the result page limit", false, null);
		}
		while (best > 0) {
			JsonObject json = build(offset, best, operationId, sessionId, dimension, "x".repeat(200), expiresAt);
			int next = offset + best;
			json.add("next_cursor", next >= columnCount() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(cursorFactory.apply(next)));
			if (Json.GSON.toJson(json).getBytes(StandardCharsets.UTF_8).length <= RegionSnapshot.MAX_PAGE_BYTES) {
				return new Page(json, next);
			}
			best--;
		}
		throw new BridgeException("RESULT_TOO_LARGE", 400,
				"one terrain column cannot fit into the result page limit", false, null);
	}

	private JsonObject build(int offset, int count, String operationId, String sessionId,
			String dimension, String cursorForOffset, String expiresAt) {
		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("operation_id", operationId);
		json.addProperty("world_session_id", sessionId);
		json.addProperty("dimension", dimension);
		json.addProperty("result_type", "terrain");
		json.addProperty("consistency", "incremental");
		json.addProperty("started_tick", startedTick);
		json.addProperty("finished_tick", finishedTick);
		json.add("min", Json.pos(minX, minY, minZ));
		json.add("max", Json.pos(maxX, maxY, maxZ));
		json.addProperty("clearance_height", clearanceHeight);
		json.addProperty("offset", offset);
		json.addProperty("count", count);
		json.addProperty("total_columns", columnCount());
		JsonArray array = new JsonArray(count);
		for (int i = 0; i < count; i++) {
			array.add(columnJson(offset + i));
		}
		json.add("columns", array);
		json.addProperty("next_cursor", cursorForOffset);
		json.addProperty("expires_at", expiresAt);
		return json;
	}
}
