import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
const t = new StdioClientTransport({ command: process.execPath, args: [resolve(here, "../dist/index.js")], env: { ...process.env }, stderr: "inherit" });
const c = new Client({ name: "integrated-e2e", version: "1.0.0" }); await c.connect(t);
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
    const sx=max.x-min.x+1, sz=max.z-min.z+1;
    for (let i=0;i<idx.length;i++){ const g=p.offset+i; values.set(`${min.x+g%sx},${min.y+Math.floor(g/(sx*sz))},${min.z+Math.floor(g/sx)%sz}`, pal[idx[i]]); }
    cursor=p.next_cursor??undefined;
  } while(cursor);
  return values;
}
const world = await call("get_world_info");
const sid = world.world_session_id;
const player = await call("get_player_position", { world_session_id: sid });
assert(player.name === "DevBuilder", `unexpected player ${player.name}`);
const dim = player.dimension;
const bedrock = await call("get_block", { world_session_id: sid, dimension: dim, pos: { x: 0, y: -64, z: 0 } });
assert(bedrock.block_state.id === "minecraft:bedrock", "integrated world block mismatch");
const terrainSubmitted = await call("get_terrain", { world_session_id: sid, dimension: dim, min: { x: -12, y: -64, z: -12 }, max: { x: 12, y: -52, z: 12 }, clearance_height: 3, request_id: randomUUID() });
const terrain = await wait(sid, terrainSubmitted.operation.operation_id);
assert(terrain.state === "succeeded" && terrain.result_available, "integrated terrain failed");
const x0 = player.block_pos.x + 4, z0 = player.block_pos.z + 5, x1 = x0 + 4, z1 = z0 + 4;
const expected = new Map();
const put=(x,y,z,id,properties={})=>expected.set(`${x},${y},${z}`,key({id,properties}));
const box=(ax,ay,az,bx,by,bz,id,properties={})=>{for(let x=ax;x<=bx;x++)for(let y=ay;y<=by;y++)for(let z=az;z<=bz;z++)put(x,y,z,id,properties);};
box(x0,-60,z0,x1,-60,z1,"minecraft:stone_bricks");
box(x0,-59,z0,x1,-57,z1,"minecraft:oak_planks");
box(x0+1,-59,z0+1,x1-1,-58,z1-1,"minecraft:air");
box(x0,-56,z0,x1,-56,z1,"minecraft:dark_oak_planks");
put(x0,-59,z0+2,"minecraft:air"); put(x0,-58,z0+2,"minecraft:air");
put(x1,-58,z0+2,"minecraft:glass");
for (const [tool,args] of [
  ["fill_region",{dimension:dim,min:{x:x0,y:-60,z:z0},max:{x:x1,y:-60,z:z1},block_state:{id:"minecraft:stone_bricks"}}],
  ["fill_region",{dimension:dim,min:{x:x0,y:-59,z:z0},max:{x:x1,y:-57,z:z1},block_state:{id:"minecraft:oak_planks"}}],
  ["fill_region",{dimension:dim,min:{x:x0+1,y:-59,z:z0+1},max:{x:x1-1,y:-58,z:z1-1},block_state:{id:"minecraft:air"}}],
  ["fill_region",{dimension:dim,min:{x:x0,y:-56,z:z0},max:{x:x1,y:-56,z:z1},block_state:{id:"minecraft:dark_oak_planks"}}],
  ["batch_set_blocks",{dimension:dim,blocks:[{pos:{x:x0,y:-59,z:z0+2},block_state:{id:"minecraft:air"}},{pos:{x:x0,y:-58,z:z0+2},block_state:{id:"minecraft:air"}},{pos:{x:x1,y:-58,z:z0+2},block_state:{id:"minecraft:glass"}}]}],
]) await submit(sid, tool, args);
const values = await region(sid,dim,{x:x0,y:-60,z:z0},{x:x1,y:-56,z:z1});
const diffs=[...expected].filter(([k,v])=>values.get(k)!==v);
console.log(JSON.stringify({ phase:"integrated", session:sid, player:{position:player.position,dimension:dim}, terrain_counts:terrain.counts, hut:{x0,z0,x1,z1}, differences:diffs.length, sample:diffs.slice(0,3) }));
assert(diffs.length===0, "integrated hut verification failed");
await c.close();
