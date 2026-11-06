import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { WideRegionCoordinator, partitionRegion } from '../dist/wide-region.js';
import { BridgeError } from '../dist/bridge.js';

const session = 'session';
const request = '11111111-1111-4111-8111-111111111111';
const args = { world_session_id: session, dimension: 'minecraft:overworld', min: { x: -1, y: 0, z: 0 }, max: { x: 16, y: 0, z: 0 }, request_id: request };
const counts = n => ({ total: n, processed: n, succeeded: n, failed: 0, skipped: 0 });
class FakeBridge {
  calls = [];
  info() { return Promise.resolve({ world_session_id: session, limits: { query_voxels: 65536, chunks_per_operation: 64 } }); }
  async callTool(name, input) {
    this.calls.push({ name, input });
    const id = `child-${this.calls.length}`;
    return { operation: { operation_id: id, state: 'succeeded', counts: counts(input.max.x - input.min.x + 1) } };
  }
  async result(_session, id) {
    const call = this.calls.find((_, i) => id === `child-${i + 1}`);
    const n = call.input.max.x - call.input.min.x + 1;
    return { ok: true, operation_id: id, min: call.input.min, max: call.input.max, offset: 0, count: n, total_voxels: n, encoding: 'palette_indices', palette: [{ id: 'minecraft:oak_log', properties: { axis: 'x' } }], indices: Array(n).fill(0), next_cursor: null };
  }
}
async function finished(coordinator, id) {
  for (let i = 0; i < 100; i++) {
    const { operation } = await coordinator.status(session, id);
    if (['succeeded', 'failed', 'cancelled'].includes(operation.state)) return operation;
    await delay(10);
  }
  throw new Error('operation did not finish');
}

test('partition covers every voxel exactly once, respecting negative chunk and tiny voxel limits', () => {
  const min = { x: -18, y: -2, z: -17 }, max = { x: 18, y: 2, z: 17 };
  const boxes = partitionRegion(min, max, 31);
  const seen = new Set();
  for (const box of boxes) {
    assert.equal(Math.floor(box.min.x / 16), Math.floor(box.max.x / 16));
    assert.equal(Math.floor(box.min.z / 16), Math.floor(box.max.z / 16));
    let size = 0;
    for (let x = box.min.x; x <= box.max.x; x++) for (let y = box.min.y; y <= box.max.y; y++) for (let z = box.min.z; z <= box.max.z; z++) {
      const key = `${x},${y},${z}`; assert.ok(!seen.has(key)); seen.add(key); size++;
    }
    assert.ok(size <= 31);
  }
  assert.equal(seen.size, 37 * 5 * 35);
});

test('composite pages preserve complete child states and deduplicate request IDs', async () => {
  const bridge = new FakeBridge(); const wide = new WideRegionCoordinator(bridge);
  const [{ operation }, replay] = await Promise.all([wide.submit(args), wide.submit({ ...args, encoding: 'auto' })]);
  assert.equal(replay.operation.operation_id, operation.operation_id);
  await assert.rejects(wide.submit({ ...args, encoding: 'palette_rle' }), { code: 'IDEMPOTENCY_CONFLICT' });
  assert.equal((await finished(wide, operation.operation_id)).counts.processed, 18);
  let cursor, seen = 0;
  do {
    const page = await wide.result(session, operation.operation_id, cursor);
    assert.equal(page.operation_id, operation.operation_id);
    assert.deepEqual(page.palette[0], { id: 'minecraft:oak_log', properties: { axis: 'x' } });
    assert.equal(page.count, page.indices.length); seen += page.count;
    cursor = page.next_cursor ?? undefined;
  } while (cursor);
  assert.equal(seen, 18); assert.equal(bridge.calls.length, 3);
  await assert.rejects(wide.result(session, operation.operation_id, 'bogus'), { code: 'INVALID_CURSOR' });
});

test('child failure exposes its frozen errors and never exposes partial snapshot', async () => {
  const bridge = new FakeBridge();
  bridge.callTool = async () => ({ operation: { operation_id: 'failed-child', state: 'failed', counts: { total: 1, processed: 1, succeeded: 0, failed: 1, skipped: 0 }, error_count: 1, terminal_error: { code: 'CHUNK_NOT_LOADED' } } });
  bridge.errors = async () => ({ ok: true, operation_id: 'failed-child', error_count: 1, errors: [{ code: 'CHUNK_NOT_LOADED', pos: { x: -1, y: 0, z: 0 } }], next_cursor: null });
  const wide = new WideRegionCoordinator(bridge); const { operation } = await wide.submit(args);
  const done = await finished(wide, operation.operation_id);
  assert.equal(done.state, 'failed'); assert.equal(done.counts.failed, 1);
  assert.equal((await wide.errors(session, operation.operation_id)).errors[0].code, 'CHUNK_NOT_LOADED');
  await assert.rejects(wide.result(session, operation.operation_id), { code: 'RESULT_UNAVAILABLE' });
});

test('cancellation during child submission waits for and cancels the accepted child', async () => {
  const bridge = new FakeBridge(); let accept;
  bridge.callTool = () => new Promise(resolve => { accept = resolve; });
  let cancelled = false;
  bridge.cancel = async () => { cancelled = true; return { ok: true }; };
  bridge.operation = async () => ({ operation: { operation_id: 'child', state: 'cancelled', counts: counts(0) } });
  const wide = new WideRegionCoordinator(bridge); const { operation } = await wide.submit(args);
  assert.equal((await wide.cancel(session, operation.operation_id)).cancel_requested, true);
  accept({ operation: { operation_id: 'child', state: 'running', counts: counts(0) } });
  assert.equal((await finished(wide, operation.operation_id)).state, 'cancelled');
  assert.equal(cancelled, true);
});

test('uncertain submission resolves by the original child request ID, without resubmitting', async () => {
  const bridge = new FakeBridge(); let submitted;
  bridge.callTool = async (name, input) => {
    if (name === 'get_region') { submitted = input.request_id; throw new BridgeError('BRIDGE_UNAVAILABLE', 'uncertain', true); }
    assert.equal(name, 'get_operation_by_request_id'); assert.equal(input.request_id, submitted);
    return { operation: { operation_id: 'child', state: 'cancelled', counts: counts(0) } };
  };
  const wide = new WideRegionCoordinator(bridge);
  const { operation } = await wide.submit({ ...args, max: args.min });
  assert.equal((await finished(wide, operation.operation_id)).state, 'cancelled');
});
