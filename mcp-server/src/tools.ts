import type { Tool } from "@modelcontextprotocol/sdk/types.js";

const uint = { type: "integer" } as const;
const pos = {
  type: "object",
  properties: { x: uint, y: uint, z: uint },
  required: ["x", "y", "z"],
  additionalProperties: false,
} as const;

const session = { type: "string", minLength: 1 } as const;
const dimension = { type: "string", minLength: 1 } as const;
const requestId = {
  type: "string",
  pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
  description: "Client-generated lowercase UUID. Reuse it only to retry the same logical request.",
} as const;
const cursor = { type: "string", minLength: 1 } as const;

const blockState = {
  type: "object",
  properties: {
    id: { type: "string", description: "Namespaced block id, for example minecraft:oak_stairs." },
    properties: {
      type: "object",
      additionalProperties: { type: "string" },
      description: "State properties such as facing=east or half=bottom. Missing properties use the block's real default state on writes.",
    },
  },
  required: ["id"],
  additionalProperties: false,
} as const;

const exactBlockState = {
  type: "object",
  properties: {
    id: { type: "string" },
    properties: { type: "object", additionalProperties: { type: "string" } },
  },
  required: ["id", "properties"],
  additionalProperties: false,
} as const;

const onError = {
  type: "string",
  enum: ["stop", "continue"],
  default: "stop",
  description: "stop aborts after the first coordinate-level failure; continue records it and proceeds.",
} as const;

const readAnnotations = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: true,
} as const;

const writeAnnotations = {
  readOnlyHint: false,
  destructiveHint: true,
  idempotentHint: true,
  openWorldHint: true,
} as const;

export const TOOLS: Tool[] = [
  {
    name: "get_world_info",
    title: "Get world information",
    description:
      "Bootstrap call. Returns the active world_session_id, loaded dimensions with real height/border bounds, online players, limits, and supported tools. Never requires a session.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: { ...readAnnotations, title: "Get world information" },
  },
  {
    name: "request_screenshot",
    title: "Capture a camera view",
    description: "Captures the configured camera account's current view, or an explicitly named online player when player_uuid is supplied. With camera, the dedicated camera account is switched to spectator and teleported to the exact position/rotation first; this avoids moving a real player and prevents falling. The image may include chat and HUD.",
    inputSchema: { type: "object", properties: {
      world_session_id: session,
      player_uuid: { type: "string", pattern: requestId.pattern, description: "Optional online player UUID. Omit to use the configured camera account." },
      camera: { type: "object", properties: {
        dimension,
        pos: { type: "object", properties: { x: { type: "number" }, y: { type: "number" }, z: { type: "number" } }, required: ["x", "y", "z"], additionalProperties: false },
        yaw: { type: "number", default: 0 },
        pitch: { type: "number", default: 0 },
      }, required: ["dimension", "pos"], additionalProperties: false },
      request_id: requestId,
    }, required: ["world_session_id", "request_id"], additionalProperties: false },
    annotations: { ...writeAnnotations, destructiveHint: false, title: "Capture a camera view" },
  },
  {
    name: "get_screenshot_status",
    title: "Get screenshot status",
    description: "Returns the pending, succeeded, failed, cancelled or timed out status of a screenshot request within its retention window.",
    inputSchema: { type: "object", properties: { world_session_id: session, capture_id: { type: "string", minLength: 1, maxLength: 128 } }, required: ["world_session_id", "capture_id"], additionalProperties: false },
    annotations: { ...readAnnotations, title: "Get screenshot status" },
  },
  {
    name: "get_screenshot",
    title: "Get screenshot",
    description: "Returns a successfully captured, bounded PNG as an MCP image content item. Captures can contain private chat and HUD data and are retained briefly.",
    inputSchema: { type: "object", properties: { world_session_id: session, capture_id: { type: "string", minLength: 1, maxLength: 128 } }, required: ["world_session_id", "capture_id"], additionalProperties: false },
    annotations: { ...readAnnotations, title: "Get screenshot" },
  },
  {
    name: "cancel_screenshot",
    title: "Cancel screenshot request",
    description: "Cancels a pending screenshot request when the player is still connected.",
    inputSchema: { type: "object", properties: { world_session_id: session, capture_id: { type: "string", minLength: 1, maxLength: 128 } }, required: ["world_session_id", "capture_id"], additionalProperties: false },
    annotations: { ...writeAnnotations, destructiveHint: false, title: "Cancel screenshot request" },
  },
  {
    name: "get_player_position",
    title: "Get player position",
    description:
      "Synchronous. Returns the selected player's UUID, exact position, block position, yaw/pitch, and actual dimension. Omit player only when exactly one player is online; otherwise the server returns PLAYER_REQUIRED.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        player: { type: "string", minLength: 1, description: "Player UUID or account name. Not a display name or selector; omit only when exactly one player is online." },
      },
      required: ["world_session_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Get player position" },
  },
  {
    name: "get_entity",
    title: "Get entity",
    description: "Synchronously looks up one UUID in the dimension's accessible loaded entity index, without loading chunks or entity data. Returns ENTITY_NOT_FOUND_OR_UNLOADED if absent or inaccessible.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, dimension, entity_uuid: { type: "string", pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" } },
      required: ["world_session_id", "dimension", "entity_uuid"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Get entity" },
  },
  {
    name: "get_block",
    title: "Get block",
    description:
      "Synchronous. Reads one loaded block, returning its complete block state plus independent air, fluid, and collision facts. Briefly highlights that exact block on modded clients in the same dimension. Never loads a chunk.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, dimension, pos },
      required: ["world_session_id", "dimension", "pos"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Get block" },
  },
  {
    name: "get_biome",
    title: "Get biome",
    description: "Reads the biome ID stored for the loaded block's 4×4×4 biome cell, without interpolation or loading chunks.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, dimension, pos },
      required: ["world_session_id", "dimension", "pos"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Get biome" },
  },
  {
    name: "get_light",
    title: "Get light",
    description: "Reads current block light, sky light, and raw brightness from the initialized light engine without loading chunks. Propagation can still be in progress; an unready light engine returns retryable LIGHT_NOT_READY.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, dimension, pos },
      required: ["world_session_id", "dimension", "pos"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Get light" },
  },
  {
    name: "get_region_sharded",
    title: "Get large region snapshot",
    description: "Automatically reads up to 1,048,576 voxels in sequential chunk-aligned shards, each within the bridge limits. Poll and page using the normal operation tools; each result page's min, max and offset describe its shard, not the entire requested box. The result is incrementally consistent, not atomic. The parent request_id is in a separate MCP-process-local namespace; look it up with get_region_sharded_by_request_id, not get_operation_by_request_id. Coordination and deduplication are lost on restart. An unresolved child submission eventually fails with SUBMISSION_OUTCOME_UNKNOWN and its child_request_id; cancellation does not prove that child was cancelled. At most 4096 shards and 8 retained operations, with a shared 64 MiB result budget.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        min: pos,
        max: pos,
        encoding: { type: "string", enum: ["auto", "palette_indices", "palette_rle"], default: "auto" },
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "min", "max", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Get large region snapshot" },
  },
  {
    name: "get_region_sharded_by_request_id",
    title: "Find large-region operation by request ID",
    description: "Looks up a parent get_region_sharded request in this MCP process. Its request ID namespace is separate from the bridge's native operations; records are lost when this process restarts.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, request_id: requestId },
      required: ["world_session_id", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Find large-region operation by request ID" },
  },
  {
    name: "get_region",
    title: "Get region snapshot",
    description:
      "Asynchronous lossless region read. Briefly highlights the exact inclusive requested box on modded clients in the same dimension. Returns an operation_id; poll get_operation, then page get_query_result. Every voxel is preserved in x-fastest, z, then y order with a per-page palette. encoding auto chooses the smallest lossless representation.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        min: pos,
        max: pos,
        encoding: { type: "string", enum: ["auto", "palette_indices", "palette_rle"], default: "auto" },
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "min", "max", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Get region snapshot" },
  },
  {
    name: "get_terrain",
    title: "Analyze terrain",
    description:
      "Asynchronous terrain analysis over an explicit bounded box. Briefly highlights the exact requested scan box on modded clients in the same dimension. Each XZ column includes highest hits, air/fluid/collision intervals, multiple support candidates, full top-face and clearance facts, and explicit truncation. Read pages with get_query_result.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        min: pos,
        max: pos,
        clearance_height: { type: "integer", minimum: 1, maximum: 64, default: 2 },
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "min", "max", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Analyze terrain" },
  },
  {
    name: "preload_chunks",
    title: "Preload chunks",
    description:
      "Asynchronous explicit chunk preload. Converts the inclusive block box to chunk coordinates, rejects boxes touching more than 1024 chunks or outside the world, adds a non-persistent loading ticket to every chunk, and succeeds only after every chunk reports loaded. counts.total is the chunk count; processed and succeeded are loaded chunks. Tickets are kept until their roughly 6000-tick timeout; cancellation, failure, and world end remove the tickets this operation added. No other tool loads chunks implicitly.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        min: pos,
        max: pos,
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "min", "max", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, destructiveHint: false, title: "Preload chunks" },
  },
  {
    name: "set_block",
    title: "Set block",
    description:
      "Asynchronous. Places or replaces one block with a complete block state. Optional expected_state is checked immediately before writing. Returns an operation_id; poll for terminal counts and errors.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        pos,
        block_state: blockState,
        expected_state: exactBlockState,
        on_error: onError,
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "pos", "block_state", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, title: "Set block" },
  },
  {
    name: "remove_block",
    title: "Remove block",
    description:
      "Asynchronous. Sets one position to minecraft:air. This is a state edit, not a player break: it creates no drops. Returns an operation_id.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        pos,
        expected_state: exactBlockState,
        on_error: onError,
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "pos", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, title: "Remove block" },
  },
  {
    name: "fill_region",
    title: "Fill region",
    description:
      "Asynchronous region fill with one complete block state. Bounds are inclusive. Processes at most one candidate per server tick, visibly placing blocks in x-fastest, z, then y order. Reports real changed/unchanged/failed counts. Returns an operation_id.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        min: pos,
        max: pos,
        block_state: blockState,
        expected_state: exactBlockState,
        on_error: onError,
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "min", "max", "block_state", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, title: "Fill region" },
  },
  {
    name: "replace_blocks",
    title: "Replace matching blocks",
    description:
      "Asynchronous. Scans an inclusive region one candidate per server tick and replaces only blocks matching filter (block id plus optional property subset). Non-matches count as skipped. Returns an operation_id.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        min: pos,
        max: pos,
        filter: {
          type: "object",
          properties: {
            id: { type: "string" },
            properties: { type: "object", additionalProperties: { type: "string" } },
          },
          required: ["id"],
          additionalProperties: false,
        },
        block_state: blockState,
        expected_state: exactBlockState,
        on_error: onError,
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "min", "max", "filter", "block_state", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, title: "Replace matching blocks" },
  },
  {
    name: "batch_set_blocks",
    title: "Batch set blocks",
    description:
      "Asynchronous. Applies up to 8192 distinct coordinates in input order, at most one candidate per server tick. Every target state is validated before any write; modded clients see translucent pending targets for 20 server ticks before placement begins. Duplicate coordinates are rejected. Returns an operation_id; preview is not completion.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        blocks: {
          type: "array",
          minItems: 1,
          maxItems: 8192,
          items: {
            type: "object",
            properties: { pos, block_state: blockState, expected_state: exactBlockState },
            required: ["pos", "block_state"],
            additionalProperties: false,
          },
        },
        on_error: onError,
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "blocks", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, title: "Batch set blocks" },
  },
  {
    name: "batch_set_blocks_sharded",
    title: "Batch set blocks with automatic sharding",
    description: "Asynchronous, non-transactional heterogeneous write across up to 16384 distinct positions and any number of loaded chunks, subject to a 1,900,000-byte input limit. All shards are validated against the real registry before any writes, then executed in input order with each shard previewed for 20 ticks. One candidate per tick. Failure or cancellation can leave partial changes. Parent operation records are MCP-process-local and expire after five minutes; never replay after an unknown outcome.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        blocks: {
          type: "array",
          minItems: 1,
          maxItems: 16384,
          items: {
            type: "object",
            properties: { pos, block_state: blockState, expected_state: exactBlockState },
            required: ["pos", "block_state"],
            additionalProperties: false,
          },
        },
        on_error: onError,
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "blocks", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, title: "Batch set blocks with automatic sharding" },
  },
  {
    name: "batch_set_blocks_sharded_by_request_id",
    title: "Find a sharded batch by request ID",
    description: "Find a retained sharded batch in this MCP process and world session. A missing record does not prove that no blocks were changed.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, request_id: requestId },
      required: ["world_session_id", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Find a sharded batch by request ID" },
  },
  {
    name: "undo_operation",
    title: "Undo a write operation",
    description: "Asynchronously restores a terminal write operation's recorded direct BlockState changes in reverse order, at most one candidate per tick. Each restore compares the current state to the recorded post-write state; this cannot detect changes subsequently reverted to the same state (ABA). Block entities, NBT and indirect world effects cannot be restored. Partial restoration is possible; cancellation does not roll it back. Journals are session-local and expire with the source operation.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        dimension,
        operation_id: { type: "string", minLength: 1 },
        on_error: onError,
        request_id: requestId,
      },
      required: ["world_session_id", "dimension", "operation_id", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, title: "Undo a write operation" },
  },
  {
    name: "get_operation",
    title: "Get operation status",
    description:
      "Synchronous read of an accepted operation. Use it to poll async reads and writes. A failed operation is a successful poll (isError=false); inspect state, counts, and terminal_error.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, operation_id: { type: "string", minLength: 1 } },
      required: ["world_session_id", "operation_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Get operation status" },
  },
  {
    name: "get_operation_by_request_id",
    title: "Find operation by request ID",
    description:
      "Looks up an accepted operation by its client request_id in the current world session. A missing record may mean it was never accepted or has expired; it does not prove a timed-out submission had no effects. Records do not survive a session change or restart.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, request_id: requestId },
      required: ["world_session_id", "request_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Find operation by request ID" },
  },
  {
    name: "cancel_operation",
    title: "Cancel operation",
    description:
      "Requests cancellation. Queued operations stop before any work. Running operations stop at the next work-unit boundary and already applied changes are not rolled back. Poll get_operation until terminal.",
    inputSchema: {
      type: "object",
      properties: { world_session_id: session, operation_id: { type: "string", minLength: 1 } },
      required: ["world_session_id", "operation_id"],
      additionalProperties: false,
    },
    annotations: { ...writeAnnotations, destructiveHint: false, title: "Cancel operation" },
  },
  {
    name: "get_operation_errors",
    title: "Page operation errors",
    description:
      "Returns a frozen, byte-bounded page of every recorded operation error. Only terminal operations can be paged. Pass next_cursor from the previous page.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        operation_id: { type: "string", minLength: 1 },
        cursor,
      },
      required: ["world_session_id", "operation_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Page operation errors" },
  },
  {
    name: "get_query_result",
    title: "Page query result",
    description:
      "Returns one immutable page of a succeeded region or terrain query. Region pages contain a local palette plus indices or RLE runs covering exactly count voxels. Pass next_cursor from the previous page; pages never re-read the world.",
    inputSchema: {
      type: "object",
      properties: {
        world_session_id: session,
        operation_id: { type: "string", minLength: 1 },
        cursor,
      },
      required: ["world_session_id", "operation_id"],
      additionalProperties: false,
    },
    annotations: { ...readAnnotations, title: "Page query result" },
  },
];
