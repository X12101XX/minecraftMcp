package dev.mcmcp.bridge;

import com.google.gson.JsonObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/** Opaque, signed pagination cursors bound to one operation and endpoint. */
public final class CursorCodec {
	private final byte[] secret;
	private final String sessionId;

	public CursorCodec(byte[] secret, String sessionId) {
		this.secret = secret;
		this.sessionId = sessionId;
	}

	public String encode(String operationId, String endpoint, int offset) {
		JsonObject payload = new JsonObject();
		payload.addProperty("s", sessionId);
		payload.addProperty("op", operationId);
		payload.addProperty("e", endpoint);
		payload.addProperty("o", offset);
		String body = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(Json.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
		return body + "." + sign(body);
	}

	public int decode(String cursor, String operationId, String endpoint) throws BridgeException {
		if (cursor == null || cursor.isEmpty() || cursor.indexOf('.') < 0) {
			throw new BridgeException("INVALID_CURSOR", 400, "cursor is invalid", false, null);
		}
		int dot = cursor.lastIndexOf('.');
		String body = cursor.substring(0, dot);
		String signature = cursor.substring(dot + 1);
		if (!constantEquals(signature, sign(body))) {
			throw new BridgeException("INVALID_CURSOR", 400, "cursor signature is invalid", false, null);
		}
		try {
			String json = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
			JsonObject payload = Json.GSON.fromJson(json, JsonObject.class);
			if (payload == null
					|| !sessionId.equals(Json.string(payload, "s", true, null))
					|| !operationId.equals(Json.string(payload, "op", true, null))
					|| !endpoint.equals(Json.string(payload, "e", true, null))) {
				throw new BridgeException("INVALID_CURSOR", 400, "cursor does not belong to this operation", false, null);
			}
			int offset = Json.integer(payload, "o", true, 0);
			if (offset < 0) {
				throw new BridgeException("INVALID_CURSOR", 400, "cursor offset is invalid", false, null);
			}
			return offset;
		} catch (IllegalArgumentException e) {
			throw new BridgeException("INVALID_CURSOR", 400, "cursor is malformed", false, null);
		}
	}

	private String sign(String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			byte[] digest = mac.doFinal((sessionId + ":" + body).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
		}
	}

	private static boolean constantEquals(String a, String b) {
		return java.security.MessageDigest.isEqual(
				a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
