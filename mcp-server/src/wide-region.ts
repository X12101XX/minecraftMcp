import { randomUUID } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import { BridgeClient, BridgeError } from "./bridge.js";

type Point = { x: number; y: number; z: number };
type Box = { min: Point; max: Point };
type RecordValue = Record<string, unknown>;
type Job = {
  id: string; request: string; session: string; dimension: string; fingerprint: string;
  boxes: Box[]; encoding: string; total: number; processed: number; state: string;
  created: string; expires: number | null; cancel: boolean; childId?: string; childRequest?: string;
  childIndex: number; pages: RecordValue[]; cursors: string[]; bytes: number;
  error: RecordValue | null; uncertain: boolean; childCounts?: RecordValue;
  errorPages: RecordValue[]; errorCursors: string[]; errorBytes: number; childErrorCount: number;
};

const MAX_VOXELS = 1_048_576;
const MAX_SHARDS = 4096;
const MAX_JOBS = 8;
const MAX_BYTES = 64 * 1024 * 1024;
const TTL = 300_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const terminal = (state: string) => state === 'succeeded' || state === 'failed' || state === 'cancelled';

function point(value: unknown, field: string): Point {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new BridgeError('INVALID_ARGUMENT', `${field} must be a position`);
  const p = value as RecordValue;
  if (Object.keys(p).sort().join(',') !== 'x,y,z' || !['x', 'y', 'z'].every(k => Number.isInteger(p[k]) && (p[k] as number) >= -2147483648 && (p[k] as number) <= 2147483647)) {
    throw new BridgeError('INVALID_ARGUMENT', `${field} must contain three signed 32-bit integers`);
  }
  return { x: p.x as number, y: p.y as number, z: p.z as number };
}
function str(value: unknown, field: string, maxLength = 128): string {
  if (typeof value !== 'string' || !value.length || value.length > maxLength) throw new BridgeError('INVALID_ARGUMENT', `${field} must be a non-empty string of at most ${maxLength} characters`);
  return value;
}
function operation(value: RecordValue): RecordValue {
  if (!value.operation || typeof value.operation !== 'object' || Array.isArray(value.operation)) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Missing child operation');
  return value.operation as RecordValue;
}
function childId(op: RecordValue): string {
  if (typeof op.operation_id !== 'string' || !op.operation_id) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Missing child operation ID');
  return op.operation_id;
}

// Partition into non-overlapping chunk-aligned XY/Z boxes. Each shard touches exactly one
// chunk; splitting Y, then Z and X also handles a runtime voxel limit below 256.
export function partitionRegion(min: Point, max: Point, limit: number): Box[] {
  if (!Number.isSafeInteger(limit) || limit < 1) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Invalid query voxel limit');
  const boxes: Box[] = [];
  for (let x = min.x; x <= max.x;) {
    const xEnd = Math.min(max.x, Math.floor(x / 16) * 16 + 15, x + limit - 1);
    const sx = xEnd - x + 1;
    for (let z = min.z; z <= max.z;) {
      const zEnd = Math.min(max.z, Math.floor(z / 16) * 16 + 15, z + Math.floor(limit / sx) - 1);
      const sz = zEnd - z + 1;
      const sy = Math.floor(limit / (sx * sz));
      for (let y = min.y; y <= max.y;) {
        const yEnd = Math.min(max.y, y + sy - 1);
        boxes.push({ min: { x, y, z }, max: { x: xEnd, y: yEnd, z: zEnd } });
        if (boxes.length > MAX_SHARDS) throw new BridgeError('LIMIT_EXCEEDED', `region requires more than ${MAX_SHARDS} shards`);
        y = yEnd + 1;
      }
      z = zEnd + 1;
    }
    x = xEnd + 1;
  }
  return boxes;
}

export class WideRegionCoordinator {
  private readonly jobs = new Map<string, Job>();
  private readonly requests = new Map<string, Job>();
  private bytes = 0;
  constructor(private readonly client: BridgeClient) {}

  private prune(): void {
    const now = Date.now();
    for (const job of this.jobs.values()) if (job.expires !== null && job.expires <= now) {
      this.jobs.delete(job.id); this.requests.delete(`${job.session}:${job.request}`); this.bytes -= job.bytes + job.errorBytes;
    }
  }
  private async session(session: string): Promise<void> {
    const info = await this.client.info();
    if (info.world_session_id !== session) throw new BridgeError('WORLD_SESSION_MISMATCH', 'World session has changed');
  }
  private get(session: string, id: string): Job {
    this.prune();
    const job = this.jobs.get(id);
    if (!job || job.session !== session) throw new BridgeError('OPERATION_NOT_FOUND', 'Operation not found');
    return job;
  }
  owns(id: string): boolean { return id.startsWith('wide-region:'); }
  async submit(args: RecordValue): Promise<RecordValue> {
    const keys = ['world_session_id', 'dimension', 'min', 'max', 'encoding', 'request_id'];
    if (Object.keys(args).some(key => !keys.includes(key))) throw new BridgeError('INVALID_ARGUMENT', 'Unknown argument');
    const session = str(args.world_session_id, 'world_session_id');
    const dimension = str(args.dimension, 'dimension', 256);
    const request = str(args.request_id, 'request_id');
    if (!UUID.test(request)) throw new BridgeError('INVALID_ARGUMENT', 'request_id must be a lowercase UUID');
    const min = point(args.min, 'min'); const max = point(args.max, 'max');
    if (min.x > max.x || min.y > max.y || min.z > max.z) throw new BridgeError('INVALID_ARGUMENT', 'min must not exceed max');
    const total = (max.x - min.x + 1) * (max.y - min.y + 1) * (max.z - min.z + 1);
    if (!Number.isSafeInteger(total) || total > MAX_VOXELS) throw new BridgeError('LIMIT_EXCEEDED', `region exceeds ${MAX_VOXELS} voxels`);
    const encoding = args.encoding === undefined ? 'auto' : args.encoding;
    if (encoding !== 'auto' && encoding !== 'palette_indices' && encoding !== 'palette_rle') throw new BridgeError('INVALID_ARGUMENT', 'Invalid encoding');
    const fingerprint = JSON.stringify({ dimension, min, max, encoding });
    const info = await this.client.info();
    if (info.world_session_id !== session) throw new BridgeError('WORLD_SESSION_MISMATCH', 'World session has changed');
    this.prune();
    const key = `${session}:${request}`;
    const previous = this.requests.get(key);
    if (previous) {
      if (previous.fingerprint !== fingerprint) throw new BridgeError('IDEMPOTENCY_CONFLICT', 'request_id was used for a different request');
      return { ok: true, operation: this.describe(previous) };
    }
    if (this.jobs.size >= MAX_JOBS) throw new BridgeError('RESOURCE_EXHAUSTED', 'Too many retained wide region operations', true);
    const limits = info.limits as RecordValue | undefined;
    if (!limits || !Number.isInteger(limits.chunks_per_operation) || (limits.chunks_per_operation as number) < 1) throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Invalid chunk limit');
    const boxes = partitionRegion(min, max, limits.query_voxels as number);
    const job: Job = { id: `wide-region:${randomUUID()}`, request, session, dimension, fingerprint, boxes, encoding, total, processed: 0, state: 'queued', created: new Date().toISOString(), expires: null, cancel: false, childIndex: 0, pages: [], cursors: [], bytes: 0, error: null, uncertain: false, errorPages: [], errorCursors: [], errorBytes: 0, childErrorCount: 0 };
    this.jobs.set(job.id, job); this.requests.set(key, job);
    void this.run(job);
    return { ok: true, operation: this.describe(job) };
  }
  private describe(j: Job): RecordValue {
    const active = j.childCounts ?? {};
    const processed = j.processed + (typeof active.processed === 'number' ? active.processed : 0);
    const succeeded = j.processed + (typeof active.succeeded === 'number' ? active.succeeded : 0);
    const failed = typeof active.failed === 'number' ? active.failed : 0;
    const skipped = typeof active.skipped === 'number' ? active.skipped : 0;
    return { operation_id: j.id, request_id: j.request, world_session_id: j.session, dimension: j.dimension, tool: 'get_region_sharded', kind: 'read', state: j.state, counts: { total: j.total, processed, succeeded, changed: 0, unchanged: 0, skipped, failed }, error_count: j.childErrorCount || (j.error ? 1 : 0), created_at: j.created, started_tick: null, finished_tick: null, expires_at: j.expires === null ? null : new Date(j.expires).toISOString(), result_available: j.state === 'succeeded', terminal_error: j.error, shard_count: j.boxes.length, completed_shards: j.childIndex, child_operation_id: j.childId ?? null, child_request_id: j.childRequest ?? null, submission_uncertain: j.uncertain };
  }
  async status(session: string, id: string): Promise<RecordValue> { await this.session(session); return { ok: true, operation: this.describe(this.get(session, id)) }; }
  async lookup(session: string, request: string): Promise<RecordValue | undefined> {
    this.prune(); const job = this.requests.get(`${session}:${request}`);
    if (!job) return undefined;
    await this.session(session); return { ok: true, operation: this.describe(job) };
  }
  async cancel(session: string, id: string): Promise<RecordValue> {
    await this.session(session); const job = this.get(session, id);
    const requested = !terminal(job.state) || job.state === 'cancelled';
    if (!terminal(job.state)) { job.cancel = true; if (job.childId) await this.client.cancel(session, job.childId); }
    return { ok: true, cancel_requested: requested, operation: this.describe(job) };
  }
  async result(session: string, id: string, cursor?: string): Promise<RecordValue> {
    await this.session(session); const job = this.get(session, id);
    if (!terminal(job.state)) throw new BridgeError('OPERATION_NOT_READY', 'Operation is not finished', true);
    if (job.state !== 'succeeded') throw new BridgeError('RESULT_UNAVAILABLE', 'Region query did not succeed');
    const index = cursor === undefined ? 0 : job.cursors.indexOf(cursor);
    if (index < 0 || index >= job.pages.length) throw new BridgeError('INVALID_CURSOR', 'Invalid cursor');
    return { ...job.pages[index], operation_id: job.id, expires_at: new Date(job.expires!).toISOString(), next_cursor: job.cursors[index + 1] ?? null };
  }
  async errors(session: string, id: string, cursor?: string): Promise<RecordValue> {
    await this.session(session); const job = this.get(session, id);
    if (!terminal(job.state)) throw new BridgeError('OPERATION_NOT_READY', 'Operation is not finished', true);
    if (job.errorPages.length) {
      const index = cursor === undefined ? 0 : job.errorCursors.indexOf(cursor);
      if (index < 0 || index >= job.errorPages.length) throw new BridgeError('INVALID_CURSOR', 'Invalid cursor');
      return { ...job.errorPages[index], operation_id: id, expires_at: new Date(job.expires!).toISOString(), next_cursor: job.errorCursors[index + 1] ?? null };
    }
    if (cursor !== undefined) throw new BridgeError('INVALID_CURSOR', 'Invalid cursor');
    return { ok: true, operation_id: id, world_session_id: session, error_count: job.error ? 1 : 0, errors: job.error ? [job.error] : [], next_cursor: null, expires_at: new Date(job.expires!).toISOString() };
  }
  private finish(j: Job, state: string, error: RecordValue | null = null): void {
    j.state = state; j.error = error; j.expires = Date.now() + TTL;
    if (state !== 'succeeded') { this.bytes -= j.bytes; j.bytes = 0; j.pages = []; j.cursors = []; }
  }
  private async run(j: Job): Promise<void> {
    j.state = 'running';
    try {
      for (; j.childIndex < j.boxes.length; j.childIndex++) {
        if (j.cancel) { this.finish(j, 'cancelled'); return; }
        j.childId = undefined; j.childRequest = randomUUID(); j.uncertain = false; j.childCounts = undefined;
        let op: RecordValue;
        try {
          op = operation(await this.client.callTool('get_region', { world_session_id: j.session, dimension: j.dimension, ...j.boxes[j.childIndex], encoding: j.encoding, request_id: j.childRequest }));
        } catch (error) {
          if (!(error instanceof BridgeError) || !['BRIDGE_UNAVAILABLE', 'SERVER_TICK_TIMEOUT', 'BRIDGE_RESPONSE_INVALID'].includes(error.code)) throw error;
          // A missing lookup does not prove non-acceptance. Stop recovery after a
          // bounded window and expose the unresolved child ID rather than reporting
          // cancellation or silently submitting a different request.
          j.uncertain = true;
          const recoveryDeadline = Date.now() + 30_000;
          for (;;) {
            if (Date.now() >= recoveryDeadline) throw new BridgeError('SUBMISSION_OUTCOME_UNKNOWN', 'Child submission could not be resolved; it may still have been accepted', false);
            await delay(500);
            try { op = operation(await this.client.callTool('get_operation_by_request_id', { world_session_id: j.session, request_id: j.childRequest })); break; }
            catch (lookupError) {
              if (lookupError instanceof BridgeError && ['OPERATION_NOT_FOUND', 'BRIDGE_UNAVAILABLE', 'SERVER_TICK_TIMEOUT'].includes(lookupError.code)) continue;
              throw lookupError;
            }
          }
          j.uncertain = false;
        }
        j.childId = childId(op);
        j.childCounts = op.counts as RecordValue | undefined;
        if (j.cancel && !terminal(String(op.state))) await this.client.cancel(j.session, j.childId);
        while (!terminal(String(op.state))) {
          await delay(500);
          op = operation(await this.client.operation(j.session, j.childId));
          j.childCounts = op.counts as RecordValue | undefined;
        }
        if (op.state === 'cancelled' || (j.cancel && op.state === 'succeeded')) { this.finish(j, 'cancelled'); return; }
        if (op.state !== 'succeeded') {
          // Freeze child errors before the shorter-lived child record can expire.
          this.bytes -= j.bytes; j.bytes = 0; j.pages = []; j.cursors = [];
          j.childErrorCount = typeof op.error_count === 'number' ? op.error_count : 0;
          let errorCursor: string | undefined;
          while (j.childErrorCount > 0) {
            const page = await this.client.errors(j.session, j.childId, errorCursor);
            const size = Buffer.byteLength(JSON.stringify(page), 'utf8');
            if (this.bytes + size > MAX_BYTES) throw new BridgeError('RESOURCE_EXHAUSTED', 'Wide-region error memory limit exceeded');
            j.errorPages.push(page); j.errorCursors.push(randomUUID()); j.errorBytes += size; this.bytes += size;
            const next = page.next_cursor;
            if (next !== null && typeof next !== 'string') throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Invalid child error cursor');
            errorCursor = next === null ? undefined : next as string;
            if (errorCursor === undefined) break;
          }
          this.finish(j, 'failed', { code: 'SHARD_FAILED', message: 'A shard failed; see operation error pages', details: { child_operation_id: j.childId, child_request_id: j.childRequest, child_terminal_error: op.terminal_error ?? null } }); return;
        }
        let cursor: string | undefined;
        do {
          const page = await this.client.result(j.session, j.childId, cursor);
          if (j.cancel) { this.finish(j, 'cancelled'); return; }
          const returned = { ...page, operation_id: j.id, expires_at: new Date(Date.now() + TTL).toISOString(), next_cursor: randomUUID() };
          if (Buffer.byteLength(JSON.stringify(returned), 'utf8') > 512 * 1024) throw new BridgeError('RESULT_TOO_LARGE', 'A shard page cannot fit the composite page limit');
          const size = Buffer.byteLength(JSON.stringify(page), 'utf8');
          if (this.bytes + size > MAX_BYTES) throw new BridgeError('RESOURCE_EXHAUSTED', 'Wide-region snapshot memory limit exceeded');
          j.pages.push(page); j.cursors.push(randomUUID()); j.bytes += size; this.bytes += size;
          const next = page.next_cursor;
          if (next !== null && typeof next !== 'string') throw new BridgeError('BRIDGE_RESPONSE_INVALID', 'Invalid child cursor');
          cursor = next === null ? undefined : next as string;
        } while (cursor !== undefined);
        const b = j.boxes[j.childIndex]!;
        j.processed += (b.max.x - b.min.x + 1) * (b.max.y - b.min.y + 1) * (b.max.z - b.min.z + 1);
        j.childCounts = undefined;
      }
      this.finish(j, 'succeeded');
    } catch (error) {
      this.bytes -= j.errorBytes; j.errorBytes = 0; j.errorPages = []; j.errorCursors = []; j.childErrorCount = 0;
      const e = error instanceof BridgeError ? error : new BridgeError('INTERNAL_ERROR', 'Wide-region coordinator failed');
      this.finish(j, 'failed', { code: e.code, message: e.message, retryable: e.retryable, details: { ...e.details, child_operation_id: j.childId ?? null, child_request_id: j.childRequest ?? null, submission_uncertain: j.uncertain } });
    }
  }
}
