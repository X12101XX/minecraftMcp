package dev.mcmcp.game;

import dev.mcmcp.MinecraftMcpMod;
import dev.mcmcp.bridge.BridgeServer;
import dev.mcmcp.bridge.CursorCodec;
import dev.mcmcp.config.BridgeConfig;
import dev.mcmcp.operation.OperationManager;
import net.minecraft.server.MinecraftServer;

import java.security.SecureRandom;
import java.util.Base64;

/** One live server world lifecycle. All mutable Minecraft access is driven from {@link #tick()}. */
public final class GameSession {
	private final String sessionId;
	private final OperationManager operations;
	private BridgeServer bridge;
	private boolean stopped;

	private GameSession(MinecraftServer server, BridgeConfig config) {
		byte[] sessionBytes = new byte[12];
		byte[] cursorSecret = new byte[32];
		SecureRandom random = new SecureRandom();
		random.nextBytes(sessionBytes);
		random.nextBytes(cursorSecret);
		this.sessionId = "s-" + Base64.getUrlEncoder().withoutPadding().encodeToString(sessionBytes);
		GameAccess access = new GameAccess(server);
		this.operations = new OperationManager(access, sessionId, new CursorCodec(cursorSecret, sessionId),
				config.allowWrites);
	}

	public static GameSession start(MinecraftServer server) {
		BridgeConfig config;
		try {
			config = BridgeConfig.load();
		} catch (Exception e) {
			MinecraftMcpMod.LOGGER.error("Could not load Minecraft MCP configuration; bridge is disabled", e);
			return null;
		}
		GameSession session = new GameSession(server, config);
		try {
			session.bridge = new BridgeServer(config, session.operations);
			session.bridge.start();
			MinecraftMcpMod.LOGGER.info("Minecraft MCP bridge listening on {} (session {})",
					config.url(), session.sessionId);
			MinecraftMcpMod.LOGGER.info("MCP client credential file: {}", config.credentialsPath);
			if (!config.allowWrites) {
				MinecraftMcpMod.LOGGER.warn("Minecraft MCP bridge is read-only (allow_writes=false)");
			}
		} catch (Exception e) {
			MinecraftMcpMod.LOGGER.error("Could not start Minecraft MCP bridge on {}; world access remains offline",
					config.url(), e);
			return null;
		}
		return session;
	}

	public void tick() {
		if (!stopped) {
			operations.tick();
		}
	}

	public void shutdown() {
		if (stopped) {
			return;
		}
		stopped = true;
		try {
			operations.shutdown();
		} catch (Throwable t) {
			MinecraftMcpMod.LOGGER.error("Error while closing MCP operations", t);
		}
		if (bridge != null) {
			bridge.stop();
			bridge = null;
		}
		MinecraftMcpMod.LOGGER.info("Minecraft MCP bridge stopped for session {}", sessionId);
	}
}
