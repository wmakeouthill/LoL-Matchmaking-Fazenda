"use strict";
const { contextBridge, ipcRenderer } = require('electron');
const fs = require('fs');
const path = require('path');
const LOG_FILE = path.join(__dirname, '..', 'electron.log');

function appendLogLinePre(line) {
  try {
    const ts = new Date().toISOString();
    fs.appendFileSync(LOG_FILE, ts + ' ' + line + '\n', { encoding: 'utf8' });
  } catch (e) {
    // best effort
  }
}

function sanitizeBody(b) {
  try {
    if (b === null || b === undefined) return b;
    if (typeof b === 'string' || typeof b === 'number' || typeof b === 'boolean') return b;
    const redact = ['password', 'auth', 'token', 'authorization', 'Authorization'];
    const out = Array.isArray(b) ? [] : {};
    for (const k in b) {
      out[k] = redact.includes(k) ? '<redacted>' : b[k];
    }
    return out;
  } catch (e) { return String(b); }
}

// Read lockfile only from the game installation folder (explicit requirement)
function readLockfile() {
  const fs = require('fs');
  const path = 'C:/Riot Games/League of Legends/lockfile';
  if (!fs.existsSync(path)) return null;
  const content = fs.readFileSync(path, 'utf8');
  try { appendLogLinePre('[preload] readLockfile content=' + content.replace(/\r?\n/g, ' ')); } catch (e) {}
  const parts = content.split(':');
  if (parts.length < 5) return null;
  const port = parseInt(String(parts[2]).trim(), 10);
  const password = String(parts[3]).trim();
  const protocol = String(parts[4]).trim().toLowerCase();
  return { host: '127.0.0.1', port, protocol, password };
}

async function postJson(url, body, timeoutMs = 5000) {
  const fetchFn = globalThis.fetch || require('node-fetch');
  const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const signal = controller ? controller.signal : undefined;
  if (controller) setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetchFn(url, { method: 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' }, signal });
    try { appendLogLinePre('[preload] postJson ' + url + ' -> status=' + res.status + ' payload=' + JSON.stringify(sanitizeBody(body))); } catch (e) {}
    return { status: res.status, ok: Boolean(res.ok) };
  } catch (e) {
    try { appendLogLinePre('[preload] postJson ' + url + ' -> error=' + (e && e.message ? e.message : String(e))); } catch (err) {}
    return { status: 0, ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

const electronAPI = {
  openExternal: (url) => ipcRenderer.invoke('shell:openExternal', url),
  getVersion: () => process.versions?.electron || 'unknown',
  getBackendApiUrl: () => {
    // If BACKEND_URL is provided in the environment, normalize it and return
    const raw = (process.env && process.env['BACKEND_URL']) ? String(process.env['BACKEND_URL']).replace(/\/+$/, '') : null;
    if (raw) {
      return raw.endsWith('/api') ? raw : `${raw}/api`;
    }

    // Otherwise, build an absolute URL based on the current renderer location
    // so the frontend can safely use it with URL() and WebSocket construction.
    try {
      const loc = globalThis.location;
      const proto = loc.protocol || 'http:';
      const host = loc.hostname || 'localhost';
      const port = loc.port ? `:${loc.port}` : '';
      return `${proto}//${host}${port}/api`;
    } catch (e) {
      // Fallback to localhost absolute URL
      return 'http://localhost:8080/api';
    }
  },
  isElectron: () => {
    try {
      const hasProcess = typeof process !== 'undefined' && !!process.versions?.electron;
      const location = globalThis?.location;
      const isFileProtocol = !!(location && location.protocol === 'file:');
      return Boolean(hasProcess || isFileProtocol || (typeof globalThis.electronAPI !== 'undefined'));
    } catch {
      return false;
    }
  },
  lcu: {
    request: async (pathname, method, body) => {
      const info = readLockfile();
      if (!info) throw new Error('LCU lockfile not found');
      const https = require('https');
      const { URL } = require('url');
      const base = `${info.protocol}://${info.host}:${info.port}`;
      const url = new URL(pathname.startsWith('/') ? pathname : `/${pathname}`, base);
      const auth = 'Basic ' + Buffer.from(`riot:${info.password}`).toString('base64');
      method = method || 'GET';
      let payload;
      if (body !== undefined && body !== null) payload = (typeof body === 'string') ? body : JSON.stringify(body);
  return new Promise((resolve, reject) => {
            const opts = { method, headers: { 'Authorization': auth, 'Content-Type': 'application/json' } };
            // When connecting to the local LCU over HTTPS, Riot uses a self-signed
            // certificate. Allow the preload helper to accept that for local dev by
            // using an agent that does not reject unauthorized certificates.
            try {
              if (String(url.protocol || '').startsWith('https')) {
                opts.agent = new https.Agent({ rejectUnauthorized: false });
              }
            } catch (e) { /* ignore */ }

            const req = https.request(url, opts, (res) => {
          let data = '';
          res.on('data', (c) => data += c);
          res.on('end', () => {
            try {
              const isJson = (res.headers['content-type'] || '').includes('application/json');
              const parsed = isJson && data ? JSON.parse(data) : data;
                  try { appendLogLinePre('[preload] lcu request ' + method + ' ' + pathname + ' -> status=' + res.statusCode + ' body=' + JSON.stringify(sanitizeBody(parsed))); } catch (e) {}
                  if (res.statusCode >= 200 && res.statusCode < 300) resolve(parsed);
                  else reject(new Error(`LCU ${res.statusCode}`));
            } catch (e) { reject(e instanceof Error ? e : new Error(String(e))); }
          });
        });
        req.on('error', (err) => reject(err instanceof Error ? err : new Error(String(err))));
        if (payload) req.write(payload);
        req.end();
      });
    }
  }
};

electronAPI.identifyPlayer = (payload) => {
  try { return ipcRenderer.invoke('lcu:identify', payload); } catch (e) { return Promise.resolve({ success: false, error: String(e) }); }
};

electronAPI.getLCULockfileInfo = () => {
  const info = readLockfile();
  if (!info) return null;
  const overrideHost = (process.env && process.env['LCU_HOST']) ? String(process.env['LCU_HOST']) : '127.0.0.1';
  return { host: overrideHost, port: info.port, protocol: info.protocol, password: info.password };
};

electronAPI.lcu.getCurrentSummoner = async () => electronAPI.lcu.request('/lol-summoner/v1/current-summoner', 'GET');
electronAPI.lcu.getGameflowPhase = async () => electronAPI.lcu.request('/lol-gameflow/v1/gameflow-phase', 'GET');
electronAPI.lcu.getSession = async () => electronAPI.lcu.request('/lol-gameflow/v1/session', 'GET');
electronAPI.lcu.getMatchHistory = async () => {
  const summoner = await electronAPI.lcu.request('/lol-summoner/v1/current-summoner', 'GET');
  const summonerId = summoner?.summonerId;
  if (!summonerId) throw new Error('summonerId unavailable');
  return electronAPI.lcu.request(`/lol-match-history/v1/matches/${summonerId}`, 'GET');
};

try {
  if (typeof contextBridge !== 'undefined' && typeof contextBridge.exposeInMainWorld === 'function') {
    contextBridge.exposeInMainWorld('electronAPI', electronAPI);
  } else {
    globalThis.electronAPI = electronAPI;
  }
} catch {
  globalThis.electronAPI = electronAPI;
}

// Auto-configure backend once: read lockfile and POST to BACKEND_URL/api/lcu/configure
(async function autoConfigure() {
  try {
    const info = readLockfile();
    if (!info) return;
    const backend = (process.env && process.env['BACKEND_URL']) ? String(process.env['BACKEND_URL']).replace(/\/+$/, '') : 'http://127.0.0.1:8080';
    const url = backend.endsWith('/api') ? `${backend}/lcu/configure` : `${backend}/api/lcu/configure`;
    const explicitHost = (process.env && process.env['LCU_HOST']) ? String(process.env['LCU_HOST']) : null;
    const candidateHosts = explicitHost ? [explicitHost] : ['host.docker.internal', '127.0.0.1'];
    let configured = false;
    for (const h of candidateHosts) {
      const payload = { host: h, port: info.port, protocol: info.protocol, password: info.password };
      try {
        const res = await postJson(url, payload, 5000);
        if (res.ok) { configured = true; break; }
      } catch (e) {
        console.log('[preload] autoConfigure attempt failed for host=', h, String(e));
      }
    }
  } catch {}
})();
