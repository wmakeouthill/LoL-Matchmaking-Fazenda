Start Electron with optional WS gateway token

1) Configure gateway token (optional for production security):
   - Create a file at the project root named `.gateway_token` containing the token string (single line, no quotes).
   - Alternatively create `gateway.token` with the token.

2) If you want to auto-run the local WS test server when the backend is not available, create an empty file `.start-ws-test` at the project root.

3) Start Electron (script will export BACKEND_GATEWAY_TOKEN if present):
   ./scripts/start-electron.sh

Notes:

- The script runs `npm run electron` from the project root so dependencies from the root `package.json` are used (verify `node_modules/ws` exists).
- The script will attempt to detect the backend at $BACKEND_URL (defaults to <http://localhost:8080>). If the backend is not up and `.start-ws-test` exists, a local WS test server (scripts/ws-gateway-test.js) will be launched on port 8090 and Electron will connect to it.
