#!/bin/zsh
# Start the local editor. Its TV connection is limited to status and live screenshots.
set -eu
cd -- "${0:A:h}"
if ! command -v node >/dev/null || ! command -v python3 >/dev/null; then
  print 'Node.js und Python fehlen. Öffne diesen Ordner mit Codex, um die lokale Einrichtung zu vervollständigen.'
  read -r '?Enter zum Schließen …'
  exit 1
fi
if [[ ! -f node_modules/interactjs/dist/interact.min.js ]]; then
  npm ci
fi
if curl --fail --silent --max-time 1 http://127.0.0.1:4797/api/editor/state >/dev/null 2>&1; then
  open http://127.0.0.1:4797/editor/
else
  exec python3 scripts/editor_server.py --port 4797 --open
fi
