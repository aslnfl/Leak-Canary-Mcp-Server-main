#!/usr/bin/env bash
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

error() { echo -e "${RED}ERROR: $1${NC}" >&2; }
info()  { echo -e "${GREEN}$1${NC}"; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LAUNCHER="$SCRIPT_DIR/scripts/leakcanary-mcp"
DEST="/usr/local/bin/leakcanary-mcp"

# ── Check Homebrew ───────────────────────────────────────────────────────────
if ! command -v brew &>/dev/null; then
    error "Homebrew not found. Install from https://brew.sh"
    exit 1
fi

# ── Install GitHub CLI if missing ────────────────────────────────────────────
if ! command -v gh &>/dev/null; then
    info "Installing GitHub CLI ..."
    brew install gh
fi

# ── Verify launcher exists ───────────────────────────────────────────────────
if [ ! -f "$LAUNCHER" ]; then
    error "Launcher script not found at $LAUNCHER"
    exit 1
fi

# ── Copy to /usr/local/bin ───────────────────────────────────────────────────
info "Installing leakcanary-mcp to $DEST ..."
sudo cp "$LAUNCHER" "$DEST"
sudo chmod +x "$DEST"

# ── Done ─────────────────────────────────────────────────────────────────────
echo ""
info "Installation complete!"
echo ""
echo "  Add this to your Cursor MCP config (~/.cursor/mcp.json):"
echo ""
echo '  "leakcanary": {'
echo '    "command": "leakcanary-mcp"'
echo '  }'
echo ""
info "Run: leakcanary-mcp"
