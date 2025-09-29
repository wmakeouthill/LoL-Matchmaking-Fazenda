#!/usr/bin/env node
const fs = require('fs');
const path = require('path');
const http = require('http');

function findLockfile() {
  const candidates = [
    'C:/Riot Games/League of Legends/lockfile',
    path.join(process.env.LOCALAPPDATA || '', 'Riot Games', 'League of Legends', 'lockfile'),
    path.join(process.env.USERPROFILE || '', 'AppData', 'Local', 'Riot Games', 'lockfile')
  ];
  for (const p of candidates) {
    try {
      if (p && fs.existsSync(p)) return p;
    } catch (e) {}
  }
  return null;
}

function parseLockfile(content) {
  const parts = (content || '').trim().split(':');
  if (parts.length >= 5) {
    return { name: parts[0], pid: parts[1], port: parseInt(parts[2], 10) || 0, password: parts[3], protocol: parts[4] };
  }
  return null;
}

const lockfilePath = findLockfile();
if (!lockfilePath) {
  console.error('Lockfile not found in known locations.');
  process.exit(1);
}

try {
  const content = fs.readFileSync(lockfilePath, 'utf8');
  const parsed = parseLockfile(content);
  if (!parsed) { console.error('Could not parse lockfile content:', content); process.exit(1); }

  const payload = JSON.stringify({ host: '127.0.0.1', port: parsed.port, protocol: parsed.protocol, password: parsed.password });
  const opts = new URL(process.env.BACKEND_URL || 'http://localhost:8080');
  const req = http.request({ hostname: opts.hostname, port: opts.port || 80, path: '/api/lcu/configure', method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) } }, (res) => {
    let data = '';
    res.on('data', (c) => data += c.toString());
    res.on('end', () => {
      console.log('POST /api/lcu/configure ->', res.statusCode, data);
    });
  });
  req.on('error', (e) => console.error('Request error', e));
  req.write(payload);
  req.end();
} catch (e) {
  console.error('Error reading lockfile', e);
  process.exit(1);
}
