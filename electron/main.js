"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
const path = __importStar(require("path"));
const isDev = process.env['NODE_ENV'] === 'development';
let mainWindow;
let isQuitting = false;
// Função segura para logging que não falha com broken pipe
function safeLog(...args) {
    // Validar se há argumentos para logar
    if (args.length === 0) {
        return; // Não fazer nada se não há argumentos
    }
    try {
        console.log(...args);
    }
    catch (error) {
        // Tratamento específico para erros de logging
        // EPIPE pode ocorrer quando o pipe de saída é fechado
        const errorObj = error;
        if (!errorObj || errorObj.code !== 'EPIPE') {
            // Em desenvolvimento, mostrar outros erros que não sejam EPIPE
            if (isDev) {
                try {
                    console.error('Erro no logging:', errorObj?.message || 'Erro desconhecido');
                }
                catch (secondaryError) {
                    // Se nem console.error funcionar, tentar fallback síncrono e registrar a falha
                    try {
                        const fs = require('fs');
                        fs.writeSync(2, 'Erro secundário no logging: ' + (secondaryError?.message || String(secondaryError)) + '\n');
                    }
                    catch (fsErr) {
                        // Registrar em global para ajudar debugar (última tentativa)
                        try {
                            globalThis.__loggingFailure = String(fsErr);
                        }
                        catch {
                            // nada mais a fazer
                        }
                    }
                }
            }
        }
        // Ignorar silenciosamente erros EPIPE - são esperados em algumas situações
    }
}
function createMainWindow() {
    safeLog('🚀 Criando janela principal do Electron...');
    // Criar a janela principal do Electron
    mainWindow = new electron_1.BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 800,
        minHeight: 600,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            webSecurity: false, // Desabilitar para desenvolvimento
            allowRunningInsecureContent: true,
            experimentalFeatures: false,
            backgroundThrottling: false,
            preload: path.join(__dirname, 'preload.js') // Adicionar preload script
        },
        show: false,
        titleBarStyle: 'default',
        title: 'LOL Matchmaking - Carregando...',
        backgroundColor: '#1e3c72'
    });
    // Configurar handlers IPC
    setupIpcHandlers();
    // Escolher URL do backend a partir de variáveis de ambiente (BACKEND_URL ou BACKEND_HOST/PORT), com fallback local
    const baseUrl = process.env['BACKEND_URL'] || (process.env['BACKEND_HOST'] ? `http://${process.env['BACKEND_HOST']}:${process.env['BACKEND_PORT'] || '8080'}` : 'http://localhost:8080');
    const startUrl = `${baseUrl}/api/`; // ✅ CORREÇÃO: Adicionar /api/ pois o frontend é servido pelo backend nesta rota
    safeLog('🔧 Backend URL base:', baseUrl);
    safeLog('🔧 Frontend URL selecionada:', startUrl);
    // ✅ NOVO: Definir variável de ambiente para o renderer process
    process.env['BACKEND_URL'] = baseUrl;
    safeLog('🚀 Electron iniciando...');
    safeLog('📡 Carregando URL:', startUrl);
    safeLog('🔧 Preload script:', path.join(__dirname, 'preload.js'));
    loadFrontendWithRetry(startUrl);
    // Event handlers com tratamento robusto de erros
    mainWindow.once('ready-to-show', () => {
        safeLog('📱 Janela pronta para exibir');
        if (mainWindow && !mainWindow.isDestroyed()) {
            try {
                mainWindow.show();
                mainWindow.focus();
                mainWindow.setTitle('LOL Matchmaking');
            }
            catch (showError) {
                const error = showError;
                safeLog('❌ Erro ao mostrar janela:', error.message);
            }
        }
    });
    mainWindow.webContents.once('dom-ready', () => {
        safeLog('🌐 DOM carregado - conteúdo pronto!');
        if (mainWindow && !mainWindow.isDestroyed()) {
            try {
                mainWindow.show();
                mainWindow.focus();
                mainWindow.webContents.executeJavaScript(`
                    console.log('🎮 Frontend carregado no Electron!');
                    console.log('URL atual:', window.location.href);
                    console.log('DOM pronto:', document.readyState);
                `).catch((jsError) => {
                    safeLog('⚠️ Erro ao executar JavaScript:', jsError.message);
                });
            }
            catch (domError) {
                const error = domError;
                safeLog('❌ Erro no evento dom-ready:', error.message);
            }
        }
    });
    mainWindow.on('closed', () => {
        safeLog('🗂️ Janela fechada');
        mainWindow = null;
    });
    mainWindow.on('unresponsive', () => {
        safeLog('⚠️ Janela não está respondendo');
    });
    mainWindow.on('responsive', () => {
        safeLog('✅ Janela voltou a responder');
    });
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
        safeLog('🔗 Link externo detectado:', url);
        // Usar Promise.resolve para tratar shell.openExternal corretamente
        Promise.resolve(electron_1.shell.openExternal(url)).catch((shellError) => {
            safeLog('❌ Erro ao abrir link externo:', shellError.message);
        });
        return { action: 'deny' };
    });
    mainWindow.webContents.on('did-start-loading', () => {
        safeLog('⏳ Iniciando carregamento...');
    });
    mainWindow.webContents.on('did-finish-load', () => {
        safeLog('✅ Carregamento finalizado');
        if (mainWindow && !mainWindow.isDestroyed()) {
            try {
                mainWindow.show();
            }
            catch (showError) {
                const error = showError;
                safeLog('❌ Erro ao mostrar janela após carregamento:', error.message);
            }
        }
    });
    mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL) => {
        safeLog('❌ Falha no carregamento:', errorCode, errorDescription, validatedURL);
        const retryTimeout = setTimeout(() => {
            if (mainWindow && !mainWindow.isDestroyed()) {
                try {
                    loadFrontendWithRetry(startUrl);
                }
                catch (retryError) {
                    const error = retryError;
                    safeLog('❌ Erro ao tentar recarregar:', error.message);
                }
            }
        }, 3000);
        // Evitar vazamento de memória limpando timeout
        if (mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.once('closed', () => {
                clearTimeout(retryTimeout);
            });
        }
    });
    mainWindow.webContents.on('render-process-gone', (_event, details) => {
        safeLog('💥 Render process gone:', details && details.reason, 'exitCode=', details && details.exitCode);
        if (!isQuitting && mainWindow && !mainWindow.isDestroyed()) {
            try {
                const response = electron_1.dialog.showMessageBoxSync(mainWindow, {
                    type: 'error',
                    title: 'Erro no Electron',
                    message: 'A aplicação travou. Deseja reiniciar?',
                    buttons: ['Reiniciar', 'Fechar']
                });
                if (response === 0) {
                    try {
                        mainWindow.reload();
                    }
                    catch (reloadError) {
                        const error = reloadError;
                        safeLog('❌ Erro ao recarregar após crash:', error.message);
                        electron_1.app.quit();
                    }
                }
                else {
                    electron_1.app.quit();
                }
            }
            catch (dialogError) {
                const error = dialogError;
                safeLog('❌ Erro ao mostrar diálogo de crash:', error.message);
                electron_1.app.quit();
            }
        }
    });
    mainWindow.webContents.on('devtools-opened', () => {
        safeLog('🔧 DevTools aberto');
    });
    mainWindow.webContents.on('devtools-closed', () => {
        safeLog('🔧 DevTools fechado');
    });
}
// Função auxiliar para mostrar janela com tratamento de erro
function showMainWindow() {
    if (mainWindow && !mainWindow.isDestroyed()) {
        try {
            mainWindow.show();
            mainWindow.focus();
        }
        catch (showError) {
            const error = showError;
            safeLog('❌ Erro ao mostrar janela:', error.message);
        }
    }
}
function loadFrontendWithRetry(url, attempt = 1, maxAttempts = 5) {
    // Validar parâmetros de entrada
    if (!url) {
        safeLog('❌ URL inválida fornecida para carregamento');
        return;
    }
    if (!mainWindow || mainWindow.isDestroyed()) {
        safeLog('❌ Janela foi destruída, cancelando carregamento');
        return;
    }
    safeLog(`🔄 Tentativa ${attempt}/${maxAttempts} de carregar: ${url}`);
    mainWindow.loadURL(url, {
        userAgent: 'LOL-Matchmaking-Electron'
    }).then(() => {
        safeLog('✅ Frontend carregado com sucesso!');
        showMainWindow();
    }).catch((error) => {
        safeLog(`❌ Erro na tentativa ${attempt}:`, error?.message || 'Erro desconhecido');
        if (attempt < maxAttempts) {
            const delay = 2000;
            safeLog(`⏳ Tentando novamente em ${delay}ms...`);
            const retryTimeout = setTimeout(() => {
                try {
                    loadFrontendWithRetry(url, attempt + 1, maxAttempts);
                }
                catch (retryError) {
                    const err = retryError;
                    safeLog('❌ Erro na nova tentativa:', err?.message || 'Erro desconhecido');
                }
            }, delay);
            // Limpar timeout se janela for destruída
            if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.once('closed', () => {
                    clearTimeout(retryTimeout);
                });
            }
        }
        else {
            safeLog('❌ Todas as tentativas falharam, carregando página de erro');
            try {
                loadErrorPage();
            }
            catch (errorPageError) {
                const err = errorPageError;
                safeLog('❌ Erro ao carregar página de erro:', err?.message || 'Erro desconhecido');
            }
        }
    });
}
function loadErrorPage() {
    if (!mainWindow || mainWindow.isDestroyed()) {
        return;
    }
    const errorHtml = `
    <!DOCTYPE html>
    <html lang="pt-BR">
    <head>
        <title>LOL Matchmaking - Erro de Conexão</title>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body {
                font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                background: linear-gradient(135deg, #1e3c72 0%, #2a5298 100%);
                color: white;
                padding: 20px;
                display: flex;
                justify-content: center;
                align-items: center;
                min-height: 100vh;
                text-align: center;
            }
            .container {
                background: rgba(255, 255, 255, 0.1);
                padding: 40px;
                border-radius: 15px;
                backdrop-filter: blur(10px);
                max-width: 600px;
                border: 1px solid rgba(255, 255, 255, 0.2);
            }
            h1 { color: #ffd700; margin-bottom: 20px; font-size: 2.5em; }
            .status { margin: 20px 0; line-height: 1.6; }
            .retry-btn {
                background: #ffd700;
                color: #1e3c72;
                border: none;
                padding: 12px 24px;
                border-radius: 8px;
                font-size: 16px;
                font-weight: bold;
                cursor: pointer;
                margin: 10px;
                transition: background 0.3s;
            }
            .retry-btn:hover { background: #ffed4e; }
            .retry-btn:focus { outline: 2px solid #fff; outline-offset: 2px; }
            code { 
                background: rgba(0,0,0,0.3); 
                padding: 5px 10px; 
                border-radius: 5px; 
                font-family: 'Courier New', monospace;
            }
            .loading {
                display: inline-block;
                width: 20px;
                height: 20px;
                border: 3px solid rgba(255,215,0,0.3);
                border-radius: 50%;
                border-top-color: #ffd700;
                animation: spin 1s ease-in-out infinite;
            }
            @keyframes spin {
                to { transform: rotate(360deg); }
            }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>🎮 LOL Matchmaking</h1>
            <p>❌ Não foi possível conectar ao servidor</p>
            <div class="status">
                <p>Verifique se o Spring Boot está executando em:</p>
                <code>http://localhost:8080</code>
                <br><br>
                <div class="loading"></div>
                <p>Tentando reconectar automaticamente...</p>
            </div>
            <button class="retry-btn" onclick="location.reload()" type="button">🔄 Tentar Agora</button>
            <button class="retry-btn" onclick="openExternalBrowser()" type="button">🌐 Abrir no Navegador</button>
        </div>
        <script>
            function openExternalBrowser() {
                console.log('Tentando abrir navegador externo...');
                try {
                    window.open('http://localhost:8080', '_blank');
                } catch (error) {
                    console.error('Erro ao abrir navegador externo:', error);
                }
            }
            
            setTimeout(() => {
                location.reload();
            }, 10000);
        </script>
    </body>
    </html>`;
    mainWindow.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(errorHtml))
        .catch((loadError) => {
        safeLog('❌ Erro ao carregar página de erro:', loadError.message);
    });
    if (!mainWindow.isDestroyed()) {
        mainWindow.show();
    }
}
// Configurar menu da aplicação
function createAppMenu() {
    const template = [
        {
            label: 'Arquivo',
            submenu: [
                {
                    label: 'Recarregar',
                    accelerator: 'F5',
                    click: () => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            safeLog('🔄 Recarregando aplicação...');
                            mainWindow.reload();
                        }
                    }
                },
                {
                    label: 'DevTools',
                    accelerator: 'F12',
                    click: () => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            safeLog('🔧 Alternando DevTools...');
                            try {
                                if (mainWindow.webContents.isDevToolsOpened()) {
                                    mainWindow.webContents.closeDevTools();
                                }
                                else {
                                    mainWindow.webContents.openDevTools({ mode: 'detach' });
                                }
                            }
                            catch (error) {
                                const err = error;
                                safeLog('❌ Erro ao abrir DevTools:', err.message);
                            }
                        }
                    }
                },
                { type: 'separator' },
                {
                    label: 'Sair',
                    accelerator: process.platform === 'darwin' ? 'Cmd+Q' : 'Alt+F4',
                    click: () => {
                        isQuitting = true;
                        electron_1.app.quit();
                    }
                }
            ]
        },
        {
            label: 'Visualizar',
            submenu: [
                {
                    label: 'Zoom In',
                    accelerator: 'CmdOrCtrl+=',
                    click: () => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            try {
                                const currentZoom = mainWindow.webContents.getZoomLevel();
                                mainWindow.webContents.setZoomLevel(currentZoom + 0.5);
                            }
                            catch (error) {
                                const err = error;
                                safeLog('❌ Erro ao aumentar zoom:', err.message);
                            }
                        }
                    }
                },
                {
                    label: 'Zoom Out',
                    accelerator: 'CmdOrCtrl+-',
                    click: () => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            try {
                                const currentZoom = mainWindow.webContents.getZoomLevel();
                                mainWindow.webContents.setZoomLevel(currentZoom - 0.5);
                            }
                            catch (error) {
                                const err = error;
                                safeLog('❌ Erro ao diminuir zoom:', err.message);
                            }
                        }
                    }
                },
                {
                    label: 'Reset Zoom',
                    accelerator: 'CmdOrCtrl+0',
                    click: () => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            try {
                                mainWindow.webContents.setZoomLevel(0);
                            }
                            catch (error) {
                                const err = error;
                                safeLog('❌ Erro ao resetar zoom:', err.message);
                            }
                        }
                    }
                }
            ]
        }
    ];
    try {
        const menu = electron_1.Menu.buildFromTemplate(template);
        electron_1.Menu.setApplicationMenu(menu);
    }
    catch (error) {
        const err = error;
        safeLog('❌ Erro ao criar menu:', err.message);
    }
}
// Função para limpar recursos antes de sair
function cleanup() {
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.removeAllListeners();
        mainWindow.webContents.removeAllListeners();
    }
}
// Eventos do aplicativo
electron_1.app.whenReady().then(() => {
    safeLog('⚡ Electron App Ready!');
    try {
        createMainWindow();
        createAppMenu();
    }
    catch (error) {
        const err = error;
        safeLog('❌ Erro ao inicializar aplicação:', err.message);
        electron_1.app.quit();
    }
    electron_1.app.on('activate', () => {
        if (electron_1.BrowserWindow.getAllWindows().length === 0) {
            try {
                createMainWindow();
            }
            catch (error) {
                const err = error;
                safeLog('❌ Erro ao recriar janela:', err.message);
            }
        }
    });
}).catch((error) => {
    safeLog('❌ Erro na inicialização do app:', error.message);
    electron_1.app.quit();
});
electron_1.app.on('window-all-closed', () => {
    safeLog('🚪 Todas as janelas fechadas');
    cleanup();
    if (process.platform !== 'darwin') {
        isQuitting = true;
        electron_1.app.quit();
    }
});
electron_1.app.on('before-quit', () => {
    safeLog('👋 Aplicação sendo encerrada...');
    isQuitting = true;
    cleanup();
});
// Prevenir múltiplas instâncias
const gotTheLock = electron_1.app.requestSingleInstanceLock();
if (!gotTheLock) {
    safeLog('❌ Já existe uma instância rodando - fechando esta instância');
    electron_1.app.quit();
}
else {
    electron_1.app.on('second-instance', () => {
        safeLog('🔍 Segunda instância detectada - focando janela principal');
        if (mainWindow && !mainWindow.isDestroyed()) {
            if (mainWindow.isMinimized()) {
                mainWindow.restore();
            }
            mainWindow.focus();
        }
    });
}
// Tratamento de erros não capturados aprimorado
process.on('uncaughtException', (error) => {
    if (error.code !== 'EPIPE') {
        safeLog('💥 Erro não capturado:', error.message);
        if (error.stack) {
            safeLog('Stack trace:', error.stack);
        }
        if (!isDev) {
            // Em produção, encerrar graciosamente
            try {
                cleanup();
                electron_1.app.quit();
            }
            catch (quitError) {
                const qe = quitError;
                safeLog('❌ Erro durante cleanup/quit:', qe?.message || String(quitError));
                // tentar forçar saída se cleanup falhar
                try {
                    process.exit(1);
                }
                catch (exitErr) {
                    safeLog('❌ Erro ao forçar exit:', String(exitErr));
                }
            }
        }
    }
});
// Tratamento de sinal de interrupção (Ctrl+C)
process.on('SIGINT', () => {
    safeLog('🛑 Sinal SIGINT recebido - encerrando aplicação...');
    try {
        cleanup();
        electron_1.app.quit();
    }
    catch (sigErr) {
        safeLog('❌ Erro ao tratar SIGINT:', String(sigErr));
        try {
            process.exit(1);
        }
        catch { }
    }
});
process.on('SIGTERM', () => {
    safeLog('🛑 Sinal SIGTERM recebido - encerrando aplicação...');
    try {
        cleanup();
        electron_1.app.quit();
    }
    catch (sigErr) {
        safeLog('❌ Erro ao tratar SIGTERM:', String(sigErr));
        try {
            process.exit(1);
        }
        catch { }
    }
});
safeLog('🚀 Electron main.ts carregado com sucesso!');
// Configurar handlers IPC para comunicação segura entre main e renderer
function setupIpcHandlers() {
    // Handler para abrir links externos
    electron_1.ipcMain.handle('shell:openExternal', async (_event, url) => {
        try {
            await electron_1.shell.openExternal(url);
            safeLog('🔗 Link externo aberto:', url);
        }
        catch (error) {
            const err = error;
            safeLog('❌ Erro ao abrir link externo:', err.message);
            throw err;
        }
    });
    // Handler para ping/pong de teste
    electron_1.ipcMain.handle('app:ping', async () => {
        safeLog('📡 Ping recebido do renderer');
        return 'pong';
    });
    // Handler para obter informações do sistema
    electron_1.ipcMain.handle('app:getSystemInfo', async () => {
        return {
            electronVersion: process.versions.electron,
            nodeVersion: process.versions.node,
            platform: process.platform,
            arch: process.arch
        };
    });
    safeLog('🔧 Handlers IPC configurados');
}
//# sourceMappingURL=main.js.map