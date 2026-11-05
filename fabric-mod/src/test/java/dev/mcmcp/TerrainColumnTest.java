package dev.mcmcp;

import com.google.gson.JsonObject;
import dev.mcmcp.query.BlockStateKey;
import dev.mcmcp.query.TerrainColumn;
import dev.mcmcp.query.TerrainFact;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainColumnTest {
	private static final BlockStateKey STONE = new BlockStateKey("minecraft:stone", Map.of());
	private static final BlockStateKey AIR = new BlockStateKey("minecraft:air", Map.of());
	private static final BlockStateKey WATER = new BlockStateKey("minecraft:water", Map.of("level", "0"));
	private static final BlockStateKey LEAVES = new BlockStateKey("minecraft:oak_leaves",
			Map.of("persistent", "false", "distance", "7"));

	private static TerrainFact stone(int y) {
		return new TerrainFact(y, STONE, false, null, true, true);
	}

	private static TerrainFact air(int y) {
		return new TerrainFact(y, AIR, true, null, false, false);
	}

	private static TerrainFact water(int y) {
		TerrainFact.FluidSample fluid = new TerrainFact.FluidSample("minecraft:water", true, WATER);
		return new TerrainFact(y, WATER, false, fluid, false, false);
	}

	@Test
	void separatesAirFluidAndCollisionFacts() {
		TerrainFact[] facts = {
				stone(60), stone(61), stone(62), stone(63),
				water(64), water(65), air(66)
		};
		JsonObject json = new TerrainColumn(0, 0, 60, 66, -64, 320, facts).toJson(2);
		assertEquals(65, json.getAsJsonObject("highest_non_air").get("y").getAsInt());
		assertEquals(65, json.getAsJsonObject("highest_fluid").get("y").getAsInt());
		assertEquals(63, json.getAsJsonObject("highest_collision").get("y").getAsInt());
		assertEquals(1, json.getAsJsonArray("air_intervals").size());
		assertEquals(1, json.getAsJsonArray("fluid_intervals").size());
		assertEquals(1, json.getAsJsonArray("collision_intervals").size());
		JsonObject fluid = json.getAsJsonArray("fluid_intervals").get(0).getAsJsonObject();
		assertEquals(64, fluid.get("min_y").getAsInt());
		assertEquals(65, fluid.get("max_y").getAsInt());
		assertEquals("minecraft:water", fluid.getAsJsonObject("fluid").get("id").getAsString());
		JsonObject collision = json.getAsJsonArray("collision_intervals").get(0).getAsJsonObject();
		assertEquals(60, collision.get("min_y").getAsInt());
		assertEquals(63, collision.get("max_y").getAsInt());
		assertTrue(collision.get("lower_truncated").getAsBoolean());
		assertFalse(collision.get("upper_truncated").getAsBoolean());
		JsonObject support = json.getAsJsonArray("support_candidates").get(0).getAsJsonObject();
		assertEquals(63, support.get("y").getAsInt());
		assertEquals(0, support.get("clear_height").getAsInt());
		assertFalse(support.get("support_ok").getAsBoolean());
	}

	@Test
	void reportsEnoughClearanceAndBoundaryTruncation() {
		TerrainFact[] facts = {stone(60), air(61), air(62), air(63), air(64), air(65), air(66)};
		JsonObject json = new TerrainColumn(4, 7, 60, 66, -64, 320, facts).toJson(2);
		JsonObject support = json.getAsJsonArray("support_candidates").get(0).getAsJsonObject();
		assertEquals(60, support.get("y").getAsInt());
		assertEquals(6, support.get("clear_height").getAsInt());
		assertTrue(support.get("clearance_truncated").getAsBoolean());
		assertTrue(support.get("support_ok").getAsBoolean());
		JsonObject air = json.getAsJsonArray("air_intervals").get(0).getAsJsonObject();
		assertEquals(61, air.get("min_y").getAsInt());
		assertEquals(66, air.get("max_y").getAsInt());
		assertTrue(air.get("upper_truncated").getAsBoolean());
	}

	@Test
	void treeCanopyDoesNotBecomeTheOnlySupport() {
		TerrainFact[] facts = {
				stone(60), air(61), air(62), new TerrainFact(63, LEAVES, false, null, true, true),
				air(64), air(65), air(66)
		};
		JsonObject json = new TerrainColumn(0, 0, 60, 66, -64, 320, facts).toJson(2);
		assertEquals(63, json.getAsJsonObject("highest_non_air").get("y").getAsInt());
		assertEquals(2, json.getAsJsonArray("support_candidates").size());
		JsonObject ground = json.getAsJsonArray("support_candidates").get(0).getAsJsonObject();
		assertEquals(60, ground.get("y").getAsInt());
		assertEquals(2, ground.get("clear_height").getAsInt());
		assertTrue(ground.get("support_ok").getAsBoolean());
	}

	@Test
	void missingFactsAreNullNotFabricatedAir() {
		TerrainFact[] facts = {air(60), air(61)};
		JsonObject json = new TerrainColumn(0, 0, 60, 61, -64, 320, facts).toJson(2);
		assertTrue(json.get("highest_non_air").isJsonNull());
		assertTrue(json.get("highest_fluid").isJsonNull());
		assertTrue(json.get("highest_collision").isJsonNull());
		assertEquals(0, json.getAsJsonArray("support_candidates").size());
	}
}
