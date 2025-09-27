import { ipcRenderer } from 'electron';

// Definir tipos para as APIs expostas
interface ElectronAPI {
  openExternal: (url: string) => Promise<void>;
  getVersion: () => string;
  onWindowReady: (callback: () => void) => void;
  removeAllListeners: (channel: string) => void;
  getBackendUrl: () => string | null; // Nova função
  getLCULockfileInfo: () => { host: string; port: number; protocol: string; password: string } | null;
  fs?: { appendFile: (p: string, data: string, cb: (err?: any) => void) => void };
  path?: any;
  process?: { cwd: () => string; versions: NodeJS.ProcessVersions; platform: string };
  getUserDataPath?: () => Promise<string>;
}

interface LCUInfo { host: string; port: number; protocol: string; password: string }

// Internal cache
let cachedLCU: LCUInfo | null = null;
let cachedLCUStatMtime = 0;

function refreshCachedLCUIfChanged(): void {
  try {
    const fs = require('fs');
    const path = require('path');
    const candidates: string[] = [
      path.join(process.env['LOCALAPPDATA'] || '', 'Riot Games', 'League of Legends', 'lockfile'),
      'C:/Riot Games/League of Legends/lockfile'
    ];
    for (const filePath of candidates) {
      if (filePath && fs.existsSync(filePath)) {
        try {
          const st = fs.statSync(filePath);
          const mtime = (st && (st as any).mtimeMs) ? (st as any).mtimeMs : ((st && st.mtime) ? +st.mtime : 0);
          if (!cachedLCU || mtime !== cachedLCUStatMtime) {
            cachedLCU = null;
            cachedLCUStatMtime = mtime;
          }
          break;
        } catch { /* ignore */ }
      }
    }
  } catch { /* ignore */ }
}

function readLockfile(): LCUInfo | null {
  try {
    const fs = require('fs');
    const path = require('path');
    const candidates: string[] = [
      path.join(process.env['LOCALAPPDATA'] || '', 'Riot Games', 'League of Legends', 'lockfile'),
      'C:/Riot Games/League of Legends/lockfile'
    ];
    for (const filePath of candidates) {
      if (filePath && fs.existsSync(filePath)) {
        const content = fs.readFileSync(filePath, 'utf8');
        const parts = content.split(':'); // leagueclient:PID:PORT:PASSWORD:PROTOCOL
        if (parts.length >= 5) {
          const portStr = String(parts[2]).trim();
          const password = String(parts[3]).trim();
          const protocol = String(parts[4]).trim().toLowerCase();
          const info = { host: '127.0.0.1', port: parseInt(portStr, 10), password, protocol };
          try {
            const st = fs.statSync(filePath);
            cachedLCUStatMtime = (st && (st as any).mtimeMs) ? (st as any).mtimeMs : ((st && st.mtime) ? +st.mtime : cachedLCUStatMtime);
            cachedLCU = info;
          } catch {
            cachedLCU = info;
          }
          return info;
        }
      }
    }
  } catch (e) {
    console.error('[preload] Erro ao ler lockfile do LCU:', e instanceof Error ? e.message : String(e));
  }
  return null;
}

async function lcuRequest(pathname: string, method: string = 'GET', body?: any): Promise<any> {
  const https = require('https');
  const { URL } = require('url');

  // Refresh cache metadata and re-read lockfile per request to tolerate LCU restarts
  try { refreshCachedLCUIfChanged(); } catch { }
  let current: LCUInfo | null = null;
  try { current = readLockfile(); } catch { current = null; }
  if (!current) { cachedLCU = null; throw new Error('LCU lockfile não encontrado'); }
  const base = `${current.protocol}://${current.host}:${current.port}`;
  const url = new URL(pathname.startsWith('/') ? pathname : `/${pathname}`, base);
  const auth = 'Basic ' + Buffer.from(`riot:${current.password}`).toString('base64');

  const agent = new https.Agent({ rejectUnauthorized: false });

  const payload = body ? (typeof body === 'string' ? body : JSON.stringify(body)) : undefined;

  const options = {
    method,
    headers: {
      'Authorization': auth,
      'Content-Type': 'application/json'
    },
    agent
  };

  return new Promise((resolve, reject) => {
    const req = https.request(url, options, (res: any) => {
      let data = '';
      res.on('data', (chunk: any) => (data += chunk));
      res.on('end', () => {
        try {
          const isJson = (res.headers['content-type'] || '').includes('application/json');
          const parsed = isJson && data ? JSON.parse(data) : data;
          if (res.statusCode >= 200 && res.statusCode < 300) resolve(parsed);
          else reject(new Error(`LCU ${res.statusCode}: ${typeof parsed === 'string' ? parsed : JSON.stringify(parsed)}`));
        } catch (e) {
          reject(e);
        }
      });
    });
    req.on('error', (err: any) => { try { cachedLCU = null; } catch {} ; reject(err); });
    if (payload) req.write(payload);
    req.end();
  });
}

async function lcuGetGameflowPhase(): Promise<any> {
  return lcuRequest('/lol-gameflow/v1/gameflow-phase', 'GET');
}

async function lcuGetSession(): Promise<any> {
  return lcuRequest('/lol-gameflow/v1/session', 'GET');
}

async function lcuGetMatchHistory(): Promise<any> {
  // Busca summonerId e depois histórico
  const summoner = await lcuRequest('/lol-summoner/v1/current-summoner', 'GET');
  const summonerId = summoner?.summonerId;
  if (!summonerId) throw new Error('summonerId indisponível');
  return lcuRequest(`/lol-match-history/v1/matches/${summonerId}`, 'GET');
}

// Expor APIs seguras para o renderer process
const electronAPI: ElectronAPI & {
  lcu?: {
    request: (pathname: string, method?: string, body?: any) => Promise<any>;
    getCurrentSummoner: () => Promise<any>;
    getGameflowPhase: () => Promise<any>;
    getSession: () => Promise<any>;
    getMatchHistory: () => Promise<any>;
  };
  sendLog?: (level: string, message: string) => void;
} = {
  // Abrir links externos
  openExternal: (url: string): Promise<void> =>
    ipcRenderer.invoke('shell:openExternal', url),

  // Obter versão do Electron
  getVersion: (): string => process.versions.electron || 'unknown',

  // Escutar eventos do main process
  onWindowReady: (callback: () => void): void => {
    ipcRenderer.on('window-ready', callback);
  },

  // Remover listeners
  removeAllListeners: (channel: string): void => {
    ipcRenderer.removeAllListeners(channel);
  },

  // Obter URL do backend a partir de variáveis de ambiente
  getBackendUrl: (): string | null => {
    return process.env['BACKEND_URL'] || null;
  },

  // Método para enviar logs diretamente para o sistema de logging do Electron
  sendLog: (level: string, message: string): void => {
    try {
      ipcRenderer.send('renderer-log', {
        level,
        message,
        timestamp: new Date().toISOString()
      });
    } catch (error) {
      // Fallback silencioso se IPC falhar
    }
  },

  // Expor apenas helpers mínimos para o renderer
  // Evitar expor `fs`, `path` e `process` diretamente para reduzir superfície de ataque e ruído de IPC

  // Obter userData path do main process (async)
  getUserDataPath: async (): Promise<string> => {
    // Use ipcRenderer to ask main process
    try {
      // @ts-ignore
      return await ipcRenderer.invoke('app:getUserDataPath');
    } catch (e) {
      // Garantir que a rejeição seja um Error para manter stack traces úteis
      return Promise.reject(e instanceof Error ? e : new Error(String(e)));
    }
  },

  // Proxy HTTP para contornar restrições do renderer em file://
  proxyRequest: async (opts: { method?: string; url: string; headers?: Record<string, string>; body?: any; timeoutMs?: number }) => {
    try {
      // @ts-ignore
      return await ipcRenderer.invoke('app:proxyRequest', opts);
    } catch (e) {
      return Promise.reject(e instanceof Error ? e : new Error(String(e)));
    }
  },

  // Ler lockfile do League of Legends no host para configurar LCU no backend (via Docker)
  getLCULockfileInfo: (): { host: string; port: number; protocol: string; password: string } | null => {
    const info = readLockfile();
    if (!info) return null;
    // Para o backend containerizado, deixar host='auto' (LCUService tentará host.docker.internal e 127.0.0.1)
    const overrideHost = process.env['LCU_HOST'] && process.env['LCU_HOST']!.trim().length > 0
      ? process.env['LCU_HOST']!.trim()
      : 'auto';
    return { host: overrideHost, port: info.port, protocol: info.protocol, password: info.password };
  }
} as any;

// Intercept renderer console minimally: only forward errors to reduce IPC overhead
try {
  const origConsoleError = console.error;

  const sendLog = (level: string, args: any[]) => {
    try {
      const message = args.map(a => (typeof a === 'string' ? a : JSON.stringify(a))).join(' ');
      ipcRenderer.send('renderer-log', { level, message, timestamp: new Date().toISOString() });
    } catch { /* noop */ }
  };

  console.error = (...args: any[]) => { sendLog('error', args); origConsoleError.apply(console, args); };

  (globalThis as any).addEventListener('error', (ev: any) => {
    try {
      const msg = `${ev.message} at ${ev.filename}:${ev.lineno}:${ev.colno}`;
      ipcRenderer.send('renderer-log', { level: 'error', message: msg, timestamp: new Date().toISOString() });
    } catch { }
  });

  (globalThis as any).addEventListener('unhandledrejection', (ev: any) => {
    try {
      const reason = ev.reason instanceof Error ? ev.reason : (ev.reason ? new Error(String(ev.reason)) : new Error('Unhandled rejection'));
      ipcRenderer.send('renderer-log', { level: 'error', message: `UnhandledRejection: ${reason.stack || String(reason)}`, timestamp: new Date().toISOString() });
    } catch { }
  });
} catch (e) {
  // ignore if cannot override
}

// Prevent OS-level drag/drop from navigating the app and neutralize broken global handlers
try {
  // Attach safe listeners that don't rely on a global variable
  (globalThis as any).addEventListener('dragover', (e: any) => { try { e.preventDefault(); } catch (err) { console.warn('[preload] dragover handler error:', err); } });
  (globalThis as any).addEventListener('drop', (e: any) => { try { e.preventDefault(); } catch (err) { console.warn('[preload] drop handler error:', err); } });
  // Also set ondragover/ondrop defensively to avoid code that references an undefined `dragEvent`
  try { (globalThis as any).ondragover = (e: any) => { try { e.preventDefault(); } catch (err) { console.warn('[preload] ondragover error:', err); } return false; }; } catch (err) { console.warn('[preload] failed set ondragover:', err); }
  try { (globalThis as any).ondrop = (e: any) => { try { e.preventDefault(); } catch (err) { console.warn('[preload] ondrop error:', err); } return false; }; } catch (err) { console.warn('[preload] failed set ondrop:', err); }
} catch (e) { console.warn('[preload] defensive drag/drop setup failed:', e); }

// Attach LCU helpers
(electronAPI as any).lcu = {
  request: (pathname: string, method?: string, body?: any) => lcuRequest(pathname, method, body),
  getCurrentSummoner: () => lcuRequest('/lol-summoner/v1/current-summoner', 'GET'),
  getGameflowPhase: () => lcuGetGameflowPhase(),
  getSession: () => lcuGetSession(),
  getMatchHistory: () => lcuGetMatchHistory()
};

// Lightweight helpers for WS integration and polling (registered by WS closure in JS)
if (!(globalThis as any).__registerPreloadSafeSend) {
  (globalThis as any).__registerPreloadSafeSend = (fn: (...args: any[]) => void) => {
    (globalThis as any).__preloadSafeSend = fn;
    if (!(globalThis as any).__preloadHeartbeatInterval) {
      const HB = Number(process.env['WS_HEARTBEAT_MS'] || 30000);
      (globalThis as any).__preloadHeartbeatInterval = setInterval(() => {
        try { if (typeof (globalThis as any).__preloadSafeSend === 'function') (globalThis as any).__preloadSafeSend({ type: 'heartbeat', timestamp: new Date().toISOString() }); } catch { }
      }, HB);
    }
  };
}
if (!(globalThis as any).__unregisterPreloadSafeSend) {
  (globalThis as any).__unregisterPreloadSafeSend = () => {
    try { (globalThis as any).__preloadSafeSend = null; if ((globalThis as any).__preloadHeartbeatInterval) { clearInterval((globalThis as any).__preloadHeartbeatInterval); (globalThis as any).__preloadHeartbeatInterval = null; } } catch { }
  };
}

// Debounced LCU status sender used by polling loop
if (!(globalThis as any).__preloadSendLcuStatus) {
  let pending: any = null;
  let stabilityTimer: any = null;
  const STABLE_MS = Number(process.env['LCU_STABLE_MS'] || 2500);
  (globalThis as any).__preloadSendLcuStatus = (status: any) => {
    try {
      if (JSON.stringify(pending) === JSON.stringify(status)) {
        if (stabilityTimer) clearTimeout(stabilityTimer);
        stabilityTimer = setTimeout(() => { try { if (typeof (globalThis as any).__preloadSafeSend === 'function') (globalThis as any).__preloadSafeSend({ type: 'lcu_status', data: status, timestamp: new Date().toISOString() }); pending = null; } catch { } }, STABLE_MS);
        return;
      }
      pending = status;
      if (stabilityTimer) clearTimeout(stabilityTimer);
      stabilityTimer = setTimeout(() => { try { if (typeof (globalThis as any).__preloadSafeSend === 'function') (globalThis as any).__preloadSafeSend({ type: 'lcu_status', data: pending, timestamp: new Date().toISOString() }); pending = null; } catch { } }, STABLE_MS);
    } catch { }
  };
}

// Polling loop to detect LCU state changes
(function startLcuPoll() {
  try {
    const POLL_INTERVAL = Number(process.env['LCU_POLL_MS'] || 2000);
    let lastStatus: any = null;
    // Store the interval id on globalThis so main or shutdown handlers can clear it
    const pollInterval = setInterval(async () => {
       try {
         let status: any = { connected: false };
         try { const phase = await lcuGetGameflowPhase(); const session = await lcuGetSession().catch(() => null); status = { connected: true, phase, session }; } catch (e) { status = { connected: false, error: (e as Error).message || String(e) }; }
         if (JSON.stringify(status) !== JSON.stringify(lastStatus)) {
           lastStatus = status;
           try { if (typeof (globalThis as any).__preloadSendLcuStatus === 'function') (globalThis as any).__preloadSendLcuStatus(status); } catch { }
         }
       } catch { }
    }, POLL_INTERVAL);

    try { (globalThis as any).__lcuPollInterval = pollInterval; } catch { }
  } catch { }
})();

// Add handler to perform cleanup from main process (app shutdown)
try {
  ipcRenderer.on('app:shutdown', () => {
    try {
      // Call existing unregister helper to stop heartbeat
      try { if (typeof (globalThis as any).__unregisterPreloadSafeSend === 'function') (globalThis as any).__unregisterPreloadSafeSend(); } catch { }

      // Clear lcu polling interval if present
      try { const iv = (globalThis as any).__lcuPollInterval; if (iv) { clearInterval(iv); (globalThis as any).__lcuPollInterval = null; } } catch { }

      // Also attempt to clear any leftover heartbeat interval
      try { const hb = (globalThis as any).__preloadHeartbeatInterval; if (hb) { clearInterval(hb); (globalThis as any).__preloadHeartbeatInterval = null; } } catch { }

      // Acknowledge to main process that shutdown tasks ran
      try { ipcRenderer.send('app:shutdown:ack'); } catch { }
    } catch (e) {
      try { ipcRenderer.send('app:shutdown:ack'); } catch { }
    }
  });
} catch (e) {
  // If IPC cannot be attached, nothing more to do
}
