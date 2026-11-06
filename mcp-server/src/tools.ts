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
