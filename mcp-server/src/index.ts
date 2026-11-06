#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { BridgeClient, BridgeError } from "./bridge.js";
import { loadSettings } from "./config.js";
import { TOOLS } from "./tools.js";
import { WideRegionCoordinator } from "./wide-region.js";
import { WideWriteCoordinator } from "./wide-write.js";

const INSTRUCTIONS = `Use these tools to inspect and modify a live Minecraft world.

1. Call get_world_info first, then pass its world_session_id and an explicit dimension.
2. get_region and get_terrain are asynchronous: poll get_operation until terminal, then page get_query_result.
3. Every write is asynchronous and returns an operation_id. Poll get_operation; inspect counts and get_operation_errors.
4. If submission times out, use get_operation_by_request_id within the active session and retention window; not found does not prove the submission was never accepted.
5. Only loaded chunks are readable/writable. Chunk loading is never implicit; call preload_chunks (asynchronous; poll get_operation) to explicitly load and keep a bounded box (at most 1024 chunks) loaded until its tickets expire.
6. Writes are not transactions. Failure or cancellation can leave earlier changes in place.
7. Re-read the region after writing and compare complete block states before claiming success.`;

async function main(): Promise<void> {
  const settings = loadSettings();
  const client = new BridgeClient(settings);
  const wide = new WideRegionCoordinator(client);
  const wideWrite = new WideWriteCoordinator(client);
  const server = new Server(
    { name: "minecraft-mcp", version: "2.1.0" },
    { capabilities: { tools: {} }, instructions: INSTRUCTIONS },
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: TOOLS }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const name = request.params.name;
    const args = (request.params.arguments ?? {}) as Record<string, unknown>;
    try {
      const result = await dispatch(client, wide, wideWrite, name, args);
      if (name === "get_screenshot") {
        const { image_base64, ...metadata } = result;
        if (typeof image_base64 !== "string" || image_base64.length > 5_592_408) {
          throw new BridgeError("BRIDGE_RESPONSE_INVALID", "Invalid screenshot data");
        }
        return { content: [
          { type: "text" as const, text: JSON.stringify(metadata) },
          { type: "image" as const, data: image_base64, mimeType: "image/png" },
        ], isError: false };
      }
      return {
        content: [{ type: "text" as const, text: JSON.stringify(result) }],
        isError: false,
      };
    } catch (error) {
      const bridgeError =
        error instanceof BridgeError
          ? error
          : new BridgeError(
              "INTERNAL_ERROR",
              error instanceof Error ? error.message : "unknown MCP server error",
              false,
            );
      if (!(error instanceof BridgeError)) {
        process.stderr.write(`minecraft-mcp: ${bridgeError.message}\n`);
      }
      return {
        content: [{ type: "text" as const, text: JSON.stringify(bridgeError.toJSON()) }],
        isError: true,
      };
    }
  });

  await server.connect(new StdioServerTransport());
  process.stderr.write("minecraft-mcp: MCP stdio server ready\n");
}

async function dispatch(
  client: BridgeClient,
  wide: WideRegionCoordinator,
  wideWrite: WideWriteCoordinator,
  name: string,
  args: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  if (!TOOLS.some((tool) => tool.name === name)) {
    throw new BridgeError("UNKNOWN_TOOL", `Unknown tool: ${name}`);
  }
  switch (name) {
    case "get_world_info":
      return client.info();
    case "get_operation":
      if (wideWrite.owns(requiredString(args, "operation_id"))) return wideWrite.status(requiredString(args, "world_session_id"), requiredString(args, "operation_id"));
      return wide.owns(requiredString(args, "operation_id"))
        ? wide.status(requiredString(args, "world_session_id"), requiredString(args, "operation_id"))
        : client.operation(requiredString(args, "world_session_id"), requiredString(args, "operation_id"));
    case "cancel_operation":
      if (wideWrite.owns(requiredString(args, "operation_id"))) return wideWrite.cancel(requiredString(args, "world_session_id"), requiredString(args, "operation_id"));
      return wide.owns(requiredString(args, "operation_id"))
        ? wide.cancel(requiredString(args, "world_session_id"), requiredString(args, "operation_id"))
        : client.cancel(requiredString(args, "world_session_id"), requiredString(args, "operation_id"));
    case "get_operation_errors":
      if (wideWrite.owns(requiredString(args, "operation_id"))) return wideWrite.errors(requiredString(args, "world_session_id"), requiredString(args, "operation_id"), optionalString(args, "cursor"));
      if (wide.owns(requiredString(args, "operation_id"))) return wide.errors(requiredString(args, "world_session_id"), requiredString(args, "operation_id"), optionalString(args, "cursor"));
      return client.errors(
        requiredString(args, "world_session_id"),
        requiredString(args, "operation_id"),
        optionalString(args, "cursor"),
      );
    case "get_query_result":
      if (wideWrite.owns(requiredString(args, "operation_id"))) throw new BridgeError("OPERATION_KIND_MISMATCH", "Operation is not a query");
      if (wide.owns(requiredString(args, "operation_id"))) return wide.result(requiredString(args, "world_session_id"), requiredString(args, "operation_id"), optionalString(args, "cursor"));
      return client.result(
        requiredString(args, "world_session_id"),
        requiredString(args, "operation_id"),
        optionalString(args, "cursor"),
      );
    case "get_region_sharded":
      return wide.submit(args);
    case "get_region_sharded_by_request_id": {
      const local = await wide.lookup(requiredString(args, "world_session_id"), requiredString(args, "request_id"));
      if (!local) throw new BridgeError("OPERATION_NOT_FOUND", "Wide-region operation not found");
      return local;
    }
    case "batch_set_blocks_sharded":
      return wideWrite.submit(args);
    case "batch_set_blocks_sharded_by_request_id": {
      const local = await wideWrite.lookup(requiredString(args, "world_session_id"), requiredString(args, "request_id"));
      if (!local) throw new BridgeError("OPERATION_NOT_FOUND", "Wide-write operation not found");
      return local;
    }
    case "get_block":
    case "get_entity":
    case "request_screenshot":
    case "get_screenshot_status":
    case "get_screenshot":
    case "cancel_screenshot":
    case "get_biome":
    case "get_light":
    case "get_player_position":
    case "get_operation_by_request_id":
    case "get_region":
    case "get_terrain":
    case "preload_chunks":
    case "set_block":
    case "remove_block":
    case "fill_region":
    case "replace_blocks":
    case "batch_set_blocks":
    case "undo_operation":
      return client.callTool(name, args);
    default:
      throw new BridgeError("UNKNOWN_TOOL", `Unknown tool: ${name}`);
  }
}

function requiredString(args: Record<string, unknown>, field: string): string {
  const value = args[field];
  if (typeof value !== "string" || value.length === 0) {
    throw new BridgeError("INVALID_ARGUMENT", `field '${field}' must be a non-empty string`);
  }
  return value;
}

function optionalString(args: Record<string, unknown>, field: string): string | undefined {
  const value = args[field];
  if (value === undefined) return undefined;
  if (typeof value !== "string" || value.length === 0) {
    throw new BridgeError("INVALID_ARGUMENT", `field '${field}' must be a non-empty string`);
  }
  return value;
}

main().catch((error) => {
  process.stderr.write(`minecraft-mcp: fatal: ${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
});
