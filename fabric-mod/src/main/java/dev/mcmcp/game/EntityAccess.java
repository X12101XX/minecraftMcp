package dev.mcmcp.game;

import com.google.gson.JsonObject;
import dev.mcmcp.bridge.BridgeException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/** Server-thread-only lookup of a single entity in the level's accessible entity index. */
public final class EntityAccess {
	private EntityAccess() {
	}

	/**
	 * Looks up a UUID in one loaded dimension without loading chunks or entity data.
	 * A missing result cannot distinguish an absent entity from one that is not accessible.
	 */
	public static JsonObject getEntity(GameAccess access, String dimension, String entityUuid)
			throws BridgeException {
		if (!access.server().isSameThread()) {
			throw new IllegalStateException("entity lookup must run on the server thread");
		}
		UUID uuid;
		try {
			uuid = UUID.fromString(entityUuid);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw BridgeException.invalid("entity_uuid must be a UUID");
		}
		if (!uuid.toString().equals(entityUuid)) {
			throw BridgeException.invalid("entity_uuid must be a lowercase canonical UUID");
		}
		ServerLevel level = access.level(dimension);
		Entity entity = level.getEntity(uuid);
		if (entity == null) {
			JsonObject details = new JsonObject();
			details.addProperty("dimension", GameAccess.dimensionId(level));
			details.addProperty("entity_uuid", uuid.toString());
			throw new BridgeException("ENTITY_NOT_FOUND_OR_UNLOADED", 404,
					"entity is not in the dimension's accessible entity index", false, details);
		}

		JsonObject position = new JsonObject();
		position.addProperty("x", entity.getX());
		position.addProperty("y", entity.getY());
		position.addProperty("z", entity.getZ());
		JsonObject result = new JsonObject();
		result.addProperty("entity_uuid", entity.getUUID().toString());
		result.addProperty("entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		result.addProperty("dimension", GameAccess.dimensionId(level));
		result.add("position", position);
		result.addProperty("observed_tick", access.tick());
		return result;
	}
}
