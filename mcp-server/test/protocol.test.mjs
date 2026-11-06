import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { once } from "node:events";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const TOKEN = "protocol-test-token-0123456789abcdef";

async function startStubBridge() {
  const requests = [];
  const server = http.createServer((req, res) => {
    requests.push({ method: req.method, url: req.url, authorization: req.headers.authorization });
    const send = (status, body) => {
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    };
    if (req.headers.authorization !== `Bearer ${TOKEN}`) {
      return send(401, { ok: false, error: { code: "UNAUTHORIZED", message: "bad token", retryable: false, details: {} } });
    }
    if (req.method === "GET" && req.url === "/v1/info") {
      return send(200, {
        ok: true, ready: true, world_session_id: "s-protocol",
        dimensions: [{ id: "minecraft:overworld", min_y: -64, max_y_exclusive: 320,
          min_x: -29999984, max_x: 29999983, min_z: -29999984, max_z: 29999983 }],
        players: [{ player_uuid: "22222222-2222-4222-8222-222222222222", name: "Builder", dimension: "minecraft:overworld" }],
        limits: { query_voxels: 65536, write_voxels: 262144, batch_entries: 8192, chunks_per_operation: 64,
          queued_operations: 32, request_bytes: 2097152, page_bytes: 524288, terminal_ttl_seconds: 300,
          tick_budget_ms: 2, write_candidates_per_tick: 256 },
        supported_tools: ["get_world_info", "get_block"],
      });
    }
    if (req.method === "POST" && req.url === "/v1/operations") {
      let body = "";
      req.setEncoding("utf8");
      req.on("data", (chunk) => (body += chunk));
      req.on("end", () => {
        const parsed = JSON.parse(body);
        if (parsed.tool === "get_block") {
          return send(200, {
            ok: true, world_session_id: "s-protocol", dimension: "minecraft:overworld",
            pos: { x: 0, y: 64, z: 0 }, block_state: { id: "minecraft:stone", properties: {} },
            is_air: false, fluid: { is_empty: true, id: null, is_source: null },
            has_collision: true, observed_tick: 10,
          });
        }
        if (parsed.tool === "get_biome" || parsed.tool === "get_light") {
          return send(200, { ok: true, ...parsed.arguments, observed_tick: 10,
            ...(parsed.tool === "get_biome" ? { biome_id: "minecraft:plains" } : { block_light: 3, sky_light: 15, raw_brightness: 12 }) });
        }
        if (parsed.tool === "undo_operation") {
          return send(202, { ok: true, operation: { operation_id: "op-undo", request_id: parsed.arguments.request_id, state: "queued" } });
        }
        if (parsed.tool === "get_operation_by_request_id") {
          assert.deepEqual(parsed.arguments, {
            world_session_id: "s-protocol", request_id: "33333333-3333-4333-8333-333333333333",
          });
          return send(200, { ok: true, operation: {
            operation_id: "op-protocol", request_id: parsed.arguments.request_id, state: "queued",
          } });
        }
        if (parsed.tool === "get_region") {
          return send(202, {
            ok: true,
            operation: {
              operation_id: "op-protocol", request_id: parsed.arguments.request_id,
              world_session_id: "s-protocol", dimension: "minecraft:overworld",
              tool: "get_region", kind: "read", state: "queued",
              counts: { total: 1, processed: 0, succeeded: 0, changed: 0, unchanged: 0, skipped: 0, failed: 0 },
              error_count: 0, created_at: "2026-11-05T12:00:00Z", started_tick: null,
              finished_tick: null, expires_at: null, result_available: false, terminal_error: null,
            },
          });
        }
        if (parsed.tool === "preload_chunks") {
          return send(202, {
            ok: true,
            operation: {
              operation_id: "op-preload", request_id: parsed.arguments.request_id,
              world_session_id: "s-protocol", dimension: "minecraft:overworld",
              tool: "preload_chunks", kind: "load", state: "queued",
              counts: { total: 4, processed: 0, succeeded: 0, changed: 0, unchanged: 0, skipped: 0, failed: 0 },
              error_count: 0, created_at: "2026-11-05T12:00:00Z", started_tick: null,
              finished_tick: null, expires_at: null, result_available: false, terminal_error: null,
            },
          });
        }
        return send(400, { ok: false, error: { code: "INVALID_ARGUMENT", message: "unexpected tool", retryable: false, details: {} } });
      });
      return;
    }
    send(404, { ok: false, error: { code: "INVALID_ARGUMENT", message: "not found", retryable: false, details: {} } });
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return { server, requests, port: server.address().port };
}

test("MCP client discovers and calls tools through the real SDK stdio transport", async (t) => {
  const stub = await startStubBridge();
  t.after(() => stub.server.close());
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [new URL("../dist/index.js", import.meta.url).pathname],
    env: { ...process.env, MINECRAFT_MCP_URL: `http://127.0.0.1:${stub.port}`, MINECRAFT_MCP_TOKEN: TOKEN },
    stderr: "pipe",
  });
  const client = new Client({ name: "protocol-test", version: "1.0.0" });
  t.after(async () => {
    await client.close();
  });
  await client.connect(transport);

  const listed = await client.listTools();
  assert.ok(listed.tools.some((tool) => tool.name === "get_terrain"));

  const info = await client.callTool({ name: "get_world_info", arguments: {} });
  assert.equal(info.isError, false);
  const infoBody = JSON.parse(info.content[0].text);
  assert.equal(infoBody.world_session_id, "s-protocol");
  assert.equal(infoBody.dimensions[0].min_y, -64);

  const block = await client.callTool({
    name: "get_block",
    arguments: { world_session_id: "s-protocol", dimension: "minecraft:overworld", pos: { x: 0, y: 64, z: 0 } },
  });
  assert.equal(block.isError, false);
  const blockBody = JSON.parse(block.content[0].text);
  assert.equal(blockBody.block_state.id, "minecraft:stone");
  assert.equal(blockBody.observed_tick, 10);
  const pointArgs = { world_session_id: "s-protocol", dimension: "minecraft:overworld", pos: { x: 0, y: 64, z: 0 } };
  const biome = await client.callTool({ name: "get_biome", arguments: pointArgs });
  assert.equal(JSON.parse(biome.content[0].text).biome_id, "minecraft:plains");
  const light = await client.callTool({ name: "get_light", arguments: pointArgs });
  assert.equal(JSON.parse(light.content[0].text).raw_brightness, 12);
  const undo = await client.callTool({ name: "undo_operation", arguments: {
    world_session_id: "s-protocol", dimension: "minecraft:overworld", operation_id: "source",
    request_id: "55555555-5555-4555-8555-555555555555",
  } });
  assert.equal(JSON.parse(undo.content[0].text).operation.operation_id, "op-undo");

  const region = await client.callTool({
    name: "get_region",
    arguments: {
      world_session_id: "s-protocol", dimension: "minecraft:overworld",
      min: { x: 0, y: 64, z: 0 }, max: { x: 0, y: 64, z: 0 },
      request_id: "33333333-3333-4333-8333-333333333333",
    },
  });
  assert.equal(region.isError, false);
  assert.equal(JSON.parse(region.content[0].text).operation.operation_id, "op-protocol");

  const preload = await client.callTool({
    name: "preload_chunks",
    arguments: {
      world_session_id: "s-protocol", dimension: "minecraft:overworld",
      min: { x: 0, y: 64, z: 0 }, max: { x: 31, y: 64, z: 31 },
      request_id: "66666666-6666-4666-8666-666666666666",
    },
  });
  assert.equal(preload.isError, false);
  const preloadBody = JSON.parse(preload.content[0].text);
  assert.equal(preloadBody.operation.operation_id, "op-preload");
  assert.equal(preloadBody.operation.kind, "load");
  assert.equal(preloadBody.operation.counts.total, 4);

  const lookup = await client.callTool({
    name: "get_operation_by_request_id",
    arguments: { world_session_id: "s-protocol", request_id: "33333333-3333-4333-8333-333333333333" },
  });
  assert.equal(lookup.isError, false);
  assert.equal(JSON.parse(lookup.content[0].text).operation.operation_id, "op-protocol");

  const unknown = await client.callTool({ name: "not_a_tool", arguments: {} });
  assert.equal(unknown.isError, true);
  assert.equal(JSON.parse(unknown.content[0].text).error.code, "UNKNOWN_TOOL");

  assert.ok(stub.requests.every((request) => request.authorization === `Bearer ${TOKEN}`));
});
