package dev.mcmcp;

import com.google.gson.JsonObject;
import dev.mcmcp.bridge.BridgeException;
import dev.mcmcp.game.GameAccess;
import dev.mcmcp.operation.OperationManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinglePositionQueryTest {
	private final OperationManager manager = new OperationManager(new GameAccess(null), "session", null, false);

	private static JsonObject arguments() {
		JsonObject args = new JsonObject();
		args.addProperty("world_session_id", "session");
		args.addProperty("dimension", "minecraft:overworld");
		JsonObject pos = new JsonObject();
		pos.addProperty("x", 0);
		pos.addProperty("y", 64);
		pos.addProperty("z", 0);
		args.add("pos", pos);
		return args;
	}

	@Test
	void queriesRejectWrongSessionsBeforeAccessingWorld() {
		for (String tool : new String[]{"get_biome", "get_light"}) {
			JsonObject args = arguments();
			args.addProperty("world_session_id", "old-session");
			BridgeException error = assertThrows(BridgeException.class, () -> manager.getBiomeOrLight(tool, args));
			assertEquals("WORLD_SESSION_MISMATCH", error.code);
		}
	}

	@Test
	void queriesRejectInvalidPositionAndUnknownArguments() {
		for (String tool : new String[]{"get_biome", "get_light"}) {
			JsonObject invalidPosition = arguments();
			invalidPosition.getAsJsonObject("pos").addProperty("y", 1.5);
			assertEquals("INVALID_ARGUMENT", assertThrows(BridgeException.class,
					() -> manager.getBiomeOrLight(tool, invalidPosition)).code);
			JsonObject extra = arguments();
			extra.addProperty("unexpected", true);
			assertEquals("INVALID_ARGUMENT", assertThrows(BridgeException.class,
					() -> manager.getBiomeOrLight(tool, extra)).code);
		}
	}

	@Test
	void uninitializedLightIsRetryable() {
		BridgeException error = BridgeException.of("LIGHT_NOT_READY", "not initialized", null);
		assertEquals(503, error.httpStatus);
		assertTrue(error.retryable);
	}
}
