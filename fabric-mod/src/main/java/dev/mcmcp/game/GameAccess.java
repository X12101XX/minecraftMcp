package dev.mcmcp.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcmcp.bridge.BridgeException;
import dev.mcmcp.bridge.Json;
import dev.mcmcp.query.BlockStateKey;
import dev.mcmcp.query.TerrainFact;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** All direct Minecraft registry and world access lives here and is called on the server thread. */
public final class GameAccess {
	private static final double TOP_EPSILON = 1.0E-6;

	private final MinecraftServer server;

	public GameAccess(MinecraftServer server) {
		this.server = server;
	}

	public MinecraftServer server() {
		return server;
	}

	public int tick() {
		return server.getTickCount();
	}

	public List<ServerLevel> levels() {
		List<ServerLevel> result = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			result.add(level);
		}
		return result;
	}

	public ServerLevel level(String dimension) throws BridgeException {
		for (ServerLevel level : server.getAllLevels()) {
			if (dimensionId(level).equals(dimension)) {
				return level;
			}
		}
		for (net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key : server.levelKeys()) {
			if (key.identifier().toString().equals(dimension)) {
				ServerLevel level = server.getLevel(key);
				if (level != null) {
					return level;
				}
			}
		}
		JsonObject details = new JsonObject();
		details.addProperty("dimension", dimension);
		throw BridgeException.of("DIMENSION_NOT_FOUND", "dimension is not loaded", details);
	}

	public static String dimensionId(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	public JsonArray dimensionsJson() {
		JsonArray array = new JsonArray();
		for (ServerLevel level : levels()) {
			array.add(dimensionJson(level));
		}
		return array;
	}

	public JsonObject dimensionJson(ServerLevel level) {
		JsonObject json = new JsonObject();
		json.addProperty("id", dimensionId(level));
		json.addProperty("min_y", level.dimensionType().minY());
		json.addProperty("max_y_exclusive", level.dimensionType().minY() + level.dimensionType().height());
		double minX = level.getWorldBorder().getMinX();
		double maxX = level.getWorldBorder().getMaxX();
		double minZ = level.getWorldBorder().getMinZ();
		double maxZ = level.getWorldBorder().getMaxZ();
		json.addProperty("min_x", (int) Math.floor(minX));
		json.addProperty("max_x", (int) Math.ceil(maxX) - 1);
		json.addProperty("min_z", (int) Math.floor(minZ));
		json.addProperty("max_z", (int) Math.ceil(maxZ) - 1);
		return json;
	}

	public JsonArray playersJson() {
		JsonArray players = new JsonArray();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("player_uuid", player.getUUID().toString());
			entry.addProperty("name", player.getGameProfile().name());
			entry.addProperty("dimension", dimensionId(player.level()));
			players.add(entry);
		}
		return players;
	}

	public ServerPlayer findPlayer(String selector) throws BridgeException {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (selector != null && selector.isBlank()) {
			throw BridgeException.invalid("player must not be empty when provided");
		}
		if (selector == null) {
			if (players.size() == 1) {
				return players.getFirst();
			}
			JsonObject details = new JsonObject();
			details.addProperty("online_players", players.size());
			throw BridgeException.of("PLAYER_REQUIRED",
					"player is required unless exactly one player is online", details);
		}
		ServerPlayer found = null;
		try {
			UUID uuid = UUID.fromString(selector);
			found = server.getPlayerList().getPlayer(uuid);
		} catch (IllegalArgumentException ignored) {
			for (ServerPlayer player : players) {
				if (player.getGameProfile().name().equalsIgnoreCase(selector)) {
					if (found != null) {
						throw BridgeException.of("PLAYER_REQUIRED", "player name is ambiguous", null);
					}
					found = player;
				}
			}
		}
		if (found == null) {
			JsonObject details = new JsonObject();
			details.addProperty("player", selector);
			throw BridgeException.of("PLAYER_NOT_FOUND", "player is not online", details);
		}
		return found;
	}

	public dev.mcmcp.operation.Operation.BlockFilter parseFilter(JsonObject input) throws BridgeException {
		if (input == null) {
			throw BridgeException.invalid("filter is required");
		}
		Json.only(input, java.util.Set.of("id", "properties"), "filter");
		String id = Json.string(input, "id", true, null);
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			throw BridgeException.of("INVALID_BLOCK_ID", "filter id is not a valid resource identifier", detail("id", id));
		}
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(identifier);
		if (block.isEmpty()) {
			throw BridgeException.of("INVALID_BLOCK_ID", "filter block is not registered", detail("id", id));
		}
		Map<String, String> properties = new TreeMap<>();
		if (input.has("properties") && !input.get("properties").isJsonNull()) {
			if (!input.get("properties").isJsonObject()) {
				throw BridgeException.of("INVALID_BLOCK_STATE", "filter properties must be an object", detail("id", id));
			}
			BlockState state = block.get().defaultBlockState();
			for (Map.Entry<String, com.google.gson.JsonElement> entry : input.getAsJsonObject("properties").entrySet()) {
				Property<?> property = null;
				for (Property<?> candidate : state.getProperties()) {
					if (candidate.getName().equals(entry.getKey())) {
						property = candidate;
						break;
					}
				}
				if (property == null) {
					throw BridgeException.of("INVALID_BLOCK_STATE",
							"filter block does not have property '" + entry.getKey() + "'", detail("id", id));
				}
				if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
					throw BridgeException.of("INVALID_BLOCK_STATE",
							"filter property value must be a string", detail("id", id));
				}
				applyProperty(state, property, entry.getValue().getAsString(), id);
				properties.put(entry.getKey(), entry.getValue().getAsString());
			}
		}
		return new dev.mcmcp.operation.Operation.BlockFilter(block.get(), properties);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static boolean matchesFilter(BlockState state, dev.mcmcp.operation.Operation.BlockFilter filter) {
		if (state.getBlock() != filter.block) {
			return false;
		}
		for (Map.Entry<String, String> entry : filter.properties.entrySet()) {
			Property property = null;
			for (Property<?> candidate : state.getProperties()) {
				if (candidate.getName().equals(entry.getKey())) {
					property = candidate;
					break;
				}
			}
			if (property == null) {
				return false;
			}
			String actual = property.getName((Comparable) state.getValue(property));
			if (!actual.equals(entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	public BlockState parseBlockState(JsonObject input, boolean expectedState) throws BridgeException {
		if (input == null) {
			throw BridgeException.invalid("block state is required");
		}
		Json.only(input, java.util.Set.of("id", "properties"), "block_state");
		String id = Json.string(input, "id", true, null);
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			throw BridgeException.of("INVALID_BLOCK_ID", "block id is not a valid resource identifier", detail("id", id));
		}
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(identifier);
		if (block.isEmpty()) {
			throw BridgeException.of("INVALID_BLOCK_ID", "block id is not registered", detail("id", id));
		}
		BlockState state = block.get().defaultBlockState();
		JsonObject properties = null;
		if (input.has("properties") && !input.get("properties").isJsonNull()) {
			if (!input.get("properties").isJsonObject()) {
				throw BridgeException.of("INVALID_BLOCK_STATE", "properties must be an object", detail("id", id));
			}
			properties = input.getAsJsonObject("properties");
		}
		Map<String, Property<?>> known = new TreeMap<>();
		for (Property<?> property : state.getProperties()) {
			known.put(property.getName(), property);
		}
		if (properties != null) {
			for (Map.Entry<String, com.google.gson.JsonElement> entry : properties.entrySet()) {
				Property<?> property = known.get(entry.getKey());
				if (property == null) {
					throw BridgeException.of("INVALID_BLOCK_STATE",
							"block does not have property '" + entry.getKey() + "'", detail("id", id));
				}
				if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
					throw BridgeException.of("INVALID_BLOCK_STATE",
							"property value must be a string", detail("id", id));
				}
				state = applyProperty(state, property, entry.getValue().getAsString(), id);
			}
		}
		if (expectedState) {
			if (properties == null || properties.size() != known.size()) {
				throw BridgeException.of("INVALID_BLOCK_STATE",
						"expected_state must contain every state property", detail("id", id));
			}
		}
		return state;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static BlockState applyProperty(BlockState state, Property property, String value, String id)
			throws BridgeException {
		Optional<?> parsed = property.getValue(value);
		if (parsed.isEmpty()) {
			throw BridgeException.of("INVALID_BLOCK_STATE",
					"invalid value '" + value + "' for property '" + property.getName() + "'", detail("id", id));
		}
		return state.setValue(property, (Comparable) parsed.get());
	}

	private static JsonObject detail(String field, String value) {
		JsonObject details = new JsonObject();
		details.addProperty(field, value);
		return details;
	}

	public static BlockStateKey stateKey(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		Map<String, String> properties = new TreeMap<>();
		for (Property<?> property : state.getProperties()) {
			properties.put(property.getName(), propertyName(state, property));
		}
		return new BlockStateKey(id.toString(), properties);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static String propertyName(BlockState state, Property property) {
		return property.getName((Comparable) state.getValue(property));
	}

	public static JsonObject stateJson(BlockState state) {
		return stateKey(state).toJson();
	}

	public boolean chunkLoaded(ServerLevel level, int x, int z) {
		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
			return false;
		}
		return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
	}

	public void requireChunk(ServerLevel level, int x, int y, int z) throws BridgeException {
		if (!chunkLoaded(level, x, z)) {
			JsonObject details = new JsonObject();
			details.addProperty("dimension", dimensionId(level));
			details.addProperty("chunk_x", x >> 4);
			details.addProperty("chunk_z", z >> 4);
			details.addProperty("x", x);
			details.addProperty("y", y);
			details.addProperty("z", z);
			throw BridgeException.of("CHUNK_NOT_LOADED",
					"target chunk is not loaded; this bridge never loads or generates chunks", details);
		}
	}

	public void requireInBounds(ServerLevel level, int x, int y, int z) throws BridgeException {
		int minY = level.dimensionType().minY();
		int maxY = minY + level.dimensionType().height();
		double minX = level.getWorldBorder().getMinX();
		double maxX = level.getWorldBorder().getMaxX();
		double minZ = level.getWorldBorder().getMinZ();
		double maxZ = level.getWorldBorder().getMaxZ();
		if (y < minY || y >= maxY || x < Math.floor(minX) || x > Math.ceil(maxX) - 1
				|| z < Math.floor(minZ) || z > Math.ceil(maxZ) - 1) {
			JsonObject details = new JsonObject();
			details.addProperty("dimension", dimensionId(level));
			details.addProperty("min_y", minY);
			details.addProperty("max_y_exclusive", maxY);
			details.addProperty("x", x);
			details.addProperty("y", y);
			details.addProperty("z", z);
			throw BridgeException.of("OUT_OF_BOUNDS", "block coordinate is outside the world", details);
		}
	}

	public JsonObject blockFacts(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		FluidState fluid = level.getFluidState(pos);
		VoxelShape collision = state.getCollisionShape(new LoadedOnlyView(level), pos, CollisionContext.empty());
		JsonObject facts = new JsonObject();
		facts.add("block_state", stateJson(state));
		facts.addProperty("is_air", state.isAir());
		if (fluid.isEmpty()) {
			JsonObject empty = new JsonObject();
			empty.addProperty("is_empty", true);
			empty.add("id", com.google.gson.JsonNull.INSTANCE);
			empty.add("is_source", com.google.gson.JsonNull.INSTANCE);
			facts.add("fluid", empty);
		} else {
			JsonObject value = new JsonObject();
			value.addProperty("is_empty", false);
			value.addProperty("id", BuiltInRegistries.FLUID.getKey(fluid.getType()).toString());
			value.addProperty("is_source", fluid.isSource());
			facts.add("fluid", value);
		}
		facts.addProperty("has_collision", !collision.isEmpty());
		return facts;
	}

	public TerrainFact terrainFact(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		FluidState fluid = level.getFluidState(pos);
		VoxelShape collision = state.getCollisionShape(new LoadedOnlyView(level), pos, CollisionContext.empty());
		TerrainFact.FluidSample fluidSample = null;
		if (!fluid.isEmpty()) {
			fluidSample = new TerrainFact.FluidSample(
					BuiltInRegistries.FLUID.getKey(fluid.getType()).toString(),
					fluid.isSource(), stateKey(state));
		}
		return new TerrainFact(pos.getY(), stateKey(state), state.isAir(), fluidSample,
				!collision.isEmpty(), coversFullTop(collision));
	}

	/**
	 * A BlockGetter that never asks the server for an unloaded chunk. Collision shapes for connected
	 * blocks (fences, walls) may under-report connections at an unloaded boundary, but the bridge
	 * must not synchronously load or generate chunks merely to inspect a shape.
	 */
	private static final class LoadedOnlyView implements BlockGetter {
		private final ServerLevel level;

		private LoadedOnlyView(ServerLevel level) {
			this.level = level;
		}

		@Override
		public BlockEntity getBlockEntity(BlockPos pos) {
			return level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return level.isLoaded(pos) ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return level.isLoaded(pos) ? level.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getHeight() {
			return level.getHeight();
		}

		@Override
		public int getMinY() {
			return level.getMinY();
		}
	}

	private static boolean coversFullTop(VoxelShape shape) {
		if (shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 1.0 - TOP_EPSILON) {
			return false;
		}
		List<AABB> rects = new ArrayList<>();
		for (AABB box : shape.toAabbs()) {
			if (box.maxY >= 1.0 - TOP_EPSILON) {
				rects.add(box);
			}
		}
		if (rects.isEmpty()) {
			return false;
		}
		// Union area of the top rectangles, clipped to the unit square. Shapes contain few boxes.
		List<Double> xs = new ArrayList<>();
		for (AABB box : rects) {
			xs.add(Math.max(0.0, box.minX));
			xs.add(Math.min(1.0, box.maxX));
		}
		xs.sort(Double::compare);
		double area = 0.0;
		for (int i = 0; i + 1 < xs.size(); i++) {
			double x0 = xs.get(i);
			double x1 = xs.get(i + 1);
			if (x1 - x0 <= TOP_EPSILON) {
				continue;
			}
			List<double[]> intervals = new ArrayList<>();
			for (AABB box : rects) {
				if (box.maxX > x0 + TOP_EPSILON && box.minX < x1 - TOP_EPSILON) {
					intervals.add(new double[] {Math.max(0.0, box.minZ), Math.min(1.0, box.maxZ)});
				}
			}
			intervals.sort((a, b) -> Double.compare(a[0], b[0]));
			double covered = 0.0;
			double start = -1.0;
			double end = -1.0;
			for (double[] interval : intervals) {
				if (start < 0.0) {
					start = interval[0];
					end = interval[1];
				} else if (interval[0] <= end + TOP_EPSILON) {
					end = Math.max(end, interval[1]);
				} else {
					covered += end - start;
					start = interval[0];
					end = interval[1];
				}
			}
			if (start >= 0.0) {
				covered += end - start;
			}
			area += (x1 - x0) * Math.min(1.0, covered);
		}
		return area >= 1.0 - TOP_EPSILON;
	}

}
