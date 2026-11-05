# Minecraft MCP 1.0.0 — release installation

This release bundles three independently installable parts:

| Asset | Purpose |
| --- | --- |
| `minecraft-mcp-1.0.0.jar` | Fabric Mod for Minecraft 26.3 |
| `minecraft-mcp-server-1.0.0.tgz` | MCP stdio Server (Node.js package) |
| `minecraft-mcp-skills-1.0.0.zip` | OMP/Agent building skill |
| `minecraft-mcp-1.0.0-bundle.zip` | All of the above + `INSTALL.md` + `install.sh` |

Requirements: Minecraft **26.3**, Fabric Loader **0.20.1**, Fabric API **0.165.2+26.3**, JDK 25+, Node.js 20+.

## 1. Install the Mod

Download `minecraft-mcp-1.0.0.jar` and Fabric API `0.165.2+26.3`, then place both in the instance/server `mods/` directory. Start Minecraft and enter a world. The world must be loaded before MCP world calls work.

After entering the world, the mod writes:

- `config/minecraft-mcp.json` — port and write permission.
- `config/minecraft-mcp.credentials.json` — URL and secret Token.

For a normal client this is usually `~/.minecraft/config/`; for a dedicated server it is the server's `config/` directory. The mod logs the credential path but never the Token.

## 2. Install the MCP Server

```bash
npm install -g ./minecraft-mcp-server-1.0.0.tgz
```

Or from the release URL:

```bash
npm install -g https://github.com/X12101XX/minecraftMcp/releases/download/v1.0.0/minecraft-mcp-server-1.0.0.tgz
```

Configure OMP in `~/.omp/agent/mcp.json`:

```json
{
  "mcpServers": {
    "minecraft": {
      "type": "stdio",
      "command": "minecraft-mcp-server",
      "env": {
        "MINECRAFT_MCP_CONFIG": "/absolute/path/to/config/minecraft-mcp.credentials.json"
      },
      "timeout": 120000
    }
  }
}
```

If `minecraft-mcp-server` is not on OMP's `PATH`, use `node /absolute/path/to/mcp-server/dist/index.js` or the path printed by `npm prefix -g`.

## 3. Install the Skill

```bash
unzip minecraft-mcp-skills-1.0.0.zip
mkdir -p ~/.agents/skills
cp -R minecraft-mcp-building ~/.agents/skills/
```

Restart OMP. The skill appears as `minecraft-mcp-building`. The `minecraft-mcp-development` skill is for working inside the full repository checkout and is not meant for standalone installation.

## Quick verification

1. Start Minecraft and enter a world.
2. In OMP, call `get_world_info`; it should return a `world_session_id`.
3. Ask OMP to read `skill://minecraft-mcp-building` and build near the player.
4. An operation ID means submitted, not completed: poll `get_operation` and re-read the region with `get_region`.

## Security

- The bridge listens only on `127.0.0.1`.
- Treat the credentials file as a password. Do not paste its Token into chat or commit it.
- Writes are real and persistent; canceled operations can leave partial changes.
