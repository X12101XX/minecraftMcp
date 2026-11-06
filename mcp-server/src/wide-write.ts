import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { BridgeClient, BridgeError } from './bridge.js';

type Obj = Record<string, unknown>;
type Counts = { total: number; processed: number; succeeded: number; changed: number; unchanged: number; skipped: number; failed: number };
type Shard = { start: number; blocks: Obj[] };
type Job = {
  id: string; request: string; session: string; dimension: string; fingerprint: string; onError: string;
  shards: Shard[]; phase: 'validation' | 'writing'; shard: number; state: string; created: string; expires: number | null;
  cancel: boolean; childId?: string; childRequest?: string; uncertain: boolean; counts: Counts; active?: Counts;
  errors: Obj[]; terminalError: Obj | null; cursors: string[];
};
const MAX_ENTRIES = 16384;
const MAX_SHARD_ENTRIES = 8192;
const MAX_INPUT_BYTES = 1900000;
const MAX_JOBS = 8;
const TTL = 300000;
const terminal = (s: unknown) => s === 'succeeded' || s === 'failed' || s === 'cancelled';
const counts = (total: number): Counts => ({ total, processed: 0, succeeded: 0, changed: 0, unchanged: 0, skipped: 0, failed: 0 });
const keys: (keyof Counts)[] = ['processed', 'succeeded', 'changed', 'unchanged', 'skipped', 'failed'];
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function text(v: unknown, field: string, max = 128): string {
  if (typeof v !== 'string' || !v.length || v.length > max) throw new BridgeError('INVALID_ARGUMENT', `Invalid ${field}`);
  return v;
}
function object(v: unknown, field: string): Obj {
  if (!v || typeof v !== 'object' || Array.isArray(v)) throw new BridgeError('INVALID_ARGUMENT', `${field} must be an object`);
  return v as Obj;
}
function validateState(v: unknown, field: string, exact: boolean): void {
  const state = object(v, field);
  if (Object.keys(state).some(k => k !== 'id' && k !== 'properties')) throw new BridgeError('INVALID_ARGUMENT', `Unknown ${field} field`);
  text(state.id, `${field}.id`, 256);
  if (exact && state.properties === undefined) throw new BridgeError('INVALID_ARGUMENT', `${field}.properties is required`);
  if (state.properties !== undefined) {
    const properties = object(state.properties, `${field}.properties`);
    if (Object.keys(properties).length > 64) throw new BridgeError('LIMIT_EXCEEDED', 'Too many state properties');
    for (const [key, value] of Object.entries(properties)) { text(key, 'property name', 64); text(value, 'property value', 64); }
  }
}
function canonical(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(canonical);
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v as Obj).sort(([a], [b]) => a.localeCompare(b)).map(([k, x]) => [k, canonical(x)]));
  return v;
}
function operation(v: Obj): Obj {
  if (!v.operation || typeof v.operation !== 'object' || Array.isArray(v.operation)) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Missing child operation');
  return v.operation as Obj;
}
function childCounts(v: unknown, total: number): Counts {
  const value = object(v, 'child counts'); const result = counts(total);
  for (const k of keys) {
    if (!Number.isSafeInteger(value[k]) || (value[k] as number) < 0 || (value[k] as number) > total) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Invalid child counts');
    result[k] = value[k] as number;
  }
  return result;
}
function retriable(e: unknown): boolean {
  return e instanceof BridgeError && ['BRIDGE_UNAVAILABLE', 'SERVER_TICK_TIMEOUT', 'BRIDGE_RESPONSE_INVALID', 'QUEUE_FULL', 'OPERATION_NOT_FOUND'].includes(e.code);
}
async function bounded<T>(promise: Promise<T>, deadline: number, code: string): Promise<T> {
  const remaining = deadline - Date.now();
  if (remaining <= 0) throw new BridgeError(code, 'Recovery deadline expired; outcome may be unknown');
  const controller = new AbortController();
  try {
    return await Promise.race([
      promise,
      delay(remaining, undefined, { signal: controller.signal }).then(() => { throw new BridgeError(code, 'Recovery deadline expired; outcome may be unknown'); }),
    ]);
  } finally { controller.abort(); }
}

export class WideWriteCoordinator {
  private jobs = new Map<string, Job>();
  private requests = new Map<string, Job>();
  constructor(private client: BridgeClient) {}
  owns(id: string): boolean { return id.startsWith('wide-write:'); }
  private prune(): void {
    for (const j of this.jobs.values()) if (j.expires !== null && Date.now() >= j.expires) { this.jobs.delete(j.id); this.requests.delete(`${j.session}:${j.request}`); }
  }
  private async session(session: string): Promise<void> {
    const info = await this.client.info();
    if (info.world_session_id !== session) throw new BridgeError('WORLD_SESSION_MISMATCH', 'World session has changed');
  }
  private get(session: string, id: string): Job {
    this.prune(); const j = this.jobs.get(id);
    if (!j || j.session !== session) throw new BridgeError('OPERATION_NOT_FOUND', 'Operation not found');
    return j;
  }
  async submit(args: Obj): Promise<Obj> {
    if (Object.keys(args).some(k => !['world_session_id', 'dimension', 'blocks', 'on_error', 'request_id'].includes(k))) throw new BridgeError('INVALID_ARGUMENT', 'Unknown argument');
    const session = text(args.world_session_id, 'world_session_id'); const dimension = text(args.dimension, 'dimension', 256);
    const request = text(args.request_id, 'request_id');
    if (!uuid.test(request)) throw new BridgeError('INVALID_ARGUMENT', 'request_id must be a lowercase UUID');
    const onError = args.on_error === undefined ? 'stop' : args.on_error;
    if (onError !== 'stop' && onError !== 'continue') throw new BridgeError('INVALID_ARGUMENT', 'Invalid on_error');
    if (!Array.isArray(args.blocks) || !args.blocks.length || args.blocks.length > MAX_ENTRIES) throw new BridgeError('LIMIT_EXCEEDED', `blocks must contain 1 to ${MAX_ENTRIES} entries`);
    if (Buffer.byteLength(JSON.stringify(args), 'utf8') > MAX_INPUT_BYTES) throw new BridgeError('LIMIT_EXCEEDED', 'Input exceeds wide-write byte limit');
    const shards: Shard[] = []; let blocks: Obj[] = []; let chunks = new Set<string>(); const positions = new Set<string>();
    for (let i = 0; i < args.blocks.length; i++) {
      const item = object(args.blocks[i], `blocks[${i}]`);
      if (Object.keys(item).some(k => !['pos', 'block_state', 'expected_state'].includes(k))) throw new BridgeError('INVALID_ARGUMENT', 'Unknown block entry field');
      const p = object(item.pos, 'pos');
      if (Object.keys(p).sort().join(',') !== 'x,y,z' || !['x', 'y', 'z'].every(k => Number.isInteger(p[k]) && (p[k] as number) >= -2147483648 && (p[k] as number) <= 2147483647)) throw new BridgeError('INVALID_ARGUMENT', 'Position must contain signed 32-bit integers');
      validateState(item.block_state, 'block_state', false);
      if (item.expected_state !== undefined) validateState(item.expected_state, 'expected_state', true);
      const position = `${p.x},${p.y},${p.z}`;
      if (positions.has(position)) throw new BridgeError('DUPLICATE_POSITION', 'Batch contains a duplicate coordinate', false, { candidate_index: i, pos: p });
      positions.add(position);
      const chunk = `${Math.floor((p.x as number) / 16)},${Math.floor((p.z as number) / 16)}`;
      if (blocks.length === MAX_SHARD_ENTRIES || (!chunks.has(chunk) && chunks.size === 64)) { shards.push({ start: i - blocks.length, blocks }); blocks = []; chunks = new Set(); }
      blocks.push(item); chunks.add(chunk);
    }
    if (blocks.length) shards.push({ start: args.blocks.length - blocks.length, blocks });
    const normalizedBlocks = args.blocks.map(value => {
      const item = value as Obj;
      const state = item.block_state as Obj;
      return state.properties === undefined ? { ...item, block_state: { ...state, properties: {} } } : item;
    });
    const fingerprint = JSON.stringify(canonical({ dimension, blocks: normalizedBlocks, on_error: onError }));
    await this.session(session); this.prune();
    const key = `${session}:${request}`; const prior = this.requests.get(key);
    if (prior) { if (prior.fingerprint !== fingerprint) throw new BridgeError('IDEMPOTENCY_CONFLICT', 'request_id was used for a different request'); return { ok: true, operation: this.describe(prior) }; }
    if (this.jobs.size >= MAX_JOBS) throw new BridgeError('RESOURCE_EXHAUSTED', 'Too many retained wide-write operations', true);
    const j: Job = { id: `wide-write:${randomUUID()}`, request, session, dimension, fingerprint, onError, shards, phase: 'validation', shard: 0, state: 'queued', created: new Date().toISOString(), expires: null, cancel: false, uncertain: false, counts: counts(args.blocks.length), errors: [], terminalError: null, cursors: [] };
    this.jobs.set(j.id, j); this.requests.set(key, j); void this.run(j);
    return { ok: true, operation: this.describe(j) };
  }
  private describe(j: Job): Obj {
    const c = { ...j.counts };
    if (j.phase === 'writing' && j.active) for (const k of keys) c[k] += j.active[k];
    return { operation_id: j.id, request_id: j.request, world_session_id: j.session, dimension: j.dimension, tool: 'batch_set_blocks_sharded', kind: 'write', state: j.state, phase: j.phase, counts: c, error_count: j.errors.length, created_at: j.created, started_tick: null, finished_tick: null, expires_at: j.expires === null ? null : new Date(j.expires).toISOString(), result_available: false, terminal_error: j.terminalError, shard_count: j.shards.length, completed_shards: j.shard, child_operation_id: j.childId ?? null, child_request_id: j.childRequest ?? null, submission_uncertain: j.uncertain, atomic: false };
  }
  async status(session: string, id: string): Promise<Obj> { return { ok: true, operation: this.describe(this.get(session, id)) }; }
  async lookup(session: string, request: string): Promise<Obj | undefined> { this.prune(); const j = this.requests.get(`${session}:${request}`); return j ? { ok: true, operation: this.describe(j) } : undefined; }
  async cancel(session: string, id: string): Promise<Obj> { const j = this.get(session, id); if (!terminal(j.state)) j.cancel = true; return { ok: true, cancel_requested: !terminal(j.state) || j.state === 'cancelled', operation: this.describe(j) }; }
  async errors(session: string, id: string, cursor?: string): Promise<Obj> {
    const j = this.get(session, id);
    if (!terminal(j.state)) throw new BridgeError('OPERATION_NOT_READY', 'Operation is not finished', true);
    const offset = cursor === undefined ? 0 : j.cursors.indexOf(cursor);
    if (offset < 0 || (offset >= j.errors.length && !(offset === 0 && j.errors.length === 0))) throw new BridgeError('INVALID_CURSOR', 'Invalid cursor');
    let end = Math.min(offset + 128, j.errors.length);
    for (;;) {
      const next = end < j.errors.length ? (j.cursors[end] ??= randomUUID()) : null;
      const page = { ok: true, world_session_id: session, operation_id: id, error_count: j.errors.length, errors: j.errors.slice(offset, end), next_cursor: next, expires_at: new Date(j.expires!).toISOString() };
      if (Buffer.byteLength(JSON.stringify(page), 'utf8') <= 512 * 1024) return page;
      if (end <= offset + 1) throw new BridgeError('RESULT_TOO_LARGE', 'One error cannot fit in a page; the child operation reference remains available');
      end--;
    }
  }
  private finish(j: Job, state: string, error: Obj | null = null): void {
    j.state = state; j.terminalError = error; j.expires = Date.now() + TTL;
    j.cursors = [];
    j.shards = j.shards.map(s => ({ start: s.start, blocks: [] }));
  }
  private async run(j: Job): Promise<void> {
    j.state = 'running';
    try {
      for (const phase of ['validation', 'writing'] as const) {
        j.phase = phase;
        for (j.shard = 0; j.shard < j.shards.length; j.shard++) {
          if (j.cancel) { this.finish(j, 'cancelled'); return; }
          const shard = j.shards[j.shard]!; j.childId = undefined; j.childRequest = randomUUID(); j.active = undefined;
          let op: Obj;
          for (;;) {
            try {
              op = operation(await this.client.callTool(phase === 'validation' ? 'validate_batch_states' : 'batch_set_blocks', { world_session_id: j.session, dimension: j.dimension, blocks: shard.blocks, on_error: j.onError, request_id: j.childRequest }));
              break;
            } catch (e) {
              if (e instanceof BridgeError && e.code === 'QUEUE_FULL') {
                if (j.cancel) { this.finish(j, 'cancelled'); return; }
                await delay(500);
                continue;
              }
              if (!retriable(e)) throw e;
              j.uncertain = true;
              const deadline = Date.now() + 30000;
              for (;;) {
                await bounded(delay(500), deadline, 'SUBMISSION_OUTCOME_UNKNOWN');
                try { op = operation(await bounded(this.client.callTool('get_operation_by_request_id', { world_session_id: j.session, request_id: j.childRequest }), deadline, 'SUBMISSION_OUTCOME_UNKNOWN')); break; }
                catch (lookup) { if (!retriable(lookup)) throw lookup; }
              }
              j.uncertain = false;
              break;
            }
          }
          j.childId = text(op.operation_id, 'child operation_id');
          for (;;) {
            if (phase === 'writing') j.active = childCounts(op.counts, shard.blocks.length);
            if (terminal(op.state)) break;
            if (j.cancel) { try { await this.client.cancel(j.session, j.childId); } catch (e) { if (!retriable(e)) throw e; } }
            await delay(500);
            const deadline = Date.now() + 30000;
            for (;;) {
              try { op = operation(await bounded(this.client.operation(j.session, j.childId), deadline, 'CHILD_OUTCOME_UNKNOWN')); break; }
              catch (e) {
                if (e instanceof BridgeError && e.code === 'OPERATION_NOT_FOUND') throw new BridgeError('CHILD_OUTCOME_UNKNOWN', 'Child operation disappeared; its final effects and counts are unknown');
                if (!retriable(e)) throw e; await bounded(delay(500), deadline, 'CHILD_OUTCOME_UNKNOWN');
              }
            }
          }
          if (phase === 'writing' && j.active) { for (const k of keys) j.counts[k] += j.active[k]; j.active = undefined; }
          if (typeof op.error_count !== 'number' || !Number.isSafeInteger(op.error_count) || op.error_count < 0 || op.error_count > shard.blocks.length + 1) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Invalid child error count');
          if (op.error_count > 0) {
            let cursor: string | undefined; let received = 0; const seenCursors = new Set<string>();
            do {
              let page: Obj;
              const deadline = Date.now() + 30000;
              for (;;) {
                try { page = await bounded(this.client.errors(j.session, j.childId, cursor), deadline, 'CHILD_ERRORS_UNAVAILABLE'); break; }
                catch (e) {
                  if (e instanceof BridgeError && e.code === 'OPERATION_NOT_FOUND') throw new BridgeError('CHILD_ERRORS_UNAVAILABLE', 'Child expired before all errors could be retrieved');
                  if (!retriable(e)) throw e; await bounded(delay(500), deadline, 'CHILD_ERRORS_UNAVAILABLE');
                }
              }
              if (!Array.isArray(page.errors) || page.errors.length === 0 || received + page.errors.length > op.error_count) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Invalid child error page');
              for (const value of page.errors) {
                const error = object(value, 'child error'); const candidate = error.candidate_index;
                j.errors.push({ ...error, index: j.errors.length, candidate_index: typeof candidate === 'number' ? shard.start + candidate : null, phase, shard_index: j.shard, child_operation_id: j.childId }); received++;
              }
              if (page.next_cursor !== null && typeof page.next_cursor !== 'string') throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Invalid error cursor');
              cursor = page.next_cursor === null ? undefined : page.next_cursor as string;
              if (cursor !== undefined) {
                if (seenCursors.has(cursor)) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Repeated child error cursor');
                seenCursors.add(cursor);
              }
            } while (cursor !== undefined);
            if (received !== op.error_count) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Incomplete child errors');
          }
          if (op.state === 'cancelled') { this.finish(j, 'cancelled'); return; }
          if (op.state !== 'succeeded') {
            const error = (op.terminal_error && typeof op.terminal_error === 'object' ? op.terminal_error : {}) as Obj;
            if (phase !== 'writing' || j.onError !== 'continue' || error.code !== 'PARTIAL_FAILURE') { this.finish(j, 'failed', { code: 'SHARD_FAILED', message: 'A shard failed; completed changes are not rolled back', details: { phase, shard_index: j.shard, child_operation_id: j.childId, child_terminal_error: error } }); return; }
          }
          if (j.cancel) { this.finish(j, 'cancelled'); return; }
        }
      }
      this.finish(j, j.counts.failed ? 'failed' : 'succeeded', j.counts.failed ? { code: 'PARTIAL_FAILURE', message: 'Some candidates failed; completed changes are not rolled back', details: {} } : null);
    } catch (e) {
      const error = e instanceof BridgeError ? e : new BridgeError('INTERNAL_ERROR', 'Wide-write coordinator failed');
      if (!j.childId && !j.uncertain) j.errors.push({ index: j.errors.length, candidate_index: null, pos: null, code: error.code, message: error.message, retryable: error.retryable, effect_unknown: false, details: error.details, phase: j.phase, shard_index: j.shard });
      this.finish(j, 'failed', { code: error.code, message: error.message, retryable: error.retryable, details: { ...error.details, child_operation_id: j.childId ?? null, child_request_id: j.childRequest ?? null, submission_uncertain: j.uncertain, effects_may_have_occurred: j.phase === 'writing' } });
    }
  }
}
