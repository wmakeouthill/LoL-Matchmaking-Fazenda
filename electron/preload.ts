import { contextBridge, ipcRenderer } from 'electron';

// Definir tipos para as APIs expostas
interface ElectronAPI {
  openExternal: (url: string) => Promise<void>;
  getVersion: () => string;
  onWindowReady: (callback: () => void) => void;
  removeAllListeners: (channel: string) => void;
  getBackendUrl: () => string | null; // Nova função
  getLCULockfileInfo: () => { host: string; port: number; protocol: string; password: string } | null;
}

interface LCUInfo { host: string; port: number; protocol: string; password: string }

// Internal cache
let cachedLCU: LCUInfo | null = null;

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
          // Para uso local no preload (requisições do host), apontar para 127.0.0.1
          return { host: '127.0.0.1', port: parseInt(portStr, 10), password, protocol };
        }
      }
    }
  } catch (e) {
    console.error('[preload] Erro ao ler lockfile do LCU:', e);
  }
  return null;
}

async function lcuRequest(pathname: string, method: string = 'GET', body?: any): Promise<any> {
  const https = require('https');
  const { URL } = require('url');

  if (!cachedLCU) cachedLCU = readLockfile();
  if (!cachedLCU) throw new Error('LCU lockfile não encontrado');

  const base = `${cachedLCU.protocol}://${cachedLCU.host}:${cachedLCU.port}`;
  const url = new URL(pathname.startsWith('/') ? pathname : `/${pathname}`, base);

  const auth = 'Basic ' + Buffer.from(`riot:${cachedLCU.password}`).toString('base64');

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
    req.on('error', reject);
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
  }
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

// Attach LCU helpers
(electronAPI as any).lcu = {
  request: (pathname: string, method?: string, body?: any) => lcuRequest(pathname, method, body),
  getCurrentSummoner: () => lcuRequest('/lol-summoner/v1/current-summoner', 'GET'),
  getGameflowPhase: () => lcuGetGameflowPhase(),
  getSession: () => lcuGetSession(),
  getMatchHistory: () => lcuGetMatchHistory()
};

// Expor API no contexto global do renderer de forma segura
contextBridge.exposeInMainWorld('electronAPI', electronAPI);

// Log para debug
console.log('🔧 Preload script carregado com sucesso');
console.log('🔧 Context isolation:', process.contextIsolated);
console.log('🔧 Electron version:', process.versions.electron);
console.log('🔧 Node version:', process.versions.node);
console.log('🔧 BACKEND_URL (preload):', process.env['BACKEND_URL'] || '(não definido)');

// Nota: O rewrite runtime foi removido. Se ainda houver pontos do frontend apontando para :3000,
// atualize-os diretamente no código do frontend; enquanto isso, o backend já foi atualizado onde havia referência explícita.
