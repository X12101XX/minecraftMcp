import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here = dirname(fileURLToPath(import.meta.url));
const t = new StdioClientTransport({ command: process.execPath, args: [resolve(here, "../dist/index.js")], env: { ...process.env }, stderr: "inherit" });
const c = new Client({ name: "batch-errors", version: "1.0.0" }); await c.connect(t);
async function call(n,a={}) { const r=await c.callTool({name:n,arguments:a}); const b=JSON.parse(r.content[0].text); if(r.isError) throw new Error(`${n}: ${b.error?.code} ${b.error?.message}`); return b; }
const sid=(await call("get_world_info")).world_session_id;
// A valid first entry followed by an invalid state. No partial write is allowed.
const submitted=await call("batch_set_blocks",{world_session_id:sid,dimension:"minecraft:overworld",request_id:randomUUID(),blocks:[
 {pos:{x:40,y:-55,z:40},block_state:{id:"minecraft:stone"}},
 {pos:{x:41,y:-55,z:40},block_state:{id:"minecraft:oak_stairs",properties:{facing:"sideways"}}},
]});
let op; do { await new Promise(r=>setTimeout(r,50)); op=(await call("get_operation",{world_session_id:sid,operation_id:submitted.operation.operation_id})).operation; } while(!["succeeded","failed","cancelled"].includes(op.state));
const errors=await call("get_operation_errors",{world_session_id:sid,operation_id:op.operation_id});
const first=await call("get_block",{world_session_id:sid,dimension:"minecraft:overworld",pos:{x:40,y:-55,z:40}});
console.log(JSON.stringify({state:op.state,counts:op.counts,terminal:op.terminal_error,errors:errors.error_count,errorSample:errors.errors,firstBlockUnchanged:first.block_state.id}));
if(op.state!=="failed"||op.counts.changed!==0||first.block_state.id!=="minecraft:air"||errors.error_count!==1) throw new Error("batch prevalidation contract failed");
await c.close();
