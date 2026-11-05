package dev.mcmcp.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class Json {
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

	private Json() {
	}

	public static JsonObject object(JsonElement element, String what) throws BridgeException {
		if (element == null || !element.isJsonObject()) {
			throw BridgeException.invalid(what + " must be an object");
		}
		return element.getAsJsonObject();
	}

	public static void only(JsonObject object, Set<String> allowed, String what) throws BridgeException {
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			if (!allowed.contains(entry.getKey())) {
				throw BridgeException.invalid(what + " has unknown field '" + entry.getKey() + "'");
			}
		}
	}

	public static String string(JsonObject object, String field, boolean required, String fallback)
			throws BridgeException {
		JsonElement value = object.get(field);
		if (value == null || value.isJsonNull()) {
			if (required) {
				throw BridgeException.invalid("missing required field '" + field + "'");
			}
			return fallback;
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw BridgeException.invalid("field '" + field + "' must be a string");
		}
		String result = value.getAsString();
		if (required && result.isEmpty()) {
			throw BridgeException.invalid("field '" + field + "' must not be empty");
		}
		return result;
	}

	public static int integer(JsonObject object, String field, boolean required, int fallback)
			throws BridgeException {
		JsonElement value = object.get(field);
		if (value == null || value.isJsonNull()) {
			if (required) {
				throw BridgeException.invalid("missing required field '" + field + "'");
			}
			return fallback;
		}
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw BridgeException.invalid("field '" + field + "' must be an integer");
		}
		try {
			java.math.BigDecimal number = value.getAsBigDecimal();
			if (number.stripTrailingZeros().scale() > 0) {
				throw BridgeException.invalid("field '" + field + "' must be an integer");
			}
			long parsed = number.longValueExact();
			if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
				throw BridgeException.invalid("field '" + field + "' is outside the 32-bit integer range");
			}
			return (int) parsed;
		} catch (NumberFormatException | ArithmeticException | UnsupportedOperationException e) {
			throw BridgeException.invalid("field '" + field + "' must be an integer");
		}
	}

	public static int[] position(JsonObject object, String field, boolean required) throws BridgeException {
		JsonElement value = object.get(field);
		if (value == null || value.isJsonNull()) {
			if (required) {
				throw BridgeException.invalid("missing required field '" + field + "'");
			}
			return null;
		}
		JsonObject pos = object(value, field);
		only(pos, Set.of("x", "y", "z"), field);
		return new int[] {
				integer(pos, "x", true, 0),
				integer(pos, "y", true, 0),
				integer(pos, "z", true, 0)
		};
	}

	public static JsonObject pos(int x, int y, int z) {
		JsonObject pos = new JsonObject();
		pos.addProperty("x", x);
		pos.addProperty("y", y);
		pos.addProperty("z", z);
		return pos;
	}

	public static JsonArray array(JsonElement... elements) {
		JsonArray array = new JsonArray();
		for (JsonElement element : elements) {
			array.add(element == null ? JsonNull.INSTANCE : element);
		}
		return array;
	}

	public static List<JsonObject> objectArray(JsonObject object, String field) throws BridgeException {
		JsonElement value = object.get(field);
		if (value == null || value.isJsonNull() || !value.isJsonArray()) {
			throw BridgeException.invalid("field '" + field + "' must be an array");
		}
		JsonArray array = value.getAsJsonArray();
		List<JsonObject> result = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			result.add(object(array.get(i), field + "[" + i + "]"));
		}
		return result;
	}

	/**
	 * Stable representation used for idempotency comparison: object keys sorted, arrays ordered.
	 */
	public static String canonical(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return "null";
		}
		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isString()) {
				return Json.GSON.toJson(primitive.getAsString());
			}
			if (primitive.isBoolean()) {
				return primitive.getAsBoolean() ? "true" : "false";
			}
			return primitive.getAsBigDecimal().stripTrailingZeros().toPlainString();
		}
		if (element.isJsonArray()) {
			StringBuilder builder = new StringBuilder("[");
			JsonArray array = element.getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				if (i > 0) {
					builder.append(',');
				}
				builder.append(canonical(array.get(i)));
			}
			return builder.append(']').toString();
		}
		JsonObject object = element.getAsJsonObject();
		TreeMap<String, JsonElement> sorted = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			sorted.put(entry.getKey(), entry.getValue());
		}
		StringBuilder builder = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
			if (!first) {
				builder.append(',');
			}
			first = false;
			builder.append(Json.GSON.toJson(entry.getKey())).append(':').append(canonical(entry.getValue()));
		}
		return builder.append('}').toString();
	}

	public static JsonObject blockState(String id, Map<String, String> properties) {
		JsonObject state = new JsonObject();
		state.addProperty("id", id);
		JsonObject props = new JsonObject();
		properties.forEach(props::addProperty);
		state.add("properties", props);
		return state;
	}
}
