# minecraft-mcp-server

MCP stdio server for the Minecraft MCP Fabric bridge. It exposes 28 standard MCP tools for reading and modifying a real Minecraft world.

## Install

From a GitHub release tarball:

```bash
npm install -g ./minecraft-mcp-server-2.0.0.tgz
```

Or run directly with `npx`:

```bash
MINECRAFT_MCP_CONFIG=/path/to/minecraft-mcp.credentials.json \
  npx -y ./minecraft-mcp-server-2.0.0.tgz
```

`MINECRAFT_MCP_CONFIG` must point at the credentials file written by the Fabric mod. Start Minecraft and enter a world before calling world tools.

To see the in-game build process, install the matching Minecraft MCP Mod and Fabric API on both the server and viewing clients (once for singleplayer). Reads briefly highlight their exact requested range. Validated batches show translucent pending targets for 20 server ticks, then all writes process at most one candidate per tick. The activity HUD shows tool calls, operation progress, and errors. Previews are not world blocks or completion evidence; keep polling the same operation and verify by reading back.

## OMP / Claude Desktop

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "stdio",
      "command": "minecraft-mcp-server",
      "env": {
        "MINECRAFT_MCP_CONFIG": "/absolute/path/to/minecraft-mcp.credentials.json"
      },
      "timeout": 120000
    }
  }
}
```

If a globally installed command is not on the MCP client's `PATH`, use `node /path/to/dist/index.js` instead.

## Tools

`get_world_info`, `get_player_position`, `get_entity`, `get_block`, `get_biome`, `get_light`, `preload_chunks`, `get_region`, `get_region_sharded`, `get_region_sharded_by_request_id`, `get_terrain`, `set_block`, `remove_block`, `fill_region`, `replace_blocks`, `batch_set_blocks`, `batch_set_blocks_sharded`, `batch_set_blocks_sharded_by_request_id`, `undo_operation`, `get_operation`, `get_operation_by_request_id`, `cancel_operation`, `get_operation_errors`, `get_query_result`, `request_screenshot`, `get_screenshot_status`, `get_screenshot`, `cancel_screenshot`.

All writes and large reads are asynchronous. Poll `get_operation`, read errors/results with the paging tools, and re-read the world to verify.

If a submission response is lost, look up the operation with its original `world_session_id` and `request_id`: use `get_region_sharded_by_request_id` or `batch_set_blocks_sharded_by_request_id` for a sharded parent, or `get_operation_by_request_id` for a bridge operation. A missing record does not prove that no changes occurred: records expire and do not survive a world session change.

Sharded reads are incrementally sampled; their parent requests and results exist only in the current MCP server process. Conditional undo only restores directly recorded block states if the current state matches; it cannot detect ABA changes or restore block entity data and indirect effects.

Sharded writes validate all targets before writing but are not transactions; their parent records are process-local. Screenshots capture the named online player's view without a consent prompt and may include private chat and HUD; restrict access to the bridge credentials. Normal reads and writes never load chunks implicitly; call `preload_chunks` first for a bounded remote area (up to 1,024 chunks, tickets expire after about 6,000 ticks).

See the repository `docs/tool-contracts.md` for full parameters, limits, and error semantics.
