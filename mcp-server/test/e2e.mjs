#!/usr/bin/env node
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const serverEntry = resolve(here, "../dist/index.js");
const configPath = process.env.MINECRAFT_MCP_CONFIG;
if (!configPath) {
  console.error("MINECRAFT_MCP_CONFIG must point at fabric-mod/run/config/minecraft-mcp.credentials.json");
  process.exit(2);
}

const transport = new StdioClientTransport({
  command: process.execPath,
  args: [serverEntry],
  env: { ...process.env },
  stderr: "inherit",
});
const client = new Client({ name: "minecraft-mcp-e2e", version: "1.0.0" });
await client.connect(transport);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}
function stateKey(state) {
  return `${state.id}|${Object.entries(state.properties).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${k}=${v}`).join(",")}`;
}
async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  const text = result.content?.find((item) => item.type === "text")?.text;
  assert(text, `${name} returned no text`);
  const body = JSON.parse(text);
  if (result.isError) throw new Error(`${name}: ${body.error?.code ?? "ERROR"} ${body.error?.message ?? text}`);
  return body;
}
async function waitFor(sessionId, operationId, timeoutMs = 120_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const body = await call("get_operation", { world_session_id: sessionId, operation_id: operationId });
    const operation = body.operation;
    if (["succeeded", "failed", "cancelled"].includes(operation.state)) return operation;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`operation ${operationId} did not terminate`);
}
async function submit(name, args, sessionId) {
  const requestId = args.request_id ?? randomUUID();
  const body = await call(name, {
    ...args,
    world_session_id: sessionId,
    request_id: requestId,
  });
  const found = await call("get_operation_by_request_id", { world_session_id: sessionId, request_id: requestId });
  assert(found.operation.operation_id === body.operation.operation_id, `${name}: request lookup mismatch`);
  const operation = await waitFor(sessionId, body.operation.operation_id);
  return body.operation.operation_id;
}
async function readRegion(sessionId, dimension, min, max) {
  const submitted = await call("get_region", {
    world_session_id: sessionId,
    dimension,
    min,
    max,
    encoding: "auto",
    request_id: randomUUID(),
  });
  const operation = await waitFor(sessionId, submitted.operation.operation_id);
  assert(operation.state === "succeeded", `get_region ${operation.state}`);
  const values = new Map();
  let cursor;
  let pages = 0;
  let started = Date.now();
  do {
    const page = await call("get_query_result", {
      world_session_id: sessionId,
      operation_id: operation.operation_id,
      ...(cursor ? { cursor } : {}),
    });
    pages++;
    const palette = page.palette.map(stateKey);
    const local = page.encoding === "palette_rle"
      ? page.runs.flatMap((run) => Array(run.length).fill(run.palette_index))
      : page.indices;
    assert(local.length === page.count, "page count mismatch");
    const sx = max.x - min.x + 1;
    const sz = max.z - min.z + 1;
    for (let i = 0; i < local.length; i++) {
      const index = page.offset + i;
      const x = min.x + index % sx;
      const z = min.z + (Math.floor(index / sx) % sz);
      const y = min.y + Math.floor(index / (sx * sz));
      values.set(`${x},${y},${z}`, palette[local[i]]);
    }
    cursor = page.next_cursor ?? undefined;
  } while (cursor);
  return { values, pages, operation };
}

const world = await call("get_world_info");
const sessionId = world.world_session_id;
assert(typeof sessionId === "string", "no world_session_id");
const dimension = "minecraft:overworld";

// Recover an accepted read by request ID without resubmitting it.
const lookupRequestId = randomUUID();
const lookupArgs = { world_session_id: sessionId, dimension,
  min: { x: 0, y: -64, z: 0 }, max: { x: 0, y: -64, z: 0 }, request_id: lookupRequestId };
const accepted = await call("get_region", lookupArgs);
const found = await call("get_operation_by_request_id", { world_session_id: sessionId, request_id: lookupRequestId });
assert(found.operation.operation_id === accepted.operation.operation_id, "request lookup returned a different operation");
await waitFor(sessionId, accepted.operation.operation_id);
const terminal = await call("get_operation_by_request_id", { world_session_id: sessionId, request_id: lookupRequestId });
assert(terminal.operation.operation_id === accepted.operation.operation_id && terminal.operation.state === "succeeded",
  "request lookup did not retain the terminal operation");
const retried = await call("get_region", lookupArgs);
assert(retried.operation.operation_id === accepted.operation.operation_id, "retry created a second operation");
const conflict = await client.callTool({ name: "get_region", arguments: { ...lookupArgs, encoding: "palette_rle" } });
assert(conflict.isError && JSON.parse(conflict.content[0].text).error.code === "IDEMPOTENCY_CONFLICT",
  "different arguments with the same request ID must conflict");
for (const [args, expected] of [
  [{ world_session_id: sessionId, request_id: randomUUID() }, "OPERATION_NOT_FOUND"],
  [{ world_session_id: sessionId, request_id: "invalid" }, "INVALID_ARGUMENT"],
  [{ world_session_id: "s-wrong", request_id: lookupRequestId }, "WORLD_SESSION_MISMATCH"],
  [{ world_session_id: sessionId, request_id: lookupRequestId, extra: true }, "INVALID_ARGUMENT"],
]) {
  const response = await client.callTool({ name: "get_operation_by_request_id", arguments: args });
  assert(response.isError && JSON.parse(response.content[0].text).error.code === expected, `expected ${expected}`);
}
console.log(JSON.stringify({ phase: "world", sessionId, players: world.players.length }));

// Error path: no players on this dedicated server, and an unloaded far chunk.
try {
  await call("get_player_position", { world_session_id: sessionId });
  console.log(JSON.stringify({ phase: "player", result: "unexpected success" }));
} catch (error) {
  const expected = String(error.message).includes("PLAYER_REQUIRED");
  console.log(JSON.stringify({ phase: "player", error: String(error.message), expected }));
  assert(expected, "expected PLAYER_REQUIRED without players");
}

const block = await call("get_block", { world_session_id: sessionId, dimension, pos: { x: 0, y: -64, z: 0 } });
assert(block.block_state.id === "minecraft:bedrock", `unexpected bedrock read: ${JSON.stringify(block.block_state)}`);
console.log(JSON.stringify({ phase: "block", block: block.block_state, tick: block.observed_tick }));

const min = { x: 8, y: -60, z: 8 };
const max = { x: 21, y: -50, z: 17 };
const expected = new Map();
function put(x, y, z, id, properties = {}) {
  expected.set(`${x},${y},${z}`, stateKey({ id, properties }));
}
function fillBox(x0, y0, z0, x1, y1, z1, id, properties = {}) {
  for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) for (let z = z0; z <= z1; z++) put(x, y, z, id, properties);
}

// Foundation and floors.
fillBox(8, -60, 8, 21, -60, 17, "minecraft:stone_bricks");
fillBox(8, -59, 8, 21, -56, 17, "minecraft:oak_planks");
fillBox(8, -55, 8, 21, -55, 17, "minecraft:oak_planks");
fillBox(8, -54, 8, 21, -51, 17, "minecraft:oak_planks");
fillBox(8, -50, 8, 21, -50, 17, "minecraft:dark_oak_planks");
// Interior air, door, and second-floor stair opening.
fillBox(9, -59, 9, 20, -57, 16, "minecraft:air");
fillBox(9, -54, 9, 20, -52, 16, "minecraft:air");
fillBox(8, -59, 12, 8, -58, 12, "minecraft:air");
put(20, -55, 12, "minecraft:air");
// Windows (ground floor and upper floor), on all four walls.
const windows = [
  [21, -58, 10], [21, -58, 14], [8, -58, 10], [8, -58, 14],
  [15, -58, 8], [11, -58, 17],
  [21, -53, 10], [21, -53, 14], [8, -53, 10], [8, -53, 14],
  [15, -53, 8], [11, -53, 17],
];
for (const [x, y, z] of windows) put(x, y, z, "minecraft:glass");
// Interior staircase: four steps then an opening through the upper floor.
const stairProps = { facing: "east", half: "bottom", shape: "straight", waterlogged: "false" };
put(17, -59, 12, "minecraft:oak_stairs", stairProps);
put(18, -58, 12, "minecraft:oak_stairs", stairProps);
put(19, -57, 12, "minecraft:oak_stairs", stairProps);
put(20, -56, 12, "minecraft:oak_stairs", stairProps);

const plan = [
  ["fill_region", { dimension, min: { x: 8, y: -60, z: 8 }, max: { x: 21, y: -60, z: 17 }, block_state: { id: "minecraft:stone_bricks" } }],
  ["fill_region", { dimension, min: { x: 8, y: -59, z: 8 }, max: { x: 21, y: -56, z: 17 }, block_state: { id: "minecraft:oak_planks" } }],
  ["fill_region", { dimension, min: { x: 8, y: -55, z: 8 }, max: { x: 21, y: -55, z: 17 }, block_state: { id: "minecraft:oak_planks" } }],
  ["fill_region", { dimension, min: { x: 8, y: -54, z: 8 }, max: { x: 21, y: -51, z: 17 }, block_state: { id: "minecraft:oak_planks" } }],
  ["fill_region", { dimension, min: { x: 8, y: -50, z: 8 }, max: { x: 21, y: -50, z: 17 }, block_state: { id: "minecraft:dark_oak_planks" } }],
  ["fill_region", { dimension, min: { x: 9, y: -59, z: 9 }, max: { x: 20, y: -57, z: 16 }, block_state: { id: "minecraft:air" } }],
  ["fill_region", { dimension, min: { x: 9, y: -54, z: 9 }, max: { x: 20, y: -52, z: 16 }, block_state: { id: "minecraft:air" } }],
  ["fill_region", { dimension, min: { x: 8, y: -59, z: 12 }, max: { x: 8, y: -58, z: 12 }, block_state: { id: "minecraft:air" } }],
  ["batch_set_blocks", {
    dimension,
    blocks: [
      ...windows.map(([x, y, z]) => ({ pos: { x, y, z }, block_state: { id: "minecraft:glass" } })),
      ...[
        [17, -59, 12], [18, -58, 12], [19, -57, 12], [20, -56, 12],
      ].map(([x, y, z]) => ({
        pos: { x, y, z },
        block_state: { id: "minecraft:oak_stairs", properties: { facing: "east", half: "bottom" } },
      })),
      { pos: { x: 20, y: -55, z: 12 }, block_state: { id: "minecraft:air" } },
    ],
  }],
];

const buildStarted = Date.now();
const operations = [];
for (const [tool, args] of plan) {
  const operationId = await submit(tool, args, sessionId);
  operations.push(operationId);
}
console.log(JSON.stringify({ phase: "build", operations, milliseconds: Date.now() - buildStarted }));

async function verify(label) {
  const region = await readRegion(sessionId, dimension, min, max);
  const differences = [];
  for (const [key, wanted] of expected) {
    const actual = region.values.get(key);
    if (actual !== wanted) differences.push({ pos: key, expected: wanted, actual });
  }
  console.log(JSON.stringify({ phase: label, pages: region.pages, compared: expected.size, differences: differences.length, sample: differences.slice(0, 5) }));
  return differences;
}
let differences = await verify("verify");
assert(differences.length === 0, `house verification failed: ${JSON.stringify(differences.slice(0, 5))}`);

// Inject two realistic defects, detect them, then repair only those coordinates.
const stair = { pos: { x: 19, y: -57, z: 12 }, block_state: { id: "minecraft:oak_stairs", properties: { facing: "west", half: "top" } }, expected_state: { id: "minecraft:oak_stairs", properties: { facing: "east", half: "bottom", shape: "straight", waterlogged: "false" } } };
const glass = { pos: { x: 21, y: -58, z: 10 }, block_state: { id: "minecraft:air" }, expected_state: { id: "minecraft:glass", properties: {} } };
const defectOp = await submit("batch_set_blocks", { dimension, blocks: [stair, glass] }, sessionId);
const damaged = await verify("damage-detect");
assert(damaged.length === 2, `expected two detected defects, got ${damaged.length}`);
const repairs = damaged.map((difference) => {
  const [x, y, z] = difference.pos.split(",").map(Number);
  const wanted = difference.expected.split("|");
  const properties = Object.fromEntries(wanted[1] ? wanted[1].split(",").filter(Boolean).map((entry) => entry.split("=")) : []);
  return { pos: { x, y, z }, block_state: { id: wanted[0], properties } };
});
await submit("batch_set_blocks", { dimension, blocks: repairs }, sessionId);
const repaired = await verify("repair");
assert(repaired.length === 0, `repair failed: ${JSON.stringify(repaired.slice(0, 5))}`);

const final = await readRegion(sessionId, dimension, min, max);
console.log(JSON.stringify({ phase: "final", pages: final.pages, compared: expected.size, ok: true, defectOperation: defectOp }));
await client.close();
