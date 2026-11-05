# minecraft-mcp-server

MCP stdio server for the Minecraft MCP Fabric bridge. It exposes 14 standard MCP tools for reading and modifying a real Minecraft world.

## Install

From a GitHub release tarball:

```bash
npm install -g ./minecraft-mcp-server-1.0.0.tgz
```

Or run directly with `npx`:

```bash
MINECRAFT_MCP_CONFIG=/path/to/minecraft-mcp.credentials.json \
  npx -y ./minecraft-mcp-server-1.0.0.tgz
```

`MINECRAFT_MCP_CONFIG` must point at the credentials file written by the Fabric mod. Start Minecraft and enter a world before calling world tools.

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

`get_world_info`, `get_player_position`, `get_block`, `get_region`, `get_terrain`, `set_block`, `remove_block`, `fill_region`, `replace_blocks`, `batch_set_blocks`, `get_operation`, `cancel_operation`, `get_operation_errors`, `get_query_result`.

All writes and large reads are asynchronous. Poll `get_operation`, read errors/results with the paging tools, and re-read the world to verify.

See the repository `docs/tool-contracts.md` for full parameters, limits, and error semantics.
