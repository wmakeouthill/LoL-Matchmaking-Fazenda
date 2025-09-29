const { app, BrowserWindow } = require('electron');
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { ipcMain } = require('electron');

const LOG_FILE = path.join(__dirname, '..', 'electron.log');

function sanitizeForLog(value) {
  try {
    if (value === null || value === undefined) return value;
    // primitives
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value;
    // Error
    if (value instanceof Error) return { message: value.message, stack: value.stack };
    // Clone objects/arrays and redact sensitive keys
    const redactKeys = ['password', 'auth', 'token', 'authorization', 'Authorization'];
    const clone = Array.isArray(value) ? [] : {};
    for (const k in value) {
      try {
        if (redactKeys.includes(k)) clone[k] = '<redacted>'; else clone[k] = sanitizeForLog(value[k]);
      } catch (e) {
        clone[k] = String(value[k]);
      }
    }
    return clone;
  } catch (e) { return String(value); }
}

function appendLogLine(line) {
  try {
    const ts = new Date().toISOString();
    const out = ts + ' ' + line + '\n';
    fs.appendFileSync(LOG_FILE, out, { encoding: 'utf8' });
  } catch (e) {
    // best-effort; don't crash the app for logging failures
    console.error('[electron] failed to append log', String(e));
  }
}

function safeLog(...args) {
  try {
    // Keep normal console output for dev
    console.log('[electron]', ...args);
  } catch (e) {
    // ignore
  }
  try {
    const parts = args.map(a => {
      try { return typeof a === 'string' ? a : JSON.stringify(sanitizeForLog(a)); } catch (e) { return String(a); }
    });
    appendLogLine('[main] ' + parts.join(' '));
  } catch (e) { /* ignore logging errors */ }
}

function ensureTrailingSlash(u) {
  return u && u.endsWith('/') ? u : u + '/';
}

function checkReachable(u, timeout = 2000) {
  return new Promise((resolve) => {
    let parsed;
    try {
      parsed = new URL(u);
    } catch (err) {
        safeLog('checkReachable invalid URL', u, String(err));
        return resolve(false);
    }

    const lib = parsed.protocol === 'https:' ? https : http;
    const options = {
      method: 'HEAD',
      hostname: parsed.hostname,
      port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
      path: (parsed.pathname || '/') + (parsed.search || ''),
      timeout
    };

    const req = lib.request(options, (res) => {
      // consider reachable for 2xx/3xx/4xx, fail only on server error or close
      resolve(res.statusCode < 500);
    });
    req.on('timeout', () => { req.destroy(); resolve(false); });
    req.on('error', () => resolve(false));
    req.end();
  });
}

async function pickBackendUrl() {
  const env = process.env.BACKEND_URL || '';
  const defaultBase = env || 'http://localhost:8080/';
  const baseNoSlash = defaultBase.replace(/\/+$/, '');

  const candidates = [
    ensureTrailingSlash(baseNoSlash),
    ensureTrailingSlash(baseNoSlash.replace('localhost', '127.0.0.1')),
    ensureTrailingSlash(baseNoSlash.replace('127.0.0.1', 'localhost'))
  ];

  // dedupe while preserving order
  const seen = new Set();
  const uniq = [];
  for (const c of candidates) {
    if (!seen.has(c)) { seen.add(c); uniq.push(c); }
  }

  for (const c of uniq) {
    safeLog('probing', c);
    try {
      // short timeout probe
      // eslint-disable-next-line no-await-in-loop
      if (await checkReachable(c, 2000)) {
        safeLog('reachable', c);
        return c;
      }
    } catch (err) {
      safeLog('probe error', String(err));
    }
  }

  safeLog('no backend reachable, returning primary candidate', uniq[0] || ensureTrailingSlash(defaultBase));
  return uniq[0] || ensureTrailingSlash(defaultBase);
}

function createWindow(startUrl, isDev) {
  safeLog('creating window for', startUrl, 'dev=', !!isDev);

  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webSecurity: true,
      // preload exposes a safe electronAPI to the renderer (lockfile helpers, lcu helpers)
      preload: path.join(__dirname, 'preload.js')
    }
  });

  win.webContents.on('did-finish-load', () => safeLog('did-finish-load ->', win.webContents.getURL()));
  win.webContents.on('did-fail-load', (_e, errorCode, errorDesc, validatedURL) => safeLog('did-fail-load', errorCode, errorDesc, validatedURL));
  win.webContents.on('render-process-gone', (_e, details) => safeLog('render-process-gone', details));
  win.webContents.on('console-message', (_e, level, message, line, sourceId) => safeLog('console-message', level, message, sourceId + ':' + line));

  if (isDev) win.webContents.openDevTools({ mode: 'right' });

  // Try to load the URL. If it fails we'll show a helpful error page.
  win.loadURL(startUrl, { userAgent: 'Mozilla/5.0 (Electron App)' }).catch((err) => {
    safeLog('loadURL threw', String(err));
    const html = `<!doctype html><html><body><h2>Application could not reach backend</h2><p>Attempted: ${startUrl}</p><p>Open devtools for details.</p></body></html>`;
    win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
  });
}

app.whenReady().then(async () => {
  const isDev = process.argv.includes('--dev') || process.env.ELECTRON_DEV === '1';
  const startUrl = await pickBackendUrl();
  createWindow(startUrl, isDev);
  // start monitoring lockfile and report to backend
  try {
    startLockfileWatcher(startUrl);
    // also start websocket gateway client to backend for LCU RPCs
    try {
      startWebSocketGateway(startUrl);
    } catch (err) {
      safeLog('websocket gateway failed to start', err);
    }
  } catch (err) {
    safeLog('lockfile watcher failed to start', err);
  }
}).catch(err => { safeLog('app.whenReady error', err); app.quit(); });

app.on('window-all-closed', () => { 
  stopWebSocketHeartbeat(); // Parar heartbeat
  if (wsReconnectTimer) {
    clearTimeout(wsReconnectTimer);
    wsReconnectTimer = null;
  }
  app.quit(); 
});

// --- Lockfile watcher & reporter -------------------------------------------------
// Parses Riot lockfile format and POSTs LCU config to backend /api/lcu/configure
function parseLockfileContent(content) {
  // expected: <name>:<pid>:<port>:<password>:<protocol>
  const parts = (content || '').trim().split(':');
  if (parts.length >= 5) {
    return {
      name: parts[0],
      pid: parts[1],
      port: parseInt(parts[2], 10) || 0,
      password: parts[3],
      protocol: parts[4]
    };
  }
  return null;
}

function postConfigToBackend(backendBase, cfg) {
  try {
    const url = new URL('/api/lcu/configure', backendBase).toString();
    safeLog('posting LCU config to', url, cfg);

    const body = JSON.stringify({ host: '127.0.0.1', port: cfg.port, protocol: cfg.protocol, password: cfg.password });
    const parsed = new URL(url);
    const lib = parsed.protocol === 'https:' ? https : http;
    const options = {
      method: 'POST',
      hostname: parsed.hostname,
      port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
      path: parsed.pathname + (parsed.search || ''),
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body, 'utf8')
      },
      timeout: 5000
    };

    const req = lib.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        safeLog('backend response', res.statusCode, data);
      });
    });
    req.on('error', (err) => safeLog('error posting config to backend', String(err)));
    req.on('timeout', () => { req.destroy(); safeLog('postConfigToBackend timeout'); });
    req.write(body);
    req.end();
  } catch (err) {
    safeLog('postConfigToBackend failed', String(err));
  }
}

function startLockfileWatcher(backendBase) {
  const candidates = [
    'C:/Riot Games/League of Legends/lockfile',
    path.join(process.env.LOCALAPPDATA || '', 'Riot Games', 'League of Legends', 'lockfile'),
    path.join(process.env.USERPROFILE || '', 'AppData', 'Local', 'Riot Games', 'League of Legends', 'lockfile')
  ];

  let lastSeen = '';

  // handle single candidate path: return true if the path exists (so we should stop searching)
  function handleCandidate(p) {
    if (!p) return false;
    if (!fs.existsSync(p)) return false;
    try {
      const content = fs.readFileSync(p, { encoding: 'utf8' });
      if (content && content !== lastSeen) {
        lastSeen = content;
        const parsed = parseLockfileContent(content);
        if (parsed) {
          safeLog('lockfile parsed', parsed);
          postConfigToBackend(backendBase, parsed);
        } else {
          safeLog('lockfile found but could not parse', p);
        }
      }
      return true; // path exists -> stop after first found path
    } catch (err) {
      safeLog('error reading lockfile candidate', p, String(err));
      return false; // error reading this candidate, continue to next
    }
  }

  function probeOnce() {
    for (const p of candidates) {
      if (handleCandidate(p)) return;
    }
  }

  // initial probe
  probeOnce();

  // poll every 5 seconds; fs.watch on this path can be flaky across permissions and Windows paths
  const iv = setInterval(probeOnce, 5000);

  // stop when app quits
  app.on('will-quit', () => { clearInterval(iv); });

  safeLog('lockfile watcher started, probing candidates', candidates);
}

// -------------------------------------------------------------------------------

// --- WebSocket gateway client ---------------------------------------------------
function startWebSocketGateway(backendBase) {
  try {
    // backendBase is like http://localhost:8080/ -> ws url replace protocol
    const parsed = new URL(backendBase);
    const wsProtocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = wsProtocol + '//' + parsed.hostname + (parsed.port ? ':' + parsed.port : '') + '/client-ws';

    safeLog('ws gateway connecting to', wsUrl);

    wsClient = new WebSocket(wsUrl, {
      headers: {
        // optional auth token for backend (set BACKEND_GATEWAY_TOKEN env)
        ...(process.env.BACKEND_GATEWAY_TOKEN ? { 'Authorization': 'Bearer ' + process.env.BACKEND_GATEWAY_TOKEN } : {})
      }
    });

    wsClient.on('open', () => {
      safeLog('ws gateway connected');
      resetWebSocketReconnect(); // Reset tentativas de reconexão
      startWebSocketHeartbeat(); // Iniciar heartbeat
      // send a register/identify message
      const info = readLockfileInfo();
      const register = { type: 'identify', playerId: process.env.ELECTRON_CLIENT_ID || 'electron-client', data: { lockfile: info } };
      try { wsClient.send(JSON.stringify(register)); } catch (e) { safeLog('ws send register error', String(e)); }
      // Proactive LCU status check: perform a quick /lol-summoner/v1/current-summoner
      // and inform backend so it can mark the gateway as having a working LCU.
      setTimeout(() => {
        (async () => {
          try {
            if (info) {
              try {
                const result = await performLcuRequest('GET', '/lol-summoner/v1/current-summoner');
                safeLog('proactive lcu current-summoner ->', result);
                // attempt to extract a stable summoner identifier so backend can mark the gateway as connected
                let summonerId = null;
                try {
                  if (result) {
                    if (typeof result === 'object') {
                      summonerId = result.summonerId || result.id || result.puuid || result.accountId || result.displayName || null;
                    } else if (typeof result === 'string') {
                      // sometimes the API returns a bare string; try to parse
                      try { const parsed = JSON.parse(result); if (parsed && typeof parsed === 'object') summonerId = parsed.summonerId || parsed.id || parsed.puuid || parsed.accountId || parsed.displayName || null; } catch (e) { /* ignore */ }
                    }
                  }
                } catch (e) {
                  safeLog('extract summoner id failed', String(e));
                }

                // ensure body contains a stable summonerId field at top-level so backend can extract it
                const bodyForBackend = (result && typeof result === 'object') ? Object.assign({}, result) : { raw: result };
                if (summonerId) bodyForBackend.summonerId = summonerId;
                const statusMsg = { type: 'lcu_status', data: { status: 200, body: bodyForBackend, summonerId: summonerId } };
                wsClient.send(JSON.stringify(statusMsg));
              } catch (err) {
                const statusMsg = { type: 'lcu_status', data: { status: 500, error: String(err), summonerId: null } };
                try { wsClient.send(JSON.stringify(statusMsg)); } catch (e) { safeLog('failed to send lcu_status', String(e)); }
              }
            }
          } catch (e) {
            safeLog('proactive lcu check failed', String(e));
          }
        })();
      }, 250);
      // if we have a pending identify from renderer, send it
      if (lastRendererIdentify) {
        try { wsClient.send(JSON.stringify({ type: 'identify', data: lastRendererIdentify })); } catch (e) { safeLog('ws send renderer identify error', String(e)); }
      }
      
      // Initialize Discord bot after WebSocket connection
      initializeDiscordOnConnect();
    });

    wsClient.on('message', async (msg) => {
      try {
        const json = JSON.parse(msg.toString());
        
        // Handle LCU requests
        if (json.type === 'lcu_request') {
          safeLog('ws gateway received lcu_request', json.id, json.method, json.path);
          try {
            safeLog('ws gateway calling performLcuRequest for', json.path);
            const result = await performLcuRequest(json.method || 'GET', json.path, json.body);
            safeLog('ws gateway performLcuRequest success for', json.path, 'result type:', typeof result);
            const resp = { type: 'lcu_response', id: json.id, status: 200, body: result };
            wsClient.send(JSON.stringify(resp));
            safeLog('ws gateway sent lcu_response for', json.id);
          } catch (err) {
            safeLog('lcu_request handler error', String(err));
            const resp = { type: 'lcu_response', id: json.id, status: 500, error: String(err) };
            wsClient.send(JSON.stringify(resp));
            safeLog('ws gateway sent lcu_response error for', json.id);
          }
        }
        // Handle Discord requests
        else if (json.type === 'discord_request') {
          safeLog('ws gateway received discord_request', json.id, json.method, json.path);
          try {
            const result = await performDiscordRequest(json.method || 'GET', json.path, json.body);
            safeLog('ws gateway performDiscordRequest success for', json.path, 'result type:', typeof result);
            const resp = { type: 'discord_response', id: json.id, status: 200, body: result };
            wsClient.send(JSON.stringify(resp));
            safeLog('ws gateway sent discord_response for', json.id);
          } catch (err) {
            safeLog('discord_request handler error', String(err));
            const resp = { type: 'discord_response', id: json.id, status: 500, error: String(err) };
            wsClient.send(JSON.stringify(resp));
            safeLog('ws gateway sent discord_response error for', json.id);
          }
        }
        // Handle Discord status requests
        else if (json.type === 'get_discord_status') {
          safeLog('ws gateway received get_discord_status');
          try {
            const status = await getDiscordStatus();
            const resp = { type: 'discord_status', data: status };
            wsClient.send(JSON.stringify(resp));
            safeLog('ws gateway sent discord_status');
          } catch (err) {
            safeLog('get_discord_status error', String(err));
            const resp = { type: 'discord_status', data: { isConnected: false, error: String(err) } };
            wsClient.send(JSON.stringify(resp));
          }
        }
        // Handle Discord users online requests
        else if (json.type === 'get_discord_users_online') {
          safeLog('ws gateway received get_discord_users_online');
          try {
            const users = await getDiscordUsersOnline();
            const resp = { type: 'discord_users_online', users: users };
            wsClient.send(JSON.stringify(resp));
            safeLog('ws gateway sent discord_users_online', users.length, 'users');
          } catch (err) {
            safeLog('get_discord_users_online error', String(err));
            const resp = { type: 'discord_users_online', users: [], error: String(err) };
            wsClient.send(JSON.stringify(resp));
          }
        }
      } catch (e) {
        safeLog('ws gateway message error', String(e));
      }
    });

    wsClient.on('close', (code, reason) => { 
      safeLog('ws gateway closed', code, reason && reason.toString()); 
      stopWebSocketHeartbeat(); // Parar heartbeat
      wsClient = null; 
      scheduleWebSocketReconnect(backendBase);
    });
    wsClient.on('error', (err) => { safeLog('ws gateway error', String(err)); });
  } catch (err) {
    safeLog('startWebSocketGateway error', String(err));
  }
}

// shared ws client reference and last identify from renderer
let wsClient = null;
let lastRendererIdentify = null;
let wsReconnectAttempts = 0;
let wsReconnectTimer = null;
let wsHeartbeatTimer = null;
const WS_MAX_RECONNECT_ATTEMPTS = 10;
const WS_BASE_BACKOFF_MS = 2000; // 2 segundos
const WS_MAX_BACKOFF_MS = 60000; // 1 minuto
const WS_HEARTBEAT_INTERVAL = 60000; // 60 segundos

// Discord integration
let discordBot = null;
let discordChannelId = null;
let discordUsers = [];
let discordStatus = { isConnected: false, botUsername: null, channelName: null };

// Função de reconexão inteligente com backoff exponencial
function scheduleWebSocketReconnect(backendBase) {
  if (wsReconnectTimer) {
    clearTimeout(wsReconnectTimer);
    wsReconnectTimer = null;
  }

  if (wsReconnectAttempts >= WS_MAX_RECONNECT_ATTEMPTS) {
    safeLog('ws gateway: max reconnect attempts reached, stopping');
    return;
  }

  wsReconnectAttempts++;
  const backoff = Math.min(WS_BASE_BACKOFF_MS * Math.pow(1.5, wsReconnectAttempts - 1), WS_MAX_BACKOFF_MS);
  
  safeLog(`ws gateway: scheduling reconnect attempt ${wsReconnectAttempts}/${WS_MAX_RECONNECT_ATTEMPTS} in ${backoff}ms`);
  
  wsReconnectTimer = setTimeout(() => {
    wsReconnectTimer = null;
    startWebSocketGateway(backendBase);
  }, backoff);
}

// Reset reconexão quando conecta com sucesso
function resetWebSocketReconnect() {
  wsReconnectAttempts = 0;
  if (wsReconnectTimer) {
    clearTimeout(wsReconnectTimer);
    wsReconnectTimer = null;
  }
}

// Iniciar heartbeat para manter conexão ativa
function startWebSocketHeartbeat() {
  if (wsHeartbeatTimer) {
    clearInterval(wsHeartbeatTimer);
  }
  
  wsHeartbeatTimer = setInterval(() => {
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      try {
        wsClient.send(JSON.stringify({ type: 'ping', ts: new Date().toISOString() }));
        safeLog('ws gateway: heartbeat sent');
      } catch (e) {
        safeLog('ws gateway: heartbeat error', String(e));
      }
    }
  }, WS_HEARTBEAT_INTERVAL);
  
  safeLog('ws gateway: heartbeat started');
}

// Parar heartbeat
function stopWebSocketHeartbeat() {
  if (wsHeartbeatTimer) {
    clearInterval(wsHeartbeatTimer);
    wsHeartbeatTimer = null;
    safeLog('ws gateway: heartbeat stopped');
  }
}

// IPC handler so preload/renderer can send a richer identify payload
ipcMain.handle('lcu:identify', async (_evt, payload) => {
  try {
    lastRendererIdentify = payload;
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      wsClient.send(JSON.stringify({ type: 'identify', data: payload }));
    }
    return { success: true };
  } catch (err) {
    safeLog('ipc lcu:identify error', String(err));
    return { success: false, error: String(err) };
  }
});

function readLockfileInfo() {
  const candidates = [
    'C:/Riot Games/League of Legends/lockfile',
    path.join(process.env.LOCALAPPDATA || '', 'Riot Games', 'League of Legends', 'lockfile'),
    path.join(process.env.USERPROFILE || '', 'AppData', 'Local', 'Riot Games', 'League of Legends', 'lockfile')
  ];
  for (const p of candidates) {
    try {
      if (p && fs.existsSync(p)) {
        const content = fs.readFileSync(p, 'utf8');
        const parsed = parseLockfileContent(content);
        if (parsed) return { host: '127.0.0.1', port: parsed.port, protocol: parsed.protocol, password: parsed.password };
      }
    } catch (err) {
      safeLog('readLockfileInfo error', String(err));
    }
  }
  return null;
}

function performLcuRequest(method, pathname, body) {
  return new Promise((resolve, reject) => {
    const info = readLockfileInfo();
    if (!info) return reject(new Error('lockfile not found'));

    try {
      const isHttps = info.protocol === 'https';
      const protocol = isHttps ? https : http;
      const urlBase = `${info.protocol}://${info.host}:${info.port}`;
      const full = new URL(pathname.startsWith('/') ? pathname : `/${pathname}`, urlBase);
      const auth = 'Basic ' + Buffer.from(`riot:${info.password}`).toString('base64');
      const payload = body ? JSON.stringify(body) : undefined;
      const opts = {
        method: method || 'GET',
        headers: {
          'Authorization': auth,
          'Content-Type': 'application/json'
        }
      };
      // if LCU is https with self-signed cert, allow it for local requests
      if (isHttps) {
        try {
          opts.agent = new https.Agent({ rejectUnauthorized: false });
        } catch (e) {
          safeLog('could not create https agent to ignore self-signed certs', String(e));
        }
      }

      const req = protocol.request(full, opts, (res) => {
        let data = '';
        res.on('data', (c) => data += c.toString());
        res.on('end', () => {
          try {
            const isJson = (res.headers['content-type'] || '').includes('application/json');
            const parsed = isJson && data ? JSON.parse(data) : data;
            if (res.statusCode >= 200 && res.statusCode < 300) resolve(parsed);
            else reject(new Error(`LCU status ${res.statusCode}: ${data}`));
          } catch (e) { reject(e); }
        });
      });
      req.on('error', (e) => reject(e));
      if (payload) req.write(payload);
      req.end();
    } catch (err) {
      reject(err);
    }
  });
}

// =============================================================================
// Discord Integration Functions
// =============================================================================

// Perform Discord API request
function performDiscordRequest(method, pathname, body) {
  return new Promise((resolve, reject) => {
    if (!discordBot) {
      return reject(new Error('Discord bot not initialized'));
    }

    try {
      // For now, return mock data - in real implementation, this would call Discord API
      const mockResponse = {
        status: 200,
        data: {
          message: 'Discord request processed',
          path: pathname,
          method: method
        }
      };
      resolve(mockResponse);
    } catch (err) {
      safeLog('discord request error', String(err));
      return reject(err);
    }
  });
}

// Get Discord status
async function getDiscordStatus() {
  try {
    // In real implementation, this would check if Discord bot is connected
    // For now, return mock status
    return {
      isConnected: discordStatus.isConnected,
      botUsername: discordStatus.botUsername,
      channelName: discordStatus.channelName,
      usersCount: discordUsers.length
    };
  } catch (err) {
    safeLog('getDiscordStatus error', String(err));
    return {
      isConnected: false,
      error: String(err)
    };
  }
}

// Get Discord users online
async function getDiscordUsersOnline() {
  try {
    // In real implementation, this would fetch users from Discord channel
    // For now, return mock users
    return discordUsers;
  } catch (err) {
    safeLog('getDiscordUsersOnline error', String(err));
    return [];
  }
}

// Initialize Discord bot (mock implementation)
async function initializeDiscordBot() {
  try {
    safeLog('initializing Discord bot...');
    
    // In real implementation, this would:
    // 1. Load Discord token from settings
    // 2. Initialize Discord client
    // 3. Connect to Discord
    // 4. Join the configured channel
    // 5. Start monitoring users
    
    // Mock initialization
    discordStatus.isConnected = true;
    discordStatus.botUsername = 'LoL Matchmaking Bot';
    discordStatus.channelName = 'lol-matchmaking';
    
    // Mock users
    discordUsers = [
      {
        id: 'user1',
        username: 'FZD Ratoso',
        displayName: 'FZD Ratoso',
        linkedNickname: {
          gameName: 'FZD Ratoso',
          tagLine: 'fzd'
        },
        inChannel: true,
        joinedAt: new Date().toISOString()
      }
    ];
    
    safeLog('Discord bot initialized successfully');
    return true;
  } catch (err) {
    safeLog('Discord bot initialization error', String(err));
    discordStatus.isConnected = false;
    return false;
  }
}

// Start Discord monitoring
function startDiscordMonitoring() {
  if (!discordStatus.isConnected) {
    safeLog('Discord bot not connected, cannot start monitoring');
    return;
  }

  // In real implementation, this would:
  // 1. Monitor voice channel for user joins/leaves
  // 2. Update discordUsers array
  // 3. Send updates to backend via WebSocket
  
  safeLog('Discord monitoring started');
  
  // Mock periodic updates
  setInterval(() => {
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      const update = {
        type: 'discord_users_online',
        users: discordUsers,
        timestamp: new Date().toISOString()
      };
      wsClient.send(JSON.stringify(update));
    }
  }, 30000); // Update every 30 seconds
}

// Initialize Discord when WebSocket connects
function initializeDiscordOnConnect() {
  setTimeout(async () => {
    await initializeDiscordBot();
    if (discordStatus.isConnected) {
      startDiscordMonitoring();
    }
  }, 2000); // Wait 2 seconds after WebSocket connection
}

// -------------------------------------------------------------------------------
