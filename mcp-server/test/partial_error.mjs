import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
const here=dirname(fileURLToPath(import.meta.url));
const t=new StdioClientTransport({command:process.execPath,args:[resolve(here,"../dist/index.js")],env:{...process.env},stderr:"inherit"});
const c=new Client({name:"partial",version:"1.0.0"}); await c.connect(t);
async function call(n,a={}){const r=await c.callTool({name:n,arguments:a});const b=JSON.parse(r.content[0].text);if(r.isError)throw new Error(`${n}: ${b.error?.code} ${b.error?.message}`);return b;}
const sid=(await call("get_world_info")).world_session_id;
const req=await call("batch_set_blocks",{world_session_id:sid,dimension:"minecraft:overworld",request_id:randomUUID(),on_error:"continue",blocks:[
 {pos:{x:62,y:-50,z:40},block_state:{id:"minecraft:stone"},expected_state:{id:"minecraft:air",properties:{}}},
 {pos:{x:63,y:-50,z:40},block_state:{id:"minecraft:dirt"},expected_state:{id:"minecraft:oak_planks",properties:{}}},
]});
let op; do{await new Promise(r=>setTimeout(r,50));op=(await call("get_operation",{world_session_id:sid,operation_id:req.operation.operation_id})).operation;}while(!["succeeded","failed","cancelled"].includes(op.state));
const errs=await call("get_operation_errors",{world_session_id:sid,operation_id:op.operation_id});
const a=await call("get_block",{world_session_id:sid,dimension:"minecraft:overworld",pos:{x:62,y:-50,z:40}});
const b=await call("get_block",{world_session_id:sid,dimension:"minecraft:overworld",pos:{x:63,y:-50,z:40}});
console.log(JSON.stringify({state:op.state,counts:op.counts,errors:errs.errors,first:a.block_state.id,second:b.block_state.id}));
if(op.state!=="failed"||op.counts.changed!==1||op.counts.failed!==1||a.block_state.id!=="minecraft:stone"||b.block_state.id!=="minecraft:air")throw new Error("partial failure contract failed");
await c.close();
