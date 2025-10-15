const { app, BrowserWindow, Menu, dialog } = require("electron");
const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");
const WebSocket = require("ws");
const { ipcMain } = require("electron");

// ✅ NOVO: Redis opcional para logs unificados
let Redis = null;
try {
  Redis = require("redis");
  safeLog(
    "✅ [Player-Sessions] [UNIFIED-LOGS] Redis disponível para logs unificados"
  );
} catch (error) {
  safeLog(
    "⚠️ [Player-Sessions] [UNIFIED-LOGS] Redis não disponível - logs unificados desabilitados"
  );
}

// ✅ NOVO: Variável global para a janela principal
let mainWindow = null;

// ⚠️ LOGS DESABILITADOS EM PRODUÇÃO - Não salvar arquivos de log
let LOG_FILE = null; // Mantido como null para desabilitar logs em arquivo

function sanitizeForLog(value) {
  try {
    if (value === null || value === undefined) return value;
    // primitives
    if (
      typeof value === "string" ||
      typeof value === "number" ||
      typeof value === "boolean"
    )
      return value;
    // Error
    if (value instanceof Error)
      return { message: value.message, stack: value.stack };
    // Clone objects/arrays and redact sensitive keys
    const redactKeys = [
      "password",
      "auth",
      "token",
      "authorization",
      "Authorization",
    ];
    const clone = Array.isArray(value) ? [] : {};
    for (const k in value) {
      try {
        if (redactKeys.includes(k)) clone[k] = "<redacted>";
        else clone[k] = sanitizeForLog(value[k]);
      } catch (e) {
        clone[k] = String(value[k]);
      }
    }
    return clone;
  } catch (e) {
    return String(value);
  }
}

function appendLogLine(line) {
  // ⚠️ LOGS DESABILITADOS - Não criar arquivos .log na raiz
  // Os logs continuam no console, mas não são salvos em arquivo
  return; // Desabilitado
}

function safeLog(...args) {
  try {
    // Keep normal console output for dev
    console.log("[electron]", ...args);
  } catch (e) {
    // ignore
  }
  try {
    const parts = args.map((a) => {
      try {
        return typeof a === "string" ? a : JSON.stringify(sanitizeForLog(a));
      } catch (e) {
        return String(a);
      }
    });
    appendLogLine("[main] " + parts.join(" "));
  } catch (e) {
    /* ignore logging errors */
  }
}

function ensureTrailingSlash(u) {
  return u && u.endsWith("/") ? u : u + "/";
}

function checkReachable(u, timeout = 2000) {
  return new Promise((resolve) => {
    let parsed;
    try {
      parsed = new URL(u);
    } catch (err) {
      safeLog("checkReachable invalid URL", u, String(err));
      return resolve(false);
    }

    const lib = parsed.protocol === "https:" ? https : http;
    const options = {
      method: "HEAD",
      hostname: parsed.hostname,
      port: parsed.port || (parsed.protocol === "https:" ? 443 : 80),
      path: (parsed.pathname || "/") + (parsed.search || ""),
      timeout,
    };

    const req = lib.request(options, (res) => {
      // consider reachable for 2xx/3xx/4xx, fail only on server error or close
      resolve(res.statusCode < 500);
    });
    req.on("timeout", () => {
      req.destroy();
      resolve(false);
    });
    req.on("error", () => resolve(false));
    req.end();
  });
}

async function pickBackendUrl() {
  // CONFIGURAÇÃO DE REDE: Altere esta URL para o IP do servidor na rede
  // Para testes locais: 'http://localhost:8080/'
  // Para rede local: 'http://192.168.1.4:8080/' (seu IP)
  // Para cloud: 'https://lol-matchmaking-368951732227.southamerica-east1.run.app/'
  // ✅ CORREÇÃO: URL correta do Cloud Run (nome do serviço é 'lol-matchmaking')
  const HARDCODED_BACKEND_URL = "http://192.168.1.2:8080/";

  const env = process.env.BACKEND_URL || "";
  const defaultBase = env || HARDCODED_BACKEND_URL;
  const baseNoSlash = defaultBase.replace(/\/+$/, "");

  const candidates = [
    ensureTrailingSlash(baseNoSlash),
    ensureTrailingSlash(baseNoSlash.replace("localhost", "127.0.0.1")),
    ensureTrailingSlash(baseNoSlash.replace("127.0.0.1", "localhost")),
  ];

  // dedupe while preserving order
  const seen = new Set();
  const uniq = [];
  for (const c of candidates) {
    if (!seen.has(c)) {
      seen.add(c);
      uniq.push(c);
    }
  }

  for (const c of uniq) {
    safeLog("probing", c);
    try {
      // short timeout probe
      // eslint-disable-next-line no-await-in-loop
      if (await checkReachable(c, 2000)) {
        safeLog("reachable", c);
        return c;
      }
    } catch (err) {
      safeLog("probe error", String(err));
    }
  }

  safeLog(
    "no backend reachable, returning primary candidate",
    uniq[0] || ensureTrailingSlash(defaultBase)
  );
  return uniq[0] || ensureTrailingSlash(defaultBase);
}

function createWindow(startUrl, isDev) {
  safeLog("creating window for", startUrl, "dev=", !!isDev);

  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webSecurity: true,
      // preload exposes a safe electronAPI to the renderer (lockfile helpers, lcu helpers)
      preload: path.join(__dirname, "preload.js"),
    },
  });

  // ✅ NOVO: Criar menu em português
  createMenu(win);

  win.webContents.on("did-finish-load", () =>
    safeLog("did-finish-load ->", win.webContents.getURL())
  );
  win.webContents.on(
    "did-fail-load",
    (_e, errorCode, errorDesc, validatedURL) =>
      safeLog("did-fail-load", errorCode, errorDesc, validatedURL)
  );
  win.webContents.on("render-process-gone", (_e, details) =>
    safeLog("render-process-gone", details)
  );
  win.webContents.on("console-message", (_e, level, message, line, sourceId) =>
    safeLog("console-message", level, message, sourceId + ":" + line)
  );

  if (isDev) win.webContents.openDevTools({ mode: "right" });

  // Try to load the URL. If it fails we'll show a helpful error page.
  win
    .loadURL(startUrl, { userAgent: "Mozilla/5.0 (Electron App)" })
    .catch((err) => {
      safeLog("loadURL threw", String(err));
      const html = `<!doctype html><html><body><h2>Application could not reach backend</h2><p>Attempted: ${startUrl}</p><p>Open devtools for details.</p></body></html>`;
      win.loadURL("data:text/html;charset=utf-8," + encodeURIComponent(html));
    });

  return win;
}

// ✅ NOVO: Menu em português com funcionalidades avançadas
function createMenu(mainWindow) {
  const template = [
    {
      label: "Arquivo",
      submenu: [
        {
          label: "Sair",
          accelerator: "CmdOrCtrl+Q",
          click: () => {
            app.quit();
          },
        },
      ],
    },
    {
      label: "Ações",
      submenu: [
        {
          label: "Atualizar",
          accelerator: "CmdOrCtrl+R",
          click: () => {
            mainWindow.reload();
          },
        },
        {
          label: "Limpar Cache Local",
          accelerator: "CmdOrCtrl+Shift+L",
          click: () => {
            performLocalCacheCleanup(mainWindow);
          },
        },
        {
          label: "Atualizar + Limpeza Completa",
          accelerator: "CmdOrCtrl+Shift+R",
          click: () => {
            performCompleteRefresh(mainWindow);
          },
        },
        {
          type: "separator",
        },
        {
          label: "Configurar Pasta do League of Legends",
          click: () => {
            selectLeagueInstallationPath(mainWindow);
          },
        },
        {
          label: "Mostrar Sessões Ativas",
          click: () => {
            requestActiveSessionsList();
          },
        },
      ],
    },
    {
      label: "Desenvolvedor",
      submenu: [
        {
          label: "Ferramentas de Desenvolvedor",
          accelerator: "F12",
          click: () => {
            mainWindow.webContents.openDevTools();
          },
        },
        {
          label: "Recarregar",
          accelerator: "CmdOrCtrl+Shift+R",
          click: () => {
            mainWindow.reload();
          },
        },
      ],
    },
  ];

  const menu = Menu.buildFromTemplate(template);
  Menu.setApplicationMenu(menu);
}

// ✅ NOVO: Seleção manual da pasta do League of Legends
async function selectLeagueInstallationPath(mainWindow) {
  try {
    const result = await dialog.showOpenDialog(mainWindow, {
      title: "Selecionar Pasta do League of Legends",
      message: "Escolha a pasta onde está instalado o League of Legends",
      properties: ["openDirectory"],
      defaultPath: "C:/Riot Games/League of Legends",
    });

    if (!result.canceled && result.filePaths.length > 0) {
      const selectedPath = result.filePaths[0];
      const lockfilePath = path.join(selectedPath, "lockfile");

      // Verificar se o lockfile existe
      if (fs.existsSync(lockfilePath)) {
        // ✅ SALVAR no diretório do usuário (mais apropriado)
        const userDataPath = app.getPath("userData");
        const configDir = path.join(userDataPath, "lol-config");

        // Criar diretório se não existir
        if (!fs.existsSync(configDir)) {
          fs.mkdirSync(configDir, { recursive: true });
        }

        const configPath = path.join(configDir, "league-config.json");
        const config = {
          customLeaguePath: selectedPath,
          lockfilePath: lockfilePath,
          lastUpdated: new Date().toISOString(),
          userId: process.env.USERNAME || "unknown",
        };

        fs.writeFileSync(configPath, JSON.stringify(config, null, 2));

        // Atualizar lista de candidatos
        updateLockfileCandidates(selectedPath);

        safeLog("✅ [Electron] Pasta do League configurada:", selectedPath);

        // Mostrar confirmação
        dialog.showMessageBox(mainWindow, {
          type: "info",
          title: "Configuração Salva",
          message: "Pasta do League of Legends configurada com sucesso!",
          detail: `Caminho: ${selectedPath}\nLockfile: ${lockfilePath}`,
        });

        // Tentar reconectar se LCU estiver ativo
        if (wsClient && wsClient.readyState === WebSocket.OPEN) {
          safeLog("🔄 [Electron] Tentando reconectar com novo caminho...");
          setTimeout(() => {
            checkLockfileAndConnect();
          }, 1000);
        }
      } else {
        dialog.showErrorBox(
          "Erro",
          "Lockfile não encontrado na pasta selecionada!\n\nVerifique se o League of Legends está instalado corretamente nesta pasta."
        );
      }
    }
  } catch (error) {
    safeLog("❌ [Electron] Erro ao selecionar pasta do League:", error);
    dialog.showErrorBox(
      "Erro",
      "Erro ao configurar pasta do League of Legends"
    );
  }
}

// ✅ NOVO: Atualizar lista de candidatos com caminho personalizado
function updateLockfileCandidates(customPath) {
  const customLockfile = path.join(customPath, "lockfile");

  // Remover duplicatas e adicionar caminho personalizado no início
  const allCandidates = [
    customLockfile,
    "C:/Riot Games/League of Legends/lockfile",
    "D:/Riot Games/League of Legends/lockfile",
    "E:/Riot Games/League of Legends/lockfile",
    "F:/Riot Games/League of Legends/lockfile",
    "G:/Riot Games/League of Legends/lockfile",
  ];

  // Remover duplicatas mantendo ordem
  const uniqueCandidates = [...new Set(allCandidates)];

  // Atualizar variável global
  lockfileCandidates = uniqueCandidates;

  safeLog(
    "✅ [Electron] Candidatos de lockfile atualizados:",
    uniqueCandidates
  );
}

// ✅ NOVO: Limpeza apenas do cache local
async function performLocalCacheCleanup(mainWindow) {
  try {
    safeLog("🧹 [Electron] Iniciando limpeza de cache local...");

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
      const userDataPath = app.getPath("userData");
      const tempDir = path.join(userDataPath, "temp");
      if (fs.existsSync(tempDir)) {
        fs.rmSync(tempDir, { recursive: true, force: true });
        safeLog("🧹 [Electron] Cache temporário do Electron limpo");
      }
    } catch (error) {
      safeLog("⚠️ [Electron] Erro ao limpar cache do Electron:", error);
    }

    safeLog("✅ [Electron] Limpeza de cache local concluída");

    // Mostrar confirmação
    dialog.showMessageBox(mainWindow, {
      type: "info",
      title: "Cache Limpo",
      message: "Cache local limpo com sucesso!",
      detail: "localStorage, sessionStorage e cache do navegador foram limpos.",
    });
  } catch (error) {
    safeLog("❌ [Electron] Erro durante limpeza de cache local:", error);
    dialog.showErrorBox("Erro", "Erro ao limpar cache local");
  }
}

// ✅ NOVO: Limpeza completa + revinculação
async function performCompleteRefresh(mainWindow) {
  try {
    safeLog("🧹 [Electron] Iniciando limpeza completa...");

    // 1. Parar monitor de identidade
    stopIdentityMonitor();

    // 2. Limpar cache local primeiro
    await performLocalCacheCleanup(mainWindow);

    // 3. Solicitar limpeza de estado Redis do backend
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      const cleanupRequest = {
        type: "clear_player_state",
        source: "electron_main",
        timestamp: Date.now(),
        reason: "complete_refresh",
      };

      wsClient.send(JSON.stringify(cleanupRequest));
      safeLog("🧹 [Electron] Solicitação de limpeza Redis enviada");
    }

    // 4. Aguardar um pouco para limpeza
    await new Promise((resolve) => setTimeout(resolve, 1000));

    // 5. Recarregar página
    mainWindow.reload();

    // 6. Aguardar carregamento e reestabelecer conexão
    setTimeout(async () => {
      if (wsClient && wsClient.readyState === WebSocket.OPEN) {
        safeLog("🔄 [Electron] Reestabelecendo vinculação...");

        // Reenviar identificação
        const lockfileInfo = readLockfileInfo();
        if (lockfileInfo) {
          await identifyPlayerToBackend(lockfileInfo);
        }

        // Reiniciar monitor de identidade
        startIdentityMonitor();

        safeLog("✅ [Electron] Limpeza completa e revinculação concluídas");
      }
    }, 3000);
  } catch (error) {
    safeLog("❌ [Electron] Erro durante limpeza completa:", error);
  }
}

// ✅ NOVO: Carregar configuração personalizada do League
function loadLeagueConfig() {
  try {
    // ✅ CARREGAR do diretório do usuário
    const userDataPath = app.getPath("userData");
    const configDir = path.join(userDataPath, "lol-config");
    const configPath = path.join(configDir, "league-config.json");

    if (fs.existsSync(configPath)) {
      const configData = fs.readFileSync(configPath, "utf8");
      const config = JSON.parse(configData);

      if (config.customLeaguePath) {
        safeLog(
          "✅ [Electron] Configuração personalizada carregada:",
          config.customLeaguePath
        );
        safeLog("✅ [Electron] Configuração salva em:", configPath);
        updateLockfileCandidates(config.customLeaguePath);
        return config.customLeaguePath;
      }
    } else {
      safeLog(
        "ℹ️ [Electron] Nenhuma configuração personalizada encontrada em:",
        configPath
      );
    }
  } catch (error) {
    safeLog(
      "⚠️ [Electron] Erro ao carregar configuração personalizada:",
      error
    );
  }
  return null;
}

// ✅ NOVO: Função auxiliar para verificar lockfile e conectar
function checkLockfileAndConnect() {
  try {
    const lockfileInfo = readLockfileInfo();
    if (lockfileInfo) {
      safeLog("🔄 [Electron] Lockfile detectado, reconectando...");
      // Aqui você pode adicionar lógica adicional de reconexão se necessário
    }
  } catch (error) {
    safeLog("❌ [Electron] Erro ao verificar lockfile:", error);
  }
}

app
  .whenReady()
  .then(async () => {
    console.log("[electron] ========== APP READY ==========");
    const isDev =
      process.argv.includes("--dev") || process.env.ELECTRON_DEV === "1";
    console.log("[electron] isDev:", isDev);

    // ✅ NOVO: Carregar configuração personalizada do League
    loadLeagueConfig();

    const startUrl = await pickBackendUrl();
    console.log("[electron] Backend URL:", startUrl);
    mainWindow = createWindow(startUrl, isDev);
    console.log("[electron] Janela criada, iniciando watchers...");
    // start monitoring lockfile and report to backend
    try {
      startLockfileWatcher(startUrl);
      console.log("[electron] Lockfile watcher iniciado");
      // also start websocket gateway client to backend for LCU RPCs
      try {
        console.log("[electron] *** CHAMANDO startWebSocketGateway ***");
        startWebSocketGateway(startUrl);
        console.log("[electron] *** startWebSocketGateway RETORNOU ***");
      } catch (err) {
        console.error("[electron] ❌ ERRO no startWebSocketGateway:", err);
        safeLog("websocket gateway failed to start", err);
      }
    } catch (err) {
      console.error("[electron] ❌ ERRO no lockfile watcher:", err);
      safeLog("lockfile watcher failed to start", err);
    }
  })
  .catch((err) => {
    safeLog("app.whenReady error", err);
    app.quit();
  });

app.on("window-all-closed", () => {
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
  const parts = (content || "").trim().split(":");
  if (parts.length >= 5) {
    return {
      name: parts[0],
      pid: parts[1],
      port: parseInt(parts[2], 10) || 0,
      password: parts[3],
      protocol: parts[4],
    };
  }
  return null;
}

function postConfigToBackend(backendBase, cfg) {
  try {
    const url = new URL("/api/lcu/configure", backendBase).toString();
    safeLog("posting LCU config to", url, cfg);

    const body = JSON.stringify({
      host: "127.0.0.1",
      port: cfg.port,
      protocol: cfg.protocol,
      password: cfg.password,
    });
    const parsed = new URL(url);
    const lib = parsed.protocol === "https:" ? https : http;
    const options = {
      method: "POST",
      hostname: parsed.hostname,
      port: parsed.port || (parsed.protocol === "https:" ? 443 : 80),
      path: parsed.pathname + (parsed.search || ""),
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(body, "utf8"),
      },
      timeout: 5000,
    };

    const req = lib.request(options, (res) => {
      let data = "";
      res.on("data", (chunk) => {
        data += chunk;
      });
      res.on("end", () => {
        safeLog("backend response", res.statusCode, data);
      });
    });
    req.on("error", (err) =>
      safeLog("error posting config to backend", String(err))
    );
    req.on("timeout", () => {
      req.destroy();
      safeLog("postConfigToBackend timeout");
    });
    req.write(body);
    req.end();
  } catch (err) {
    safeLog("postConfigToBackend failed", String(err));
  }
}

function startLockfileWatcher(backendBase) {
  const candidates = [
    "C:/Riot Games/League of Legends/lockfile",
    "D:/Riot Games/League of Legends/lockfile",
    "E:/Riot Games/League of Legends/lockfile",
    path.join(
      process.env.LOCALAPPDATA || "",
      "Riot Games",
      "League of Legends",
      "lockfile"
    ),
    path.join(
      process.env.USERPROFILE || "",
      "AppData",
      "Local",
      "Riot Games",
      "League of Legends",
      "lockfile"
    ),
  ];

  let lastSeen = "";

  // handle single candidate path: return true if the path exists (so we should stop searching)
  function handleCandidate(p) {
    if (!p) return false;
    if (!fs.existsSync(p)) return false;
    try {
      const content = fs.readFileSync(p, { encoding: "utf8" });
      if (content && content !== lastSeen) {
        lastSeen = content;
        const parsed = parseLockfileContent(content);
        if (parsed) {
          safeLog("lockfile parsed", parsed);
          postConfigToBackend(backendBase, parsed);

          // ✅ NOVO: Identificar jogador automaticamente quando lockfile é detectado
          setTimeout(() => {
            identifyPlayerToBackend(parsed);
          }, 2000); // Aguardar 2s para garantir que LCU está pronto

          // ✅ NOVO: Inicializar monitoramento proativo
          setTimeout(() => {
            initializeProactiveMonitoring();
          }, 3000); // Aguardar 3s para garantir que identificação foi enviada
        } else {
          safeLog("lockfile found but could not parse", p);
        }
      }
      return true; // path exists -> stop after first found path
    } catch (err) {
      safeLog("error reading lockfile candidate", p, String(err));
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
  app.on("will-quit", () => {
    clearInterval(iv);

    // ✅ NOVO: Limpar conexão Redis e desabilitar logs unificados
    if (redisSubscriber) {
      redisSubscriber.quit();
      safeLog("📋 [Player-Sessions] [UNIFIED-LOGS] Conexão Redis encerrada");
    }
    unifiedLogsEnabled = false;
    safeLog(
      "📋 [Player-Sessions] [UNIFIED-LOGS] Logs unificados desabilitados"
    );
  });

  safeLog("lockfile watcher started, probing candidates", candidates);
}

// -------------------------------------------------------------------------------

// --- WebSocket gateway client ---------------------------------------------------
function startWebSocketGateway(backendBase) {
  // Log direto para garantir que aparece mesmo se safeLog falhar
  console.log("[electron] === startWebSocketGateway CHAMADO ===");
  console.log("[electron] backendBase:", backendBase);

  try {
    // backendBase is like http://localhost:8080/ -> ws url replace protocol
    const parsed = new URL(backendBase);
    const wsProtocol = parsed.protocol === "https:" ? "wss:" : "ws:";
    // ✅ CORREÇÃO: Usar /api/ws que tem CoreWebSocketHandler com suporte a register_lcu_connection
    const wsUrl =
      wsProtocol +
      "//" +
      parsed.hostname +
      (parsed.port ? ":" + parsed.port : "") +
      "/api/ws";

    console.log("[electron] 🔌 WebSocket URL construída:", wsUrl);
    safeLog(
      "🔌 [ELECTRON MAIN] Tentando conectar WebSocket gateway em:",
      wsUrl
    );

    wsClient = new WebSocket(wsUrl, {
      headers: {
        // ✅ CORREÇÃO: Adicionar User-Agent para identificar o Electron
        "User-Agent": "LoL-Matchmaking-Electron/1.0.0",
        // optional auth token for backend (set BACKEND_GATEWAY_TOKEN env)
        ...(process.env.BACKEND_GATEWAY_TOKEN
          ? { Authorization: "Bearer " + process.env.BACKEND_GATEWAY_TOKEN }
          : {}),
      },
    });

    wsClient.on("open", () => {
      safeLog("✅ [ELECTRON MAIN] WebSocket gateway conectado:", wsUrl);
      resetWebSocketReconnect(); // Reset tentativas de reconexão
      startWebSocketHeartbeat(); // Iniciar heartbeat
      // send a register/identify message
      const info = readLockfileInfo();
      const register = {
        type: "identify",
        playerId: process.env.ELECTRON_CLIENT_ID || "electron-client",
        data: { lockfile: info },
      };
      try {
        wsClient.send(JSON.stringify(register));
      } catch (e) {
        safeLog("ws send register error", String(e));
      }
      // Proactive LCU status check: perform a quick /lol-summoner/v1/current-summoner
      // and inform backend so it can mark the gateway as having a working LCU.
      setTimeout(() => {
        (async () => {
          try {
            if (info) {
              try {
                const result = await performLcuRequest(
                  "GET",
                  "/lol-summoner/v1/current-summoner"
                );

                // ✅ CRÍTICO: Construir displayName e summonerName se vierem vazios do LCU
                if (
                  result &&
                  typeof result === "object" &&
                  result.gameName &&
                  result.tagLine
                ) {
                  const fullName = `${result.gameName}#${result.tagLine}`;
                  if (!result.displayName || result.displayName === "") {
                    result.displayName = fullName;
                  }
                  if (!result.summonerName || result.summonerName === "") {
                    result.summonerName = fullName;
                  }
                }

                safeLog("proactive lcu current-summoner ->", result);

                // ✅ NOVO: Enviar identify com summonerName para configurar LCU no banco
                if (
                  result &&
                  typeof result === "object" &&
                  result.gameName &&
                  result.tagLine
                ) {
                  const summonerName =
                    result.displayName ||
                    `${result.gameName}#${result.tagLine}`;
                  const identifyWithSummoner = {
                    type: "identify",
                    playerId:
                      process.env.ELECTRON_CLIENT_ID || "electron-client",
                    summonerName: summonerName,
                    data: {
                      lockfile: info,
                      gameName: result.gameName,
                      tagLine: result.tagLine,
                    },
                  };
                  try {
                    wsClient.send(JSON.stringify(identifyWithSummoner));
                    safeLog(
                      `✅ Identify com summonerName enviado: ${summonerName}`
                    );
                  } catch (e) {
                    safeLog(
                      "❌ Erro ao enviar identify com summonerName",
                      String(e)
                    );
                  }

                  // ✅ NOVO: Registrar conexão LCU no registry para roteamento multi-player
                  const registerLcuConnection = {
                    type: "register_lcu_connection",
                    summonerName: summonerName,
                    port: info.port,
                    authToken: info.password,
                    protocol: info.protocol || "https",
                    profileIconId: result.profileIconId || null, // ✅ NOVO: Incluir profileIconId do LCU
                    puuid: result.puuid || null, // ✅ NOVO: Incluir puuid
                    summonerId: result.summonerId || null, // ✅ NOVO: Incluir summonerId
                  };
                  try {
                    wsClient.send(JSON.stringify(registerLcuConnection));
                    safeLog(
                      `🎯 LCU connection registrada para ${summonerName} (profileIcon: ${
                        result.profileIconId || "N/A"
                      })`
                    );
                  } catch (e) {
                    safeLog("❌ Erro ao registrar LCU connection", String(e));
                  }
                }

                // attempt to extract a stable summoner identifier so backend can mark the gateway as connected
                let summonerId = null;
                try {
                  if (result) {
                    if (typeof result === "object") {
                      summonerId =
                        result.summonerId ||
                        result.id ||
                        result.puuid ||
                        result.accountId ||
                        result.displayName ||
                        null;
                    } else if (typeof result === "string") {
                      // sometimes the API returns a bare string; try to parse
                      try {
                        const parsed = JSON.parse(result);
                        if (parsed && typeof parsed === "object")
                          summonerId =
                            parsed.summonerId ||
                            parsed.id ||
                            parsed.puuid ||
                            parsed.accountId ||
                            parsed.displayName ||
                            null;
                      } catch (e) {
                        /* ignore */
                      }
                    }
                  }
                } catch (e) {
                  safeLog("extract summoner id failed", String(e));
                }

                // ensure body contains a stable summonerId field at top-level so backend can extract it
                const bodyForBackend =
                  result && typeof result === "object"
                    ? Object.assign({}, result)
                    : { raw: result };
                if (summonerId) bodyForBackend.summonerId = summonerId;
                const statusMsg = {
                  type: "lcu_status",
                  data: {
                    status: 200,
                    body: bodyForBackend,
                    summonerId: summonerId,
                  },
                };
                wsClient.send(JSON.stringify(statusMsg));
              } catch (err) {
                const statusMsg = {
                  type: "lcu_status",
                  data: { status: 500, error: String(err), summonerId: null },
                };
                try {
                  wsClient.send(JSON.stringify(statusMsg));
                } catch (e) {
                  safeLog("failed to send lcu_status", String(e));
                }
              }
            }
          } catch (e) {
            safeLog("proactive lcu check failed", String(e));
          }
        })();
      }, 250);
      // if we have a pending identify from renderer, send it
      if (lastRendererIdentify) {
        try {
          wsClient.send(
            JSON.stringify({ type: "identify", data: lastRendererIdentify })
          );
        } catch (e) {
          safeLog("ws send renderer identify error", String(e));
        }
      }

      // Initialize Discord bot after WebSocket connection
      initializeDiscordOnConnect();
    });

    wsClient.on("message", async (msg) => {
      console.log(
        "[electron] 📨 [MAIN] Mensagem WebSocket recebida (raw):",
        msg.toString().substring(0, 200)
      );
      try {
        const json = JSON.parse(msg.toString());
        console.log(
          "[electron] 📨 [MAIN] Mensagem WebSocket parsed, type:",
          json.type
        );

        // Handle LCU requests
        if (json.type === "lcu_request") {
          console.log(
            "[electron] 🎯 [MAIN] LCU REQUEST recebido!",
            json.id,
            json.method,
            json.path
          );
          safeLog(
            "ws gateway received lcu_request",
            json.id,
            json.method,
            json.path
          );
          try {
            safeLog("ws gateway calling performLcuRequest for", json.path);
            const result = await performLcuRequest(
              json.method || "GET",
              json.path,
              json.body
            );
            safeLog(
              "ws gateway performLcuRequest success for",
              json.path,
              "result type:",
              typeof result
            );
            const resp = {
              type: "lcu_response",
              id: json.id,
              status: 200,
              body: result,
            };
            wsClient.send(JSON.stringify(resp));
            safeLog("ws gateway sent lcu_response for", json.id);
          } catch (err) {
            safeLog("lcu_request handler error", String(err));
            const resp = {
              type: "lcu_response",
              id: json.id,
              status: 500,
              error: String(err),
            };
            wsClient.send(JSON.stringify(resp));
            safeLog("ws gateway sent lcu_response error for", json.id);
          }
        }
        // Handle Discord requests
        else if (json.type === "discord_request") {
          safeLog(
            "ws gateway received discord_request",
            json.id,
            json.method,
            json.path
          );
          try {
            const result = await performDiscordRequest(
              json.method || "GET",
              json.path,
              json.body
            );
            safeLog(
              "ws gateway performDiscordRequest success for",
              json.path,
              "result type:",
              typeof result
            );
            const resp = {
              type: "discord_response",
              id: json.id,
              status: 200,
              body: result,
            };
            wsClient.send(JSON.stringify(resp));
            safeLog("ws gateway sent discord_response for", json.id);
          } catch (err) {
            safeLog("discord_request handler error", String(err));
            const resp = {
              type: "discord_response",
              id: json.id,
              status: 500,
              error: String(err),
            };
            wsClient.send(JSON.stringify(resp));
            safeLog("ws gateway sent discord_response error for", json.id);
          }
        }
        // Handle Discord status requests
        else if (json.type === "get_discord_status") {
          safeLog("ws gateway received get_discord_status");
          try {
            const status = await getDiscordStatus();
            const resp = { type: "discord_status", data: status };
            wsClient.send(JSON.stringify(resp));
            safeLog("ws gateway sent discord_status");
          } catch (err) {
            safeLog("get_discord_status error", String(err));
            const resp = {
              type: "discord_status",
              data: { isConnected: false, error: String(err) },
            };
            wsClient.send(JSON.stringify(resp));
          }
        }
        // Handle Discord users online requests
        else if (json.type === "get_discord_users_online") {
          safeLog("ws gateway received get_discord_users_online");
          try {
            const users = await getDiscordUsersOnline();
            const resp = { type: "discord_users_online", users: users };
            wsClient.send(JSON.stringify(resp));
            safeLog(
              "ws gateway sent discord_users_online",
              users.length,
              "users"
            );
          } catch (err) {
            safeLog("get_discord_users_online error", String(err));
            const resp = {
              type: "discord_users_online",
              users: [],
              error: String(err),
            };
            wsClient.send(JSON.stringify(resp));
          }
        }
        // ✅ NOVO: Handle identity confirmation requests
        else if (json.type === "confirm_identity") {
          safeLog("ws gateway received confirm_identity");
          try {
            // ✅ Buscar summoner ATUAL do LCU
            const summoner = await performLcuRequest(
              "GET",
              "/lol-summoner/v1/current-summoner"
            );

            const response = {
              type: "identity_confirmed",
              requestId: json.id,
              summonerName: `${summoner.gameName}#${summoner.tagLine}`,
              puuid: summoner.puuid,
              summonerId: summoner.summonerId,
              timestamp: Date.now(),
            };

            wsClient.send(JSON.stringify(response));
            safeLog(
              "✅ [Electron] Identidade confirmada:",
              response.summonerName
            );
          } catch (err) {
            safeLog("❌ [Electron] Erro ao confirmar identidade:", err);

            // ✅ Enviar erro ao backend (LCU desconectado)
            wsClient.send(
              JSON.stringify({
                type: "identity_confirmation_failed",
                requestId: json.id,
                error: "LCU_DISCONNECTED",
              })
            );
          }
        } else if (json.type === "confirm_identity_critical") {
          safeLog(
            "ws gateway received confirm_identity_critical - ação crítica:",
            json.actionType
          );
          try {
            // ✅ Buscar summoner ATUAL do LCU
            const summoner = await performLcuRequest(
              "GET",
              "/lol-summoner/v1/current-summoner"
            );

            const response = {
              type: "identity_confirmed_critical",
              requestId: json.id,
              summonerName: `${summoner.gameName}#${summoner.tagLine}`,
              puuid: summoner.puuid,
              summonerId: summoner.summonerId,
              actionType: json.actionType,
              timestamp: Date.now(),
            };

            wsClient.send(JSON.stringify(response));
            safeLog(
              "✅ [Electron] Identidade CRÍTICA confirmada:",
              response.summonerName,
              "para ação:",
              json.actionType
            );
          } catch (err) {
            safeLog("❌ [Electron] Erro ao confirmar identidade CRÍTICA:", err);

            // ✅ Enviar erro ao backend (LCU desconectado)
            wsClient.send(
              JSON.stringify({
                type: "identity_confirmation_failed",
                requestId: json.id,
                error: "LCU_DISCONNECTED",
              })
            );
          }
        }
        // ✅ NOVO: Handler para lista de sessões ativas
        else if (json.type === "active_sessions_list") {
          safeLog("📋 [Player-Sessions] ===== LISTA DE SESSÕES ATIVAS =====");
          safeLog("📋 [Player-Sessions] Total de sessões:", json.totalSessions);
          safeLog(
            "📋 [Player-Sessions] Sessões identificadas:",
            json.identifiedSessions
          );
          safeLog("📋 [Player-Sessions] Sessões locais:", json.localSessions);

          if (json.sessions && json.sessions.length > 0) {
            safeLog("📋 [Player-Sessions] === DETALHES DAS SESSÕES ===");
            json.sessions.forEach((session, index) => {
              safeLog(`📋 [Player-Sessions] Sessão ${index + 1}:`);
              safeLog(
                `📋 [Player-Sessions]   - Session ID: ${session.sessionId}`
              );
              safeLog(
                `📋 [Player-Sessions]   - Summoner: ${
                  session.summonerName || "N/A"
                }`
              );
              safeLog(
                `📋 [Player-Sessions]   - PUUID: ${session.puuid || "N/A"}`
              );
              safeLog(
                `📋 [Player-Sessions]   - Conectado em: ${
                  session.connectedAt || "N/A"
                }`
              );
              safeLog(
                `📋 [Player-Sessions]   - Última atividade: ${
                  session.lastActivity || "N/A"
                }`
              );
              safeLog(`📋 [Player-Sessions]   - IP: ${session.ip || "N/A"}`);
              safeLog(
                `📋 [Player-Sessions]   - User Agent: ${
                  session.userAgent || "N/A"
                }`
              );
            });
          } else {
            safeLog(
              "📋 [Player-Sessions] Nenhuma sessão identificada encontrada"
            );
          }
          safeLog("📋 [Player-Sessions] ===================================");
        }
        // ✅ NOVO: Handler para logs unificados [Player-Sessions]
        else if (json.type === "player_session_log") {
          displayUnifiedLog(json);
        }
        // ✅ NOVO: Handler para confirmação de logs habilitados
        else if (json.type === "player_session_logs_enabled") {
          safeLog(
            "✅ [Player-Sessions] [UNIFIED-LOGS] Logs [Player-Sessions] habilitados com sucesso!"
          );
          safeLog(
            `✅ [Player-Sessions] [UNIFIED-LOGS] SessionId: ${json.sessionId}`
          );
        }
        // ✅ NOVO: Handler para erro nos logs unificados
        else if (json.type === "player_session_logs_error") {
          safeLog(
            "❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao habilitar logs unificados:",
            json.error
          );
        }
        // ✅ NOVO: Handler para solicitação de identificação LCU
        else if (json.type === "request_identity_confirmation") {
          safeLog(
            "🔗 [Player-Sessions] [BACKEND→ELECTRON] Solicitação de identificação LCU recebida"
          );
          safeLog(
            `🔗 [Player-Sessions] [BACKEND→ELECTRON] Summoner: ${json.summonerName}`
          );
          safeLog(
            `🔗 [Player-Sessions] [BACKEND→ELECTRON] Motivo: ${json.reason}`
          );

          // ✅ PROATIVO: Enviar identificação LCU imediatamente
          await sendProactiveIdentification(json.reason);
        }
        // ✅ NOVO: Handler para entrada na fila (detectar via frontend)
        else if (json.type === "queue_entry_requested") {
          safeLog(
            "🔗 [Player-Sessions] [FRONTEND→ELECTRON] Entrada na fila solicitada - enviando identificação proativa"
          );

          // ✅ PROATIVO: Enviar identificação LCU antes do backend solicitar
          await sendProactiveIdentification("queue_entry_proactive");
        }
        // ✅ NOVO: Handler para solicitação de verificação de identidade (LÓGICA CORRETA)
        else if (json.type === "request_identity_verification") {
          safeLog(
            "🔗 [Player-Sessions] [BACKEND→ELECTRON] Solicitação de verificação de identidade recebida"
          );

          // ✅ CORREÇÃO: Extrair dados corretamente (pode estar em json.data ou json)
          const data = json.data || json;
          const summonerName = data.summonerName;
          const reason = data.reason;
          const redisKey = data.redisKey;

          safeLog(
            `🔗 [Player-Sessions] [BACKEND→ELECTRON] Summoner solicitado: ${summonerName}`
          );
          safeLog(`🔗 [Player-Sessions] [BACKEND→ELECTRON] Motivo: ${reason}`);
          safeLog(
            `🔗 [Player-Sessions] [BACKEND→ELECTRON] Redis Key: ${redisKey}`
          );

          // ✅ VERIFICAR: Se a solicitação é para este jogador
          const isForThisPlayer = await verifyIfRequestIsForThisPlayer(
            summonerName
          );

          if (isForThisPlayer) {
            safeLog(
              "✅ [Player-Sessions] [ELECTRON] Solicitação é para este jogador - enviando identificação"
            );
            // ✅ PROATIVO: Enviar identificação LCU imediatamente
            await sendProactiveIdentification("identity_verification_request");
          } else {
            safeLog(
              "🔕 [Player-Sessions] [ELECTRON] Solicitação NÃO é para este jogador - ignorando"
            );
          }
        }
        // ✅ NOVO: Handler para match_found (CRÍTICO PARA DEBUG)
        else if (json.type === "match_found") {
          await handleMatchFoundEvent(json);
        }
        // ✅ NOVO: Handler para draft_started
        else if (json.type === "draft_started") {
          await handleDraftStartedEvent(json);
        }
        // ✅ NOVO: Handler para draft_starting (evento de transição)
        else if (json.type === "draft_starting") {
          await handleDraftStartedEvent(json);
        }
        // ✅ NOVO: Handler para game_in_progress
        else if (json.type === "game_in_progress") {
          await handleGameInProgressEvent(json);
        }
        // ✅ NOVO: Handler para match_cancelled
        else if (json.type === "match_cancelled") {
          await handleMatchCancelledEvent(json);
        }
        // ✅ NOVO: Handler para acceptance_timer
        else if (json.type === "acceptance_timer") {
          await handleAcceptanceTimerEvent(json);
        }
        // ✅ NOVO: Handler para acceptance_progress
        else if (json.type === "acceptance_progress") {
          await handleAcceptanceProgressEvent(json);
        }
        // ✅ NOVO: Handler para reconnect_check (verificar partida ativa)
        else if (json.type === "reconnect_check") {
          await handleReconnectCheckEvent(json);
        }
        // ✅ NOVO: Handler para restore_active_match (restaurar estado da partida)
        else if (json.type === "restore_active_match") {
          await handleRestoreActiveMatchEvent(json);
        }
        // ✅ DRAFT EVENTS
        else if (json.type === "draft_started") {
          await handleDraftStartedEvent(json);
        } else if (json.type === "draft_timer") {
          await handleDraftTimerEvent(json);
        } else if (json.type === "draft_update") {
          await handleDraftUpdateEvent(json);
        } else if (json.type === "draft_updated") {
          await handleDraftUpdatedEvent(json);
        } else if (json.type === "pick_champion") {
          await handlePickChampionEvent(json);
        } else if (json.type === "ban_champion") {
          await handleBanChampionEvent(json);
        } else if (json.type === "draft_confirmed") {
          await handleDraftConfirmedEvent(json);
        }
        // ✅ GAME EVENTS
        else if (json.type === "game_started") {
          await handleGameStartedEvent(json);
        } else if (json.type === "winner_modal") {
          await handleWinnerModalEvent(json);
        } else if (json.type === "vote_winner") {
          await handleVoteWinnerEvent(json);
        }
        // ✅ SPECTATOR EVENTS
        else if (json.type === "spectator_muted") {
          await handleSpectatorMutedEvent(json);
        } else if (json.type === "spectator_unmuted") {
          await handleSpectatorUnmutedEvent(json);
        }
        // ✅ CANCELLATION EVENTS
        else if (json.type === "match_cancelled") {
          await handleMatchCancelledEvent(json);
        } else if (json.type === "draft_cancelled") {
          await handleDraftCancelledEvent(json);
        } else if (json.type === "game_cancelled") {
          await handleGameCancelledEvent(json);
        }
        // ✅ DEPRECIADO: Mantido para compatibilidade
        else if (json.type === "queue_entry_request") {
          safeLog(
            "🔗 [Player-Sessions] [BACKEND→ELECTRON] Solicitação direta de entrada na fila recebida via Redis (DEPRECIADO)"
          );
          safeLog(
            `🔗 [Player-Sessions] [BACKEND→ELECTRON] Summoner: ${json.summonerName}`
          );

          // ✅ VERIFICAR: Se a solicitação é para este jogador
          const isForThisPlayer = await verifyIfRequestIsForThisPlayer(
            json.summonerName
          );

          if (isForThisPlayer) {
            safeLog(
              "✅ [Player-Sessions] [ELECTRON] Solicitação é para este jogador - enviando identificação"
            );
            await sendProactiveIdentification("backend_direct_request_redis");
          } else {
            safeLog(
              "🔕 [Player-Sessions] [ELECTRON] Solicitação NÃO é para este jogador - ignorando"
            );
          }
        }
        // ✅ NOVO: Monitoramento proativo - qualquer mensagem que mencione summoner atual
        else {
          // ✅ CORRIGIDO: Não processar mensagens de confirmação para evitar loop infinito
          if (
            json.type !== "electron_identified" &&
            json.type !== "player_identified"
          ) {
            // Verificar se a mensagem menciona o summoner atual e revincular
            checkAndRebindOnSummonerEvent(json.type || "unknown", json);
          }
        }
      } catch (e) {
        safeLog("ws gateway message error", String(e));
      }
    });

    wsClient.on("close", (code, reason) => {
      safeLog(
        "⚠️ [ELECTRON MAIN] WebSocket gateway fechado - code:",
        code,
        "reason:",
        reason && reason.toString()
      );
      stopWebSocketHeartbeat(); // Parar heartbeat
      stopIdentityMonitor(); // ✅ NOVO: Parar monitor de identidade
      wsClient = null;
      scheduleWebSocketReconnect(backendBase);
    });
    wsClient.on("error", (err) => {
      safeLog("❌ [ELECTRON MAIN] Erro no WebSocket gateway:", String(err));
      safeLog(
        "❌ [ELECTRON MAIN] Detalhes do erro:",
        JSON.stringify(err, null, 2)
      );
    });
  } catch (err) {
    safeLog("startWebSocketGateway error", String(err));
  }
}

// shared ws client reference and last identify from renderer
let wsClient = null;
let lastRendererIdentify = null;
let wsReconnectAttempts = 0;

// ✅ NOVO: Flag para logs unificados habilitados
let unifiedLogsEnabled = false;
let redisSubscriber = null;
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
  "C:/Riot Games/League of Legends/lockfile",
  "D:/Riot Games/League of Legends/lockfile",
  "E:/Riot Games/League of Legends/lockfile",
  path.join(
    process.env.LOCALAPPDATA || "",
    "Riot Games",
    "League of Legends",
    "lockfile"
  ),
  path.join(
    process.env.USERPROFILE || "",
    "AppData",
    "Local",
    "Riot Games",
    "League of Legends",
    "lockfile"
  ),
];

// Discord integration
let discordBot = null;
let discordChannelId = null;
let discordUsers = [];
let discordStatus = {
  isConnected: false,
  botUsername: null,
  channelName: null,
};

// ✅ NOVO: Função proativa para enviar identificação LCU
async function sendProactiveIdentification(reason) {
  try {
    safeLog(
      "🔗 [Player-Sessions] [ELECTRON] Enviando identificação proativa (motivo: " +
        reason +
        ")"
    );

    const lockfileInfo = readLockfileInfo();
    if (lockfileInfo) {
      await identifyPlayerToBackend(lockfileInfo);
      safeLog(
        "✅ [Player-Sessions] [ELECTRON] Identificação proativa enviada com sucesso"
      );
    } else {
      safeLog(
        "❌ [Player-Sessions] [ELECTRON] Lockfile não encontrado para identificação proativa"
      );
    }
  } catch (error) {
    safeLog(
      "❌ [Player-Sessions] [ELECTRON] Erro na identificação proativa:",
      error
    );
  }
}

// ✅ NOVO: Sistema de monitoramento proativo de eventos summonerName#tagline
let currentSummonerInfo = null;
let proactiveMonitoringEnabled = false;

// ✅ NOVO: Inicializar monitoramento proativo
function initializeProactiveMonitoring() {
  try {
    safeLog(
      "🔗 [Player-Sessions] [ELECTRON] Inicializando monitoramento proativo..."
    );

    // Buscar informações do summoner atual
    const lockfileInfo = readLockfileInfo();
    if (lockfileInfo) {
      performLcuRequest("GET", "/lol-summoner/v1/current-summoner")
        .then((summoner) => {
          currentSummonerInfo = {
            summonerName: summoner.displayName,
            gameName: summoner.gameName,
            tagLine: summoner.tagLine,
            puuid: summoner.puuid,
            summonerId: summoner.summonerId,
          };

          safeLog(
            "🔗 [Player-Sessions] [ELECTRON] Monitoramento proativo ativo para: " +
              currentSummonerInfo.summonerName
          );
          proactiveMonitoringEnabled = true;
        })
        .catch((error) => {
          safeLog(
            "❌ [Player-Sessions] [ELECTRON] Erro ao inicializar monitoramento proativo:",
            error
          );
        });
    }
  } catch (error) {
    safeLog(
      "❌ [Player-Sessions] [ELECTRON] Erro ao inicializar monitoramento proativo:",
      error
    );
  }
}

// ✅ NOVO: Verificar se evento envolve summoner atual e revincular
function checkAndRebindOnSummonerEvent(eventType, eventData) {
  if (!proactiveMonitoringEnabled || !currentSummonerInfo) {
    return false;
  }

  try {
    // Verificar se o evento menciona o summoner atual
    const summonerMentioned = checkSummonerMention(eventData);

    if (summonerMentioned) {
      safeLog(
        "🔗 [Player-Sessions] [ELECTRON] Evento " +
          eventType +
          " menciona summoner atual - revinculando..."
      );
      sendProactiveIdentification("event_" + eventType);
      return true;
    }
  } catch (error) {
    safeLog(
      "❌ [Player-Sessions] [ELECTRON] Erro ao verificar evento summoner:",
      error
    );
  }

  return false;
}

// ✅ NOVO: Verificar se dados mencionam o summoner atual
function checkSummonerMention(data) {
  if (!data || !currentSummonerInfo) {
    return false;
  }

  try {
    const dataStr = JSON.stringify(data).toLowerCase();
    const summonerName = currentSummonerInfo.summonerName.toLowerCase();
    const gameName = currentSummonerInfo.gameName.toLowerCase();

    return (
      dataStr.includes(summonerName) ||
      dataStr.includes(gameName) ||
      dataStr.includes(currentSummonerInfo.puuid.toLowerCase())
    );
  } catch (error) {
    return false;
  }
}

// ✅ NOVO: Verificar se a solicitação é para este jogador (LÓGICA CORRETA)
async function verifyIfRequestIsForThisPlayer(requestedSummonerName) {
  try {
    // Verificar se temos informações do summoner atual
    if (!currentSummonerInfo) {
      safeLog(
        "⚠️ [Player-Sessions] [ELECTRON] Nenhuma informação de summoner atual disponível"
      );
      return false;
    }

    // Buscar summoner atual do LCU
    const summoner = await performLcuRequest(
      "GET",
      "/lol-summoner/v1/current-summoner"
    );

    if (!summoner || !summoner.gameName || !summoner.tagLine) {
      safeLog("⚠️ [Player-Sessions] [ELECTRON] Summoner não disponível no LCU");
      return false;
    }

    const currentSummonerName = `${summoner.gameName}#${summoner.tagLine}`;

    // ✅ COMPARAR: Normalizar ambos os nomes para comparação
    const requestedNormalized = requestedSummonerName.toLowerCase().trim();
    const currentNormalized = currentSummonerName.toLowerCase().trim();

    const isMatch = requestedNormalized === currentNormalized;

    safeLog(`🔍 [Player-Sessions] [ELECTRON] Verificação de identidade:`);
    safeLog(
      `🔍 [Player-Sessions] [ELECTRON] Solicitado: "${requestedSummonerName}" → "${requestedNormalized}"`
    );
    safeLog(
      `🔍 [Player-Sessions] [ELECTRON] Atual LCU: "${currentSummonerName}" → "${currentNormalized}"`
    );
    safeLog(
      `🔍 [Player-Sessions] [ELECTRON] É para este jogador: ${
        isMatch ? "SIM" : "NÃO"
      }`
    );

    return isMatch;
  } catch (error) {
    safeLog(
      "❌ [Player-Sessions] [ELECTRON] Erro ao verificar se solicitação é para este jogador:",
      error
    );
    return false;
  }
}

// ✅ NOVO: Sistema de Handlers Centralizados para Eventos de Jogo
async function handleMatchFoundEvent(json) {
  try {
    safeLog(
      "🎯 [session-match-found] ===== MATCH_FOUND RECEBIDO NO ELECTRON ====="
    );
    safeLog("🎯 [session-match-found] MatchId:", json.matchId);
    safeLog("🎯 [session-match-found] Timestamp:", json.timestamp);

    // ✅ BUSCAR summoner atual do LCU para comparação
    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🎯 [session-match-found] Current summoner:",
      currentSummoner || "UNKNOWN"
    );
    safeLog(
      "🎯 [session-match-found] WebSocket conectado:",
      wsClient && wsClient.readyState === WebSocket.OPEN ? "SIM" : "NÃO"
    );

    // ✅ VERIFICAR se este match_found é para o jogador atual
    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🎯 [session-match-found] ❌ Match_found NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🎯 [session-match-found] ✅ Match_found É para este jogador!");

    // ✅ LOG detalhado dos jogadores na partida
    if (json.team1 && Array.isArray(json.team1)) {
      safeLog("🎯 [session-match-found] Team 1:");
      json.team1.forEach((player, index) => {
        safeLog(
          `🎯 [session-match-found]   [${index}] ${
            player.summonerName || player.name || "UNKNOWN"
          }`
        );
      });
    }

    if (json.team2 && Array.isArray(json.team2)) {
      safeLog("🎯 [session-match-found] Team 2:");
      json.team2.forEach((player, index) => {
        safeLog(
          `🎯 [session-match-found]   [${index}] ${
            player.summonerName || player.name || "UNKNOWN"
          }`
        );
      });
    }

    safeLog(
      "🎯 [session-match-found] ================================================"
    );

    // ✅ ENVIAR para o frontend via IPC
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("match-found", json);
      safeLog(
        "🎯 [session-match-found] ✅ Match_found enviado para o frontend via IPC"
      );
    } else {
      safeLog(
        "🎯 [session-match-found] ❌ MainWindow não disponível - não foi possível enviar para frontend"
      );
    }
  } catch (error) {
    safeLog("❌ [session-match-found] Erro ao processar match_found:", error);
  }
}

// ✅ NOVO: Buscar summoner atual do LCU
async function getCurrentSummonerFromLCU() {
  try {
    const summoner = await performLcuRequest(
      "GET",
      "/lol-summoner/v1/current-summoner"
    );
    if (summoner && summoner.gameName && summoner.tagLine) {
      return `${summoner.gameName}#${summoner.tagLine}`;
    }
    return null;
  } catch (error) {
    safeLog(
      "⚠️ [session-match-found] Erro ao buscar summoner atual do LCU:",
      error.message
    );
    return null;
  }
}

// ✅ NOVO: Verificar se match_found é para este jogador
async function isMatchFoundForThisPlayer(json, currentSummoner) {
  if (!currentSummoner) {
    safeLog("⚠️ [session-match-found] Summoner atual não disponível");
    return false;
  }

  const currentNormalized = currentSummoner.toLowerCase().trim();

  // Verificar em team1
  if (json.team1 && Array.isArray(json.team1)) {
    for (const player of json.team1) {
      const playerName = player.summonerName || player.name;
      if (playerName && playerName.toLowerCase().trim() === currentNormalized) {
        return true;
      }
    }
  }

  // Verificar em team2
  if (json.team2 && Array.isArray(json.team2)) {
    for (const player of json.team2) {
      const playerName = player.summonerName || player.name;
      if (playerName && playerName.toLowerCase().trim() === currentNormalized) {
        return true;
      }
    }
  }

  // Verificar em players (fallback)
  if (json.players && Array.isArray(json.players)) {
    for (const player of json.players) {
      const playerName = player.summonerName || player.name;
      if (playerName && playerName.toLowerCase().trim() === currentNormalized) {
        return true;
      }
    }
  }

  return false;
}

// ✅ NOVO: Handler para draft_started
async function handleDraftStartedEvent(json) {
  try {
    safeLog(
      "🎮 [draft-started] ===== DRAFT_STARTED RECEBIDO NO ELECTRON ====="
    );
    safeLog("🎮 [draft-started] MatchId:", json.matchId);
    safeLog("🎮 [draft-started] Timestamp:", json.timestamp);
    safeLog("🎮 [draft-started] JSON completo:", JSON.stringify(json, null, 2));

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🎮 [draft-started] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    // ✅ DIAGNÓSTICO: Verificar se há dados de times no JSON
    if (json.team1) {
      safeLog(
        "🎮 [draft-started] Team1 players:",
        json.team1.map((p) => p.summonerName || p).join(", ")
      );
    }
    if (json.team2) {
      safeLog(
        "🎮 [draft-started] Team2 players:",
        json.team2.map((p) => p.summonerName || p).join(", ")
      );
    }
    if (json.teams) {
      safeLog("🎮 [draft-started] Teams structure:", Object.keys(json.teams));
    }

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🎮 [draft-started] ❌ Draft_started NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🎮 [draft-started] ✅ Draft_started É para este jogador!");
    safeLog(
      "🎮 [draft-started] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("draft-started", json);
      safeLog(
        "🎮 [draft-started] ✅ Draft_started enviado para o frontend via IPC"
      );
    } else {
      safeLog(
        "🎮 [draft-started] ❌ MainWindow não disponível - não foi possível enviar para frontend"
      );
    }
  } catch (error) {
    safeLog("❌ [draft-started] Erro ao processar draft_started:", error);
  }
}

// ✅ NOVO: Handler para game_in_progress
async function handleGameInProgressEvent(json) {
  try {
    safeLog(
      "🏁 [game-in-progress] ===== GAME_IN_PROGRESS RECEBIDO NO ELECTRON ====="
    );
    safeLog("🏁 [game-in-progress] MatchId:", json.matchId);
    safeLog("🏁 [game-in-progress] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🏁 [game-in-progress] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🏁 [game-in-progress] ❌ Game_in_progress NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🏁 [game-in-progress] ✅ Game_in_progress É para este jogador!");
    safeLog(
      "🏁 [game-in-progress] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("game-in-progress", json);
      safeLog(
        "🏁 [game-in-progress] ✅ Game_in_progress enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [game-in-progress] Erro ao processar game_in_progress:", error);
  }
}

// ✅ NOVO: Handler para match_cancelled
async function handleMatchCancelledEvent(json) {
  try {
    safeLog(
      "❌ [match-cancelled] ===== MATCH_CANCELLED RECEBIDO NO ELECTRON ====="
    );
    safeLog("❌ [match-cancelled] MatchId:", json.matchId);
    safeLog("❌ [match-cancelled] Reason:", json.reason);
    safeLog("❌ [match-cancelled] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "❌ [match-cancelled] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    // ✅ MATCH_CANCELLED é sempre relevante se o jogador está em uma partida
    safeLog("❌ [match-cancelled] ✅ Match_cancelled processado!");
    safeLog(
      "❌ [match-cancelled] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("match-cancelled", json);
      safeLog(
        "❌ [match-cancelled] ✅ Match_cancelled enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [match-cancelled] Erro ao processar match_cancelled:", error);
  }
}

async function handleAcceptanceTimerEvent(json) {
  try {
    safeLog(
      "⏰ [acceptance-timer] ===== ACCEPTANCE_TIMER RECEBIDO NO ELECTRON ====="
    );
    safeLog("⏰ [acceptance-timer] MatchId:", json.matchId);
    safeLog("⏰ [acceptance-timer] SecondsRemaining:", json.secondsRemaining);
    safeLog("⏰ [acceptance-timer] Timestamp:", json.timestamp);
    safeLog(
      "⏰ [acceptance-timer] JSON completo:",
      JSON.stringify(json, null, 2)
    );

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "⏰ [acceptance-timer] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    // ✅ Para acceptance_timer, sempre enviar para o jogador atual
    // (o backend já filtra quem deve receber)
    if (!currentSummoner) {
      safeLog(
        "⏰ [acceptance-timer] ❌ Current summoner não disponível - ignorando"
      );
      return;
    }

    safeLog("⏰ [acceptance-timer] ✅ Acceptance_timer É para este jogador!");
    safeLog(
      "⏰ [acceptance-timer] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("acceptance-timer", json);
      safeLog(
        "⏰ [acceptance-timer] ✅ Acceptance_timer enviado para o frontend via IPC"
      );
    } else {
      safeLog(
        "⏰ [acceptance-timer] ❌ MainWindow não disponível - não foi possível enviar para frontend"
      );
    }
  } catch (error) {
    safeLog("❌ [acceptance-timer] Erro ao processar acceptance_timer:", error);
  }
}

async function handleAcceptanceProgressEvent(json) {
  try {
    safeLog(
      "📊 [acceptance-progress] ===== ACCEPTANCE_PROGRESS RECEBIDO NO ELECTRON ====="
    );
    safeLog("📊 [acceptance-progress] MatchId:", json.matchId);
    safeLog("📊 [acceptance-progress] AcceptedCount:", json.acceptedCount);
    safeLog("📊 [acceptance-progress] TotalPlayers:", json.totalPlayers);
    safeLog("📊 [acceptance-progress] AcceptedPlayers:", json.acceptedPlayers);
    safeLog("📊 [acceptance-progress] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "📊 [acceptance-progress] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    // ✅ Para acceptance_progress, sempre enviar para o jogador atual
    // (o backend já filtra quem deve receber)
    if (!currentSummoner) {
      safeLog(
        "📊 [acceptance-progress] ❌ Current summoner não disponível - ignorando"
      );
      return;
    }

    safeLog(
      "📊 [acceptance-progress] ✅ Acceptance_progress É para este jogador!"
    );
    safeLog(
      "📊 [acceptance-progress] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("acceptance-progress", json);
      safeLog(
        "📊 [acceptance-progress] ✅ Acceptance_progress enviado para o frontend via IPC"
      );
    } else {
      safeLog(
        "📊 [acceptance-progress] ❌ MainWindow não disponível - não foi possível enviar para frontend"
      );
    }
  } catch (error) {
    safeLog(
      "❌ [acceptance-progress] Erro ao processar acceptance_progress:",
      error
    );
  }
}

// ✅ NOVO: Handler para reconnect_check
async function handleReconnectCheckEvent(json) {
  try {
    safeLog(
      "🔄 [reconnect-check] ===== RECONNECT_CHECK RECEBIDO NO ELECTRON ====="
    );
    safeLog("🔄 [reconnect-check] Timestamp:", json.timestamp);
    safeLog("🔄 [reconnect-check] Reason:", json.reason);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🔄 [reconnect-check] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    if (!currentSummoner) {
      safeLog(
        "🔄 [reconnect-check] ❌ Current summoner não disponível - ignorando"
      );
      return;
    }

    // ✅ Verificar se tem partida ativa chamando o endpoint my-active-match
    try {
      const backendBase = await pickBackendUrl();
      const response = await fetch(
        `${backendBase}api/queue/my-active-match?summonerName=${encodeURIComponent(
          currentSummoner
        )}`,
        {
          method: "GET",
          headers: {
            "User-Agent": "LoL-Matchmaking-Electron/1.0.0",
            "X-Summoner-Name": currentSummoner,
            ...(process.env.BACKEND_GATEWAY_TOKEN
              ? { Authorization: "Bearer " + process.env.BACKEND_GATEWAY_TOKEN }
              : {}),
          },
        }
      );

      let hasActiveMatch = false;
      let matchData = null;

      if (response.ok) {
        const data = await response.json();
        if (data && data.success !== false) {
          hasActiveMatch = true;
          matchData = data;
          safeLog(
            "🔄 [reconnect-check] ✅ Partida ativa encontrada:",
            data.matchId,
            data.status
          );
        }
      } else if (response.status === 404) {
        safeLog("🔄 [reconnect-check] ✅ Nenhuma partida ativa (404)");
      } else {
        safeLog(
          "🔄 [reconnect-check] ⚠️ Erro ao verificar partida ativa:",
          response.status,
          response.statusText
        );
      }

      // ✅ Enviar resposta para o backend
      if (wsClient && wsClient.readyState === WebSocket.OPEN) {
        const responseData = {
          type: "reconnect_check_response",
          data: {
            summonerName: currentSummoner,
            hasActiveMatch: hasActiveMatch,
            matchData: matchData,
            timestamp: Date.now(),
          },
        };

        wsClient.send(JSON.stringify(responseData));
        safeLog(
          "🔄 [reconnect-check] ✅ Resposta enviada para o backend:",
          hasActiveMatch ? "TEM partida ativa" : "NÃO TEM partida ativa"
        );
      } else {
        safeLog(
          "🔄 [reconnect-check] ❌ WebSocket não conectado - não foi possível responder"
        );
      }
    } catch (error) {
      safeLog(
        "❌ [reconnect-check] Erro ao verificar partida ativa:",
        error.message
      );
    }

    safeLog(
      "🔄 [reconnect-check] ================================================"
    );
  } catch (error) {
    safeLog("❌ [reconnect-check] Erro ao processar reconnect_check:", error);
  }
}

// ✅ NOVO: Handler para restore_active_match
async function handleRestoreActiveMatchEvent(json) {
  try {
    safeLog(
      "🔄 [restore-active-match] ===== RESTORE_ACTIVE_MATCH RECEBIDO NO ELECTRON ====="
    );
    safeLog("🔄 [restore-active-match] MatchId:", json.matchId);
    safeLog("🔄 [restore-active-match] Status:", json.status);
    safeLog("🔄 [restore-active-match] SummonerName:", json.summonerName);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🔄 [restore-active-match] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    if (!currentSummoner) {
      safeLog(
        "🔄 [restore-active-match] ❌ Current summoner não disponível - ignorando"
      );
      return;
    }

    // ✅ Verificar se é para este jogador
    if (currentSummoner.toLowerCase() !== json.summonerName.toLowerCase()) {
      safeLog(
        "🔄 [restore-active-match] ❌ Não é para este jogador - ignorando"
      );
      return;
    }

    safeLog(
      "🔄 [restore-active-match] ✅ Restaurando estado da partida para este jogador!"
    );

    // ✅ Enviar evento para o frontend baseado no status da partida
    if (mainWindow && !mainWindow.isDestroyed()) {
      if (json.status === "found") {
        // Partida encontrada - mostrar modal de aceitação
        mainWindow.webContents.send("match-found", json.matchData);
        safeLog(
          "🔄 [restore-active-match] ✅ Modal match_found enviado para o frontend"
        );
      } else if (json.status === "draft") {
        // Draft ativo - mostrar tela de draft
        mainWindow.webContents.send("draft-started", json.matchData);
        safeLog(
          "🔄 [restore-active-match] ✅ Tela de draft enviada para o frontend"
        );
      } else if (json.status === "in_progress") {
        // Jogo em progresso - mostrar modal de game in progress
        mainWindow.webContents.send("game-in-progress", json.matchData);
        safeLog(
          "🔄 [restore-active-match] ✅ Modal game_in_progress enviado para o frontend"
        );
      } else {
        safeLog(
          "🔄 [restore-active-match] ⚠️ Status desconhecido:",
          json.status
        );
      }
    } else {
      safeLog(
        "🔄 [restore-active-match] ❌ MainWindow não disponível - não foi possível restaurar estado"
      );
    }

    safeLog(
      "🔄 [restore-active-match] ================================================"
    );
  } catch (error) {
    safeLog(
      "❌ [restore-active-match] Erro ao processar restore_active_match:",
      error
    );
  }
}

// ✅ DRAFT EVENT HANDLERS
async function handleDraftStartedEvent(json) {
  try {
    safeLog(
      "🎬 [draft-started] ===== DRAFT_STARTED RECEBIDO NO ELECTRON ====="
    );
    safeLog("🎬 [draft-started] MatchId:", json.matchId);
    safeLog("🎬 [draft-started] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🎬 [draft-started] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🎬 [draft-started] ❌ Draft_started NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🎬 [draft-started] ✅ Draft_started É para este jogador!");
    safeLog(
      "🎬 [draft-started] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("draft-started", json);
      safeLog(
        "🎬 [draft-started] ✅ Draft_started enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [draft-started] Erro ao processar draft_started:", error);
  }
}

async function handleDraftTimerEvent(json) {
  try {
    safeLog("⏰ [draft-timer] ===== DRAFT_TIMER RECEBIDO NO ELECTRON =====");
    safeLog("⏰ [draft-timer] MatchId:", json.matchId);
    safeLog("⏰ [draft-timer] SecondsRemaining:", json.secondsRemaining);
    safeLog("⏰ [draft-timer] CurrentPlayer:", json.currentPlayer);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog("⏰ [draft-timer] Current summoner:", currentSummoner || "UNKNOWN");

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "⏰ [draft-timer] ❌ Draft_timer NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("⏰ [draft-timer] ✅ Draft_timer É para este jogador!");
    safeLog(
      "⏰ [draft-timer] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("draft-timer", json);
      safeLog(
        "⏰ [draft-timer] ✅ Draft_timer enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [draft-timer] Erro ao processar draft_timer:", error);
  }
}

async function handleDraftUpdateEvent(json) {
  try {
    safeLog("🔄 [draft-update] ===== DRAFT_UPDATE RECEBIDO NO ELECTRON =====");
    safeLog("🔄 [draft-update] MatchId:", json.matchId);
    safeLog("🔄 [draft-update] CurrentPlayer:", json.currentPlayer);
    safeLog("🔄 [draft-update] ActionType:", json.actionType);
    safeLog("🔄 [draft-update] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🔄 [draft-update] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🔄 [draft-update] ❌ Draft_update NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🔄 [draft-update] ✅ Draft_update É para este jogador!");
    safeLog(
      "🔄 [draft-update] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("draft-update", json);
      safeLog(
        "🔄 [draft-update] ✅ Draft_update enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [draft-update] Erro ao processar draft_update:", error);
  }
}

async function handleDraftUpdatedEvent(json) {
  try {
    safeLog(
      "✅ [draft-updated] ===== DRAFT_UPDATED RECEBIDO NO ELECTRON ====="
    );
    safeLog("✅ [draft-updated] MatchId:", json.matchId);
    safeLog("✅ [draft-updated] UpdatedBy:", json.updatedBy);
    safeLog("✅ [draft-updated] ActionType:", json.actionType);
    safeLog("✅ [draft-updated] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "✅ [draft-updated] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "✅ [draft-updated] ❌ Draft_updated NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("✅ [draft-updated] ✅ Draft_updated É para este jogador!");
    safeLog(
      "✅ [draft-updated] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("draft-updated", json);
      safeLog(
        "✅ [draft-updated] ✅ Draft_updated enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [draft-updated] Erro ao processar draft_updated:", error);
  }
}

async function handlePickChampionEvent(json) {
  try {
    safeLog(
      "⚔️ [pick-champion] ===== PICK_CHAMPION RECEBIDO NO ELECTRON ====="
    );
    safeLog("⚔️ [pick-champion] MatchId:", json.matchId);
    safeLog("⚔️ [pick-champion] ChampionId:", json.championId);
    safeLog("⚔️ [pick-champion] Player:", json.player);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "⚔️ [pick-champion] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "⚔️ [pick-champion] ❌ Pick_champion NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("⚔️ [pick-champion] ✅ Pick_champion É para este jogador!");
    safeLog(
      "⚔️ [pick-champion] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("pick-champion", json);
      safeLog(
        "⚔️ [pick-champion] ✅ Pick_champion enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [pick-champion] Erro ao processar pick_champion:", error);
  }
}

async function handleBanChampionEvent(json) {
  try {
    safeLog("🚫 [ban-champion] ===== BAN_CHAMPION RECEBIDO NO ELECTRON =====");
    safeLog("🚫 [ban-champion] MatchId:", json.matchId);
    safeLog("🚫 [ban-champion] ChampionId:", json.championId);
    safeLog("🚫 [ban-champion] Player:", json.player);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🚫 [ban-champion] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🚫 [ban-champion] ❌ Ban_champion NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🚫 [ban-champion] ✅ Ban_champion É para este jogador!");
    safeLog(
      "🚫 [ban-champion] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("ban-champion", json);
      safeLog(
        "🚫 [ban-champion] ✅ Ban_champion enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [ban-champion] Erro ao processar ban_champion:", error);
  }
}

async function handleDraftConfirmedEvent(json) {
  try {
    safeLog(
      "✅ [draft-confirmed] ===== DRAFT_CONFIRMED RECEBIDO NO ELECTRON ====="
    );
    safeLog("✅ [draft-confirmed] MatchId:", json.matchId);
    safeLog("✅ [draft-confirmed] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "✅ [draft-confirmed] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "✅ [draft-confirmed] ❌ Draft_confirmed NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("✅ [draft-confirmed] ✅ Draft_confirmed É para este jogador!");
    safeLog(
      "✅ [draft-confirmed] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("draft-confirmed", json);
      safeLog(
        "✅ [draft-confirmed] ✅ Draft_confirmed enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [draft-confirmed] Erro ao processar draft_confirmed:", error);
  }
}

// ✅ GAME EVENT HANDLERS
async function handleGameStartedEvent(json) {
  try {
    safeLog("🎮 [game-started] ===== GAME_STARTED RECEBIDO NO ELECTRON =====");
    safeLog("🎮 [game-started] MatchId:", json.matchId);
    safeLog("🎮 [game-started] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🎮 [game-started] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🎮 [game-started] ❌ Game_started NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🎮 [game-started] ✅ Game_started É para este jogador!");
    safeLog(
      "🎮 [game-started] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("game-started", json);
      safeLog(
        "🎮 [game-started] ✅ Game_started enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [game-started] Erro ao processar game_started:", error);
  }
}

async function handleWinnerModalEvent(json) {
  try {
    safeLog("🏆 [winner-modal] ===== WINNER_MODAL RECEBIDO NO ELECTRON =====");
    safeLog("🏆 [winner-modal] MatchId:", json.matchId);
    safeLog("🏆 [winner-modal] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🏆 [winner-modal] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🏆 [winner-modal] ❌ Winner_modal NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🏆 [winner-modal] ✅ Winner_modal É para este jogador!");
    safeLog(
      "🏆 [winner-modal] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("winner-modal", json);
      safeLog(
        "🏆 [winner-modal] ✅ Winner_modal enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [winner-modal] Erro ao processar winner_modal:", error);
  }
}

async function handleVoteWinnerEvent(json) {
  try {
    safeLog("🗳️ [vote-winner] ===== VOTE_WINNER RECEBIDO NO ELECTRON =====");
    safeLog("🗳️ [vote-winner] MatchId:", json.matchId);
    safeLog("🗳️ [vote-winner] Winner:", json.winner);
    safeLog("🗳️ [vote-winner] Voter:", json.voter);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog("🗳️ [vote-winner] Current summoner:", currentSummoner || "UNKNOWN");

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🗳️ [vote-winner] ❌ Vote_winner NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🗳️ [vote-winner] ✅ Vote_winner É para este jogador!");
    safeLog(
      "🗳️ [vote-winner] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("vote-winner", json);
      safeLog(
        "🗳️ [vote-winner] ✅ Vote_winner enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [vote-winner] Erro ao processar vote_winner:", error);
  }
}

// ✅ SPECTATOR EVENT HANDLERS
async function handleSpectatorMutedEvent(json) {
  try {
    safeLog(
      "🔇 [spectator-muted] ===== SPECTATOR_MUTED RECEBIDO NO ELECTRON ====="
    );
    safeLog("🔇 [spectator-muted] MatchId:", json.matchId);
    safeLog("🔇 [spectator-muted] Spectator:", json.spectator);
    safeLog("🔇 [spectator-muted] MutedBy:", json.mutedBy);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🔇 [spectator-muted] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🔇 [spectator-muted] ❌ Spectator_muted NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🔇 [spectator-muted] ✅ Spectator_muted É para este jogador!");
    safeLog(
      "🔇 [spectator-muted] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("spectator-muted", json);
      safeLog(
        "🔇 [spectator-muted] ✅ Spectator_muted enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [spectator-muted] Erro ao processar spectator_muted:", error);
  }
}

async function handleSpectatorUnmutedEvent(json) {
  try {
    safeLog(
      "🔊 [spectator-unmuted] ===== SPECTATOR_UNMUTED RECEBIDO NO ELECTRON ====="
    );
    safeLog("🔊 [spectator-unmuted] MatchId:", json.matchId);
    safeLog("🔊 [spectator-unmuted] Spectator:", json.spectator);
    safeLog("🔊 [spectator-unmuted] UnmutedBy:", json.unmutedBy);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🔊 [spectator-unmuted] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🔊 [spectator-unmuted] ❌ Spectator_unmuted NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🔊 [spectator-unmuted] ✅ Spectator_unmuted É para este jogador!");
    safeLog(
      "🔊 [spectator-unmuted] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("spectator-unmuted", json);
      safeLog(
        "🔊 [spectator-unmuted] ✅ Spectator_unmuted enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog(
      "❌ [spectator-unmuted] Erro ao processar spectator_unmuted:",
      error
    );
  }
}

// ✅ CANCELLATION EVENT HANDLERS
async function handleMatchCancelledEvent(json) {
  try {
    safeLog(
      "❌ [match-cancelled] ===== MATCH_CANCELLED RECEBIDO NO ELECTRON ====="
    );
    safeLog("❌ [match-cancelled] MatchId:", json.matchId);
    safeLog("❌ [match-cancelled] Reason:", json.reason);
    safeLog("❌ [match-cancelled] CancelledBy:", json.cancelledBy);
    safeLog("❌ [match-cancelled] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "❌ [match-cancelled] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "❌ [match-cancelled] ❌ Match_cancelled NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("❌ [match-cancelled] ✅ Match_cancelled É para este jogador!");
    safeLog(
      "❌ [match-cancelled] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("match-cancelled", json);
      safeLog(
        "❌ [match-cancelled] ✅ Match_cancelled enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [match-cancelled] Erro ao processar match_cancelled:", error);
  }
}

async function handleDraftCancelledEvent(json) {
  try {
    safeLog(
      "🚫 [draft-cancelled] ===== DRAFT_CANCELLED RECEBIDO NO ELECTRON ====="
    );
    safeLog("🚫 [draft-cancelled] MatchId:", json.matchId);
    safeLog("🚫 [draft-cancelled] Reason:", json.reason);
    safeLog("🚫 [draft-cancelled] CancelledBy:", json.cancelledBy);
    safeLog("🚫 [draft-cancelled] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🚫 [draft-cancelled] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🚫 [draft-cancelled] ❌ Draft_cancelled NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🚫 [draft-cancelled] ✅ Draft_cancelled É para este jogador!");
    safeLog(
      "🚫 [draft-cancelled] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("draft-cancelled", json);
      safeLog(
        "🚫 [draft-cancelled] ✅ Draft_cancelled enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [draft-cancelled] Erro ao processar draft_cancelled:", error);
  }
}

async function handleGameCancelledEvent(json) {
  try {
    safeLog(
      "🏳️ [game-cancelled] ===== GAME_CANCELLED RECEBIDO NO ELECTRON ====="
    );
    safeLog("🏳️ [game-cancelled] MatchId:", json.matchId);
    safeLog("🏳️ [game-cancelled] Reason:", json.reason);
    safeLog("🏳️ [game-cancelled] CancelledBy:", json.cancelledBy);
    safeLog("🏳️ [game-cancelled] Timestamp:", json.timestamp);

    const currentSummoner = await getCurrentSummonerFromLCU();
    safeLog(
      "🏳️ [game-cancelled] Current summoner:",
      currentSummoner || "UNKNOWN"
    );

    const isForThisPlayer = await isMatchFoundForThisPlayer(
      json,
      currentSummoner
    );

    if (!isForThisPlayer) {
      safeLog(
        "🏳️ [game-cancelled] ❌ Game_cancelled NÃO é para este jogador - ignorando"
      );
      return;
    }

    safeLog("🏳️ [game-cancelled] ✅ Game_cancelled É para este jogador!");
    safeLog(
      "🏳️ [game-cancelled] ================================================"
    );

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("game-cancelled", json);
      safeLog(
        "🏳️ [game-cancelled] ✅ Game_cancelled enviado para o frontend via IPC"
      );
    }
  } catch (error) {
    safeLog("❌ [game-cancelled] Erro ao processar game_cancelled:", error);
  }
}

// ✅ NOVO: Sistema de Validação de Ações do Jogo
async function validateAndSendGameAction(actionType, actionData) {
  try {
    safeLog(
      `🔐 [game-action] ===== VALIDANDO AÇÃO: ${actionType.toUpperCase()} =====`
    );

    // ✅ BUSCAR summoner atual do LCU para validação
    const currentSummoner = await getCurrentSummonerFromLCU();
    if (!currentSummoner) {
      safeLog(
        `🔐 [game-action] ❌ Summoner não disponível - ação ${actionType} negada`
      );
      return false;
    }

    safeLog(`🔐 [game-action] Summoner atual: ${currentSummoner}`);
    safeLog(`🔐 [game-action] Ação: ${actionType}`);
    safeLog(`🔐 [game-action] Dados:`, actionData);

    // ✅ ADICIONAR dados de validação
    const validatedAction = {
      ...actionData,
      summonerName: currentSummoner,
      timestamp: Date.now(),
      validatedByElectron: true,
    };

    // ✅ ENVIAR para o backend via WebSocket
    if (ws && ws.readyState === WebSocket.OPEN) {
      const message = {
        type: actionType,
        data: validatedAction,
      };

      ws.send(JSON.stringify(message));
      safeLog(
        `🔐 [game-action] ✅ Ação ${actionType} enviada para o backend com validação`
      );
      safeLog(
        `🔐 [game-action] ================================================`
      );
      return true;
    } else {
      safeLog(
        `🔐 [game-action] ❌ WebSocket não conectado - ação ${actionType} não enviada`
      );
      return false;
    }
  } catch (error) {
    safeLog(`❌ [game-action] Erro ao validar ação ${actionType}:`, error);
    return false;
  }
}

// ✅ NOVO: Handlers IPC para TODAS as ações do frontend (SEGURANÇA TOTAL)

// === MATCH ACTIONS ===
ipcMain.handle("game-action-accept-match", async (event, matchData) => {
  return await validateAndSendGameAction("accept_match", matchData);
});

ipcMain.handle("game-action-decline-match", async (event, matchData) => {
  return await validateAndSendGameAction("decline_match", matchData);
});

ipcMain.handle("game-action-cancel-match", async (event, matchData) => {
  return await validateAndSendGameAction("cancel_match", matchData);
});

// === QUEUE ACTIONS ===
ipcMain.handle("game-action-join-queue", async (event, queueData) => {
  return await validateAndSendGameAction("join_queue", queueData);
});

ipcMain.handle("game-action-leave-queue", async (event, queueData) => {
  return await validateAndSendGameAction("leave_queue", queueData);
});

// === DRAFT ACTIONS ===
ipcMain.handle("game-action-pick-champion", async (event, pickData) => {
  return await validateAndSendGameAction("pick_champion", pickData);
});

ipcMain.handle("game-action-ban-champion", async (event, banData) => {
  return await validateAndSendGameAction("ban_champion", banData);
});

ipcMain.handle("game-action-select-lane", async (event, laneData) => {
  return await validateAndSendGameAction("select_lane", laneData);
});

ipcMain.handle("game-action-confirm-draft", async (event, draftData) => {
  return await validateAndSendGameAction("confirm_draft", draftData);
});

// === GAME IN PROGRESS ACTIONS ===
ipcMain.handle("game-action-vote-winner", async (event, voteData) => {
  return await validateAndSendGameAction("vote_winner", voteData);
});

ipcMain.handle("game-action-report-result", async (event, resultData) => {
  return await validateAndSendGameAction("report_result", resultData);
});

ipcMain.handle("game-action-surrender", async (event, surrenderData) => {
  return await validateAndSendGameAction("surrender", surrenderData);
});

// === SPECTATOR ACTIONS (OPCIONAL - só se tiver ações de moderação) ===
ipcMain.handle("game-action-mute-spectator", async (event, muteData) => {
  return await validateAndSendGameAction("mute_spectator", muteData);
});

ipcMain.handle("game-action-unmute-spectator", async (event, unmuteData) => {
  return await validateAndSendGameAction("unmute_spectator", unmuteData);
});

// === GENERAL ACTIONS ===
ipcMain.handle("game-action-ping", async (event, pingData) => {
  return await validateAndSendGameAction("ping", pingData);
});

ipcMain.handle("game-action-heartbeat", async (event, heartbeatData) => {
  return await validateAndSendGameAction("heartbeat", heartbeatData);
});

// ✅ NOVO: Função para identificar jogador automaticamente ao backend
async function identifyPlayerToBackend(lockfileInfo) {
  try {
    safeLog("🔍 [Electron] Identificando jogador automaticamente...");

    // 1. Buscar summoner do LCU
    const summoner = await performLcuRequest(
      "GET",
      "/lol-summoner/v1/current-summoner"
    );

    if (!summoner || !summoner.gameName || !summoner.tagLine) {
      safeLog("⚠️ [Electron] Summoner não disponível no LCU ainda");
      return;
    }

    // 2. Buscar ranked info (opcional, mas útil)
    const ranked = await performLcuRequest(
      "GET",
      "/lol-ranked/v1/current-ranked-stats"
    ).catch(() => null);

    // 3. Construir payload COMPLETO
    const fullName = `${summoner.gameName}#${summoner.tagLine}`;

    const payload = {
      type: "electron_identify",
      source: "electron_main", // ✅ Fonte confiável!
      timestamp: Date.now(),

      // Dados do summoner
      summonerName: fullName,
      gameName: summoner.gameName,
      tagLine: summoner.tagLine,
      puuid: summoner.puuid, // ✅ CRÍTICO para validação!
      summonerId: summoner.summonerId,
      profileIconId: summoner.profileIconId,
      summonerLevel: summoner.summonerLevel,

      // Dados de ranked (opcional)
      tier: ranked?.queueMap?.RANKED_SOLO_5x5?.tier,
      division: ranked?.queueMap?.RANKED_SOLO_5x5?.division,

      // LCU connection info
      lcuInfo: lockfileInfo
        ? {
            host: lockfileInfo.host || "127.0.0.1",
            port: lockfileInfo.port,
            protocol: lockfileInfo.protocol || "https",
            authToken: btoa(`riot:${lockfileInfo.password}`),
          }
        : null,
    };

    // ✅ NOVO: LOG DETALHADO DA VINCULAÇÃO PLAYER-SESSÃO (ELECTRON → BACKEND)
    safeLog(
      "🔗 [Player-Sessions] ===== ELECTRON → BACKEND: IDENTIFICAÇÃO LCU ====="
    );
    safeLog("🔗 [Player-Sessions] [ELECTRON] Summoner:", fullName);
    safeLog("🔗 [Player-Sessions] [ELECTRON] PUUID:", summoner.puuid);
    safeLog(
      "🔗 [Player-Sessions] [ELECTRON] Summoner ID:",
      summoner.summonerId
    );
    safeLog(
      "🔗 [Player-Sessions] [ELECTRON] Profile Icon:",
      summoner.profileIconId
    );
    safeLog("🔗 [Player-Sessions] [ELECTRON] Level:", summoner.summonerLevel);
    safeLog(
      "🔗 [Player-Sessions] [ELECTRON] Ranked:",
      ranked?.queueMap?.RANKED_SOLO_5x5?.tier,
      ranked?.queueMap?.RANKED_SOLO_5x5?.division
    );
    safeLog(
      "🔗 [Player-Sessions] [ELECTRON] LCU Host:",
      lockfileInfo?.host || "127.0.0.1"
    );
    safeLog("🔗 [Player-Sessions] [ELECTRON] LCU Port:", lockfileInfo?.port);
    safeLog(
      "🔗 [Player-Sessions] [ELECTRON] WebSocket Session ID:",
      wsClient?.readyState === WebSocket.OPEN ? "CONECTADO" : "DESCONECTADO"
    );
    safeLog(
      "🔗 [Player-Sessions] ======================================================"
    );

    // 4. ✅ ENVIAR ao backend
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      wsClient.send(JSON.stringify(payload));
      safeLog("✅ [Electron] Identificação automática enviada:", fullName);

      // Armazenar PUUID localmente para detectar mudanças
      lastKnownPuuid = summoner.puuid;
      lastKnownSummoner = fullName;

      // ✅ NOVO: SOLICITAR LISTA DE SESSÕES ATIVAS APÓS IDENTIFICAÇÃO
      setTimeout(() => {
        requestActiveSessionsList();
        // ✅ NOVO: Habilitar logs unificados [Player-Sessions]
        enableUnifiedLogs();
      }, 2000); // Aguardar 2s para backend processar
    } else {
      safeLog(
        "❌ [Electron] WebSocket não está conectado, não foi possível enviar identificação"
      );
    }
  } catch (err) {
    safeLog("❌ [Electron] Erro ao identificar player:", err);
  }
}

// ✅ NOVO: Monitor de mudanças de summoner (a cada 30s)
function startIdentityMonitor() {
  if (identityMonitorTimer) {
    clearInterval(identityMonitorTimer);
  }

  identityMonitorTimer = setInterval(async () => {
    try {
      const summoner = await performLcuRequest(
        "GET",
        "/lol-summoner/v1/current-summoner"
      );

      if (summoner && summoner.puuid !== lastKnownPuuid) {
        // 🚨 SUMMONER MUDOU!
        safeLog(
          "🔄 [Electron] Summoner mudou! Antigo:",
          lastKnownPuuid,
          "Novo:",
          summoner.puuid
        );
        lastKnownPuuid = summoner.puuid;

        // ✅ Reenviar identificação
        const lockfileInfo = readLockfileInfo();
        if (lockfileInfo) {
          await identifyPlayerToBackend(lockfileInfo);
        }

        // ✅ NOVO: Reinicializar monitoramento proativo após reconexão
        setTimeout(() => {
          initializeProactiveMonitoring();
        }, 2000);
      }
    } catch (e) {
      // LCU desconectado ou erro
      if (lastKnownPuuid !== null) {
        safeLog("⚠️ [Electron] LCU desconectado, limpando identificação");
        lastKnownPuuid = null;
        lastKnownSummoner = null;
      }
    }
  }, 30000); // A cada 30s

  safeLog("✅ [Electron] Monitor de identidade iniciado (30s)");
}

// ✅ NOVO: Parar monitor de identidade
function stopIdentityMonitor() {
  if (identityMonitorTimer) {
    clearInterval(identityMonitorTimer);
    identityMonitorTimer = null;
    safeLog("🛑 [Electron] Monitor de identidade parado");
  }
}

// ✅ NOVO: Solicitar lista de sessões ativas do backend
function requestActiveSessionsList() {
  try {
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      const request = {
        type: "get_active_sessions",
        timestamp: Date.now(),
      };

      wsClient.send(JSON.stringify(request));
      safeLog("📋 [Electron] Solicitando lista de sessões ativas...");
    } else {
      safeLog(
        "❌ [Electron] WebSocket não conectado para solicitar sessões ativas"
      );
    }
  } catch (error) {
    safeLog("❌ [Electron] Erro ao solicitar sessões ativas:", error);
  }
}

// ✅ NOVO: Habilitar logs unificados [Player-Sessions]
function enableUnifiedLogs() {
  try {
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      const request = {
        type: "enable_unified_logs",
        timestamp: Date.now(),
      };

      wsClient.send(JSON.stringify(request));
      safeLog(
        "📋 [Player-Sessions] [UNIFIED-LOGS] Habilitando logs unificados..."
      );

      // ✅ NOVO: Desabilitar Redis temporariamente (logs unificados opcionais)
      safeLog(
        "⚠️ [Player-Sessions] [UNIFIED-LOGS] Redis desabilitado - usando apenas WebSocket"
      );
      unifiedLogsEnabled = true;
    } else {
      safeLog(
        "❌ [Player-Sessions] [UNIFIED-LOGS] WebSocket não conectado para habilitar logs unificados"
      );
    }
  } catch (error) {
    safeLog(
      "❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao habilitar logs unificados:",
      error
    );
  }
}

// ✅ NOVO: Conectar ao Redis para receber logs unificados (se disponível)
function connectToRedisForUnifiedLogs() {
  if (!Redis) {
    safeLog("❌ [Player-Sessions] [UNIFIED-LOGS] Redis não disponível");
    return;
  }

  try {
    // Configuração do Redis (ajustar conforme necessário)
    const redisConfig = {
      socket: {
        host: "localhost", // ou IP do servidor Redis
        port: 6379,
      },
      retryDelayOnFailover: 100,
      maxRetriesPerRequest: 3,
    };

    // Criar subscriber para logs unificados
    redisSubscriber = Redis.createClient(redisConfig);

    redisSubscriber.on("connect", () => {
      safeLog(
        "✅ [Player-Sessions] [UNIFIED-LOGS] Conectado ao Redis para logs unificados"
      );

      // Subscrever ao canal de logs player-sessions
      redisSubscriber.subscribe("player_session_logs");
      safeLog(
        "📋 [Player-Sessions] [UNIFIED-LOGS] Inscrito no canal player_session_logs"
      );
      unifiedLogsEnabled = true;
    });

    redisSubscriber.on("message", (channel, message) => {
      try {
        const logData = JSON.parse(message);
        displayUnifiedLog(logData);
      } catch (error) {
        safeLog(
          "❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao processar log do Redis:",
          error
        );
      }
    });

    redisSubscriber.on("error", (error) => {
      safeLog("❌ [Player-Sessions] [UNIFIED-LOGS] Erro no Redis:", error);
      unifiedLogsEnabled = false;
    });

    redisSubscriber.on("end", () => {
      safeLog("📋 [Player-Sessions] [UNIFIED-LOGS] Conexão Redis encerrada");
      unifiedLogsEnabled = false;
    });

    // Conectar
    redisSubscriber.connect().catch((error) => {
      safeLog(
        "❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao conectar ao Redis:",
        error
      );
      unifiedLogsEnabled = false;
    });
  } catch (error) {
    safeLog(
      "❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao configurar Redis:",
      error
    );
    unifiedLogsEnabled = false;
  }
}

// ✅ NOVO: Função para exibir logs unificados no console do Electron
function displayUnifiedLog(logData) {
  try {
    if (logData.type === "player_session_log") {
      const timestamp = new Date(logData.timestamp).toLocaleTimeString();
      const level = logData.level.toUpperCase();
      const tag = logData.tag || "[Player-Sessions]";
      const message = logData.message || "";

      // Formatar log com timestamp e nível
      const logMessage = `[${timestamp}] ${level} ${tag} ${message}`;

      // Exibir no console do Electron
      safeLog(logMessage);

      // ✅ NOVO: Também exibir no console do DevTools se estiver aberto
      if (mainWindow && mainWindow.webContents) {
        mainWindow.webContents
          .executeJavaScript(
            `
          console.log('${logMessage.replace(/'/g, "\\'")}');
        `
          )
          .catch((err) => {
            // Ignorar erros se DevTools não estiver aberto
          });
      }
    }
  } catch (error) {
    safeLog(
      "❌ [Player-Sessions] [UNIFIED-LOGS] Erro ao processar log unificado:",
      error
    );
  }
}

// Função de reconexão inteligente com backoff exponencial
function scheduleWebSocketReconnect(backendBase) {
  if (wsReconnectTimer) {
    clearTimeout(wsReconnectTimer);
    wsReconnectTimer = null;
  }

  if (wsReconnectAttempts >= WS_MAX_RECONNECT_ATTEMPTS) {
    safeLog("ws gateway: max reconnect attempts reached, stopping");
    return;
  }

  wsReconnectAttempts++;
  const backoff = Math.min(
    WS_BASE_BACKOFF_MS * Math.pow(1.5, wsReconnectAttempts - 1),
    WS_MAX_BACKOFF_MS
  );

  safeLog(
    `ws gateway: scheduling reconnect attempt ${wsReconnectAttempts}/${WS_MAX_RECONNECT_ATTEMPTS} in ${backoff}ms`
  );

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
        wsClient.send(
          JSON.stringify({ type: "ping", ts: new Date().toISOString() })
        );
        safeLog("ws gateway: heartbeat sent");
      } catch (e) {
        safeLog("ws gateway: heartbeat error", String(e));
      }
    }
  }, WS_HEARTBEAT_INTERVAL);

  safeLog("ws gateway: heartbeat started");
}

// Parar heartbeat
function stopWebSocketHeartbeat() {
  if (wsHeartbeatTimer) {
    clearInterval(wsHeartbeatTimer);
    wsHeartbeatTimer = null;
    safeLog("ws gateway: heartbeat stopped");
  }
}

// IPC handler so preload/renderer can send a richer identify payload
ipcMain.handle("lcu:identify", async (_evt, payload) => {
  try {
    lastRendererIdentify = payload;
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      wsClient.send(JSON.stringify({ type: "identify", data: payload }));
    }
    return { success: true };
  } catch (err) {
    safeLog("ipc lcu:identify error", String(err));
    return { success: false, error: String(err) };
  }
});

// ✅ NOVO: Handlers para sistema de storage por usuário
ipcMain.handle("storage:savePlayerData", async (_evt, summonerName, data) => {
  try {
    const userDataPath = app.getPath("userData");
    const storageDir = path.join(userDataPath, "player-cache");

    // Criar diretório se não existir
    if (!fs.existsSync(storageDir)) {
      fs.mkdirSync(storageDir, { recursive: true });
    }

    // Sanitizar nome do arquivo (remover caracteres inválidos)
    const safeName = summonerName.replace(/[^a-zA-Z0-9#-]/g, "_");
    const filePath = path.join(storageDir, `${safeName}.json`);

    // Salvar dados
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2), "utf8");
    safeLog(`💾 [Storage] Dados salvos para: ${summonerName} em ${filePath}`);

    return { success: true, path: filePath };
  } catch (err) {
    safeLog("❌ [Storage] Erro ao salvar dados:", String(err));
    return { success: false, error: String(err) };
  }
});

ipcMain.handle("storage:loadPlayerData", async (_evt, summonerName) => {
  try {
    const userDataPath = app.getPath("userData");
    const storageDir = path.join(userDataPath, "player-cache");
    const safeName = summonerName.replace(/[^a-zA-Z0-9#-]/g, "_");
    const filePath = path.join(storageDir, `${safeName}.json`);

    if (!fs.existsSync(filePath)) {
      safeLog(`📂 [Storage] Arquivo não encontrado para: ${summonerName}`);
      return null;
    }

    const content = fs.readFileSync(filePath, "utf8");
    const data = JSON.parse(content);
    safeLog(`✅ [Storage] Dados carregados para: ${summonerName}`);

    return data;
  } catch (err) {
    safeLog("❌ [Storage] Erro ao carregar dados:", String(err));
    return null;
  }
});

ipcMain.handle("storage:clearPlayerData", async (_evt, summonerName) => {
  try {
    const userDataPath = app.getPath("userData");
    const storageDir = path.join(userDataPath, "player-cache");
    const safeName = summonerName.replace(/[^a-zA-Z0-9#-]/g, "_");
    const filePath = path.join(storageDir, `${safeName}.json`);

    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
      safeLog(`🗑️ [Storage] Dados removidos para: ${summonerName}`);
    }

    return { success: true };
  } catch (err) {
    safeLog("❌ [Storage] Erro ao remover dados:", String(err));
    return { success: false, error: String(err) };
  }
});

ipcMain.handle("storage:listPlayers", async (_evt) => {
  try {
    const userDataPath = app.getPath("userData");
    const storageDir = path.join(userDataPath, "player-cache");

    if (!fs.existsSync(storageDir)) {
      return [];
    }

    const files = fs.readdirSync(storageDir);
    const players = files
      .filter((f) => f.endsWith(".json"))
      .map((f) => f.replace(".json", "").replace(/_/g, " ")); // Reverter sanitização

    safeLog(`📋 [Storage] ${players.length} jogadores encontrados no cache`);
    return players;
  } catch (err) {
    safeLog("❌ [Storage] Erro ao listar jogadores:", String(err));
    return [];
  }
});

function readLockfileInfo() {
  // ✅ NOVO: Usar lista de candidatos atualizada (inclui caminho personalizado)
  for (const p of lockfileCandidates) {
    try {
      if (p && fs.existsSync(p)) {
        const content = fs.readFileSync(p, "utf8");
        const parsed = parseLockfileContent(content);
        if (parsed) {
          safeLog("✅ [Electron] Lockfile encontrado em:", p);
          return {
            host: "127.0.0.1",
            port: parsed.port,
            protocol: parsed.protocol,
            password: parsed.password,
          };
        }
      }
    } catch (err) {
      safeLog("readLockfileInfo error", String(err));
    }
  }
  safeLog(
    "⚠️ [Electron] Nenhum lockfile encontrado nos candidatos:",
    lockfileCandidates
  );
  return null;
}

function performLcuRequest(method, pathname, body) {
  return new Promise((resolve, reject) => {
    const info = readLockfileInfo();
    if (!info) return reject(new Error("lockfile not found"));

    try {
      const isHttps = info.protocol === "https";
      const protocol = isHttps ? https : http;
      const urlBase = `${info.protocol}://${info.host}:${info.port}`;
      const full = new URL(
        pathname.startsWith("/") ? pathname : `/${pathname}`,
        urlBase
      );
      const auth =
        "Basic " + Buffer.from(`riot:${info.password}`).toString("base64");
      const payload = body ? JSON.stringify(body) : undefined;
      const opts = {
        method: method || "GET",
        headers: {
          Authorization: auth,
          "Content-Type": "application/json",
        },
      };
      // if LCU is https with self-signed cert, allow it for local requests
      if (isHttps) {
        try {
          opts.agent = new https.Agent({ rejectUnauthorized: false });
        } catch (e) {
          safeLog(
            "could not create https agent to ignore self-signed certs",
            String(e)
          );
        }
      }

      const req = protocol.request(full, opts, (res) => {
        let data = "";
        res.on("data", (c) => (data += c.toString()));
        res.on("end", () => {
          try {
            const isJson = (res.headers["content-type"] || "").includes(
              "application/json"
            );
            const parsed = isJson && data ? JSON.parse(data) : data;
            if (res.statusCode >= 200 && res.statusCode < 300) resolve(parsed);
            else reject(new Error(`LCU status ${res.statusCode}: ${data}`));
          } catch (e) {
            reject(e);
          }
        });
      });
      req.on("error", (e) => reject(e));
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
      return reject(new Error("Discord bot not initialized"));
    }

    try {
      // For now, return mock data - in real implementation, this would call Discord API
      const mockResponse = {
        status: 200,
        data: {
          message: "Discord request processed",
          path: pathname,
          method: method,
        },
      };
      resolve(mockResponse);
    } catch (err) {
      safeLog("discord request error", String(err));
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
      usersCount: discordUsers.length,
    };
  } catch (err) {
    safeLog("getDiscordStatus error", String(err));
    return {
      isConnected: false,
      error: String(err),
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
    safeLog("getDiscordUsersOnline error", String(err));
    return [];
  }
}

// Initialize Discord bot (mock implementation)
async function initializeDiscordBot() {
  try {
    safeLog("initializing Discord bot...");

    // In real implementation, this would:
    // 1. Load Discord token from settings
    // 2. Initialize Discord client
    // 3. Connect to Discord
    // 4. Join the configured channel
    // 5. Start monitoring users

    // Mock initialization
    discordStatus.isConnected = true;
    discordStatus.botUsername = "LoL Matchmaking Bot";
    discordStatus.channelName = "lol-matchmaking";

    // Mock users
    discordUsers = [
      {
        id: "user1",
        username: "FZD Ratoso",
        displayName: "FZD Ratoso",
        linkedNickname: {
          gameName: "FZD Ratoso",
          tagLine: "fzd",
        },
        inChannel: true,
        joinedAt: new Date().toISOString(),
      },
    ];

    safeLog("Discord bot initialized successfully");
    return true;
  } catch (err) {
    safeLog("Discord bot initialization error", String(err));
    discordStatus.isConnected = false;
    return false;
  }
}

// Start Discord monitoring
function startDiscordMonitoring() {
  if (!discordStatus.isConnected) {
    safeLog("Discord bot not connected, cannot start monitoring");
    return;
  }

  // In real implementation, this would:
  // 1. Monitor voice channel for user joins/leaves
  // 2. Update discordUsers array
  // 3. Send updates to backend via WebSocket

  safeLog("Discord monitoring started");

  // Mock periodic updates
  setInterval(() => {
    if (wsClient && wsClient.readyState === WebSocket.OPEN) {
      const update = {
        type: "discord_users_online",
        users: discordUsers,
        timestamp: new Date().toISOString(),
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
safeLog("🚀 [ELECTRON MAIN] Iniciando processo principal do Electron");
safeLog("📁 [ELECTRON MAIN] Logs sendo salvos em:", LOG_FILE);
