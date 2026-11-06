#!/usr/bin/env node
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [resolve(here, "../dist/index.js")],
  env: { ...process.env },
  stderr: "inherit",
});
const client = new Client({ name: "negative-test", version: "1.0.0" });
await client.connect(transport);
const infoText = await client.callTool({ name: "get_world_info", arguments: {} });
const info = JSON.parse(infoText.content[0].text);
const sid = info.world_session_id;

async function expectError(label, tool, args, code) {
  const result = await client.callTool({ name: tool, arguments: args });
  const body = JSON.parse(result.content[0].text);
  const actual = body.error?.code;
  console.log(JSON.stringify({ label, isError: result.isError, code: actual, expected: code, ok: result.isError && actual === code }));
  if (!result.isError || actual !== code) throw new Error(`${label}: expected ${code}, got ${actual}`);
}

await expectError("out-of-bounds", "get_block", { world_session_id: sid, dimension: "minecraft:overworld", pos: { x: 0, y: 400, z: 0 } }, "OUT_OF_BOUNDS");
await expectError("unloaded-chunk", "get_block", { world_session_id: sid, dimension: "minecraft:overworld", pos: { x: 50000, y: -60, z: 50000 } }, "CHUNK_NOT_LOADED");
await expectError("invalid-block", "set_block", { world_session_id: sid, dimension: "minecraft:overworld", pos: { x: 0, y: -60, z: 0 }, block_state: { id: "minecraft:not_a_block" }, request_id: crypto.randomUUID() }, "INVALID_BLOCK_ID");
await expectError("invalid-state", "set_block", { world_session_id: sid, dimension: "minecraft:overworld", pos: { x: 0, y: -60, z: 0 }, block_state: { id: "minecraft:stone", properties: { facing: "north" } }, request_id: crypto.randomUUID() }, "INVALID_BLOCK_STATE");
await expectError("oversize-query", "get_region", { world_session_id: sid, dimension: "minecraft:overworld", min: { x: 0, y: -64, z: 0 }, max: { x: 200, y: 0, z: 200 }, request_id: crypto.randomUUID() }, "LIMIT_EXCEEDED");
await expectError("preload-too-many-chunks", "preload_chunks", { world_session_id: sid, dimension: "minecraft:overworld", min: { x: 0, y: -60, z: 0 }, max: { x: 33 * 16 - 1, y: -60, z: 32 * 16 - 1 }, request_id: crypto.randomUUID() }, "LIMIT_EXCEEDED");
await expectError("preload-out-of-bounds", "preload_chunks", { world_session_id: sid, dimension: "minecraft:overworld", min: { x: 0, y: 400, z: 0 }, max: { x: 0, y: 400, z: 0 }, request_id: crypto.randomUUID() }, "OUT_OF_BOUNDS");
await expectError("wrong-session", "get_block", { world_session_id: "s-wrong", dimension: "minecraft:overworld", pos: { x: 0, y: -60, z: 0 } }, "WORLD_SESSION_MISMATCH");
await expectError("unknown-tool", "not_a_tool", {}, "UNKNOWN_TOOL");
await expectError("duplicate-position", "batch_set_blocks", { world_session_id: sid, dimension: "minecraft:overworld", request_id: crypto.randomUUID(), blocks: [
  { pos: { x: 0, y: -60, z: 0 }, block_state: { id: "minecraft:stone" } },
  { pos: { x: 0, y: -60, z: 0 }, block_state: { id: "minecraft:dirt" } },
] }, "DUPLICATE_POSITION");
await expectError("missing-operation", "get_operation", { world_session_id: sid, operation_id: "op-does-not-exist" }, "OPERATION_NOT_FOUND");
await client.close();
