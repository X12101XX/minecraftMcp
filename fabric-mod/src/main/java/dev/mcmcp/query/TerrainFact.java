package dev.mcmcp.query;

import com.google.gson.JsonObject;

/** One sampled block inside a terrain column. */
public record TerrainFact(int y, BlockStateKey state, boolean air, FluidSample fluid,
		boolean collision, boolean fullTopFace) {
	public record FluidSample(String id, boolean source, BlockStateKey block) {
		public JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("id", id);
			json.addProperty("is_source", source);
			return json;
		}
	}
}
