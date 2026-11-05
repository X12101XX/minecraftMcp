#!/usr/bin/env node
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
const transport = new StdioClientTransport({ command: process.execPath, args: [resolve(here, "../dist/index.js")], env: { ...process.env }, stderr: "inherit" });
const client = new Client({ name: "region-large", version: "1.0.0" });
await client.connect(transport);
async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  const body = JSON.parse(result.content[0].text);
  if (result.isError) throw new Error(`${name}: ${body.error?.code} ${body.error?.message}`);
  return body;
}
const sid = (await call("get_world_info")).world_session_id;
const min = { x: -32, y: -64, z: -32 };
const max = { x: 31, y: -49, z: 31 };
const started = Date.now();
const submitted = await call("get_region", { world_session_id: sid, dimension: "minecraft:overworld", min, max, encoding: "auto", request_id: randomUUID() });
let op;
do {
  await new Promise((resolve) => setTimeout(resolve, 50));
  op = (await call("get_operation", { world_session_id: sid, operation_id: submitted.operation.operation_id })).operation;
} while (!["succeeded", "failed", "cancelled"].includes(op.state));
if (op.state !== "succeeded") throw new Error(JSON.stringify(op));
let cursor;
let pages = 0;
let total = 0;
let maxPageBytes = 0;
let palette = 0;
do {
  const page = await call("get_query_result", { world_session_id: sid, operation_id: op.operation_id, ...(cursor ? { cursor } : {}) });
  const bytes = Buffer.byteLength(JSON.stringify(page));
  maxPageBytes = Math.max(maxPageBytes, bytes);
  pages++;
  total += page.count;
  palette = Math.max(palette, page.palette.length);
  if (bytes > 512 * 1024) throw new Error(`page over limit: ${bytes}`);
  cursor = page.next_cursor;
} while (cursor);
console.log(JSON.stringify({ state: op.state, counts: op.counts, pages, total, maxPageBytes, maxPalette: palette, milliseconds: Date.now() - started }));
if (total !== 65536) throw new Error(`decoded total ${total}`);
await client.close();
