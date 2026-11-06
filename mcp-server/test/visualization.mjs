#!/usr/bin/env node
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const configPath = process.env.MINECRAFT_MCP_CONFIG;
if (!configPath) throw new Error("MINECRAFT_MCP_CONFIG must point at fabric-mod/run/config/minecraft-mcp.credentials.json");
const transport = new StdioClientTransport({ command: process.execPath, args: [resolve(here, "../dist/index.js")], env: { ...process.env }, stderr: "inherit" });
const client = new Client({ name: "visualization-e2e", version: "1.0.0" });
const DIM = process.env.MINECRAFT_VISUAL_DIMENSION ?? "minecraft:overworld";
const base = { x: 24, y: Number(process.env.MINECRAFT_VISUAL_Y ?? 150), z: 8 };
const fixtureSize = Number(process.env.MINECRAFT_VISUAL_CANCEL_SIZE ?? 64);
const mixedCount = Number(process.env.MINECRAFT_VISUAL_MIXED_SIZE ?? 6);
const terminal = new Set(["succeeded", "failed", "cancelled"]);
const pendingWrites = new Map();
const writeTools = new Set(["set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks"]);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const key = (s) => `${s.id}|${Object.entries(s.properties ?? {}).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${k}=${v}`).join(",")}`;
function phase(name, data = {}) { console.log(JSON.stringify({ phase: name, ...data })); }
async function call(name, args = {}) {
  const result = await client.callTool({ name, arguments: args });
  const text = result.content?.find((item) => item.type === "text")?.text;
  assert(text, `${name}: missing text content`);
  const body = JSON.parse(text);
  if (result.isError) throw new Error(`${name}: ${body.error?.code ?? "ERROR"} ${body.error?.message ?? text}`);
  if (writeTools.has(name)) pendingWrites.set(body.operation.operation_id, args.world_session_id);
  if (body.operation && terminal.has(body.operation.state)) pendingWrites.delete(body.operation.operation_id);
  return body;
}
async function assertCounts(op) {
  const c = op.counts;
  assert(c.processed === c.succeeded + c.skipped + c.failed && c.succeeded === c.changed + c.unchanged, `inconsistent write counts: ${JSON.stringify(op)}`);
}
async function poll(session, id, timeoutMs = 120000) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const operation = (await call("get_operation", { world_session_id: session, operation_id: id })).operation;
    if (terminal.has(operation.state)) return operation;
    await sleep(50);
  }
  throw new Error(`operation ${id} timed out`);
}
async function single(session, pos) {
  return call("get_block", { world_session_id: session, dimension: DIM, pos });
}
async function assertAir(session, positions, label) {
  for (const p of positions) {
    const got = await single(session, p);
    assert(got.block_state.id === "minecraft:air", `${label}: fixture is not air at ${JSON.stringify(p)}: ${key(got.block_state)}`);
  }
}
function linePositions(start, count) {
  // Stay in the documented, already-loaded 24..35 / 8..12 fixture footprint; use
  // distinct y layers for the 600-entry prevalidation case.
  return Array.from({ length: count }, (_, i) => ({ x: base.x + ((start + i) % 12), y: base.y + Math.floor((start + i) / 60), z: base.z + Math.floor(((start + i) % 60) / 12) }));
}
async function region(session, min, max) {
  const submitted = await call("get_region", { world_session_id: session, dimension: DIM, min, max, encoding: "auto", request_id: randomUUID() });
  const op = await poll(session, submitted.operation.operation_id);
  assert(op.state === "succeeded" && op.result_available, `get_region failed: ${JSON.stringify(op)}`);
  const values = new Map(); let cursor; let count = 0; let pages = 0;
  do {
    const page = await call("get_query_result", { world_session_id: session, operation_id: op.operation_id, ...(cursor ? { cursor } : {}) });
    const palette = page.palette.map(key);
    const indices = page.encoding === "palette_rle" ? page.runs.flatMap((run) => Array(run.length).fill(run.palette_index)) : page.indices;
    assert(indices.length === page.count, `region page count mismatch at offset ${page.offset}`);
    const sx = max.x - min.x + 1, sz = max.z - min.z + 1;
    for (let i = 0; i < indices.length; i++) {
      const n = page.offset + i;
      values.set(`${min.x + n % sx},${min.y + Math.floor(n / (sx * sz))},${min.z + Math.floor(n / sx) % sz}`, palette[indices[i]]);
    }
    count += page.count; pages++; cursor = page.next_cursor ?? undefined;
  } while (cursor);
  const expectedCount = (max.x-min.x+1)*(max.y-min.y+1)*(max.z-min.z+1);
  assert(count === expectedCount && values.size === expectedCount, `region decoded ${values.size}/${expectedCount}`);
  return { values, pages, operation: op };
}
function waitTicks(operation, minTicks) {
  assert(Number.isInteger(operation.started_tick) && Number.isInteger(operation.finished_tick), `missing operation ticks: ${JSON.stringify(operation)}`);
  assert(operation.finished_tick - operation.started_tick >= minTicks, `operation tick span ${operation.finished_tick - operation.started_tick} < ${minTicks}`);
}

try {
  await client.connect(transport);
  const world = await call("get_world_info");
  const session = world.world_session_id;
  assert(session, "get_world_info returned no session");
  const blockPos = linePositions(0, Math.max(mixedCount, fixtureSize));
  assert(Number.isInteger(mixedCount) && mixedCount >= 3 && mixedCount <= 60 && Number.isInteger(fixtureSize) && fixtureSize >= 2 && fixtureSize <= 120, "MINECRAFT_VISUAL_MIXED_SIZE 3..60 and MINECRAFT_VISUAL_CANCEL_SIZE 2..120 required");
  const invalidBatch = linePositions(60, 600);
  await assertAir(session, [...blockPos, ...invalidBatch], "visualization fixture");
  phase("fixture", { session_id: session, dimension: DIM, origin: base, mixed_size: mixedCount, cancel_size: fixtureSize, invalid_batch_size: 600, verified_air: blockPos.length + invalidBatch.length });

  // Real range requests must complete and their immutable query payloads decode fully.
  const reg = await region(session, base, { x: base.x + 2, y: base.y, z: base.z + 1 });
  assert([...reg.values.values()].every((v) => v.startsWith("minecraft:air|")), "range fixture region was not air");
  const terrainRequest = await call("get_terrain", { world_session_id: session, dimension: DIM, min: base, max: { x: base.x + 2, y: base.y + 1, z: base.z + 2 }, clearance_height: 1, request_id: randomUUID() });
  const terrainOp = await poll(session, terrainRequest.operation.operation_id);
  assert(terrainOp.state === "succeeded" && terrainOp.result_available, `get_terrain failed: ${JSON.stringify(terrainOp)}`);
  const terrain = await call("get_query_result", { world_session_id: session, operation_id: terrainOp.operation_id });
  assert(Array.isArray(terrain.columns) && terrain.columns.length === 9, `terrain frozen result decode mismatch: ${JSON.stringify(terrain).slice(0,500)}`);
  phase("range_queries", { region_pages: reg.pages, region_voxels: reg.values.size, terrain_columns: terrain.columns.length, terrain_tick_span: terrainOp.finished_tick - terrainOp.started_tick });
  // Mixed targets exercise complete state round-tripping, including directional stairs.
  const mixed = blockPos.slice(0, mixedCount).map((pos, i) => ({ pos, block_state: [
    { id: "minecraft:stone" }, { id: "minecraft:glass" }, { id: "minecraft:oak_stairs", properties: { facing: "east", half: "bottom", shape: "straight", waterlogged: "false" } },
    { id: "minecraft:oak_slab", properties: { type: "bottom", waterlogged: "false" } }, { id: "minecraft:polished_andesite" }, { id: "minecraft:bricks" },
  ][i % 6] }));
  const submitted = await call("batch_set_blocks", { world_session_id: session, dimension: DIM, request_id: randomUUID(), blocks: mixed });
  const id = submitted.operation.operation_id;
  let op; let previousProcessed = 0; let previousBeforeTick; let polls = 0;
  for (;;) {
    await sleep(10);
    const beforeTick = (await single(session, mixed[0].pos)).observed_tick;
    op = (await call("get_operation", { world_session_id: session, operation_id: id })).operation; polls++;
    const afterTick = (await single(session, mixed[0].pos)).observed_tick;
    await assertCounts(op);
    assert(op.counts.processed >= previousProcessed, "processed cursor moved backward");
    if (previousBeforeTick !== undefined) assert(op.counts.processed - previousProcessed <= afterTick - previousBeforeTick + 1, "candidate processing exceeded one per elapsed server Tick");
    previousProcessed = op.counts.processed; previousBeforeTick = beforeTick;
    if (terminal.has(op.state)) break;
  }
  assert(op.state === "succeeded" && op.counts.changed === mixedCount && op.counts.processed === mixedCount, `mixed batch did not fully change: ${JSON.stringify(op)}`);
  waitTicks(op, 20 + mixedCount - 1);
  const tickSpan = op.finished_tick - op.started_tick;
  const mixedReadback = await region(session, base, { x: base.x + 11, y: base.y, z: base.z + 4 });
  for (let i = 0; i < mixed.length; i++) assert(mixedReadback.values.get(`${mixed[i].pos.x},${mixed[i].pos.y},${mixed[i].pos.z}`) === key(mixed[i].block_state), `mixed target mismatch at input index ${i}`);
  phase("mixed_batch", { count: mixedCount, changed: op.counts.changed, ticks: tickSpan, polls, states: mixed.map((b) => key(b.block_state)) });

  // Invalid final state in a 600-entry batch must fail prevalidation before any write.
  const invalid = invalidBatch.map((pos) => ({ pos, block_state: { id: "minecraft:stone" } }));
  invalid[invalid.length - 1].block_state = { id: "minecraft:oak_stairs", properties: { facing: "sideways" } };
  const bad = await call("batch_set_blocks", { world_session_id: session, dimension: DIM, request_id: randomUUID(), blocks: invalid });
  const badOp = await poll(session, bad.operation.operation_id);
  assert(badOp.state === "failed" && badOp.counts.changed === 0 && badOp.counts.succeeded === 0 && badOp.counts.failed === 1 && badOp.counts.processed === 1, `invalid 600-item batch was not rejected before writes: ${JSON.stringify(badOp)}`);
  await assertCounts(badOp);
  await assertAir(session, [invalid[0].pos, invalid[invalid.length - 2].pos], "invalid batch no-write proof");
  phase("invalid_600_batch", { state: badOp.state, total: badOp.counts.total, changed: badOp.counts.changed, processed: badOp.counts.processed, terminal_error: badOp.terminal_error });

  // Cancel a long batch only after evidence of at least one real change.
  const cancelPositions = linePositions(120, fixtureSize);
  await assertAir(session, cancelPositions, "cancel fixture");
  const cancelBlocks = cancelPositions.map((pos) => ({ pos, block_state: { id: "minecraft:stone" } }));
  const cancelRequest = await call("batch_set_blocks", { world_session_id: session, dimension: DIM, request_id: randomUUID(), blocks: cancelBlocks });
  const cancelId = cancelRequest.operation.operation_id;
  let cancelOp;
  const cancelDeadline = Date.now() + 120000;
  while (Date.now() < cancelDeadline) {
    await sleep(50);
    cancelOp = (await call("get_operation", { world_session_id: session, operation_id: cancelId })).operation;
    if (cancelOp.counts.changed > 0 || terminal.has(cancelOp.state)) break;
  }
  assert(cancelOp.counts.changed > 0 && !terminal.has(cancelOp.state), `no cancellable changed prefix before deadline: ${JSON.stringify(cancelOp)}`);
  const cancelResult = await call("cancel_operation", { world_session_id: session, operation_id: cancelId });
  cancelOp = await poll(session, cancelId);
  assert(cancelOp.state === "cancelled", `expected cancelled batch, got ${cancelOp.state}`);
  const cancelReadback = await region(session, { x: base.x, y: base.y + 2, z: base.z }, { x: base.x + 11, y: base.y + 3, z: base.z + 4 });
  const changed = cancelOp.counts.changed;
  assert(changed > 0 && changed < fixtureSize, `cancel did not preserve a strict prefix: ${changed}/${fixtureSize}`);
  for (let i = 0; i < fixtureSize; i++) {
    const actual = cancelReadback.values.get(`${cancelPositions[i].x},${cancelPositions[i].y},${cancelPositions[i].z}`);
    const expected = i < changed ? "minecraft:stone|" : "minecraft:air|";
    assert(actual === expected, `cancel prefix mismatch at ${i}: expected ${expected}, got ${actual}`);
  }
  phase("cancel_prefix", { state: cancelOp.state, changed, total: fixtureSize, cancel_requested: cancelResult.cancel_requested, readback: "exact sequential prefix" });

  const expectedPos = { x: base.x + 8, y: base.y + 4, z: base.z + 4 };
  await assertAir(session, [expectedPos], "expected-state fixture");
  const expectedRequest = await call("set_block", { world_session_id: session, dimension: DIM, request_id: randomUUID(), pos: expectedPos, block_state: { id: "minecraft:gold_block" }, expected_state: { id: "minecraft:dirt", properties: {} } });
  const expectedOp = await poll(session, expectedRequest.operation.operation_id);
  assert(expectedOp.counts.failed === 1 && expectedOp.counts.changed === 0, `expected_state mismatch did not reject write: ${JSON.stringify(expectedOp)}`);
  assert((await single(session, expectedPos)).block_state.id === "minecraft:air", "expected_state rejection changed target");
  phase("expected_state", { state: expectedOp.state, counts: expectedOp.counts, target_unchanged: true });
  const unchangedPos = { x: base.x + 9, y: base.y + 4, z: base.z + 4 };
  await assertAir(session, [unchangedPos], "unchanged fixture");
  const first = await call("set_block", { world_session_id: session, dimension: DIM, request_id: randomUUID(), pos: unchangedPos, block_state: { id: "minecraft:stone" } });
  const firstOp = await poll(session, first.operation.operation_id);
  assert(firstOp.counts.changed === 1, `unchanged setup write failed: ${JSON.stringify(firstOp)}`);
  const same = await call("set_block", { world_session_id: session, dimension: DIM, request_id: randomUUID(), pos: unchangedPos, block_state: { id: "minecraft:stone" } });
  const sameOp = await poll(session, same.operation.operation_id);
  assert(sameOp.counts.unchanged === 1 && sameOp.counts.changed === 0, `same-state write counted as changed: ${JSON.stringify(sameOp)}`);
  phase("unchanged", { state: sameOp.state, counts: sameOp.counts, target: key((await single(session, unchangedPos)).block_state) });
  phase("complete", { ok: true });
} catch (error) {
  phase("failure", { error: error instanceof Error ? error.message : String(error), stack: error instanceof Error ? error.stack : undefined });
  for (const [operation_id, world_session_id] of pendingWrites) {
    try { await call("cancel_operation", { world_session_id, operation_id }); }
    catch (cancelError) { console.error(`failed to cancel test operation ${operation_id}: ${cancelError.message}`); }
  }
  process.exitCode = 1;
} finally {
  await client.close().catch((error) => { console.error(`client close failed: ${error.message}`); process.exitCode = 1; });
}
