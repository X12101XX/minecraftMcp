package dev.mcmcp.operation;

import com.google.gson.JsonObject;
import dev.mcmcp.bridge.Json;

public final class OperationError {
	public final int index;
	public final Integer candidateIndex;
	public final Integer x;
	public final Integer y;
	public final Integer z;
	public final String code;
	public final String message;
	public final boolean retryable;
	public final boolean effectUnknown;
	public final JsonObject details;

	public OperationError(int index, Integer candidateIndex, Integer x, Integer y, Integer z,
			String code, String message, boolean retryable, boolean effectUnknown, JsonObject details) {
		this.index = index;
		this.candidateIndex = candidateIndex;
		this.x = x;
		this.y = y;
		this.z = z;
		this.code = code;
		this.message = message;
		this.retryable = retryable;
		this.effectUnknown = effectUnknown;
		this.details = details == null ? new JsonObject() : details;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("index", index);
		json.addProperty("candidate_index", candidateIndex);
		json.add("pos", x == null ? com.google.gson.JsonNull.INSTANCE : Json.pos(x, y, z));
		json.addProperty("code", code);
		json.addProperty("message", message);
		json.addProperty("retryable", retryable);
		json.addProperty("effect_unknown", effectUnknown);
		json.add("details", details);
		return json;
	}
}
