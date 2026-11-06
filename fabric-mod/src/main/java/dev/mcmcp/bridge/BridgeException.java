package dev.mcmcp.bridge;

import com.google.gson.JsonObject;

/**
 * A typed, user-facing failure. Never carries stack traces or credentials.
 */
public final class BridgeException extends Exception {
	public final String code;
	public final int httpStatus;
	public final boolean retryable;
	public final JsonObject details;

	public BridgeException(String code, int httpStatus, String message, boolean retryable, JsonObject details) {
		super(message);
		this.code = code;
		this.httpStatus = httpStatus;
		this.retryable = retryable;
		this.details = details == null ? new JsonObject() : details;
	}

	public static BridgeException invalid(String message) {
		return new BridgeException("INVALID_ARGUMENT", 400, message, false, null);
	}

	public static BridgeException of(String code, String message, JsonObject details) {
		int status = switch (code) {
			case "UNAUTHORIZED" -> 401;
			case "FORBIDDEN" -> 403;
			case "OPERATION_NOT_FOUND", "PLAYER_NOT_FOUND", "PLAYER_REQUIRED", "DIMENSION_NOT_FOUND" -> 404;
			case "WORLD_SESSION_MISMATCH", "CHUNK_NOT_LOADED", "IDEMPOTENCY_CONFLICT",
					"OPERATION_NOT_READY", "OPERATION_KIND_MISMATCH", "RESULT_UNAVAILABLE" -> 409;
			case "REQUEST_TOO_LARGE", "LIMIT_EXCEEDED" -> 413;
			case "QUEUE_FULL", "RESOURCE_EXHAUSTED" -> 429;
			case "WORLD_NOT_READY", "SERVER_TICK_TIMEOUT", "LIGHT_NOT_READY" -> 503;
			default -> 400;
		};
		boolean retryable = code.equals("QUEUE_FULL") || code.equals("RESOURCE_EXHAUSTED")
				|| code.equals("WORLD_NOT_READY") || code.equals("SERVER_TICK_TIMEOUT")
				|| code.equals("OPERATION_NOT_READY") || code.equals("LIGHT_NOT_READY");
		return new BridgeException(code, status, message, retryable, details);
	}

	public JsonObject toJson() {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", getMessage());
		error.addProperty("retryable", retryable);
		error.add("details", details);
		JsonObject root = new JsonObject();
		root.addProperty("ok", false);
		root.add("error", error);
		return root;
	}
}
