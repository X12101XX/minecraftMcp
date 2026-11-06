package dev.mcmcp;

import com.google.gson.JsonObject;
import dev.mcmcp.bridge.BridgeException;
import dev.mcmcp.game.GameAccess;
import dev.mcmcp.operation.Operation;
import dev.mcmcp.operation.OperationManager;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UndoOperationTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void guardComparesCompleteStateAndRejectsUnsafeOrUnknownEntries() {
		var before = Blocks.STONE.defaultBlockState();
		var after = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP);
		var differentProperty = after.setValue(BlockStateProperties.HALF, Half.BOTTOM);
		var entry = new Operation.UndoEntry(1, 2, 3, before, after, false, false);
		assertFalse(entry.unsafeToRestore());
		assertTrue(entry.matchesAfter(after));
		assertFalse(entry.matchesAfter(differentProperty));
		assertTrue(new Operation.UndoEntry(1, 2, 3, before, after, true, false).unsafeToRestore());
		assertTrue(new Operation.UndoEntry(1, 2, 3, before, after, false, true).unsafeToRestore());
		assertTrue(new Operation.UndoEntry(1, 2, 3, before, null, false, false).unsafeToRestore());
		assertTrue(new Operation.UndoEntry(1, 2, 3, Blocks.CHEST.defaultBlockState(), after,
				false, false).unsafeToRestore());
	}
	private static Operation operation(String tool, int total) {
		return new Operation("op-1", "request", tool, "write", "minecraft:overworld", "session",
				total, false, 0, 0, 0, 0, 0, 0, 0, "auto");
	}

	private static JsonObject arguments() {
		JsonObject args = new JsonObject();
		args.addProperty("world_session_id", "session");
		args.addProperty("dimension", "minecraft:overworld");
		args.addProperty("operation_id", "missing");
		args.addProperty("request_id", "00000000-0000-0000-0000-000000000001");
		return args;
	}

	@Test
	void rejectedAndUnknownAttemptsRemainRecordedAfterFailureOrCancellation() {
		for (Operation.State terminal : new Operation.State[]{Operation.State.FAILED, Operation.State.CANCELLED}) {
			Operation op = operation("set_block", 2);
			op.recordUndo(new Operation.UndoEntry(1, 2, 3, null, null, false, false));
			op.recordUndo(new Operation.UndoEntry(4, 5, 6, null, null, true, true));
			op.finish(1, terminal, "WRITE_OUTCOME_UNKNOWN", "unknown", null);
			JsonObject journal = op.snapshotJson().getAsJsonObject("undo_log");
			assertEquals(2, journal.get("recorded").getAsInt());
			assertEquals(1, journal.get("unsafe").getAsInt());
			assertEquals(1, journal.get("outcome_unknown").getAsInt());
			assertEquals(4, op.undoJournal.get(1).x());
			assertTrue(journal.get("source_operation_id").isJsonNull());
		}
	}

	@Test
	void terminalUndoReleasesSourceReferenceButKeepsItsIdentity() {
		Operation source = operation("set_block", 1);
		Operation undo = operation("undo_operation", 1);
		undo.undoSource = source;
		undo.undoSourceId = source.id;
		undo.finish(10, Operation.State.CANCELLED, null, null, null);
		assertNull(undo.undoSource);
		assertEquals(source.id, undo.snapshotJson().getAsJsonObject("undo_log")
				.get("source_operation_id").getAsString());
	}

	@Test
	void undoRejectsMissingSourceAndRespectsWriteAuthorization() {
		OperationManager allowed = new OperationManager(new GameAccess(null), "session", null, true);
		assertEquals("OPERATION_NOT_FOUND", assertThrows(BridgeException.class,
				() -> allowed.acceptAsync("undo_operation", arguments())).code);
		OperationManager readOnly = new OperationManager(new GameAccess(null), "session", null, false);
		assertEquals("FORBIDDEN", assertThrows(BridgeException.class,
				() -> readOnly.acceptAsync("undo_operation", arguments())).code);
		JsonObject stale = arguments();
		stale.addProperty("world_session_id", "old-session");
		assertEquals("WORLD_SESSION_MISMATCH", assertThrows(BridgeException.class,
				() -> allowed.acceptAsync("undo_operation", stale)).code);
	}
}
