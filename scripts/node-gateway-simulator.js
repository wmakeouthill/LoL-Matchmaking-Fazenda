#!/usr/bin/env node
const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');

function readLockfile() {
  const candidates = [
    'C:/Riot Games/League of Legends/lockfile',
    path.join(process.env.LOCALAPPDATA || '', 'Riot Games', 'League of Legends', 'lockfile'),
    path.join(process.env.USERPROFILE || '', 'AppData', 'Local', 'Riot Games', 'League of Legends', 'lockfile')
  ];
  for (const p of candidates) {
    try {
      if (p && fs.existsSync(p)) {
        const content = fs.readFileSync(p, 'utf8');
        const parts = content.trim().split(':');
        if (parts.length >= 5) return { host: '127.0.0.1', port: parseInt(parts[2], 10), protocol: parts[4], password: parts[3] };
      }
    } catch (e) {}
  }
  return null;
}

const backendWsUrl = process.env.BACKEND_WS_URL || 'ws://localhost:8090/client-ws';
console.log('Connecting to', backendWsUrl);
const ws = new WebSocket(backendWsUrl, { headers: { ...(process.env.BACKEND_GATEWAY_TOKEN ? { Authorization: 'Bearer ' + process.env.BACKEND_GATEWAY_TOKEN } : {}) } });

ws.on('open', () => {
  console.log('Connected to backend WS');
  const info = readLockfile();
  ws.send(JSON.stringify({ type: 'identify', playerId: 'sim-gateway', data: { lockfile: info } }));
});

ws.on('message', async (msg) => {
  try {
    const json = JSON.parse(msg.toString());
    console.log('received', json.type);
    if (json.type === 'lcu_request') {
      // perform local call
      const info = readLockfile();
      if (!info) {
        ws.send(JSON.stringify({ type: 'lcu_response', id: json.id, status: 500, error: 'lockfile not found' }));
        return;
      }
      const protocol = info.protocol === 'http' ? http : https;
      const url = (info.protocol || 'https') + '://' + info.host + ':' + info.port + (json.path.startsWith('/') ? json.path : '/' + json.path);
      const payload = json.body ? JSON.stringify(json.body) : undefined;
      const parsed = new URL(url);
      const opts = { method: json.method || 'GET', hostname: parsed.hostname, port: parsed.port, path: parsed.pathname + (parsed.search || ''), headers: { 'Authorization': 'Basic ' + Buffer.from('riot:' + info.password).toString('base64'), 'Content-Type': 'application/json' }, timeout: 5000 };
      // When connecting to the local LCU over HTTPS, Riot uses a self-signed cert.
      // Allow the simulator to accept it for local dev by using an agent that does
      // not reject unauthorized certificates.
      if ((info.protocol || 'https').startsWith('https')) {
        opts.agent = new https.Agent({ rejectUnauthorized: false });
      }
      const req = protocol.request(opts, (res) => {
        let data = '';
        res.on('data', (c) => data += c);
        res.on('end', () => {
          try {
            const isJson = (res.headers['content-type'] || '').includes('application/json');
            const parsedBody = isJson && data ? JSON.parse(data) : data;
            ws.send(JSON.stringify({ type: 'lcu_response', id: json.id, status: res.statusCode, body: parsedBody }));
          } catch (e) { ws.send(JSON.stringify({ type: 'lcu_response', id: json.id, status: 500, error: String(e) })); }
        });
      });
      req.on('error', (e) => ws.send(JSON.stringify({ type: 'lcu_response', id: json.id, status: 500, error: e.message })));
      if (payload) req.write(payload);
      req.end();
    }
  } catch (e) { console.error('ws msg error', e); }
});

ws.on('close', (c) => { console.log('ws closed', c); setTimeout(()=>process.exit(0),1000); });
ws.on('error', (e) => console.error('ws error', e));
