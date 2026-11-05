package dev.mcmcp.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcmcp.MinecraftMcpMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Configuration is deliberately local and boring: one non-secret settings file plus one credential
 * file readable only by the current user.
 */
public final class BridgeConfig {
	public static final int DEFAULT_PORT = 8765;
	private static final int MAX_PORT = 65535;

	public final int port;
	public final boolean allowWrites;
	public final String token;
	public final Path credentialsPath;

	private BridgeConfig(int port, boolean allowWrites, String token, Path credentialsPath) {
		this.port = port;
		this.allowWrites = allowWrites;
		this.token = token;
		this.credentialsPath = credentialsPath;
	}

	public String url() {
		return "http://127.0.0.1:" + port;
	}

	public static BridgeConfig load() throws IOException {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Files.createDirectories(configDir);
		Path settingsPath = configDir.resolve("minecraft-mcp.json");
		int port = DEFAULT_PORT;
		boolean allowWrites = true;
		if (Files.isRegularFile(settingsPath)) {
			try {
				JsonObject settings = JsonParser.parseString(Files.readString(settingsPath, StandardCharsets.UTF_8))
						.getAsJsonObject();
				if (settings.has("port")) {
					int candidate = settings.get("port").getAsInt();
					if (candidate >= 1 && candidate <= MAX_PORT) {
						port = candidate;
					} else {
						MinecraftMcpMod.LOGGER.warn("Invalid minecraft-mcp.json port; using {}", DEFAULT_PORT);
					}
				}
				if (settings.has("allow_writes")) {
					allowWrites = settings.get("allow_writes").getAsBoolean();
				}
			} catch (RuntimeException e) {
				MinecraftMcpMod.LOGGER.warn("Could not parse {}; using defaults", settingsPath, e);
			}
		} else {
			JsonObject settings = new JsonObject();
			settings.addProperty("port", DEFAULT_PORT);
			settings.addProperty("allow_writes", true);
			Files.writeString(settingsPath, dev.mcmcp.bridge.Json.GSON.toJson(settings) + System.lineSeparator(),
					StandardCharsets.UTF_8);
		}

		Path credentialsPath = configDir.resolve("minecraft-mcp.credentials.json");
		String token = null;
		if (Files.isRegularFile(credentialsPath)) {
			try {
				JsonObject credentials = JsonParser.parseString(Files.readString(credentialsPath, StandardCharsets.UTF_8))
						.getAsJsonObject();
				if (credentials.has("token") && credentials.get("token").isJsonPrimitive()) {
					String candidate = credentials.get("token").getAsString();
					if (candidate.length() >= 32) {
						token = candidate;
					}
				}
			} catch (RuntimeException e) {
				MinecraftMcpMod.LOGGER.warn("Could not parse {}; generating a new token", credentialsPath, e);
			}
		}
		if (token == null) {
			byte[] bytes = new byte[32];
			new SecureRandom().nextBytes(bytes);
			token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		}
		JsonObject credentials = new JsonObject();
		credentials.addProperty("url", "http://127.0.0.1:" + port);
		credentials.addProperty("token", token);
		credentials.addProperty("allow_writes", allowWrites);
		Files.writeString(credentialsPath, dev.mcmcp.bridge.Json.GSON.toJson(credentials) + System.lineSeparator(),
				StandardCharsets.UTF_8);
		try {
			Files.setPosixFilePermissions(credentialsPath,
					Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		} catch (UnsupportedOperationException ignored) {
			// Windows: the per-user profile directory is the practical boundary. README documents it.
		}
		return new BridgeConfig(port, allowWrites, token, credentialsPath);
	}
}
