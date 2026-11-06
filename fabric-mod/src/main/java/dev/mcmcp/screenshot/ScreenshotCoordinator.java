package dev.mcmcp.screenshot;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Server-thread-only capture state for one bridge world session. */
public final class ScreenshotCoordinator {
	private static final Map<MinecraftServer, ScreenshotCoordinator> INSTANCES = new HashMap<>();
	private static final long TIMEOUT = 30_000_000_000L, RETENTION = 60_000_000_000L;
	private static final int LIMIT = 4;
	private final MinecraftServer server;
	private final String session;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Capture> captures = new HashMap<>();
	private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(LIMIT), runnable -> {
		Thread thread = new Thread(runnable, "mcp-screenshot-validation"); thread.setDaemon(true); return thread;
	});

	public enum State { PENDING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
	public record Status(String id, UUID player, State state, String error, int bytes) {}
	/** Server-side camera pose applied to the dedicated camera account before capture. */
	public record CameraPose(String dimension, double x, double y, double z, float yaw, float pitch) {}
	private static final class Capture {
		final String id, requestId; final UUID player; final ServerPlayer connectionPlayer; final long deadline;
		State state = State.PENDING; String error = ""; long expires; int total; boolean validating;
		ByteArrayOutputStream data;
		Capture(String id, String requestId, ServerPlayer player, long deadline) { this.id = id; this.requestId = requestId; this.player = player.getUUID(); this.connectionPlayer = player; this.deadline = deadline; }
	}
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(ScreenshotPayload.Request.TYPE, ScreenshotPayload.Request.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ScreenshotPayload.Reply.TYPE, ScreenshotPayload.Reply.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ScreenshotPayload.Reply.TYPE, (payload, context) -> context.server().execute(() -> {
			ScreenshotCoordinator coordinator = INSTANCES.get(context.server());
			if (coordinator != null) coordinator.receive(context.player(), payload);
		}));
	}
	public ScreenshotCoordinator(MinecraftServer server, String session) {
		if (session == null || session.isEmpty() || session.length() > 128) throw new IllegalArgumentException("invalid world session");
		this.server = server; this.session = session;
		if (INSTANCES.putIfAbsent(server, this) != null) throw new IllegalStateException("capture coordinator already attached");
	}
	private void checkSession(String requested) { if (!session.equals(requested)) throw new IllegalArgumentException("WORLD_SESSION_MISMATCH"); }
	/** Must run on the server thread. Resolves a camera account by UUID or exact account name. */
	public String startBySelector(String requested, String selector, String requestId) {
		return startBySelector(requested, selector, requestId, null);
	}
	public String startBySelector(String requested, String selector, String requestId, CameraPose pose) {
		checkSession(requested);
		if (selector == null || selector.isBlank()) throw new IllegalArgumentException("CAMERA_PLAYER_REQUIRED");
		ServerPlayer player = null;
		try {
			player = server.getPlayerList().getPlayer(UUID.fromString(selector));
		} catch (IllegalArgumentException ignored) {
			for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
				if (candidate.getGameProfile().name().equalsIgnoreCase(selector)) {
					if (player != null) throw new IllegalArgumentException("PLAYER_REQUIRED");
					player = candidate;
				}
			}
		}
		if (player == null) throw new IllegalArgumentException("PLAYER_NOT_FOUND");
		return start(requested, player.getUUID(), requestId, pose);
	}
	/** Must run on the server thread. A canonical UUID request ID makes retries idempotent. */
	public String start(String requested, UUID playerId, String requestId) {
		return start(requested, playerId, requestId, null);
	}
	public String start(String requested, UUID playerId, String requestId, CameraPose pose) {
		checkSession(requested); tick();
		if (playerId == null) throw new IllegalArgumentException("PLAYER_REQUIRED");
		try {
			if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException();
		} catch (IllegalArgumentException e) { throw new IllegalArgumentException("INVALID_REQUEST_ID"); }
		for (Capture capture : captures.values()) if (capture.requestId.equals(requestId)) {
			if (!capture.player.equals(playerId)) throw new IllegalArgumentException("REQUEST_ID_CONFLICT");
			return capture.id;
		}
		if (captures.size() >= LIMIT) throw new IllegalStateException("CAPTURE_LIMIT");
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null) throw new IllegalArgumentException("PLAYER_NOT_FOUND");
		if (!ServerPlayNetworking.canSend(player, ScreenshotPayload.Request.TYPE)) throw new IllegalStateException("CLIENT_CAPTURE_UNAVAILABLE");
		for (Capture capture : captures.values()) if (capture.player.equals(playerId) && capture.state == State.PENDING) throw new IllegalStateException("PLAYER_CAPTURE_BUSY");
		if (pose != null) applyPose(player, pose);
		byte[] nonce = new byte[18]; random.nextBytes(nonce); String id = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
		Capture capture = new Capture(id, requestId, player, System.nanoTime() + TIMEOUT); captures.put(id, capture);
		try { ServerPlayNetworking.send(player, new ScreenshotPayload.Request(session, id, ScreenshotPayload.Command.REQUEST)); }
		catch (RuntimeException e) { captures.remove(id); throw e; }
		return id;
	}
	private void applyPose(ServerPlayer player, CameraPose pose) {
		ServerLevel level = null;
		for (ServerLevel candidate : server.getAllLevels()) {
			if (candidate.dimension().identifier().toString().equals(pose.dimension())) { level = candidate; break; }
		}
		if (level == null) throw new IllegalArgumentException("DIMENSION_NOT_FOUND");
		int minY = level.dimensionType().minY();
		int maxY = minY + level.dimensionType().height();
		if (pose.y() < minY || pose.y() >= maxY) throw new IllegalArgumentException("OUT_OF_BOUNDS");
		double minX = level.getWorldBorder().getMinX(), maxX = level.getWorldBorder().getMaxX();
		double minZ = level.getWorldBorder().getMinZ(), maxZ = level.getWorldBorder().getMaxZ();
		if (pose.x() < minX || pose.x() >= maxX || pose.z() < minZ || pose.z() >= maxZ) {
			throw new IllegalArgumentException("OUT_OF_BOUNDS");
		}
		int chunkX = (int) Math.floor(pose.x()) >> 4, chunkZ = (int) Math.floor(pose.z()) >> 4;
		if (!level.getChunkSource().hasChunk(chunkX, chunkZ) || level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
			throw new IllegalArgumentException("CHUNK_NOT_LOADED");
		}
		if (!player.isSpectator() && !player.setGameMode(GameType.SPECTATOR)) throw new IllegalArgumentException("SPECTATOR_UNAVAILABLE");
		if (!player.isSpectator()) throw new IllegalArgumentException("SPECTATOR_UNAVAILABLE");
		if (!player.teleportTo(level, pose.x(), pose.y(), pose.z(), Set.of(), pose.yaw(), pose.pitch(), false)) {
			throw new IllegalArgumentException("TELEPORT_REJECTED");
		}
	}
	public Status status(String requested, String id) { checkSession(requested); tick(); Capture c = require(id); return new Status(c.id, c.player, c.state, c.error, c.data == null ? 0 : c.data.size()); }
	public void cancel(String requested, String id) { checkSession(requested); tick(); Capture c = require(id); if (c.state == State.PENDING) { finish(c, State.CANCELLED, "CANCELLED"); sendCancel(c); } }
	/** Return a defensive copy of the successful PNG until its retention deadline. */
	public byte[] takeResult(String requested, String id) { checkSession(requested); tick(); Capture c = require(id); if (c.state != State.SUCCEEDED) throw new IllegalStateException("RESULT_UNAVAILABLE"); return c.data.toByteArray(); }
	private Capture require(String id) { Capture c = captures.get(id); if (c == null) throw new IllegalArgumentException("CAPTURE_NOT_FOUND"); return c; }
	public void tick() {
		long now = System.nanoTime(); Iterator<Capture> iterator = captures.values().iterator();
		while (iterator.hasNext()) { Capture c = iterator.next();
			if (c.state == State.PENDING && now >= c.deadline) { finish(c, State.TIMED_OUT, "TIMEOUT"); sendCancel(c); }
			else if (c.state == State.PENDING && server.getPlayerList().getPlayer(c.player) != c.connectionPlayer) finish(c, State.FAILED, "PLAYER_DISCONNECTED");
			if (c.state != State.PENDING && now >= c.expires) iterator.remove();
		}
	}
	public void shutdown() { for (Capture c : captures.values()) if (c.state == State.PENDING) sendCancel(c); captures.clear(); decoder.shutdownNow(); INSTANCES.remove(server, this); }
	private void finish(Capture c, State state, String error) { c.state = state; c.error = error; c.expires = System.nanoTime() + RETENTION; if (state != State.SUCCEEDED) c.data = null; }
	private void sendCancel(Capture c) { ServerPlayer player = server.getPlayerList().getPlayer(c.player); if (player == c.connectionPlayer && ServerPlayNetworking.canSend(player, ScreenshotPayload.Request.TYPE)) ServerPlayNetworking.send(player, new ScreenshotPayload.Request(session, c.id, ScreenshotPayload.Command.CANCEL)); }
	private void receive(ServerPlayer player, ScreenshotPayload.Reply reply) {
		if (!session.equals(reply.session())) return;
		Capture c = captures.get(reply.id());
		if (c == null || c.state != State.PENDING || c.connectionPlayer != player || server.getPlayerList().getPlayer(c.player) != player) return;
		if (System.nanoTime() >= c.deadline) { finish(c, State.TIMED_OUT, "TIMEOUT"); sendCancel(c); return; }
		if (c.validating) return;
		if (reply.response() == ScreenshotPayload.Response.TIMEOUT) { finish(c, State.TIMED_OUT, "TIMEOUT"); return; }
		if (reply.response() == ScreenshotPayload.Response.ERROR) { finish(c, State.FAILED, "CLIENT_CAPTURE_FAILED"); return; }
		if (c.data == null) { if (reply.offset() != 0) { finish(c, State.FAILED, "INVALID_IMAGE_SEQUENCE"); return; } c.total = reply.total(); c.data = new ByteArrayOutputStream(c.total); }
		if (reply.total() != c.total || reply.offset() != c.data.size()) { finish(c, State.FAILED, "INVALID_IMAGE_SEQUENCE"); return; }
		c.data.writeBytes(reply.data());
		if (c.data.size() == c.total) validate(c);
	}
	private void validate(Capture c) {
		c.validating = true;
		byte[] bytes = c.data.toByteArray();
		try {
			decoder.execute(() -> {
				boolean valid = validPng(bytes);
				server.execute(() -> {
					if (captures.get(c.id) != c || c.state != State.PENDING) return;
					if (System.nanoTime() >= c.deadline) { finish(c, State.TIMED_OUT, "TIMEOUT"); return; }
					if (server.getPlayerList().getPlayer(c.player) != c.connectionPlayer) { finish(c, State.FAILED, "PLAYER_DISCONNECTED"); return; }
					finish(c, valid ? State.SUCCEEDED : State.FAILED, valid ? "" : "INVALID_IMAGE");
				});
			});
		} catch (RejectedExecutionException e) { finish(c, State.FAILED, "CAPTURE_VALIDATION_BUSY"); }
	}
	private static boolean validPng(byte[] bytes) {
		byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
		if (bytes.length < 33) return false;
		for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
		try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
			if (!readers.hasNext()) return false;
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0), height = reader.getHeight(0);
				return width > 0 && height > 0 && (long) width * height <= 8_000_000L && reader.read(0) != null;
			} finally { reader.dispose(); }
		} catch (IOException | RuntimeException e) { return false; }
	}
}
