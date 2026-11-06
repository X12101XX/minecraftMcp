#!/usr/bin/env node
// Pre-build site check: fails when the requested box or its clearance margin
// already contains blocks. Run from mcp-server with MINECRAFT_MCP_CONFIG set.
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : fallback;
};
const vec = (text, name) => {
  const parts = text?.split(",").map(Number);
  if (!parts || parts.length !== 3 || parts.some((n) => !Number.isInteger(n))) throw new Error(`--${name} must be x,y,z integers`);
  return { x: parts[0], y: parts[1], z: parts[2] };
};
const min = vec(opt("min"), "min");
const max = vec(opt("max"), "max");
const margin = Number(opt("margin", "2"));
const dimension = opt("dimension", "minecraft:overworld");
if (!Number.isInteger(margin) || margin < 0 || margin > 16) throw new Error("--margin must be 0..16");
if (min.x > max.x || min.y > max.y || min.z > max.z) throw new Error("min must not exceed max");
const volume = (max.x - min.x + 1 + 2 * margin) * (max.y - min.y + 1) * (max.z - min.z + 1 + 2 * margin);
if (volume > 65536) throw new Error(`expanded volume ${volume} exceeds 65536; reduce the box or margin`);

const config = process.env.MINECRAFT_MCP_CONFIG;
if (!config) throw new Error("MINECRAFT_MCP_CONFIG is required");
const client = new Client({ name: "site-check", version: "1" });
await client.connect(new StdioClientTransport({ command: process.execPath, args: [new URL("../dist/index.js", import.meta.url).pathname], env: { ...process.env }, stderr: "inherit" }));
const call = async (name, a) => {
  const r = await client.callTool({ name, arguments: a });
  const b = JSON.parse(r.content[0].text);
  if (r.isError) throw new Error(`${name}: ${JSON.stringify(b)}`);
  return b;
};
const wait = async (session, id) => {
  for (;;) {
    const o = (await call("get_operation", { world_session_id: session, operation_id: id })).operation;
    if (["succeeded", "failed", "cancelled"].includes(o.state)) return o;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
};

try {
  const world = await call("get_world_info", {});
  const session = world.world_session_id;
  const lo = { x: min.x - margin, y: min.y, z: min.z - margin };
  const hi = { x: max.x + margin, y: max.y, z: max.z + margin };
  const submitted = await call("get_region", { world_session_id: session, dimension, min: lo, max: hi, request_id: randomUUID() });
  const op = await wait(session, submitted.operation.operation_id);
  if (op.state !== "succeeded") throw new Error(JSON.stringify(op));
  const occupiedCore = [];
  const occupiedMargin = [];
  let cursor;
  do {
    const page = await call("get_query_result", { world_session_id: session, operation_id: op.operation_id, ...(cursor ? { cursor } : {}) });
    const sx = hi.x - lo.x + 1, sz = hi.z - lo.z + 1;
    const indices = page.encoding === "palette_rle" ? page.runs.flatMap((r) => Array(r.length).fill(r.palette_index)) : page.indices;
    for (let i = 0; i < indices.length; i++) {
      const n = page.offset + i;
      const x = lo.x + n % sx;
      const z = lo.z + Math.floor(n / sx) % sz;
      const y = lo.y + Math.floor(n / (sx * sz));
      const id = page.palette[indices[i]].id;
      if (id === "minecraft:air" || id === "minecraft:grass_block") continue;
      const inCore = x >= min.x && x <= max.x && z >= min.z && z <= max.z;
      (inCore ? occupiedCore : occupiedMargin).push({ x, y, z, id });
    }
    cursor = page.next_cursor;
  } while (cursor);
  const summary = {
    core: { occupied: occupiedCore.length, sample: occupiedCore.slice(0, 12) },
    margin: { width: margin, occupied: occupiedMargin.length, sample: occupiedMargin.slice(0, 12) },
  };
  console.log(JSON.stringify(summary));
  if (occupiedCore.length || occupiedMargin.length) process.exitCode = 1;
} finally {
  await client.close();
}
