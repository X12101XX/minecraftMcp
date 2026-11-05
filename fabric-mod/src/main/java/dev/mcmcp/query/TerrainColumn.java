package dev.mcmcp.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Immutable terrain column. All values describe only the requested bounded Y interval; truncation is
 * explicit so callers cannot mistake a scan boundary for a world boundary.
 */
public final class TerrainColumn {
	private final int x;
	private final int z;
	private final int scanMinY;
	private final int scanMaxY;
	private final int worldMinY;
	private final int worldMaxYExclusive;
	private final TerrainFact[] facts;

	public TerrainColumn(int x, int z, int scanMinY, int scanMaxY, int worldMinY,
			int worldMaxYExclusive, TerrainFact[] facts) {
		this.x = x;
		this.z = z;
		this.scanMinY = scanMinY;
		this.scanMaxY = scanMaxY;
		this.worldMinY = worldMinY;
		this.worldMaxYExclusive = worldMaxYExclusive;
		this.facts = facts;
		if (facts.length != scanMaxY - scanMinY + 1) {
			throw new IllegalArgumentException("terrain facts do not match scan height");
		}
	}

	public JsonObject toJson(int clearanceHeight) {
		JsonObject column = new JsonObject();
		column.addProperty("x", x);
		column.addProperty("z", z);

		Hit highestNonAir = null;
		Hit highestFluid = null;
		Hit highestCollision = null;
		for (TerrainFact fact : facts) {
			if (!fact.air()) {
				highestNonAir = new Hit(fact.y(), fact.state(), null);
			}
			if (fact.fluid() != null) {
				highestFluid = new Hit(fact.y(), fact.state(), fact.fluid());
			}
			if (fact.collision()) {
				highestCollision = new Hit(fact.y(), fact.state(), null);
			}
		}
		column.add("highest_non_air", highestNonAir == null ? JsonNull.INSTANCE : highestNonAir.toJson(false));
		column.add("highest_fluid", highestFluid == null ? JsonNull.INSTANCE : highestFluid.toJson(true));
		column.add("highest_collision", highestCollision == null ? JsonNull.INSTANCE : highestCollision.toJson(false));

		JsonArray air = new JsonArray();
		JsonArray fluid = new JsonArray();
		JsonArray collision = new JsonArray();
		for (int index = 0; index < facts.length; index++) {
			TerrainFact fact = facts[index];
			if (fact.air()) {
				int end = index;
				while (end + 1 < facts.length && facts[end + 1].air()) {
					end++;
				}
				air.add(interval(facts[index].y(), facts[end].y(), null, false));
				index = end;
			}
		}
		for (int index = 0; index < facts.length; index++) {
			TerrainFact fact = facts[index];
			if (fact.fluid() == null) {
				continue;
			}
			int end = index;
			while (end + 1 < facts.length && facts[end + 1].fluid() != null
					&& facts[end + 1].fluid().equals(fact.fluid())
					&& facts[end + 1].state().equals(fact.state())) {
				end++;
			}
			fluid.add(interval(facts[index].y(), facts[end].y(), fact, false));
			index = end;
		}
		for (int index = 0; index < facts.length; index++) {
			TerrainFact fact = facts[index];
			if (!fact.collision()) {
				continue;
			}
			int end = index;
			while (end + 1 < facts.length && facts[end + 1].collision()
					&& facts[end + 1].state().equals(fact.state())
					&& facts[end + 1].fullTopFace() == fact.fullTopFace()) {
				end++;
			}
			collision.add(interval(facts[index].y(), facts[end].y(), fact, true));
			index = end;
		}
		column.add("air_intervals", air);
		column.add("fluid_intervals", fluid);
		column.add("collision_intervals", collision);

		JsonArray supports = new JsonArray();
		for (int i = 0; i < facts.length; i++) {
			TerrainFact fact = facts[i];
			if (!fact.collision()) {
				continue;
			}
			boolean aboveVisible = i + 1 >= facts.length || !facts[i + 1].collision();
			if (!aboveVisible) {
				continue;
			}
			int clear = 0;
			boolean truncated = false;
			for (int y = i + 1; y < facts.length; y++) {
				TerrainFact above = facts[y];
				if (!above.air() || above.fluid() != null) {
					break;
				}
				clear++;
				if (y == facts.length - 1 && fact.y() + clear + 1 < worldMaxYExclusive) {
					truncated = true;
				}
			}
			if (i + 1 >= facts.length && scanMaxY + 1 < worldMaxYExclusive) {
				truncated = true;
			}
			boolean supportOk = fact.fullTopFace() && fact.fluid() == null && clear >= clearanceHeight;
			JsonObject support = new JsonObject();
			support.addProperty("y", fact.y());
			support.add("block_state", fact.state().toJson());
			support.addProperty("full_top_face", fact.fullTopFace());
			support.addProperty("clear_height", clear);
			support.addProperty("clearance_truncated", truncated);
			support.addProperty("support_ok", supportOk);
			supports.add(support);
		}
		column.add("support_candidates", supports);
		return column;
	}

	private JsonObject interval(int min, int max, TerrainFact sample, boolean collision) {
		JsonObject interval = new JsonObject();
		interval.addProperty("min_y", min);
		interval.addProperty("max_y", max);
		if (sample != null && !collision) {
			interval.add("fluid", sample.fluid().toJson());
			interval.add("block_state", sample.state().toJson());
		}
		if (sample != null && collision) {
			interval.add("block_state", sample.state().toJson());
			interval.addProperty("full_top_face", sample.fullTopFace());
		}
		interval.addProperty("lower_truncated", min == scanMinY && scanMinY > worldMinY);
		interval.addProperty("upper_truncated", max == scanMaxY && scanMaxY + 1 < worldMaxYExclusive);
		return interval;
	}

	private record Hit(int y, BlockStateKey state, TerrainFact.FluidSample fluid) {
		JsonObject toJson(boolean withFluid) {
			JsonObject json = new JsonObject();
			json.addProperty("y", y);
			if (withFluid) {
				json.add("fluid", fluid.toJson());
			}
			json.add("block_state", state.toJson());
			return json;
		}
	}
}
