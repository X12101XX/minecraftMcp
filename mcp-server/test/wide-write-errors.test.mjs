import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { WideWriteCoordinator } from '../dist/wide-write.js';

const session = 'session';
const input = { world_session_id: session, dimension: 'minecraft:overworld', request_id: '22222222-2222-4222-8222-222222222222', blocks: Array.from({ length: 128 }, (_, i) => ({ pos: { x: i, y: 0, z: 0 }, block_state: { id: 'minecraft:stone' } })) };
const counts = { total: 128, processed: 128, succeeded: 0, changed: 0, unchanged: 0, skipped: 0, failed: 128 };

test('parent error pages are byte bounded, complete and stable without world-info calls', async () => {
  let infoCalls = 0;
  const bridge = {
    async info() { infoCalls++; if (infoCalls > 1) throw new Error('world tick stopped'); return { world_session_id: session }; },
    async callTool() { return { operation: { operation_id: 'child', state: 'failed', counts, error_count: 128, terminal_error: { code: 'INVALID_BLOCK_STATE' } } }; },
    async errors() { return { errors: Array.from({ length: 128 }, (_, i) => ({ candidate_index: i, message: 'x'.repeat(5000) })), next_cursor: null }; },
  };
  const wide = new WideWriteCoordinator(bridge); const { operation } = await wide.submit(input);
  let done;
  for (let i = 0; i < 100; i++) { done = (await wide.status(session, operation.operation_id)).operation; if (done.state === 'failed') break; await delay(10); }
  assert.equal(done.state, 'failed'); assert.equal(infoCalls, 1);
  let cursor; let count = 0;
  do {
    const page = await wide.errors(session, operation.operation_id, cursor);
    assert.ok(Buffer.byteLength(JSON.stringify(page)) <= 512 * 1024);
    assert.deepEqual(page, await wide.errors(session, operation.operation_id, cursor));
    count += page.errors.length; cursor = page.next_cursor ?? undefined;
  } while (cursor);
  assert.equal(count, 128);
  assert.equal((await wide.lookup(session, input.request_id)).operation.operation_id, operation.operation_id);
});
