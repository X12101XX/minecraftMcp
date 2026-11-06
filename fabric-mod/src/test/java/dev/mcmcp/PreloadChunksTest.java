package dev.mcmcp;

import com.google.gson.JsonObject;
import dev.mcmcp.bridge.BridgeException;
import dev.mcmcp.game.GameAccess;
import dev.mcmcp.operation.Operation;
import dev.mcmcp.operation.OperationManager;
import dev.mcmcp.operation.PreloadTicketLeases;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreloadChunksTest {
	private static JsonObject position(int x, int y, int z) {
		JsonObject pos = new JsonObject();
		pos.addProperty("x", x);
		pos.addProperty("y", y);
		pos.addProperty("z", z);
		return pos;
	}

	private static JsonObject arguments() {
		JsonObject args = new JsonObject();
		args.addProperty("world_session_id", "session");
		args.addProperty("dimension", "minecraft:overworld");
		args.add("min", position(0, 64, 0));
		args.add("max", position(15, 64, 15));
		args.addProperty("request_id", "11111111-1111-4111-8111-111111111111");
		return args;
	}

	private static BridgeException reject(OperationManager manager, JsonObject args) {
		return assertThrows(BridgeException.class, () -> manager.acceptAsync("preload_chunks", args));
	}

	@Test
	void rejectsInvalidRequestsBeforeTouchingTheWorld() {
		OperationManager manager = new OperationManager(new GameAccess(null), "session", null, true);

		JsonObject wrongSession = arguments();
		wrongSession.addProperty("world_session_id", "old-session");
		assertEquals("WORLD_SESSION_MISMATCH", reject(manager, wrongSession).code);

		JsonObject missingRequest = arguments();
		missingRequest.remove("request_id");
		assertEquals("INVALID_ARGUMENT", reject(manager, missingRequest).code);

		JsonObject unknownField = arguments();
		unknownField.addProperty("loaded_only", true);
		assertEquals("INVALID_ARGUMENT", reject(manager, unknownField).code);

		JsonObject inverted = arguments();
		inverted.add("min", position(16, 64, 0));
		assertEquals("INVALID_ARGUMENT", reject(manager, inverted).code);

		JsonObject tooLarge = arguments();
		tooLarge.add("max", position(33 * 16 - 1, 64, 32 * 16 - 1));
		BridgeException limit = reject(manager, tooLarge);
		assertEquals("LIMIT_EXCEEDED", limit.code);
		assertEquals(1_056L, limit.details.get("chunks").getAsLong());
		assertEquals(1_024L, limit.details.get("limit").getAsLong());
	}

	@Test
	void readOnlyBridgeRejectsPreload() {
		OperationManager manager = new OperationManager(new GameAccess(null), "session", null, false);
		assertEquals("FORBIDDEN", reject(manager, arguments()).code);
	}

	@Test
	void loadOperationCountsAreLoadedChunks() {
		Operation op = new Operation("op-preload", "request", "preload_chunks", "load",
				"minecraft:overworld", "session", 4, false,
				0, 64, 0, 63, 64, 63, 0, "auto");
		op.markStarted(10);
		op.processed = 4;
		op.succeeded = 4;
		op.finish(20, Operation.State.SUCCEEDED, null, null, null);

		JsonObject snapshot = op.snapshotJson();
		assertEquals("load", snapshot.get("kind").getAsString());
		JsonObject counts = snapshot.getAsJsonObject("counts");
		assertEquals(4, counts.get("total").getAsInt());
		assertEquals(4, counts.get("processed").getAsInt());
		assertEquals(4, counts.get("succeeded").getAsInt());
		assertFalse(snapshot.get("result_available").getAsBoolean());
		assertTrue(op.isLoad());
		assertFalse(op.isRead());
		assertFalse(op.isWrite());
	}

	@Test
	void aSuccessfulPeerKeepsTheSharedTicket() {
		PreloadTicketLeases leases = new PreloadTicketLeases(16, 100);
		assertTrue(leases.acquire(5, 0));
		assertTrue(leases.acquire(5, 0));
		assertFalse(leases.release(5, true));
		assertFalse(leases.release(5, false));
		assertEquals(1, leases.size());
		assertEquals(List.of(5L), leases.drain());
		assertTrue(leases.isEmpty());
	}

	@Test
	void theLastFailedLeaseRemovesTheTicket() {
		PreloadTicketLeases leases = new PreloadTicketLeases(16, 100);
		assertTrue(leases.acquire(6, 0));
		assertTrue(leases.acquire(6, 0));
		assertFalse(leases.release(6, false));
		assertTrue(leases.release(6, false));
		assertTrue(leases.isEmpty());
	}

	@Test
	void capacityRejectsOnlyNewChunks() {
		PreloadTicketLeases leases = new PreloadTicketLeases(1, 100);
		assertTrue(leases.acquire(1, 0));
		assertFalse(leases.acquire(2, 0));
		assertTrue(leases.acquire(1, 1));
		assertFalse(leases.release(1, false));
		assertTrue(leases.release(1, false));
		assertTrue(leases.isEmpty());
	}

	@Test
	void pruneDropsOnlyExpiredRetainedEntries() {
		PreloadTicketLeases leases = new PreloadTicketLeases(16, 100);
		assertTrue(leases.acquire(1, 0));
		assertFalse(leases.release(1, true));
		assertTrue(leases.prune(150).isEmpty());
		assertEquals(List.of(1L), leases.prune(250));
		assertTrue(leases.isEmpty());

		PreloadTicketLeases live = new PreloadTicketLeases(16, 100);
		assertTrue(live.acquire(2, 0));
		assertTrue(live.prune(250).isEmpty());
		assertFalse(live.isEmpty());
	}
}
