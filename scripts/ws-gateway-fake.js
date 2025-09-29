#!/usr/bin/env node
const WebSocket = require('ws');

// Connect to backend WS (adjust if your backend runs on different host/port)
const backendWsUrl = process.env.BACKEND_WS_URL || 'ws://localhost:8080/client-ws';
console.log('Connecting to', backendWsUrl);

const ws = new WebSocket(backendWsUrl);

ws.on('open', () => {
  console.log('Connected to backend WS, sending identify');
  // Send identify with fake lockfile info (backend will accept in dev)
  const identify = {
    type: 'identify',
    playerId: 'fake-gateway',
    data: {
      lockfile: {
        host: '127.0.0.1',
        port: 65500,
        protocol: 'https',
        password: 'fakepass'
      }
    }
  };
  ws.send(JSON.stringify(identify));
  // Also proactively inform backend of LCU status (like Electron does)
  setTimeout(() => {
    const summoner = { summonerId: 'fake-summoner-id-123', gameName: 'FZD Ratoso', tagLine: 'fzd', puuid: 'fake-puuid-xyz' };
    const statusMsg = { type: 'lcu_status', data: { status: 200, body: summoner } };
    try { ws.send(JSON.stringify(statusMsg)); console.log('>> sent proactive lcu_status'); } catch (e) { console.error('failed to send lcu_status', e); }
  }, 500);
});

ws.on('message', (msg) => {
  try {
    const json = JSON.parse(msg.toString());
    console.log('<< received', json.type, json.id || '');

    // Backend may proactively send lcu_request for current-summoner, or send other messages
    if (json.type === 'lcu_request') {
      const id = json.id;
      const path = json.path || '';
      console.log('Handling lcu_request for', path);

      if (path === '/lol-summoner/v1/current-summoner') {
        const summoner = {
          summonerId: 'fake-summoner-id-123',
          gameName: 'FZD Ratoso',
          tagLine: 'fzd',
          puuid: 'fake-puuid-xyz'
        };
        const resp = { type: 'lcu_response', id, status: 200, body: summoner };
        ws.send(JSON.stringify(resp));
        console.log('>> sent lcu_response current-summoner');
        return;
      }

      // match history path: may include summoner id
      if (path.startsWith('/lol-match-history/v1/matches')) {
        // create a fake match history array
        const fakeMatches = [
          { matchId: 'm1', timestamp: Date.now() - 3600 * 1000, result: 'WIN', champions: ['Ahri'] },
          { matchId: 'm2', timestamp: Date.now() - 7200 * 1000, result: 'LOSS', champions: ['Lux'] }
        ];
        const resp = { type: 'lcu_response', id, status: 200, body: fakeMatches };
        ws.send(JSON.stringify(resp));
        console.log('>> sent lcu_response match-history');
        return;
      }

      // default: return 404
      const resp = { type: 'lcu_response', id, status: 404, error: 'Not implemented in fake gateway' };
      ws.send(JSON.stringify(resp));
    }

    if (json.type === 'lcu_status_ack') {
      console.log('Backend acknowledged lcu_status:', json);
    }

  } catch (e) {
    console.error('msg parse error', e);
  }
});

ws.on('close', (c) => console.log('ws closed', c));
ws.on('error', (e) => console.error('ws error', e));
