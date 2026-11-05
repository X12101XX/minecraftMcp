#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="1.0.0"
MCP_TGZ="${MCP_TGZ:-$HERE/minecraft-mcp-server-$VERSION.tgz}"
MOD_JAR="${MOD_JAR:-$HERE/minecraft-mcp-$VERSION.jar}"
SKILLS_DIR="${SKILLS_DIR:-$HOME/.agents/skills}"
MODS_DIR="${MODS_DIR:-}"

if ! command -v npm >/dev/null 2>&1; then
  echo "ERROR: npm (Node.js >= 20) is required for the MCP server." >&2
  exit 1
fi
if [ ! -f "$MCP_TGZ" ]; then
  echo "ERROR: MCP package not found: $MCP_TGZ" >&2
  exit 1
fi

echo "Installing MCP server globally from $MCP_TGZ ..."
npm install -g "$MCP_TGZ"

mkdir -p "$SKILLS_DIR"
rm -rf "$SKILLS_DIR/minecraft-mcp-building"
cp -R "$HERE/skills/minecraft-mcp-building" "$SKILLS_DIR/"
echo "Installed skill: $SKILLS_DIR/minecraft-mcp-building"

if [ -f "$MOD_JAR" ] && [ -n "$MODS_DIR" ]; then
  mkdir -p "$MODS_DIR"
  cp "$MOD_JAR" "$MODS_DIR/"
  echo "Installed mod JAR: $MODS_DIR/$(basename "$MOD_JAR")"
else
  echo "Mod JAR not copied. Put $(basename "$MOD_JAR") and Fabric API 0.165.2+26.3 into your mods/ directory."
fi

cat <<'MESSAGE'

MCP server installed. Next:

1. Install the mod JAR and Fabric API 0.165.2+26.3 into your Minecraft mods/ directory.
2. Start Minecraft and enter a world. The mod writes:
     <game-dir>/config/minecraft-mcp.credentials.json
3. Add this to ~/.omp/agent/mcp.json (merge with existing mcpServers):
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
4. Restart OMP. If the command is not on OMP's PATH, use the absolute
   path from `npm prefix -g` followed by /bin/minecraft-mcp-server.
5. Watch the first call with `get_world_info`; do not put the token in chat.
MESSAGE
