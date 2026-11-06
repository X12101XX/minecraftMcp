package dev.mcmcp;

import dev.mcmcp.game.GameSession;
import dev.mcmcp.visual.VisualEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server entrypoint. The bridge follows the integrated or dedicated server lifecycle and never
 * touches a client-only world.
 */
public final class MinecraftMcpMod implements ModInitializer {
	public static final String MOD_ID = "minecraft-mcp";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static GameSession session;

	@Override
	public void onInitialize() {
		VisualEvents.registerPayload();
		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			GameSession current = session;
			session = null;
			if (current != null) {
				current.shutdown();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
		LOGGER.info("Minecraft MCP bridge initialized");
	}

	private void onServerStarted(MinecraftServer server) {
		GameSession previous = session;
		if (previous != null) {
			previous.shutdown();
		}
		session = GameSession.start(server);
	}

	private void onServerStopping(MinecraftServer server) {
		GameSession current = session;
		if (current != null) {
			current.shutdown();
		}
	}

	private void onEndTick(MinecraftServer server) {
		GameSession current = session;
		if (current != null) {
			try {
				current.tick();
			} catch (Throwable t) {
				LOGGER.error("MCP tick failed; disabling bridge to protect the server", t);
				current.shutdown();
				session = null;
			}
		}
	}
}
