/*
  Diagnostic WebSocket client for the backend.
  - Connects to ws://127.0.0.1:8080/ws (adjust URL if different)
  - Logs open/close/error/messages
  - Implements automatic reconnect with exponential backoff
  - Optionally simulates a local disconnect to test reconnection

  Usage:
    npm install ws
    node scripts/diagnose-ws.js

  Environment variables:
    WS_URL - override WebSocket URL (default ws://127.0.0.1:8080/ws)
    SIMULATE_FLAP_MS - if set, script will close the connection after this many ms to simulate a backend flap
*/

const WebSocket = require('ws');
const url = process.env.WS_URL || 'ws://127.0.0.1:8080/ws';
const simulateFlapMs = process.env.SIMULATE_FLAP_MS ? Number(process.env.SIMULATE_FLAP_MS) : 0;

let ws = null;
let reconnectAttempts = 0;
let reconnectTimer = null;
const baseBackoff = 1000; // 1s
const maxBackoff = 30000; // 30s

function log(...args) { console.log(new Date().toISOString(), ...args); }

function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

  log('➤ Connecting to', url, 'attempt', reconnectAttempts + 1);
  ws = new WebSocket(url);

  ws.on('open', () => {
    reconnectAttempts = 0;
    log('✅ Connected to WS');
    // send a simple identify/ping
    try { ws.send(JSON.stringify({ type: 'identify', playerId: 'diag-client', summonerName: 'diag' })); } catch {}
    try { ws.send(JSON.stringify({ type: 'ping', ts: Date.now() })); } catch {}

    if (simulateFlapMs > 0) {
      setTimeout(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
          log('~ Simulating local flap: closing socket now');
          ws.close();
        }
      }, simulateFlapMs);
    }
  });

  ws.on('message', (msg) => {
    let parsed = msg.toString();
    try { parsed = JSON.parse(parsed); } catch {}
    log('◂ message:', parsed);
  });

  ws.on('error', (err) => {
    log('⚠️ ws error:', err.message || err);
  });

  ws.on('close', (code, reason) => {
    log('✖ Closed', code, reason ? reason.toString() : '');
    scheduleReconnect();
  });
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectAttempts++;
  const backoff = Math.min(baseBackoff * Math.pow(1.5, reconnectAttempts), maxBackoff);
  log(`⏳ Reconnecting in ${backoff}ms (attempt ${reconnectAttempts})`);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, backoff);
}

process.on('SIGINT', () => {
  log('SIGINT received, closing client');
  try { if (reconnectTimer) clearTimeout(reconnectTimer); } catch {}
  try { if (ws) ws.close(); } catch {}
  setTimeout(() => process.exit(0), 200);
});

connect();

