package dev.mcmcp.operation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reference-counts chunk tickets added by explicit preload operations.
 *
 * <p>The engine keys tickets by {@code TicketType} and chunk position, so two preloads that cover
 * the same chunk share one ticket. A successful preload keeps that ticket until its timeout; a
 * failed or cancelled preload must not remove a ticket that another preload still relies on.
 *
 * <p>Not thread-safe: owned by {@link OperationManager} and used only on the server thread.
 */
public final class PreloadTicketLeases {
	public static final int DEFAULT_CAPACITY = 16_384;
	private static final int PRUNE_INTERVAL_TICKS = 200;
	private static final int MAX_PRUNE_PER_CALL = 512;

	private final int capacity;
	private final int retentionTicks;
	private final Map<Long, Entry> entries = new HashMap<>();
	private int lastPruneTick;

	private static final class Entry {
		int live;
		boolean retained;
		int lastAcquiredTick;
	}

	public PreloadTicketLeases(int capacity, int retentionTicks) {
		if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
		if (retentionTicks < 0) throw new IllegalArgumentException("retentionTicks must not be negative");
		this.capacity = capacity;
		this.retentionTicks = retentionTicks;
	}

	/**
	 * Registers one live lease. The caller must add or refresh the engine ticket when this returns
	 * true; a false result means the tracked-chunk capacity is exhausted and no ticket was added.
	 */
	public boolean acquire(long chunk, int tick) {
		Entry entry = entries.get(chunk);
		if (entry == null) {
			if (entries.size() >= capacity) {
				return false;
			}
			entry = new Entry();
			entries.put(chunk, entry);
		}
		if (entry.retained && (tick - entry.lastAcquiredTick > retentionTicks || tick < entry.lastAcquiredTick)) {
			entry.retained = false;
		}
		entry.live++;
		entry.lastAcquiredTick = tick;
		return true;
	}

	/**
	 * Releases one live lease. Returns true only when the caller must remove the engine ticket:
	 * no live lease remains and no successful preload retained it.
	 */
	public boolean release(long chunk, boolean retain) {
		Entry entry = entries.get(chunk);
		if (entry == null) {
			return false;
		}
		if (entry.live > 0) {
			entry.live--;
		}
		if (retain) {
			entry.retained = true;
		}
		if (entry.live == 0 && !entry.retained) {
			entries.remove(chunk);
			return true;
		}
		return false;
	}

	/**
	 * Drops retained entries whose ticket timeout has certainly elapsed and returns their chunk
	 * positions so the caller can remove any engine ticket still present.
	 */
	public List<Long> prune(int tick) {
		if (tick - lastPruneTick < PRUNE_INTERVAL_TICKS && tick >= lastPruneTick) {
			return List.of();
		}
		lastPruneTick = tick;
		List<Long> removed = new ArrayList<>();
		Iterator<Map.Entry<Long, Entry>> iterator = entries.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, Entry> candidate = iterator.next();
			Entry entry = candidate.getValue();
			if (entry.live == 0 && entry.retained && (tick - entry.lastAcquiredTick > retentionTicks || tick < entry.lastAcquiredTick)) {
				removed.add(candidate.getKey());
				iterator.remove();
				if (removed.size() >= MAX_PRUNE_PER_CALL) {
					break;
				}
			}
		}
		return removed;
	}

	/** Removes every entry and returns the chunk positions whose tickets the caller must remove. */
	public List<Long> drain() {
		List<Long> chunks = new ArrayList<>(entries.keySet());
		entries.clear();
		return chunks;
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public int size() {
		return entries.size();
	}
}
