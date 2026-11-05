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
const client = new Client({ name: "cancel-test", version: "1.0.0" });
await client.connect(transport);
async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  const body = JSON.parse(result.content[0].text);
  if (result.isError) throw new Error(`${name}: ${body.error?.code} ${body.error?.message}`);
  return body;
}
const world = await call("get_world_info");
const sid = world.world_session_id;
const submitted = await call("fill_region", {
  world_session_id: sid, dimension: "minecraft:overworld", request_id: randomUUID(),
  min: { x: -60, y: -60, z: 0 }, max: { x: -1, y: -41, z: 59 },
  block_state: { id: "minecraft:stone" },
});
const operationId = submitted.operation.operation_id;
await new Promise((resolve) => setTimeout(resolve, 250));
const canceled = await call("cancel_operation", { world_session_id: sid, operation_id: operationId });
let operation = canceled.operation;
for (let i = 0; i < 200 && !["succeeded", "failed", "cancelled"].includes(operation.state); i++) {
  await new Promise((resolve) => setTimeout(resolve, 25));
  operation = (await call("get_operation", { world_session_id: sid, operation_id: operationId })).operation;
}
console.log(JSON.stringify({ cancel_requested: canceled.cancel_requested, state: operation.state, counts: operation.counts }));
if (operation.state !== "cancelled") throw new Error(`expected cancelled, got ${operation.state}`);
if (operation.counts.changed <= 0) throw new Error("expected some work before cancellation");
// Cancellation must not roll back; read one coordinate that changed.
const x = -60, y = -60, z = 0;
const block = await call("get_block", { world_session_id: sid, dimension: "minecraft:overworld", pos: { x, y, z } });
console.log(JSON.stringify({ phase: "partial-change-preserved", pos: { x, y, z }, block: block.block_state }));
if (block.block_state.id !== "minecraft:stone") throw new Error("cancelled write should not roll back completed blocks");
await client.close();
