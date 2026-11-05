import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
const t = new StdioClientTransport({ command: process.execPath, args: [resolve(here, "../dist/index.js")], env: { ...process.env }, stderr: "inherit" });
const c = new Client({ name: "player-house", version: "1.0.0" }); await c.connect(t);
const assert = (ok, msg) => { if (!ok) throw new Error(msg); };
const key = (s) => `${s.id}|${Object.entries(s.properties).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>`${k}=${v}`).join(",")}`;
async function call(n, a = {}) { const r = await c.callTool({ name: n, arguments: a }); const b = JSON.parse(r.content[0].text); if (r.isError) throw new Error(`${n}: ${b.error?.code} ${b.error?.message}`); return b; }
async function wait(sid, oid) { for (;;) { const o = (await call("get_operation", { world_session_id: sid, operation_id: oid })).operation; if (["succeeded","failed","cancelled"].includes(o.state)) return o; await new Promise(r=>setTimeout(r,50)); } }
async function submit(sid, tool, args) { const b = await call(tool, { ...args, world_session_id: sid, request_id: randomUUID() }); const o = await wait(sid, b.operation.operation_id); assert(o.state === "succeeded", `${tool}: ${o.state} ${JSON.stringify(o.terminal_error)}`); return o; }
async function region(sid, dim, min, max) {
  const b = await call("get_region", { world_session_id: sid, dimension: dim, min, max, encoding: "auto", request_id: randomUUID() });
  const o = await wait(sid, b.operation.operation_id); assert(o.state === "succeeded", "region query failed");
  const values = new Map(); let cursor;
  do {
    const p = await call("get_query_result", { world_session_id: sid, operation_id: o.operation_id, ...(cursor?{cursor}:{}) });
    const pal = p.palette.map(key);
    const idx = p.encoding === "palette_rle" ? p.runs.flatMap(r=>Array(r.length).fill(r.palette_index)) : p.indices;
    const sx = max.x-min.x+1, sz = max.z-min.z+1;
    for (let i=0;i<idx.length;i++) { const g=p.offset+i; values.set(`${min.x+g%sx},${min.y+Math.floor(g/(sx*sz))},${min.z+Math.floor(g/sx)%sz}`, pal[idx[i]]); }
    cursor = p.next_cursor ?? undefined;
  } while (cursor);
  return values;
}
const info = await call("get_world_info");
const sid = info.world_session_id;
const player = await call("get_player_position", { world_session_id: sid });
assert(player.name === "DevBuilder", "expected the real test player");
const dim = player.dimension;
const baseY = -61; // observed grass top around the spawn platform
const x0 = player.block_pos.x - 9, z0 = player.block_pos.z + 5;
const x1 = x0 + 8, z1 = z0 + 8;
const min = { x: x0, y: baseY + 1, z: z0 }, max = { x: x1, y: baseY + 11, z: z1 };
const expected = new Map();
const put = (x,y,z,id,properties={}) => expected.set(`${x},${y},${z}`, key({id,properties}));
const box = (ax,ay,az,bx,by,bz,id,properties={}) => { for(let x=ax;x<=bx;x++)for(let y=ay;y<=by;y++)for(let z=az;z<=bz;z++)put(x,y,z,id,properties); };
// Site is the grass platform at y=-61; the building floor sits at -60.
box(x0, baseY+1, z0, x1, baseY+1, z1, "minecraft:stone_bricks");
box(x0, baseY+2, z0, x1, baseY+5, z1, "minecraft:oak_planks");
box(x0, baseY+6, z0, x1, baseY+6, z1, "minecraft:oak_planks");
box(x0, baseY+7, z0, x1, baseY+10, z1, "minecraft:oak_planks");
box(x0, baseY+11, z0, x1, baseY+11, z1, "minecraft:dark_oak_planks");
box(x0+1, baseY+2, z0+1, x1-1, baseY+4, z1-1, "minecraft:air");
box(x0+1, baseY+7, z0+1, x1-1, baseY+9, z1-1, "minecraft:air");
box(x0, baseY+2, z0+4, x0, baseY+3, z0+4, "minecraft:air");
put(x0+6, baseY+6, z0+4, "minecraft:air");
const windows = [
  [x1, baseY+3, z0+2], [x1, baseY+3, z0+6], [x0, baseY+3, z0+6],
  [x0+4, baseY+3, z1], [x0+4, baseY+3, z0],
  [x1, baseY+8, z0+2], [x1, baseY+8, z0+6], [x0, baseY+8, z0+6],
  [x0+4, baseY+8, z1], [x0+4, baseY+8, z0],
];
for (const [x,y,z] of windows) put(x,y,z,"minecraft:glass");
const stairProps = { facing: "east", half: "bottom", shape: "straight", waterlogged: "false" };
put(x0+2, baseY+2, z0+4, "minecraft:oak_stairs", stairProps);
put(x0+3, baseY+3, z0+4, "minecraft:oak_stairs", stairProps);
put(x0+4, baseY+4, z0+4, "minecraft:oak_stairs", stairProps);
put(x0+5, baseY+5, z0+4, "minecraft:oak_stairs", stairProps);
put(x0+6, baseY+6, z0+4, "minecraft:air");

const fills = [
  ["fill_region", { dimension: dim, min: { x:x0, y:baseY+1, z:z0 }, max: { x:x1, y:baseY+1, z:z1 }, block_state: { id:"minecraft:stone_bricks" } }],
  ["fill_region", { dimension: dim, min: { x:x0, y:baseY+2, z:z0 }, max: { x:x1, y:baseY+5, z:z1 }, block_state: { id:"minecraft:oak_planks" } }],
  ["fill_region", { dimension: dim, min: { x:x0, y:baseY+6, z:z0 }, max: { x:x1, y:baseY+6, z:z1 }, block_state: { id:"minecraft:oak_planks" } }],
  ["fill_region", { dimension: dim, min: { x:x0, y:baseY+7, z:z0 }, max: { x:x1, y:baseY+10, z:z1 }, block_state: { id:"minecraft:oak_planks" } }],
  ["fill_region", { dimension: dim, min: { x:x0, y:baseY+11, z:z0 }, max: { x:x1, y:baseY+11, z:z1 }, block_state: { id:"minecraft:dark_oak_planks" } }],
  ["fill_region", { dimension: dim, min: { x:x0+1, y:baseY+2, z:z0+1 }, max: { x:x1-1, y:baseY+4, z:z1-1 }, block_state: { id:"minecraft:air" } }],
  ["fill_region", { dimension: dim, min: { x:x0+1, y:baseY+7, z:z0+1 }, max: { x:x1-1, y:baseY+9, z:z1-1 }, block_state: { id:"minecraft:air" } }],
  ["fill_region", { dimension: dim, min: { x:x0, y:baseY+2, z:z0+4 }, max: { x:x0, y:baseY+3, z:z0+4 }, block_state: { id:"minecraft:air" } }],
  ["batch_set_blocks", { dimension: dim, blocks: [
    ...windows.map(([x,y,z])=>({ pos:{x,y,z}, block_state:{ id:"minecraft:glass" } })),
    ...[ [x0+2,baseY+2,z0+4],[x0+3,baseY+3,z0+4],[x0+4,baseY+4,z0+4],[x0+5,baseY+5,z0+4] ].map(([x,y,z])=>({ pos:{x,y,z}, block_state:{ id:"minecraft:oak_stairs", properties:{ facing:"east", half:"bottom" } } })),
    { pos:{ x:x0+6, y:baseY+6, z:z0+4 }, block_state:{ id:"minecraft:air" } },
  ] }],
];
const started = Date.now();
for (const [tool, args] of fills) await submit(sid, tool, args);
const values = await region(sid, dim, min, max);
const diffs = [...expected].filter(([k,v]) => values.get(k) !== v);
console.log(JSON.stringify({ phase: "player-build", player: { position: player.position, dimension: dim }, site: { x0,z0,x1,z1, baseY }, blocks: expected.size, milliseconds: Date.now()-started, differences: diffs.length, sample: diffs.slice(0,5) }));
assert(diffs.length === 0, `verification failed: ${JSON.stringify(diffs.slice(0,5))}`);
await c.close();
