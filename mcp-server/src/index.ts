#!/usr/bin/env node
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { BridgeClient, BridgeError } from "./bridge.js";
import { loadSettings } from "./config.js";
import { TOOLS } from "./tools.js";

const INSTRUCTIONS = `Use these tools to inspect and modify a live Minecraft world.

1. Call get_world_info first, then pass its world_session_id and an explicit dimension.
2. get_region and get_terrain are asynchronous: poll get_operation until terminal, then page get_query_result.
3. Every write is asynchronous and returns an operation_id. Poll get_operation; inspect counts and get_operation_errors.
4. Only loaded chunks are readable/writable. Chunk loading is never implicit.
5. Writes are not transactions. Failure or cancellation can leave earlier changes in place.
6. Re-read the region after writing and compare complete block states before claiming success.`;

async function main(): Promise<void> {
  const settings = loadSettings();
  const client = new BridgeClient(settings);
  const server = new Server(
    { name: "minecraft-mcp", version: "1.1.0" },
    { capabilities: { tools: {} }, instructions: INSTRUCTIONS },
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: TOOLS }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const name = request.params.name;
    const args = (request.params.arguments ?? {}) as Record<string, unknown>;
    try {
      const result = await dispatch(client, name, args);
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
      return client.operation(requiredString(args, "world_session_id"), requiredString(args, "operation_id"));
    case "cancel_operation":
      return client.cancel(requiredString(args, "world_session_id"), requiredString(args, "operation_id"));
    case "get_operation_errors":
      return client.errors(
        requiredString(args, "world_session_id"),
        requiredString(args, "operation_id"),
        optionalString(args, "cursor"),
      );
    case "get_query_result":
      return client.result(
        requiredString(args, "world_session_id"),
        requiredString(args, "operation_id"),
        optionalString(args, "cursor"),
      );
    case "get_block":
    case "get_player_position":
    case "get_region":
    case "get_terrain":
    case "set_block":
    case "remove_block":
    case "fill_region":
    case "replace_blocks":
    case "batch_set_blocks":
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
