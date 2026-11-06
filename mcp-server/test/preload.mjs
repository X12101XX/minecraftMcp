#!/usr/bin/env node
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [resolve(here, "../dist/index.js")],
  env: { ...process.env },
  stderr: "inherit",
});
const client = new Client({ name: "preload-test", version: "1.0.0" });
await client.connect(transport);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  const text = result.content?.find((item) => item.type === "text")?.text;
  assert(text, `${name} returned no text`);
  const body = JSON.parse(text);
  if (result.isError) throw new Error(`${name}: ${body.error?.code ?? "ERROR"} ${body.error?.message ?? text}`);
  return body;
}

async function expectError(name, args, code) {
  const result = await client.callTool({ name, arguments: args });
  const body = JSON.parse(result.content[0].text);
  assert(result.isError && body.error?.code === code, `${name}: expected ${code}, got ${body.error?.code}`);
}

async function waitFor(sessionId, operationId, timeoutMs = 300_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const body = await call("get_operation", { world_session_id: sessionId, operation_id: operationId });
    const operation = body.operation;
    if (["succeeded", "failed", "cancelled"].includes(operation.state)) return operation;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`operation ${operationId} did not terminate`);
}

const world = await call("get_world_info");
const sessionId = world.world_session_id;
const dimension = "minecraft:overworld";
const blockX = 60_000 + Math.floor(Math.random() * 4_096) * 16;
const blockZ = 60_000 + Math.floor(Math.random() * 4_096) * 16;
const min = { x: blockX, y: -60, z: blockZ };
const max = { x: blockX + 15, y: -60, z: blockZ + 15 };
const blockPos = { x: blockX, y: -60, z: blockZ };

let before = null;
try {
  await call("get_block", { world_session_id: sessionId, dimension, pos: blockPos });
} catch (error) {
  before = String(error.message);
}
assert(before?.includes("CHUNK_NOT_LOADED"), `expected an unloaded far chunk, got ${before}`);
console.log(JSON.stringify({ phase: "before", error: before }));

const requestId = randomUUID();
const args = { world_session_id: sessionId, dimension, min, max, request_id: requestId };
const submitted = await call("preload_chunks", args);
assert(submitted.operation.kind === "load", "preload operation kind must be load");
assert(submitted.operation.counts.total === 1, "one chunk must be requested");

const found = await call("get_operation_by_request_id", { world_session_id: sessionId, request_id: requestId });
assert(found.operation.operation_id === submitted.operation.operation_id, "request lookup mismatch");

const terminal = await waitFor(sessionId, submitted.operation.operation_id);
assert(terminal.state === "succeeded", `preload ${terminal.state}: ${JSON.stringify(terminal.terminal_error)}`);
assert(terminal.counts.total === 1, `total ${terminal.counts.total}`);
assert(terminal.counts.processed === 1, `processed ${terminal.counts.processed}`);
assert(terminal.counts.succeeded === 1, `succeeded ${terminal.counts.succeeded}`);
console.log(JSON.stringify({ phase: "preload", operation: terminal.operation_id, counts: terminal.counts }));

const after = await call("get_block", { world_session_id: sessionId, dimension, pos: blockPos });
console.log(JSON.stringify({ phase: "after", block: after.block_state, tick: after.observed_tick }));

await expectError("get_query_result", {
  world_session_id: sessionId,
  operation_id: terminal.operation_id,
}, "OPERATION_KIND_MISMATCH");

const retried = await call("preload_chunks", args);
assert(retried.operation.operation_id === submitted.operation.operation_id, "retry created a second preload");

await expectError("preload_chunks", {
  world_session_id: sessionId, dimension,
  min: { x: 0, y: -60, z: 0 }, max: { x: 33 * 16 - 1, y: -60, z: 32 * 16 - 1 },
  request_id: randomUUID(),
}, "LIMIT_EXCEEDED");
console.log(JSON.stringify({ phase: "limit", ok: true }));

await client.close();
