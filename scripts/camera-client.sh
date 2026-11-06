#!/usr/bin/env bash
set -euo pipefail

# Dedicated camera client: runs a separate Minecraft client account, does not
# steal focus from the player's client, and stays ticking when unfocused.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CAMERA_DIR="${CAMERA_DIR:-$ROOT/integration/camera}"
CAMERA_NAME="${MC_CAMERA_NAME:-MCPCamera}"
SERVER="${MC_SERVER:-127.0.0.1:25565}"

mkdir -p "$CAMERA_DIR"
OPTIONS="$CAMERA_DIR/options.txt"
touch "$OPTIONS"
if grep -q '^pauseOnLostFocus:' "$OPTIONS"; then
  sed -i 's/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' "$OPTIONS"
else
  printf 'pauseOnLostFocus:false\n' >> "$OPTIONS"
fi

cd "$ROOT/fabric-mod"
exec ./gradlew \
  -Pmc_username="$CAMERA_NAME" \
  -Pmc_rundir="$CAMERA_DIR" \
  -Pmc_quickplay="$SERVER" \
  -Pmc_xvfb=false \
  runClient
