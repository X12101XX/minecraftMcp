package dev.mcmcp;

import com.google.gson.JsonObject;
import dev.mcmcp.bridge.CursorCodec;
import dev.mcmcp.bridge.Json;
import dev.mcmcp.query.BlockStateKey;
import dev.mcmcp.query.RegionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionSnapshotTest {
	private static RegionSnapshot snapshot(String encoding) {
		List<BlockStateKey> palette = List.of(
				new BlockStateKey("minecraft:stone", Map.of()),
				new BlockStateKey("minecraft:air", Map.of()),
				new BlockStateKey("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom")));
		int[] indices = new int[24];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = i % 3;
		}
		return new RegionSnapshot(10, 60, -5, 2, 3, 4, palette, indices, encoding, 100, 101);
	}

	@Test
	void pagesCoverEveryVoxelExactlyOnce() throws Exception {
		for (String encoding : List.of("auto", "palette_indices", "palette_rle")) {
			RegionSnapshot snapshot = snapshot(encoding);
			CursorCodec cursors = new CursorCodec(new byte[32], "s-test");
			int offset = 0;
			int global = 0;
			while (true) {
				RegionSnapshot.Page page = snapshot.page(offset, "op-test", "s-test", "minecraft:overworld",
						"2099-01-01T00:00:00Z", next -> cursors.encode("op-test", "result", next));
				JsonObject json = page.json();
				assertEquals(offset, json.get("offset").getAsInt());
				assertEquals(page.nextOffset(), offset + json.get("count").getAsInt());
				int[] local = RegionSnapshot.decodePage(json);
				List<BlockStateKey> localPalette = RegionSnapshot.paletteFromPage(json);
				for (int value : local) {
					assertEquals(snapshot.palette.get(snapshot.indices[global]), localPalette.get(value));
					global++;
				}
				offset = page.nextOffset();
				if (json.get("next_cursor").isJsonNull()) {
					break;
				}
				String next = json.get("next_cursor").getAsString();
				assertEquals(offset, cursors.decode(next, "op-test", "result"));
			}
			assertEquals(24, global);
			assertArrayEquals(new int[] {10, 60, -5}, snapshot.coordinates(0));
			assertArrayEquals(new int[] {11, 60, -5}, snapshot.coordinates(1));
			assertArrayEquals(new int[] {10, 60, -4}, snapshot.coordinates(2));
			assertArrayEquals(new int[] {10, 61, -5}, snapshot.coordinates(8));
		}
	}

	@Test
	void localPaletteRemapsRepeatedStates() throws Exception {
		RegionSnapshot snapshot = snapshot("palette_rle");
		RegionSnapshot.Page page = snapshot.page(0, "op", "s", "minecraft:overworld",
				"t", next -> "cursor");
		JsonObject json = page.json();
		assertEquals("palette_rle", json.get("encoding").getAsString());
		assertEquals(3, json.getAsJsonArray("palette").size());
		assertEquals(24, json.getAsJsonArray("runs").size());
		assertEquals(1, json.getAsJsonArray("runs").get(0).getAsJsonObject().get("length").getAsInt());
		org.junit.jupiter.api.Assertions.assertTrue(json.get("next_cursor").isJsonNull());
	}

	@Test
	void rejectsOffsetOutsideResult() {
		RegionSnapshot snapshot = snapshot("auto");
		CursorCodec cursors = new CursorCodec(new byte[32], "s");
		assertThrows(dev.mcmcp.bridge.BridgeException.class,
				() -> snapshot.page(24, "op", "s", "d", "t", next -> "c"));
	}

	@Test
	void cursorIsBoundToOperationAndEndpoint() throws Exception {
		CursorCodec codec = new CursorCodec(new byte[32], "session");
		String cursor = codec.encode("op-a", "result", 17);
		assertEquals(17, codec.decode(cursor, "op-a", "result"));
		assertThrows(dev.mcmcp.bridge.BridgeException.class, () -> codec.decode(cursor, "op-b", "result"));
		assertThrows(dev.mcmcp.bridge.BridgeException.class, () -> codec.decode(cursor, "op-a", "errors"));
		assertThrows(dev.mcmcp.bridge.BridgeException.class,
				() -> codec.decode(cursor.substring(0, cursor.length() - 2) + "aa", "op-a", "result"));
	}

	@Test
	void canonicalJsonIgnoresObjectOrderButNotArrayOrder() {
		JsonObject a = Json.GSON.fromJson("{\"b\":2,\"a\":[1,2]}", JsonObject.class);
		JsonObject b = Json.GSON.fromJson("{\"a\":[1,2],\"b\":2}", JsonObject.class);
		JsonObject c = Json.GSON.fromJson("{\"a\":[2,1],\"b\":2}", JsonObject.class);
		assertEquals(Json.canonical(a), Json.canonical(b));
		org.junit.jupiter.api.Assertions.assertNotEquals(Json.canonical(a), Json.canonical(c));
	}

	@Test
	void largeUniquePaletteSplitsIntoBoundedPages() throws Exception {
		int total = 65_536;
		List<BlockStateKey> palette = new ArrayList<>(total);
		int[] indices = new int[total];
		for (int i = 0; i < total; i++) {
			palette.add(new BlockStateKey("test:state_" + i, Map.of("value", Integer.toString(i))));
			indices[i] = i;
		}
		RegionSnapshot snapshot = new RegionSnapshot(0, 0, 0, 256, 256, 1, palette, indices, "auto", 1, 2);
		CursorCodec cursors = new CursorCodec(new byte[32], "s-large");
		int offset = 0;
		int pages = 0;
		int decoded = 0;
		while (true) {
			RegionSnapshot.Page page = snapshot.page(offset, "op-large", "s-large", "minecraft:overworld",
					"2099-01-01T00:00:00Z", next -> cursors.encode("op-large", "result", next));
			pages++;
			JsonObject json = page.json();
			int bytes = Json.GSON.toJson(json).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
			org.junit.jupiter.api.Assertions.assertTrue(bytes <= 512 * 1024, "page too large: " + bytes);
			int[] local = RegionSnapshot.decodePage(json);
			decoded += local.length;
			offset = page.nextOffset();
			if (json.get("next_cursor").isJsonNull()) break;
		}
		assertEquals(total, decoded);
		org.junit.jupiter.api.Assertions.assertTrue(pages > 1, "expected multiple pages");
	}
}
