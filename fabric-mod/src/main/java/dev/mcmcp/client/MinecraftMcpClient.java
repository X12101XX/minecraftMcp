package dev.mcmcp.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mcmcp.visual.VisualPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Client-only transient overlays. No ghost is ever installed in ClientLevel or sent as a world edit. */
public final class MinecraftMcpClient implements ClientModInitializer {
	private static final VisualPayload.Entry[] NO_GHOSTS = new VisualPayload.Entry[0];
	private static final Direction[] FACES = Direction.values();
	private static final int[][] BOX_FACES = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
	private final LinkedHashMap<String, Selection> selections = new LinkedHashMap<>();
	private final LinkedHashMap<String, Long> tools = new LinkedHashMap<>();
	private String session = "", dimension = "", previewId = "", status = "";
	private long sequence = -1, previewExpires, statusExpires;
	private ClientLevel level;
	private VisualPayload.Entry[] ghosts = NO_GHOSTS;
	private int processed, total;
	private int ghostCursor;
	private boolean dirty = true;
	private Object modelSet;
	private final LinkedHashMap<BlockPos, GhostModel> models = new LinkedHashMap<>();
	private volatile Frame frame = new Frame(List.of(), List.of());

	@Override public void onInitializeClient() {
		new ScreenshotClient().register();
		ClientPlayNetworking.registerGlobalReceiver(VisualPayload.TYPE, (payload, context) -> context.client().execute(() -> receive(payload)));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level != level) { reset(); level = client.level; dimension = level == null ? "" : level.dimension().identifier().toString(); }
			long now = System.nanoTime();
			dirty |= selections.values().removeIf(s -> now >= s.expires);
			tools.values().removeIf(expiry -> now >= expiry);
			if (ghosts.length > 0) { dirty = true; if (now >= previewExpires) clearGhosts(); }
		});
		LevelExtractionEvents.END_EXTRACTION.register(context -> extract(context.level()));
		LevelRenderEvents.COLLECT_SUBMITS.register(this::render);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minecraft-mcp", "activity"), (gui, delta) -> {
			Minecraft client = Minecraft.getInstance();
			if (client.level == null || client.gui.hud.isHidden() || (tools.isEmpty() && System.nanoTime() >= statusExpires)) return;
			int x = 10, y = 10, width = Math.min(340, gui.guiWidth() - 20);
			gui.fill(x, y, x + width, y + 41, 0xB0121823);
			gui.fill(x, y, x + 2, y + 41, 0xFF63E5F5);
			gui.text(client.font, "MCP  /  AGENT ACTIVITY", x + 8, y + 6, 0xFF9CF3FF);
			String recent = String.join(" / ", tools.keySet());
			gui.text(client.font, client.font.plainSubstrByWidth(recent, width - 16), x + 8, y + 18, 0xFFD7E5ED);
			if (System.nanoTime() < statusExpires) {
				gui.text(client.font, status, x + 8, y + 29, status.contains("failed") ? 0xFFFF7777 : 0xFF9EE8B3);
				if (total > 0) gui.fill(x + 2, y + 39, x + 2 + (int) ((width - 2L) * processed / total), y + 41, 0xFF63E5F5);
			}
		});
	}

	private void receive(VisualPayload p) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		if (client.level != level) { reset(); level = client.level; dimension = level.dimension().identifier().toString(); }
		if (!p.dimension().isEmpty() && !dimension.equals(p.dimension())) return;
		if (!session.equals(p.session())) { reset(); level = client.level; dimension = level.dimension().identifier().toString(); session = p.session(); }
		if (p.sequence() <= sequence) return;
		sequence = p.sequence();
		long expires = System.nanoTime() + p.duration() * 50_000_000L;
		switch (p.kind()) {
			case RESET -> reset();
			case NOTICE -> {
				tools.remove(p.style()); tools.put(p.style(), expires);
				while (tools.size() > 3) tools.remove(tools.keySet().iterator().next());
				if (p.style().startsWith("failed ")) { status = p.style(); statusExpires = expires; processed = 0; total = 0; }
			}
			case RANGE -> {
				dirty = true;
				selections.remove(p.id());
				selections.put(p.id(), new Selection(p.min(), Shapes.box(-0.003, -0.003, -0.003, p.max().getX() - (double) p.min().getX() + 1.003, p.max().getY() - (double) p.min().getY() + 1.003, p.max().getZ() - (double) p.min().getZ() + 1.003), p.style(), System.nanoTime(), expires));
				while (selections.size() > 64) selections.remove(selections.keySet().iterator().next());
			}
			case PREVIEW -> {
				dirty = true;
				if (p.offset() == 0) { clearGhosts(); previewId = p.id(); ghosts = new VisualPayload.Entry[p.total()]; }
				if (!previewId.equals(p.id()) || ghosts.length != p.total()) return;
				for (int i = 0; i < p.entries().size(); i++) ghosts[p.offset() + i] = p.entries().get(i);
				previewExpires = expires;
			}
			case PROGRESS -> {
				processed = p.processed(); total = p.total(); status = p.style() + "  " + processed + " / " + total; statusExpires = expires;
				if (previewId.equals(p.id())) {
					previewExpires = expires;
					int end = Math.min(processed, ghosts.length);
					if (end > ghostCursor) dirty = true;
					for (; ghostCursor < end; ghostCursor++) if (ghosts[ghostCursor] != null) { models.remove(ghosts[ghostCursor].pos()); ghosts[ghostCursor] = null; }
				}
			}
			case CLEAR -> {
				dirty |= selections.remove(p.id()) != null;
				if (previewId.equals(p.id())) clearGhosts();
			}
		}
	}

	private void clearGhosts() { ghosts = NO_GHOSTS; models.clear(); previewId = ""; ghostCursor = 0; dirty = true; }
	private void reset() {
		selections.clear(); tools.clear(); clearGhosts(); session = ""; sequence = -1; status = ""; statusExpires = 0;
		frame = new Frame(List.of(), List.of()); modelSet = null;
	}

	/** Extraction owns all ClientLevel/model access; render callbacks consume frozen value data only. */
	private void extract(ClientLevel world) {
		Minecraft client = Minecraft.getInstance();
		Object currentModels = client.getModelManager().getBlockStateModelSet();
		if (currentModels != modelSet) { models.clear(); modelSet = currentModels; dirty = true; }
		if (!dirty) return;
		dirty = false;
		List<GhostModel> visible = new ArrayList<>();
		for (VisualPayload.Entry ghost : ghosts) {
			if (ghost == null || !world.hasChunkAt(ghost.pos())) continue;
			GhostModel model = models.get(ghost.pos());
			if (model == null) {
				List<BlockStateModelPart> parts = new ArrayList<>();
				client.getModelManager().getBlockStateModelSet().get(ghost.state()).collectParts(RandomSource.create(ghost.pos().asLong()), parts);
				List<ColoredQuad> quads = new ArrayList<>();
				for (BlockStateModelPart part : parts) {
					collectQuads(client, world, ghost, part.getQuads(null), quads);
					for (Direction face : FACES) collectQuads(client, world, ghost, part.getQuads(face), quads);
				}
				VoxelShape shape = ghost.state().isAir() ? Shapes.block() : ghost.state().getShape(world, ghost.pos());
				if (shape.isEmpty() && !ghost.state().getFluidState().isEmpty()) shape = ghost.state().getFluidState().getShape(world, ghost.pos());
				model = new GhostModel(ghost.pos(), List.copyOf(quads), shape, ghost.state().isAir(), quads.isEmpty() && !ghost.state().isAir() ? List.copyOf(shape.toAabbs()) : List.of()); models.put(ghost.pos(), model);
			}
			visible.add(model);
		}
		frame = new Frame(List.copyOf(selections.values()), List.copyOf(visible));
	}

	private static void collectQuads(Minecraft client, ClientLevel level, VisualPayload.Entry ghost, List<BakedQuad> quads, List<ColoredQuad> result) {
		for (BakedQuad quad : quads) {
			int tint = 0xFFFFFF;
			if (quad.materialInfo().isTinted()) {
				var source = client.getBlockColors().getTintSource(ghost.state(), quad.materialInfo().tintIndex());
				if (source != null) tint = source.colorInWorld(ghost.state(), level, ghost.pos());
			}
			QuadInstance instance = new QuadInstance(); instance.setColor(0x66000000 | (tint & 0xFFFFFF)); instance.setLightCoords(0xF000F0); instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
			result.add(new ColoredQuad(quad, instance));
		}
	}

	private void render(LevelRenderContext context) {
		Frame current = frame;
		Vec3 camera = context.levelState().cameraRenderState.pos;
		PoseStack pose = context.poseStack();
		long now = System.nanoTime();
		for (Selection selection : current.selections) {
			if (now >= selection.expires) continue;
			float remaining = (float) (selection.expires - now) / (selection.expires - selection.created);
			int alpha = (int) (255 * Math.min(1, remaining * 3));
			int rgb = switch (selection.style) { case "error", "remove" -> 0xFF6B75; case "place" -> 0x78FFAD; case "unchanged" -> 0xE9C86D; default -> 0x75E9FF; };
			pose.pushPose(); pose.translate(selection.pos.getX() - camera.x, selection.pos.getY() - camera.y, selection.pos.getZ() - camera.z);
			context.submitNodeCollector().submitShapeOutline(pose, selection.shape, RenderTypes.linesTranslucentNoDepthWrite(), (alpha << 24) | rgb, 3.0F, false);
			pose.popPose();
		}
		for (GhostModel ghost : current.ghosts) {
			if (ghost.pos.distToCenterSqr(camera) > 192 * 192) continue;
			pose.pushPose(); pose.translate(ghost.pos.getX() - camera.x, ghost.pos.getY() - camera.y, ghost.pos.getZ() - camera.z);
			if (!ghost.boxes.isEmpty()) context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugQuads(), (matrix, buffer) -> {
				for (AABB box : ghost.boxes) drawBox(matrix, buffer, box);
			});
			if (!ghost.quads.isEmpty()) context.submitNodeCollector().submitCustomGeometry(pose, RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS), (matrix, buffer) -> {
				for (ColoredQuad quad : ghost.quads) buffer.putBakedQuad(matrix, quad.quad, quad.instance);
			});
			context.submitNodeCollector().submitShapeOutline(pose, ghost.shape.isEmpty() ? Shapes.block() : ghost.shape, RenderTypes.linesTranslucentNoDepthWrite(), ghost.removal ? 0xBBFF6B75 : 0x8875E9FF, 1.5F, false);
			pose.popPose();
		}
	}

	private static void drawBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box) {
		for (int[] face : BOX_FACES) for (int vertex : face) {
			float x = (float) ((vertex & 1) == 0 ? box.minX : box.maxX);
			float y = (float) ((vertex & 2) == 0 ? box.minY : box.maxY);
			float z = (float) ((vertex & 4) == 0 ? box.minZ : box.maxZ);
			buffer.addVertex(pose, x, y, z).setColor(0x5575E9FF);
		}
	}

	private record Selection(BlockPos pos, VoxelShape shape, String style, long created, long expires) {}
	private record ColoredQuad(BakedQuad quad, QuadInstance instance) {}
	private record GhostModel(BlockPos pos, List<ColoredQuad> quads, VoxelShape shape, boolean removal, List<AABB> boxes) {}
	private record Frame(List<Selection> selections, List<GhostModel> ghosts) {}
}
