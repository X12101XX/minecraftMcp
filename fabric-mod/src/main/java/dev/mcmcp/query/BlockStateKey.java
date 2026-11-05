package dev.mcmcp.query;

import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Serialized block identity: registry id plus the complete state property map. */
public record BlockStateKey(String id, Map<String, String> properties) {
	public BlockStateKey {
		properties = Collections.unmodifiableMap(new TreeMap<>(properties));
	}

	public JsonObject toJson() {
		JsonObject state = new JsonObject();
		state.addProperty("id", id);
		JsonObject props = new JsonObject();
		properties.forEach(props::addProperty);
		state.add("properties", props);
		return state;
	}

	public static BlockStateKey fromJson(JsonObject object) {
		Map<String, String> props = new LinkedHashMap<>();
		if (object.has("properties") && object.get("properties").isJsonObject()) {
			object.getAsJsonObject("properties").entrySet()
					.forEach(e -> props.put(e.getKey(), e.getValue().getAsString()));
		}
		return new BlockStateKey(object.get("id").getAsString(), props);
	}
}
