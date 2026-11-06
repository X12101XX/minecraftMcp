import test from "node:test";
import assert from "node:assert/strict";
import { TOOLS } from "../dist/tools.js";

const EXPECTED = [
  "get_world_info", "get_player_position", "get_block", "get_biome", "get_light", "get_region_sharded", "get_region_sharded_by_request_id", "get_region", "get_terrain", "preload_chunks",
  "set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks", "undo_operation",
  "get_operation", "get_operation_by_request_id", "cancel_operation", "get_operation_errors", "get_query_result",
  "get_entity", "batch_set_blocks_sharded", "batch_set_blocks_sharded_by_request_id",
  "request_screenshot", "get_screenshot_status", "get_screenshot", "cancel_screenshot",
];

test("advertises every required tool exactly once", () => {
  assert.equal(TOOLS.length, EXPECTED.length);
  assert.deepEqual(TOOLS.map((tool) => tool.name).sort(), [...EXPECTED].sort());
  assert.equal(new Set(TOOLS.map((tool) => tool.name)).size, TOOLS.length);
});

test("every tool has a closed object schema with resolvable required fields", () => {
  for (const tool of TOOLS) {
    assert.ok(tool.description && tool.description.length > 20, tool.name);
    assert.equal(tool.inputSchema.type, "object", tool.name);
    assert.equal(tool.inputSchema.additionalProperties, false, tool.name);
    const properties = tool.inputSchema.properties ?? {};
    for (const required of tool.inputSchema.required ?? []) {
      assert.ok(Object.hasOwn(properties, required), `${tool.name}.${required}`);
    }
    assert.ok(tool.annotations, tool.name);
  }
});

test("async tools require request_id and write tools are destructive", () => {
  const asyncWrites = new Set(["set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks", "undo_operation"]);
  for (const tool of TOOLS) {
    if (asyncWrites.has(tool.name)) {
      assert.ok(tool.inputSchema.required.includes("request_id"), tool.name);
      assert.equal(tool.annotations.readOnlyHint, false, tool.name);
      assert.equal(tool.annotations.destructiveHint, true, tool.name);
    }
  }
  for (const name of ["get_region", "get_region_sharded", "get_terrain"]) {
    const tool = TOOLS.find((candidate) => candidate.name === name);
    assert.ok(tool.inputSchema.required.includes("request_id"), name);
    assert.equal(tool.annotations.readOnlyHint, true, name);
  }
  const preload = TOOLS.find((candidate) => candidate.name === "preload_chunks");
  assert.deepEqual(preload.inputSchema.required, ["world_session_id", "dimension", "min", "max", "request_id"]);
  assert.equal(preload.annotations.readOnlyHint, false);
  assert.equal(preload.annotations.destructiveHint, false);
});

test("request lookup requires session and canonical request ID", () => {
  const tool = TOOLS.find((candidate) => candidate.name === "get_operation_by_request_id");
  assert.deepEqual(tool.inputSchema.required, ["world_session_id", "request_id"]);
  assert.equal(tool.annotations.readOnlyHint, true);
  const pattern = new RegExp(tool.inputSchema.properties.request_id.pattern);
  assert.ok(pattern.test("33333333-3333-4333-8333-333333333333"));
  assert.ok(!pattern.test("33333333-3333-4333-8333-33333333333A"));
});

test("screenshots can set a camera pose without a player UUID", () => {
  const shot = TOOLS.find((candidate) => candidate.name === "request_screenshot");
  assert.deepEqual(shot.inputSchema.required, ["world_session_id", "request_id"]);
  const camera = shot.inputSchema.properties.camera;
  assert.deepEqual(camera.required, ["dimension", "pos"]);
  assert.equal(camera.properties.pos.properties.x.type, "number");
  assert.equal(camera.properties.pos.properties.y.type, "number");
  assert.equal(camera.properties.pos.properties.z.type, "number");
  assert.equal(camera.additionalProperties, false);
});

test("bounds and enums match the bridge contract", () => {
  const batch = TOOLS.find((tool) => tool.name === "batch_set_blocks");
  assert.equal(batch.inputSchema.properties.blocks.maxItems, 8192);
  const terrain = TOOLS.find((tool) => tool.name === "get_terrain");
  assert.equal(terrain.inputSchema.properties.clearance_height.maximum, 64);
  assert.equal(terrain.inputSchema.properties.clearance_height.default, 2);
  for (const name of ["set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks"]) {
    const tool = TOOLS.find((candidate) => candidate.name === name);
    assert.deepEqual(tool.inputSchema.properties.on_error.enum, ["stop", "continue"]);
    assert.equal(tool.inputSchema.properties.on_error.default, "stop");
  }
});
