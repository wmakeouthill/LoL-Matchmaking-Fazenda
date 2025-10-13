const { app, BrowserWindow, Menu, dialog } = require('electron');
const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { ipcMain } = require('electron');

// ⚠️ LOGS DESABILITADOS EM PRODUÇÃO - Não salvar arquivos de log
let LOG_FILE = null; // Mantido como null para desabilitar logs em arquivo

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
  // ⚠️ LOGS DESABILITADOS - Não criar arquivos .log na raiz
  // Os logs continuam no console, mas não são salvos em arquivo
  return; // Desabilitado
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
  // CONFIGURAÇÃO DE REDE: Altere esta URL para o IP do servidor na rede
  // Para testes locais: 'http://localhost:8080/'
  // Para rede local: 'http://192.168.1.5:8080/' (seu IP)
  // Para cloud: 'https://lol-matchmaking-368951732227.southamerica-east1.run.app/'
  // ✅ CORREÇÃO: URL correta do Cloud Run (nome do serviço é 'lol-matchmaking')
  const HARDCODED_BACKEND_URL = 'http://localhost:8080/';
  
  const env = process.env.BACKEND_URL || '';
  const defaultBase = env || HARDCODED_BACKEND_URL;
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

  // ✅ NOVO: Criar menu em português
  createMenu(win);

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

  return win;
}

// ✅ NOVO: Menu em português com funcionalidades avançadas
function createMenu(mainWindow) {
  const template = [
    {
      label: 'Arquivo',
      submenu: [
        {
          label: 'Sair',
          accelerator: 'CmdOrCtrl+Q',
          click: () => {
            app.quit();
          }
        }
      ]
    },
    {
      label: 'Ações',
      submenu: [
        {
          label: 'Atualizar',
          accelerator: 'CmdOrCtrl+R',
          click: () => {
            mainWindow.reload();
          }
        },
        {
          label: 'Limpar Cache Local',
          accelerator: 'CmdOrCtrl+Shift+L',
          click: () => {
            performLocalCacheCleanup(mainWindow);
          }
        },
        {
          label: 'Atualizar + Limpeza Completa',
          accelerator: 'CmdOrCtrl+Shift+R',
          click: () => {
            performCompleteRefresh(mainWindow);
          }
        },
        {
          type: 'separator'
        },
        {
          label: 'Configurar Pasta do League of Legends',
          click: () => {
            selectLeagueInstallationPath(mainWindow);
          }
        },
        {
          label: 'Mostrar Sessões Ativas',
          click: () => {
            requestActiveSessionsList();
          }
        }
      ]
    },
    {
      label: 'Desenvolvedor',
      submenu: [
        {
          label: 'Ferramentas de Desenvolvedor',
          accelerator: 'F12',
          click: () => {
            mainWindow.webContents.openDevTools();
          }
        },
        {
          label: 'Recarregar',
          accelerator: 'CmdOrCtrl+Shift+R',
          click: () => {
            mainWindow.reload();
          }
        }
      ]
    }
  ];

  const menu = Menu.buildFromTemplate(template);
  Menu.setApplicationMenu(menu);
}

// ✅ NOVO: Seleção manual da pasta do League of Legends
async function selectLeagueInstallationPath(mainWindow) {
  try {
    const result = await dialog.showOpenDialog(mainWindow, {
      title: 'Selecionar Pasta do League of Legends',
      message: 'Escolha a pasta onde está instalado o League of Legends',
      properties: ['openDirectory'],
      defaultPath: 'C:/Riot Games/League of Legends'
    });

    if (!result.canceled && result.filePaths.length > 0) {
      const selectedPath = result.filePaths[0];
      const lockfilePath = path.join(selectedPath, 'lockfile');
      
      // Verificar se o lockfile existe
      if (fs.existsSync(lockfilePath)) {
        // ✅ SALVAR no diretório do usuário (mais apropriado)
        const userDataPath = app.getPath('userData');
        const configDir = path.join(userDataPath, 'lol-config');
        
        // Criar diretório se não existir
        if (!fs.existsSync(configDir)) {
          fs.mkdirSync(configDir, { recursive: true });
        }
        
        const configPath = path.join(configDir, 'league-config.json');
        const config = {
          customLeaguePath: selectedPath,
          lockfilePath: lockfilePath,
          lastUpdated: new Date().toISOString(),
          userId: process.env.USERNAME || 'unknown'
        };
        
        fs.writeFileSync(configPath, JSON.stringify(config, null, 2));
        
        // Atualizar lista de candidatos
        updateLockfileCandidates(selectedPath);
        
        safeLog('✅ [Electron] Pasta do League configurada:', selectedPath);
        
        // Mostrar confirmação
        dialog.showMessageBox(mainWindow, {
          type: 'info',
          title: 'Configuração Salva',
          message: 'Pasta do League of Legends configurada com sucesso!',
          detail: `Caminho: ${selectedPath}\nLockfile: ${lockfilePath}`
        });
        
        // Tentar reconectar se LCU estiver ativo
        if (wsClient && wsClient.readyState === WebSocket.OPEN) {
          safeLog('🔄 [Electron] Tentando reconectar com novo caminho...');
          setTimeout(() => {
            checkLockfileAndConnect();
          }, 1000);
        }
        
      } else {
        dialog.showErrorBox(
          'Erro',
          'Lockfile não encontrado na pasta selecionada!\n\nVerifique se o League of Legends está instalado corretamente nesta pasta.'
        );
      }
    }
  } catch (error) {
    safeLog('❌ [Electron] Erro ao selecionar pasta do League:', error);
    dialog.showErrorBox('Erro', 'Erro ao configurar pasta do League of Legends');
  }
}

// ✅ NOVO: Atualizar lista de candidatos com caminho personalizado
function updateLockfileCandidates(customPath) {
  const customLockfile = path.join(customPath, 'lockfile');
  
  // Remover duplicatas e adicionar caminho personalizado no início
  const allCandidates = [
    customLockfile,
    'C:/Riot Games/League of Legends/lockfile',
    'D:/Riot Games/League of Legends/lockfile',
    'E:/Riot Games/League of Legends/lockfile',
    'F:/Riot Games/League of Legends/lockfile',
    'G:/Riot Games/League of Legends/lockfile'
  ];
  
  // Remover duplicatas mantendo ordem
  const uniqueCandidates = [...new Set(allCandidates)];
  
  // Atualizar variável global
  lockfileCandidates = uniqueCandidates;
  
  safeLog('✅ [Electron] Candidatos de lockfile atualizados:', uniqueCandidates);
}

// ✅ NOVO: Limpeza apenas do cache local
async function performLocalCacheCleanup(mainWindow) {
  try {
    safeLog('🧹 [Electron] Iniciando limpeza de cache local...');
    
    // Limpar cache do frontend
    await mainWindow.webContents.executeJavaScript(`
      // Limpar localStorage
      localStorage.clear();
      
      // Limpar sessionStorage
      sessionStorage.clear();
      
      // Limpar cache do navegador
      if ('caches' in window) {
        caches.keys().then(names => {
          names.forEach(name => {
            caches.delete(name);
          });
        });
      }
      
      console.log('🧹 [Frontend] Cache local limpo');
    `);
    
    // Limpar cache do Electron (dados do usuário se necessário)
    try {
      const userDataPath = app.getPath('userData');
      const tempDir = path.join(userDataPath, 'temp');
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true, force: true });
        safeLog('🧹 [Electron] Cache temporário do Electron limpo');
      }
    } catch (error) {
      safeLog('⚠️ [Electron] Erro ao limpar cache do Electron:', error);
    }
    
    safeLog('✅ [Electron] Limpeza de cache local concluída');
    
    // Mostrar confirmação
    dialog.showMessageBox(mainWindow, {
      type: 'info',
      title: 'Cache Limpo',
      message: 'Cache local limpo com sucesso!',
      detail: 'localStorage, sessionStorage e cache do navegador foram limpos.'
    });
    
  } catch (error) {
    safeLog('❌ [Electron] Erro durante limpeza de cache local:', error);
    dialog.showErrorBox('Erro', 'Erro ao limpar cache local');
  }
}

// ✅ NOVO: Limpeza completa + revinculação
async function performCompleteRefresh(mainWindow) {
  try {
    safeLog('🧹 [Electron] Iniciando limpeza completa...');
    
    // 1. Parar monitor de identidade
    stopIdentityMonitor();
    
    // 2. Limpar cache local primeiro
    await performLocalCacheCleanup(mainWindow);
    
    // 3. Solicitar limpeza de estado Redis do backend
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      const cleanupRequest = {
        type: 'clear_player_state',
        source: 'electron_main',
        timestamp: Date.now(),
        reason: 'complete_refresh'
      };
      
      wsClient.send(JSON.stringify(cleanupRequest));
      safeLog('🧹 [Electron] Solicitação de limpeza Redis enviada');
    }
    
    // 4. Aguardar um pouco para limpeza
    await new Promise(resolve => setTimeout(resolve, 1000));
    
    // 5. Recarregar página
    mainWindow.reload();
    
    // 6. Aguardar carregamento e reestabelecer conexão
    setTimeout(async () => {
      if (wsClient && wsClient.readyState === WebSocket.OPEN) {
        safeLog('🔄 [Electron] Reestabelecendo vinculação...');
        
        // Reenviar identificação
        const lockfileInfo = readLockfileInfo();
        if (lockfileInfo) {
          await identifyPlayerToBackend(lockfileInfo);
        }
        
        // Reiniciar monitor de identidade
        startIdentityMonitor();
        
        safeLog('✅ [Electron] Limpeza completa e revinculação concluídas');
      }
    }, 3000);
    
  } catch (error) {
    safeLog('❌ [Electron] Erro durante limpeza completa:', error);
  }
}

// ✅ NOVO: Carregar configuração personalizada do League
function loadLeagueConfig() {
  try {
    // ✅ CARREGAR do diretório do usuário
    const userDataPath = app.getPath('userData');
    const configDir = path.join(userDataPath, 'lol-config');
    const configPath = path.join(configDir, 'league-config.json');
    
    if (fs.existsSync(configPath)) {
      const configData = fs.readFileSync(configPath, 'utf8');
      const config = JSON.parse(configData);
      
      if (config.customLeaguePath) {
        safeLog('✅ [Electron] Configuração personalizada carregada:', config.customLeaguePath);
        safeLog('✅ [Electron] Configuração salva em:', configPath);
        updateLockfileCandidates(config.customLeaguePath);
        return config.customLeaguePath;
      }
    } else {
      safeLog('ℹ️ [Electron] Nenhuma configuração personalizada encontrada em:', configPath);
    }
  } catch (error) {
    safeLog('⚠️ [Electron] Erro ao carregar configuração personalizada:', error);
  }
  return null;
}

// ✅ NOVO: Função auxiliar para verificar lockfile e conectar
function checkLockfileAndConnect() {
  try {
    const lockfileInfo = readLockfileInfo();
    if (lockfileInfo) {
      safeLog('🔄 [Electron] Lockfile detectado, reconectando...');
      // Aqui você pode adicionar lógica adicional de reconexão se necessário
    }
  } catch (error) {
    safeLog('❌ [Electron] Erro ao verificar lockfile:', error);
  }
}

app.whenReady().then(async () => {
  console.log('[electron] ========== APP READY ==========');
  const isDev = process.argv.includes('--dev') || process.env.ELECTRON_DEV === '1';
  console.log('[electron] isDev:', isDev);
  
  // ✅ NOVO: Carregar configuração personalizada do League
  loadLeagueConfig();
  
  const startUrl = await pickBackendUrl();
  console.log('[electron] Backend URL:', startUrl);
  createWindow(startUrl, isDev);
  console.log('[electron] Janela criada, iniciando watchers...');
  // start monitoring lockfile and report to backend
  try {
    startLockfileWatcher(startUrl);
    console.log('[electron] Lockfile watcher iniciado');
    // also start websocket gateway client to backend for LCU RPCs
    try {
      console.log('[electron] *** CHAMANDO startWebSocketGateway ***');
      startWebSocketGateway(startUrl);
      console.log('[electron] *** startWebSocketGateway RETORNOU ***');
    } catch (err) {
      console.error('[electron] ❌ ERRO no startWebSocketGateway:', err);
      safeLog('websocket gateway failed to start', err);
    }
  } catch (err) {
    console.error('[electron] ❌ ERRO no lockfile watcher:', err);
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
    'D:/Riot Games/League of Legends/lockfile',
    'E:/Riot Games/League of Legends/lockfile',
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
          
          // ✅ NOVO: Identificar jogador automaticamente quando lockfile é detectado
          setTimeout(() => {
            identifyPlayerToBackend(parsed);
          }, 2000); // Aguardar 2s para garantir que LCU está pronto
          
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
  // Log direto para garantir que aparece mesmo se safeLog falhar
  console.log('[electron] === startWebSocketGateway CHAMADO ===');
  console.log('[electron] backendBase:', backendBase);
  
  try {
    // backendBase is like http://localhost:8080/ -> ws url replace protocol
    const parsed = new URL(backendBase);
    const wsProtocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
    // ✅ CORREÇÃO: Usar /api/ws que tem CoreWebSocketHandler com suporte a register_lcu_connection
    const wsUrl = wsProtocol + '//' + parsed.hostname + (parsed.port ? ':' + parsed.port : '') + '/api/ws';

    console.log('[electron] 🔌 WebSocket URL construída:', wsUrl);
    safeLog('🔌 [ELECTRON MAIN] Tentando conectar WebSocket gateway em:', wsUrl);

    wsClient = new WebSocket(wsUrl, {
      headers: {
        // optional auth token for backend (set BACKEND_GATEWAY_TOKEN env)
        ...(process.env.BACKEND_GATEWAY_TOKEN ? { 'Authorization': 'Bearer ' + process.env.BACKEND_GATEWAY_TOKEN } : {})
      }
    });

    wsClient.on('open', () => {
      safeLog('✅ [ELECTRON MAIN] WebSocket gateway conectado:', wsUrl);
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
                
                // ✅ CRÍTICO: Construir displayName e summonerName se vierem vazios do LCU
                if (result && typeof result === 'object' && result.gameName && result.tagLine) {
                  const fullName = `${result.gameName}#${result.tagLine}`;
                  if (!result.displayName || result.displayName === '') {
                    result.displayName = fullName;
                  }
                  if (!result.summonerName || result.summonerName === '') {
                    result.summonerName = fullName;
                  }
                }
                
                safeLog('proactive lcu current-summoner ->', result);
                
                // ✅ NOVO: Enviar identify com summonerName para configurar LCU no banco
                if (result && typeof result === 'object' && result.gameName && result.tagLine) {
                  const summonerName = result.displayName || `${result.gameName}#${result.tagLine}`;
                  const identifyWithSummoner = {
                    type: 'identify',
                    playerId: process.env.ELECTRON_CLIENT_ID || 'electron-client',
                    summonerName: summonerName,
                    data: { 
                      lockfile: info,
                      gameName: result.gameName,
                      tagLine: result.tagLine
                    }
                  };
                  try {
                    wsClient.send(JSON.stringify(identifyWithSummoner));
                    safeLog(`✅ Identify com summonerName enviado: ${summonerName}`);
                  } catch (e) {
                    safeLog('❌ Erro ao enviar identify com summonerName', String(e));
                  }

                  // ✅ NOVO: Registrar conexão LCU no registry para roteamento multi-player
                  const registerLcuConnection = {
                    type: 'register_lcu_connection',
                    summonerName: summonerName,
                    port: info.port,
                    authToken: info.password,
                    protocol: info.protocol || 'https',
                    profileIconId: result.profileIconId || null, // ✅ NOVO: Incluir profileIconId do LCU
                    puuid: result.puuid || null, // ✅ NOVO: Incluir puuid
                    summonerId: result.summonerId || null // ✅ NOVO: Incluir summonerId
                  };
                  try {
                    wsClient.send(JSON.stringify(registerLcuConnection));
                    safeLog(`🎯 LCU connection registrada para ${summonerName} (profileIcon: ${result.profileIconId || 'N/A'})`);
                  } catch (e) {
                    safeLog('❌ Erro ao registrar LCU connection', String(e));
                  }
                }
                
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
      console.log('[electron] 📨 [MAIN] Mensagem WebSocket recebida (raw):', msg.toString().substring(0, 200));
      try {
        const json = JSON.parse(msg.toString());
        console.log('[electron] 📨 [MAIN] Mensagem WebSocket parsed, type:', json.type);
        
        // Handle LCU requests
        if (json.type === 'lcu_request') {
          console.log('[electron] 🎯 [MAIN] LCU REQUEST recebido!', json.id, json.method, json.path);
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
        // ✅ NOVO: Handle identity confirmation requests
        else if (json.type === 'confirm_identity') {
          safeLog('ws gateway received confirm_identity');
          try {
            // ✅ Buscar summoner ATUAL do LCU
            const summoner = await performLcuRequest('GET', '/lol-summoner/v1/current-summoner');
            
            const response = {
              type: 'identity_confirmed',
              requestId: json.id,
              summonerName: `${summoner.gameName}#${summoner.tagLine}`,
              puuid: summoner.puuid,
              summonerId: summoner.summonerId,
              timestamp: Date.now()
            };
            
            wsClient.send(JSON.stringify(response));
            safeLog('✅ [Electron] Identidade confirmada:', response.summonerName);
            
          } catch (err) {
            safeLog('❌ [Electron] Erro ao confirmar identidade:', err);
            
            // ✅ Enviar erro ao backend (LCU desconectado)
            wsClient.send(JSON.stringify({
              type: 'identity_confirmation_failed',
              requestId: json.id,
              error: 'LCU_DISCONNECTED'
            }));
          }
        }
        else if (json.type === 'confirm_identity_critical') {
          safeLog('ws gateway received confirm_identity_critical - ação crítica:', json.actionType);
          try {
            // ✅ Buscar summoner ATUAL do LCU
            const summoner = await performLcuRequest('GET', '/lol-summoner/v1/current-summoner');
            
            const response = {
              type: 'identity_confirmed_critical',
              requestId: json.id,
              summonerName: `${summoner.gameName}#${summoner.tagLine}`,
              puuid: summoner.puuid,
              summonerId: summoner.summonerId,
              actionType: json.actionType,
              timestamp: Date.now()
            };
            
            wsClient.send(JSON.stringify(response));
            safeLog('✅ [Electron] Identidade CRÍTICA confirmada:', response.summonerName, 'para ação:', json.actionType);
            
          } catch (err) {
            safeLog('❌ [Electron] Erro ao confirmar identidade CRÍTICA:', err);
            
            // ✅ Enviar erro ao backend (LCU desconectado)
            wsClient.send(JSON.stringify({
              type: 'identity_confirmation_failed',
              requestId: json.id,
              error: 'LCU_DISCONNECTED'
            }));
          }
        }
        // ✅ NOVO: Handler para lista de sessões ativas
        else if (json.type === 'active_sessions_list') {
          safeLog('📋 [Electron] ===== LISTA DE SESSÕES ATIVAS =====');
          safeLog('📋 [Electron] Total de sessões:', json.totalSessions);
          safeLog('📋 [Electron] Sessões identificadas:', json.identifiedSessions);
          safeLog('📋 [Electron] Sessões locais:', json.localSessions);
          
          if (json.sessions && json.sessions.length > 0) {
            safeLog('📋 [Electron] === DETALHES DAS SESSÕES ===');
            json.sessions.forEach((session, index) => {
              safeLog(`📋 [Electron] Sessão ${index + 1}:`);
              safeLog(`📋 [Electron]   - Session ID: ${session.sessionId}`);
              safeLog(`📋 [Electron]   - Summoner: ${session.summonerName || 'N/A'}`);
              safeLog(`📋 [Electron]   - PUUID: ${session.puuid || 'N/A'}`);
              safeLog(`📋 [Electron]   - Conectado em: ${session.connectedAt || 'N/A'}`);
              safeLog(`📋 [Electron]   - Última atividade: ${session.lastActivity || 'N/A'}`);
              safeLog(`📋 [Electron]   - IP: ${session.ip || 'N/A'}`);
              safeLog(`📋 [Electron]   - User Agent: ${session.userAgent || 'N/A'}`);
            });
          } else {
            safeLog('📋 [Electron] Nenhuma sessão identificada encontrada');
          }
          safeLog('📋 [Electron] ===================================');
        }
      } catch (e) {
        safeLog('ws gateway message error', String(e));
      }
    });

    wsClient.on('close', (code, reason) => { 
      safeLog('⚠️ [ELECTRON MAIN] WebSocket gateway fechado - code:', code, 'reason:', reason && reason.toString()); 
      stopWebSocketHeartbeat(); // Parar heartbeat
      stopIdentityMonitor(); // ✅ NOVO: Parar monitor de identidade
      wsClient = null; 
      scheduleWebSocketReconnect(backendBase);
    });
    wsClient.on('error', (err) => { 
      safeLog('❌ [ELECTRON MAIN] Erro no WebSocket gateway:', String(err)); 
      safeLog('❌ [ELECTRON MAIN] Detalhes do erro:', JSON.stringify(err, null, 2));
    });
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

// ✅ NOVO: Variáveis para identificação automática
let lastKnownPuuid = null;
let lastKnownSummoner = null;
let identityMonitorTimer = null;

// ✅ NOVO: Variáveis para configuração personalizada do League
let lockfileCandidates = [
  'C:/Riot Games/League of Legends/lockfile',
  'D:/Riot Games/League of Legends/lockfile',
  'E:/Riot Games/League of Legends/lockfile',
  path.join(process.env.LOCALAPPDATA || '', 'Riot Games', 'League of Legends', 'lockfile'),
  path.join(process.env.USERPROFILE || '', 'AppData', 'Local', 'Riot Games', 'League of Legends', 'lockfile')
];

// Discord integration
let discordBot = null;
let discordChannelId = null;
let discordUsers = [];
let discordStatus = { isConnected: false, botUsername: null, channelName: null };

// ✅ NOVO: Função para identificar jogador automaticamente ao backend
async function identifyPlayerToBackend(lockfileInfo) {
  try {
    safeLog('🔍 [Electron] Identificando jogador automaticamente...');
    
    // 1. Buscar summoner do LCU
    const summoner = await performLcuRequest('GET', '/lol-summoner/v1/current-summoner');
    
    if (!summoner || !summoner.gameName || !summoner.tagLine) {
      safeLog('⚠️ [Electron] Summoner não disponível no LCU ainda');
      return;
    }
    
    // 2. Buscar ranked info (opcional, mas útil)
    const ranked = await performLcuRequest('GET', '/lol-ranked/v1/current-ranked-stats')
      .catch(() => null);
    
    // 3. Construir payload COMPLETO
    const fullName = `${summoner.gameName}#${summoner.tagLine}`;
    
    const payload = {
      type: 'electron_identify',
      source: 'electron_main',  // ✅ Fonte confiável!
      timestamp: Date.now(),
      
      // Dados do summoner
      summonerName: fullName,
      gameName: summoner.gameName,
      tagLine: summoner.tagLine,
      puuid: summoner.puuid,  // ✅ CRÍTICO para validação!
      summonerId: summoner.summonerId,
      profileIconId: summoner.profileIconId,
      summonerLevel: summoner.summonerLevel,
      
      // Dados de ranked (opcional)
      tier: ranked?.queueMap?.RANKED_SOLO_5x5?.tier,
      division: ranked?.queueMap?.RANKED_SOLO_5x5?.division,
      
      // LCU connection info
      lcuInfo: lockfileInfo ? {
        host: lockfileInfo.host || '127.0.0.1',
        port: lockfileInfo.port,
        protocol: lockfileInfo.protocol || 'https',
        authToken: btoa(`riot:${lockfileInfo.password}`)
      } : null
    };
    
    // ✅ NOVO: LOG DETALHADO DA VINCULAÇÃO PLAYER-SESSÃO
    safeLog('🔗 [Electron] ===== VINCULAÇÃO PLAYER-SESSÃO =====');
    safeLog('🔗 [Electron] Summoner:', fullName);
    safeLog('🔗 [Electron] PUUID:', summoner.puuid);
    safeLog('🔗 [Electron] Summoner ID:', summoner.summonerId);
    safeLog('🔗 [Electron] Profile Icon:', summoner.profileIconId);
    safeLog('🔗 [Electron] Level:', summoner.summonerLevel);
    safeLog('🔗 [Electron] Ranked:', ranked?.queueMap?.RANKED_SOLO_5x5?.tier, ranked?.queueMap?.RANKED_SOLO_5x5?.division);
    safeLog('🔗 [Electron] LCU Host:', lockfileInfo?.host || '127.0.0.1');
    safeLog('🔗 [Electron] LCU Port:', lockfileInfo?.port);
    safeLog('🔗 [Electron] WebSocket Session ID:', wsClient?.readyState === WebSocket.OPEN ? 'CONECTADO' : 'DESCONECTADO');
    safeLog('🔗 [Electron] ===================================');
    
    // 4. ✅ ENVIAR ao backend
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      wsClient.send(JSON.stringify(payload));
      safeLog('✅ [Electron] Identificação automática enviada:', fullName);
      
      // Armazenar PUUID localmente para detectar mudanças
      lastKnownPuuid = summoner.puuid;
      lastKnownSummoner = fullName;
      
      // ✅ NOVO: SOLICITAR LISTA DE SESSÕES ATIVAS APÓS IDENTIFICAÇÃO
      setTimeout(() => {
        requestActiveSessionsList();
      }, 2000); // Aguardar 2s para backend processar
      
    } else {
      safeLog('❌ [Electron] WebSocket não está conectado, não foi possível enviar identificação');
    }
    
  } catch (err) {
    safeLog('❌ [Electron] Erro ao identificar player:', err);
  }
}

// ✅ NOVO: Monitor de mudanças de summoner (a cada 30s)
function startIdentityMonitor() {
  if (identityMonitorTimer) {
    clearInterval(identityMonitorTimer);
  }
  
  identityMonitorTimer = setInterval(async () => {
    try {
      const summoner = await performLcuRequest('GET', '/lol-summoner/v1/current-summoner');
      
      if (summoner && summoner.puuid !== lastKnownPuuid) {
        // 🚨 SUMMONER MUDOU!
        safeLog('🔄 [Electron] Summoner mudou! Antigo:', lastKnownPuuid, 'Novo:', summoner.puuid);
        lastKnownPuuid = summoner.puuid;
        
        // ✅ Reenviar identificação
        const lockfileInfo = readLockfileInfo();
        if (lockfileInfo) {
          await identifyPlayerToBackend(lockfileInfo);
        }
      }
    } catch (e) {
      // LCU desconectado ou erro
      if (lastKnownPuuid !== null) {
        safeLog('⚠️ [Electron] LCU desconectado, limpando identificação');
        lastKnownPuuid = null;
        lastKnownSummoner = null;
      }
    }
  }, 30000); // A cada 30s
  
  safeLog('✅ [Electron] Monitor de identidade iniciado (30s)');
}

// ✅ NOVO: Parar monitor de identidade
function stopIdentityMonitor() {
  if (identityMonitorTimer) {
    clearInterval(identityMonitorTimer);
    identityMonitorTimer = null;
    safeLog('🛑 [Electron] Monitor de identidade parado');
  }
}

// ✅ NOVO: Solicitar lista de sessões ativas do backend
function requestActiveSessionsList() {
  try {
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      const request = {
        type: 'get_active_sessions',
        timestamp: Date.now()
      };
      
      wsClient.send(JSON.stringify(request));
      safeLog('📋 [Electron] Solicitando lista de sessões ativas...');
    } else {
      safeLog('❌ [Electron] WebSocket não conectado para solicitar sessões ativas');
    }
  } catch (error) {
    safeLog('❌ [Electron] Erro ao solicitar sessões ativas:', error);
  }
}

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

// ✅ NOVO: Handlers para sistema de storage por usuário
ipcMain.handle('storage:savePlayerData', async (_evt, summonerName, data) => {
  try {
    const userDataPath = app.getPath('userData');
    const storageDir = path.join(userDataPath, 'player-cache');
    
    // Criar diretório se não existir
    if (!fs.existsSync(storageDir)) {
      fs.mkdirSync(storageDir, { recursive: true });
    }
    
    // Sanitizar nome do arquivo (remover caracteres inválidos)
    const safeName = summonerName.replace(/[^a-zA-Z0-9#-]/g, '_');
    const filePath = path.join(storageDir, `${safeName}.json`);
    
    // Salvar dados
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2), 'utf8');
    safeLog(`💾 [Storage] Dados salvos para: ${summonerName} em ${filePath}`);
    
    return { success: true, path: filePath };
  } catch (err) {
    safeLog('❌ [Storage] Erro ao salvar dados:', String(err));
    return { success: false, error: String(err) };
  }
});

ipcMain.handle('storage:loadPlayerData', async (_evt, summonerName) => {
  try {
    const userDataPath = app.getPath('userData');
    const storageDir = path.join(userDataPath, 'player-cache');
    const safeName = summonerName.replace(/[^a-zA-Z0-9#-]/g, '_');
    const filePath = path.join(storageDir, `${safeName}.json`);
    
    if (!fs.existsSync(filePath)) {
      safeLog(`📂 [Storage] Arquivo não encontrado para: ${summonerName}`);
      return null;
    }
    
    const content = fs.readFileSync(filePath, 'utf8');
    const data = JSON.parse(content);
    safeLog(`✅ [Storage] Dados carregados para: ${summonerName}`);
    
    return data;
  } catch (err) {
    safeLog('❌ [Storage] Erro ao carregar dados:', String(err));
    return null;
  }
});

ipcMain.handle('storage:clearPlayerData', async (_evt, summonerName) => {
  try {
    const userDataPath = app.getPath('userData');
    const storageDir = path.join(userDataPath, 'player-cache');
    const safeName = summonerName.replace(/[^a-zA-Z0-9#-]/g, '_');
    const filePath = path.join(storageDir, `${safeName}.json`);
    
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
      safeLog(`🗑️ [Storage] Dados removidos para: ${summonerName}`);
    }
    
    return { success: true };
  } catch (err) {
    safeLog('❌ [Storage] Erro ao remover dados:', String(err));
    return { success: false, error: String(err) };
  }
});

ipcMain.handle('storage:listPlayers', async (_evt) => {
  try {
    const userDataPath = app.getPath('userData');
    const storageDir = path.join(userDataPath, 'player-cache');
    
    if (!fs.existsSync(storageDir)) {
      return [];
    }
    
    const files = fs.readdirSync(storageDir);
    const players = files
      .filter(f => f.endsWith('.json'))
      .map(f => f.replace('.json', '').replace(/_/g, ' ')); // Reverter sanitização
    
    safeLog(`📋 [Storage] ${players.length} jogadores encontrados no cache`);
    return players;
  } catch (err) {
    safeLog('❌ [Storage] Erro ao listar jogadores:', String(err));
    return [];
  }
});

function readLockfileInfo() {
  // ✅ NOVO: Usar lista de candidatos atualizada (inclui caminho personalizado)
  for (const p of lockfileCandidates) {
    try {
      if (p && fs.existsSync(p)) {
        const content = fs.readFileSync(p, 'utf8');
        const parsed = parseLockfileContent(content);
        if (parsed) {
          safeLog('✅ [Electron] Lockfile encontrado em:', p);
          return { host: '127.0.0.1', port: parsed.port, protocol: parsed.protocol, password: parsed.password };
        }
      }
    } catch (err) {
      safeLog('readLockfileInfo error', String(err));
    }
  }
  safeLog('⚠️ [Electron] Nenhum lockfile encontrado nos candidatos:', lockfileCandidates);
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

// Log onde os logs estão sendo salvos
safeLog('🚀 [ELECTRON MAIN] Iniciando processo principal do Electron');
safeLog('📁 [ELECTRON MAIN] Logs sendo salvos em:', LOG_FILE);

