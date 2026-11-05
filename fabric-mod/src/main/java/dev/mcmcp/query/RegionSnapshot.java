package dev.mcmcp.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.mcmcp.bridge.BridgeException;
import dev.mcmcp.bridge.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, already-sampled region. Pages are derived deterministically from this object; no world
 * access is ever needed after construction.
 */
public final class RegionSnapshot {
	public static final int MAX_PAGE_BYTES = 512 * 1024;
	private static final int PAGE_SAFETY_BYTES = 4096;

	public final int minX;
	public final int minY;
	public final int minZ;
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	public final List<BlockStateKey> palette;
	public final int[] indices;
	public final String encodingRequest;
	public final int startedTick;
	public final int finishedTick;

	public RegionSnapshot(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
			List<BlockStateKey> palette, int[] indices, String encodingRequest,
			int startedTick, int finishedTick) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.palette = List.copyOf(palette);
		this.indices = indices;
		this.encodingRequest = encodingRequest;
		this.startedTick = startedTick;
		this.finishedTick = finishedTick;
		if (indices.length != sizeX * sizeY * sizeZ) {
			throw new IllegalArgumentException("region index count does not match dimensions");
		}
	}

	public int total() {
		return indices.length;
	}

	public record Page(JsonObject json, int nextOffset) {
	}

	public Page page(int offset, String operationId, String sessionId, String dimension,
			String expiresAt, java.util.function.IntFunction<String> cursorFactory) throws BridgeException {
		if (offset < 0 || offset >= total()) {
			throw new BridgeException("INVALID_CURSOR", 400, "cursor offset is outside the result", false, null);
		}
		int remaining = total() - offset;
		int low = 1;
		int high = remaining;
		int best = 0;
		int bestCount = 0;
		String bestEncoding = null;
		JsonObject bestJson = null;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			Candidate candidate = build(offset, mid, operationId, sessionId, dimension,
					"x".repeat(200), expiresAt, MAX_PAGE_BYTES - PAGE_SAFETY_BYTES);
			int bytes = Json.GSON.toJson(candidate.json).getBytes(StandardCharsets.UTF_8).length;
			if (bytes <= MAX_PAGE_BYTES - PAGE_SAFETY_BYTES) {
				best = bytes;
				bestCount = mid;
				bestEncoding = candidate.encoding;
				bestJson = candidate.json;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		if (bestCount == 0) {
			throw new BridgeException("RESULT_TOO_LARGE", 400,
					"one region entry cannot fit into the result page limit", false, null);
		}
		// Rebuild with the real next cursor and enforce the hard byte limit.
		while (bestCount > 0) {
			int nextOffset = offset + bestCount;
			JsonObject json = build(offset, bestCount, operationId, sessionId, dimension,
					"x".repeat(200), expiresAt, MAX_PAGE_BYTES).json;
			json.add("next_cursor", nextOffset >= total() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(cursorFactory.apply(nextOffset)));
			if (Json.GSON.toJson(json).getBytes(StandardCharsets.UTF_8).length <= MAX_PAGE_BYTES) {
				return new Page(json, nextOffset);
			}
			bestCount--;
		}
		throw new BridgeException("RESULT_TOO_LARGE", 400,
				"one region entry cannot fit into the result page limit", false, null);
	}

	private Candidate build(int offset, int count, String operationId, String sessionId,
			String dimension, String cursorForOffset, String expiresAt, int limit) throws BridgeException {
		Map<BlockStateKey, Integer> local = new LinkedHashMap<>();
		int[] localIndices = new int[count];
		for (int i = 0; i < count; i++) {
			BlockStateKey key = palette.get(indices[offset + i]);
			Integer index = local.get(key);
			if (index == null) {
				index = local.size();
				local.put(key, index);
			}
			localIndices[i] = index;
		}
		JsonArray localPalette = new JsonArray();
		local.keySet().forEach(key -> localPalette.add(key.toJson()));

		JsonArray indicesJson = new JsonArray(count);
		for (int value : localIndices) {
			indicesJson.add(value);
		}
		JsonArray runs = new JsonArray();
		int runStart = 0;
		for (int i = 1; i <= count; i++) {
			if (i == count || localIndices[i] != localIndices[runStart]) {
				JsonObject run = new JsonObject();
				run.addProperty("palette_index", localIndices[runStart]);
				run.addProperty("length", i - runStart);
				runs.add(run);
				runStart = i;
			}
		}

		String encoding;
		JsonElement data;
		if ("palette_indices".equals(encodingRequest)) {
			encoding = "palette_indices";
			data = indicesJson;
		} else if ("palette_rle".equals(encodingRequest)) {
			encoding = "palette_rle";
			data = runs;
		} else {
			int indicesBytes = Json.GSON.toJson(indicesJson).getBytes(StandardCharsets.UTF_8).length;
			int runsBytes = Json.GSON.toJson(runs).getBytes(StandardCharsets.UTF_8).length;
			if (runsBytes < indicesBytes) {
				encoding = "palette_rle";
				data = runs;
			} else {
				encoding = "palette_indices";
				data = indicesJson;
			}
		}

		JsonObject json = new JsonObject();
		json.addProperty("ok", true);
		json.addProperty("operation_id", operationId);
		json.addProperty("world_session_id", sessionId);
		json.addProperty("dimension", dimension);
		json.addProperty("result_type", "region");
		json.addProperty("consistency", "incremental");
		json.addProperty("started_tick", startedTick);
		json.addProperty("finished_tick", finishedTick);
		json.add("min", Json.pos(minX, minY, minZ));
		json.add("max", Json.pos(minX + sizeX - 1, minY + sizeY - 1, minZ + sizeZ - 1));
		json.addProperty("encoding", encoding);
		json.addProperty("offset", offset);
		json.addProperty("count", count);
		json.addProperty("total_voxels", total());
		json.add("palette", localPalette);
		json.add(encoding.equals("palette_rle") ? "runs" : "indices", data);
		json.addProperty("next_cursor", cursorForOffset);
		json.addProperty("expires_at", expiresAt);
		return new Candidate(json, encoding);
	}

	private record Candidate(JsonObject json, String encoding) {
	}

	/** Convert a linear index to coordinates, x fastest, then z, then y. */
	public int[] coordinates(int index) {
		int x = minX + index % sizeX;
		int z = minZ + (index / sizeX) % sizeZ;
		int y = minY + index / (sizeX * sizeZ);
		return new int[] {x, y, z};
	}

	/** Decode a page back to global palette indices; used by tests and clients. */
	public static int[] decodePage(JsonObject page) {
		int count = page.get("count").getAsInt();
		int[] result = new int[count];
		if ("palette_rle".equals(page.get("encoding").getAsString())) {
			int cursor = 0;
			for (JsonElement element : page.getAsJsonArray("runs")) {
				JsonObject run = element.getAsJsonObject();
				int index = run.get("palette_index").getAsInt();
				int length = run.get("length").getAsInt();
				for (int i = 0; i < length; i++) {
					result[cursor++] = index;
				}
			}
			if (cursor != count) {
				throw new IllegalArgumentException("RLE length does not match page count");
			}
		} else {
			JsonArray array = page.getAsJsonArray("indices");
			if (array.size() != count) {
				throw new IllegalArgumentException("index length does not match page count");
			}
			for (int i = 0; i < count; i++) {
				result[i] = array.get(i).getAsInt();
			}
		}
		return result;
	}

	public static List<BlockStateKey> paletteFromPage(JsonObject page) {
		List<BlockStateKey> result = new ArrayList<>();
		for (JsonElement element : page.getAsJsonArray("palette")) {
			result.add(BlockStateKey.fromJson(element.getAsJsonObject()));
		}
		return result;
	}
}
