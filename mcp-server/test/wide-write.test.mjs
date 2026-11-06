import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { WideWriteCoordinator } from '../dist/wide-write.js';
import { BridgeError } from '../dist/bridge.js';

const session = 'session';
const request = '11111111-1111-4111-8111-111111111111';
const block = i => ({ pos: { x: i * 16, y: 0, z: 0 }, block_state: { id: 'minecraft:stone' } });
const args = n => ({ world_session_id: session, dimension: 'minecraft:overworld', blocks: Array.from({ length: n }, (_, i) => block(i)), request_id: request });
const counts = (n, failed = 0) => ({ total: n, processed: n, succeeded: n - failed, changed: n - failed, unchanged: 0, skipped: 0, failed });
class FakeBridge {
  calls = [];
  info() { return Promise.resolve({ world_session_id: session }); }
  async callTool(name, input) {
    this.calls.push({ name, input });
    return { operation: { operation_id: `child-${this.calls.length}`, state: 'succeeded', counts: counts(input.blocks.length), error_count: 0 } };
  }
}
async function finished(wide, id) {
  for (let i = 0; i < 300; i++) {
    const { operation } = await wide.status(session, id);
    if (['succeeded', 'failed', 'cancelled'].includes(operation.state)) return operation;
    await delay(10);
  }
  throw new Error('operation did not finish');
}

test('validates every shard before ordered writes across 8192 items and 64 chunks', async () => {
  const bridge = new FakeBridge(); const wide = new WideWriteCoordinator(bridge); const input = args(8200);
  const { operation } = await wide.submit(input);
  assert.equal((await wide.submit(input)).operation.operation_id, operation.operation_id);
  const explicitDefaults = { ...input, blocks: input.blocks.map(b => ({ ...b, block_state: { ...b.block_state, properties: {} } })) };
  assert.equal((await wide.submit(explicitDefaults)).operation.operation_id, operation.operation_id);
  const done = await finished(wide, operation.operation_id);
  assert.equal(done.state, 'succeeded'); assert.equal(done.counts.changed, 8200); assert.equal(done.atomic, false);
  const firstWrite = bridge.calls.findIndex(call => call.name === 'batch_set_blocks');
  assert.ok(firstWrite > 1);
  assert.ok(bridge.calls.slice(0, firstWrite).every(call => call.name === 'validate_batch_states'));
  const writes = bridge.calls.slice(firstWrite);
  assert.ok(writes.every(call => call.name === 'batch_set_blocks' && call.input.blocks.length <= 8192));
  assert.deepEqual(writes.flatMap(call => call.input.blocks.map(b => b.pos.x)), input.blocks.map(b => b.pos.x));
  assert.ok(writes.every(call => new Set(call.input.blocks.map(b => Math.floor(b.pos.x / 16))).size <= 64));
  await assert.rejects(wide.submit({ ...input, blocks: [block(0)] }), { code: 'IDEMPOTENCY_CONFLICT' });
});

test('splits a contiguous batch at the 8192-entry shard limit', async () => {
  const bridge = new FakeBridge(); const wide = new WideWriteCoordinator(bridge);
  const blocks = Array.from({ length: 9000 }, (_, i) => ({ pos: { x: i % 16, y: Math.floor(i / 256), z: Math.floor(i / 16) % 16 }, block_state: { id: 'minecraft:stone' } }));
  const { operation } = await wide.submit({ world_session_id: session, dimension: 'minecraft:overworld', request_id: request, blocks });
  const done = await finished(wide, operation.operation_id);
  assert.equal(done.state, 'succeeded'); assert.equal(done.counts.changed, 9000);
  const validations = bridge.calls.filter(call => call.name === 'validate_batch_states');
  const writes = bridge.calls.filter(call => call.name === 'batch_set_blocks');
  assert.deepEqual(validations.map(call => call.input.blocks.length), [8192, 808]);
  assert.deepEqual(writes.map(call => call.input.blocks.length), [8192, 808]);
  assert.ok(bridge.calls.indexOf(writes[0]) > bridge.calls.indexOf(validations[1]));
});

test('a late validation error forbids every write and maps candidate indices', async () => {
  const bridge = new FakeBridge();
  bridge.callTool = async function (name, input) {
    this.calls.push({ name, input });
    if (this.calls.length === 2) return { operation: { operation_id: 'bad', state: 'failed', counts: counts(64, 1), error_count: 1, terminal_error: { code: 'INVALID_BLOCK_STATE' } } };
    return { operation: { operation_id: 'ok', state: 'succeeded', counts: counts(input.blocks.length), error_count: 0 } };
  };
  bridge.errors = async () => ({ errors: [{ index: 0, candidate_index: 3, code: 'INVALID_BLOCK_ID' }], next_cursor: null });
  const wide = new WideWriteCoordinator(bridge); const { operation } = await wide.submit(args(130));
  const done = await finished(wide, operation.operation_id);
  assert.equal(done.state, 'failed'); assert.equal(done.counts.processed, 0);
  assert.ok(bridge.calls.every(call => call.name === 'validate_batch_states'));
  assert.equal((await wide.errors(session, operation.operation_id)).errors[0].candidate_index, 67);
});

test('duplicate positions across shards are rejected before child submission', async () => {
  const bridge = new FakeBridge(); const wide = new WideWriteCoordinator(bridge); const input = args(130);
  input.blocks[129] = input.blocks[0];
  await assert.rejects(wide.submit(input), { code: 'DUPLICATE_POSITION' });
  assert.equal(bridge.calls.length, 0);
});

test('uncertain submission is recovered by its original request without replaying writes', async () => {
  const bridge = new FakeBridge(); let requestId;
  bridge.callTool = async function (name, input) {
    this.calls.push({ name, input });
    if (name === 'validate_batch_states') { requestId = input.request_id; throw new BridgeError('BRIDGE_UNAVAILABLE', 'lost response', true); }
    if (name === 'get_operation_by_request_id') { assert.equal(input.request_id, requestId); return { operation: { operation_id: 'child', state: 'cancelled', counts: counts(0), error_count: 0 } }; }
    throw new Error('unexpected submission');
  };
  const wide = new WideWriteCoordinator(bridge); const { operation } = await wide.submit(args(1));
  assert.equal((await finished(wide, operation.operation_id)).state, 'cancelled');
  assert.equal(bridge.calls.filter(call => call.name === 'validate_batch_states').length, 1);
});

test('cancellation waits for the active child and retains its counts', async () => {
  const bridge = new FakeBridge(); let resolveWrite; let cancelCalls = 0;
  bridge.callTool = async (name, input) => name === 'validate_batch_states'
    ? { operation: { operation_id: 'valid', state: 'succeeded', counts: counts(input.blocks.length), error_count: 0 } }
    : new Promise(resolve => { resolveWrite = resolve; });
  bridge.cancel = async () => { cancelCalls++; return { ok: true }; };
  bridge.operation = async () => ({ operation: { operation_id: 'writing', state: 'cancelled', counts: counts(1), error_count: 0 } });
  const wide = new WideWriteCoordinator(bridge); const { operation } = await wide.submit(args(2));
  while (!resolveWrite) await delay(1);
  await wide.cancel(session, operation.operation_id);
  resolveWrite({ operation: { operation_id: 'writing', state: 'running', counts: counts(0), error_count: 0 } });
  const done = await finished(wide, operation.operation_id);
  assert.equal(done.state, 'cancelled'); assert.equal(done.counts.changed, 1); assert.equal(cancelCalls, 1);
});

test('continue records partial errors and executes the next shard', async () => {
  const bridge = new FakeBridge(); let writes = 0;
  bridge.callTool = async function (name, input) {
    this.calls.push({ name, input });
    if (name === 'batch_set_blocks' && ++writes === 1) return { operation: { operation_id: 'failed', state: 'failed', counts: counts(input.blocks.length, 1), error_count: 1, terminal_error: { code: 'PARTIAL_FAILURE' } } };
    return { operation: { operation_id: `child-${this.calls.length}`, state: 'succeeded', counts: counts(input.blocks.length), error_count: 0 } };
  };
  bridge.errors = async () => ({ errors: [{ index: 0, candidate_index: 2, code: 'PRECONDITION_FAILED' }], next_cursor: null });
  const wide = new WideWriteCoordinator(bridge);
  const { operation } = await wide.submit({ ...args(130), on_error: 'continue' });
  const done = await finished(wide, operation.operation_id);
  assert.equal(done.state, 'failed'); assert.equal(done.counts.processed, 130); assert.equal(done.counts.failed, 1);
  assert.equal(writes, 3); assert.equal((await wide.errors(session, operation.operation_id)).errors[0].candidate_index, 2);
});
