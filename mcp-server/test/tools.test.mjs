import test from "node:test";
import assert from "node:assert/strict";
import { TOOLS } from "../dist/tools.js";

const EXPECTED = [
  "get_world_info", "get_player_position", "get_block", "get_region", "get_terrain",
  "set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks",
  "get_operation", "cancel_operation", "get_operation_errors", "get_query_result",
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
  const asyncWrites = new Set(["set_block", "remove_block", "fill_region", "replace_blocks", "batch_set_blocks"]);
  for (const tool of TOOLS) {
    if (asyncWrites.has(tool.name)) {
      assert.ok(tool.inputSchema.required.includes("request_id"), tool.name);
      assert.equal(tool.annotations.readOnlyHint, false, tool.name);
      assert.equal(tool.annotations.destructiveHint, true, tool.name);
    }
  }
  for (const name of ["get_region", "get_terrain"]) {
    const tool = TOOLS.find((candidate) => candidate.name === name);
    assert.ok(tool.inputSchema.required.includes("request_id"), name);
    assert.equal(tool.annotations.readOnlyHint, true, name);
  }
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
