package dev.mcmcp;

import dev.mcmcp.visual.VisualPayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VisualPayloadTest {
	@BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

	@Test void previewRoundTripPreservesFullDirectionalStateAndNegativeCoordinates() {
		var state = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP);
		var entry = new VisualPayload.Entry(new BlockPos(-17, 75, -1), state);
		var original = new VisualPayload("session", "minecraft:overworld", "operation", 14,
				VisualPayload.Kind.PREVIEW, 200, BlockPos.ZERO, BlockPos.ZERO, "preview", 128, 129, 0, List.of(entry));
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			VisualPayload.CODEC.encode(buffer, original);
			assertEquals(original, VisualPayload.CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes());
		} finally { buffer.release(); }
	}

	@Test void rejectsPreviewPagesBeyondBoundOrBeyondDeclaredPlan() {
		var entry = new VisualPayload.Entry(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
		assertThrows(IllegalArgumentException.class, () -> new VisualPayload("s", "d", "o", 1,
				VisualPayload.Kind.PREVIEW, 200, BlockPos.ZERO, BlockPos.ZERO, "preview", 0, 129, 0, Collections.nCopies(129, entry)));
		assertThrows(IllegalArgumentException.class, () -> new VisualPayload("s", "d", "o", 1,
				VisualPayload.Kind.PREVIEW, 200, BlockPos.ZERO, BlockPos.ZERO, "preview", 1, 1, 0, List.of(entry)));
	}

	@Test void rejectsInvertedAndOverflowingDisplayRanges() {
		for (BlockPos max : List.of(new BlockPos(-1, 0, 0), new BlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE))) {
			assertThrows(IllegalArgumentException.class, () -> new VisualPayload("s", "d", "o", 1,
					VisualPayload.Kind.RANGE, 30, BlockPos.ZERO, max, "read", 0, 0, 0, List.of()));
		}
	}

	@Test void rejectsOversizedWireCountBeforeReadingAnyBlockEntries() {
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		try {
			buffer.writeUtf("s"); buffer.writeUtf("d"); buffer.writeUtf("o"); buffer.writeVarLong(1);
			buffer.writeEnum(VisualPayload.Kind.PREVIEW); buffer.writeVarInt(200);
			buffer.writeBlockPos(BlockPos.ZERO); buffer.writeBlockPos(BlockPos.ZERO); buffer.writeUtf("preview");
			buffer.writeVarInt(0); buffer.writeVarInt(8192); buffer.writeVarInt(0); buffer.writeVarInt(Integer.MAX_VALUE);
			assertThrows(DecoderException.class, () -> VisualPayload.CODEC.decode(buffer));
			assertEquals(0, buffer.readableBytes());
		} finally { buffer.release(); }
	}
}
