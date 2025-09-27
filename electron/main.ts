import { app, BrowserWindow, Menu, shell, dialog, Event, ipcMain, MenuItemConstructorOptions } from 'electron';

// Workaround: disable Chromium NetworkService in renderer which can cause loopback requests to be aborted
// See: consistent net::ERR_ABORTED on renderer -> forcing network to use older stack may help in some environments
// Minimal default: DO NOT apply aggressive Chromium flags by default.
// Flags that are useful during development are applied below only when `isDev` is true.
import * as path from 'path';
import * as fs from 'fs';
import * as http from 'http';

const isDev: boolean = process.env['NODE_ENV'] === 'development';
// By default keep webRequest interceptors disabled; enable only when explicitly requested
const enableInterceptors: boolean = process.env['ENABLE_ELECTRON_INTERCEPTORS'] === '1';

let mainWindow: BrowserWindow | null;
let isQuitting: boolean = false;

// Sistema robusto de logging
class ElectronLogger {
    private readonly logFilePath: string;
    private logBuffer: string[] = [];
    private flushTimeout: NodeJS.Timeout | null = null;

    constructor() {
        this.logFilePath = path.join(process.cwd(), 'electron.log');
        this.initializeLogFile();
    }

    private initializeLogFile(): void {
        try {
            const timestamp = new Date().toISOString();
            const separator = '\n' + '='.repeat(80) + '\n';
            const header = `${separator}[${timestamp}] ELECTRON SESSION STARTED${separator}`;
            fs.appendFileSync(this.logFilePath, header);
        } catch (error) {
            console.error('❌ Erro ao inicializar arquivo de log:', error);
        }
    }

    private formatLogEntry(source: string, level: string, message: string, data?: any): string {
        const timestamp = new Date().toISOString();
        let formattedMessage = message;

        if (data !== undefined) {
            try {
                const dataStr = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
                formattedMessage += ` | ${dataStr}`;
            } catch {
                formattedMessage += ` | [Não serializável]`;
            }
        }

        return `[${timestamp}] [${source}:${level}] ${formattedMessage}\n`;
    }

    private flushBuffer(): void {
        if (this.logBuffer.length === 0) return;

        try {
            const content = this.logBuffer.join('');
            fs.appendFileSync(this.logFilePath, content);
            this.logBuffer = [];
        } catch (error) {
            console.error('❌ Erro ao salvar logs:', error);
        }
    }

    log(source: string, level: string, message: string, data?: any): void {
        const entry = this.formatLogEntry(source, level, message, data);
        this.logBuffer.push(entry);

        // Console output para desenvolvimento
        if (isDev) {
            const prefix = `[${source}:${level}]`;
            switch (level.toLowerCase()) {
                case 'error': console.error(prefix, message, data || ''); break;
                case 'warn': console.warn(prefix, message, data || ''); break;
                case 'info': console.info(prefix, message, data || ''); break;
                default: console.log(prefix, message, data || ''); break;
            }
        }

        // Flush imediato para erros críticos
        if (level === 'error' || this.logBuffer.length > 50) {
            this.flushBuffer();
        } else {
            // Flush com delay para otimizar I/O
            if (this.flushTimeout) clearTimeout(this.flushTimeout);
            this.flushTimeout = setTimeout(() => this.flushBuffer(), 1000);
        }
    }

    flushSync(): void {
        if (this.flushTimeout) {
            clearTimeout(this.flushTimeout);
            this.flushTimeout = null;
        }
        this.flushBuffer();
    }
}

const logger = new ElectronLogger();

// Função segura para logging que não falha com broken pipe
function safeLog(...args: unknown[]): void {
    // Validar se há argumentos para logar
    if (args.length === 0) {
        return; // Não fazer nada se não há argumentos
    }

    try {
        const message = args.map(arg => {
            if (typeof arg === 'string') return arg;
            try {
                return JSON.stringify(arg);
            } catch {
                return String(arg);
            }
        }).join(' ');

        logger.log('Main', 'info', message);
    } catch (error: unknown) {
        // Tratamento específico para erros de logging
        // EPIPE pode ocorrer quando o pipe de saída é fechado
        const errorObj = error as NodeJS.ErrnoException;
        if (errorObj?.code !== 'EPIPE') {
            // Em desenvolvimento, mostrar outros erros que não sejam EPIPE
            if (isDev) {
                try {
                    console.error('Erro no logging:', errorObj?.message || 'Erro desconhecido');
                } catch (secondaryError: unknown) {
                    // Se nem console.error funcionar, tentar fallback síncrono e registrar a falha
                    try {
                        const fs = require('fs');
                        fs.writeSync(2, 'Erro secundário no logging: ' + ((secondaryError as Error)?.message || String(secondaryError)) + '\n');
                    } catch (fsErr: unknown) {
                        // Registrar em global para ajudar debugar (última tentativa)
                        try {
                            (globalThis as any).__loggingFailure = String(fsErr);
                        } catch {
                            // nada mais a fazer
                        }
                    }
                }
            }
        }
        // Ignorar silenciosamente erros EPIPE - são esperados em algumas situações
    }
}

// Testar se o backend é alcançável a partir do processo main
function testBackendReachable(url: string, timeoutMs: number = 1500): Promise<boolean> {
    try {
        const { URL } = require('url');
        const uBase = new URL(url);
        // prefer hitting the health endpoint to get a meaningful status
        const healthPath = '/actuator/health';
        const pathToCheck = uBase.pathname && uBase.pathname !== '/' ? `${uBase.pathname.replace(/\/+$/, '')}${healthPath}` : healthPath;
        const u = new URL(pathToCheck, `${uBase.protocol}//${uBase.hostname}${uBase.port ? ':' + uBase.port : ''}`);
        const httpMod = u.protocol === 'https:' ? require('https') : require('http');

        return new Promise<boolean>((resolve) => {
            const options = { method: 'GET', hostname: u.hostname, port: u.port || (u.protocol === 'https:' ? 443 : 80), path: u.pathname + (u.search || ''), timeout: timeoutMs };
            let settled = false;
            const req = httpMod.request(options, (res: any) => {
                try {
                    const status = res && res.statusCode ? Number(res.statusCode) : 0;
                    safeLog('🔍 TestBackend: status', status, 'for', u.href);
                    if (!settled) {
                        settled = true;
                        resolve(status >= 200 && status < 300);
                    }
                } catch (err) {
                    if (!settled) { settled = true; resolve(false); }
                }
            });

            req.on('error', (err: any) => {
                safeLog('🔍 Teste backend falhou:', String(err));
                if (!settled) { settled = true; resolve(false); }
            });

            req.on('timeout', () => {
                try { req.destroy(); } catch { }
                safeLog('🔍 Teste backend timeout for', u.href);
                if (!settled) { settled = true; resolve(false); }
            });

            try { req.end(); } catch (e) { safeLog('🔍 Erro ao finalizar request de teste:', String(e)); if (!settled) { settled = true; resolve(false); } }
        });
    }
    catch (e) {
        safeLog('🔍 Erro ao testar backend reachability:', (e as Error)?.message || String(e));
        return Promise.resolve(false);
    }
}

async function createMainWindow(): Promise<void> {
    safeLog('🚀 Criando janela principal do Electron...');

    // Limpar cache em caso de problemas de permissão
    try {
        const { session } = require('electron');
        // Definir proxy direto para evitar que proxies de sistema bloqueiem requests locais
        try {
            if (session && session.defaultSession && typeof session.defaultSession.setProxy === 'function') {
                // aguardar setProxy resolver antes de prosseguir
                await session.defaultSession.setProxy({ proxyRules: 'direct://' });
                safeLog('🔧 Proxy da sessão definido para direct://');
            }
        } catch (proxyEx) {
            safeLog('⚠️ setProxy não disponível ou falhou:', (proxyEx as Error)?.message || String(proxyEx));
        }

        try {
            await session.defaultSession.clearCache();
            safeLog('🧹 Cache do Electron limpo');
        } catch (cacheError) {
            safeLog('⚠️ Não foi possível limpar cache:', (cacheError as Error)?.message || String(cacheError));
        }
    } catch (e) {
        safeLog('⚠️ Erro ao preparar sessão do Electron:', (e as Error)?.message || String(e));
    }

    // Criar a janela principal do Electron
    const disableInterceptors = process.env['DISABLE_ELECTRON_INTERCEPTORS'] === '1';
    if (disableInterceptors) safeLog('⚠️ DISABLE_ELECTRON_INTERCEPTORS=1 -> preload and webRequest handlers will be disabled for testing');

    const webPrefs: Electron.BrowserWindowConstructorOptions['webPreferences'] = {
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: false, // ✅ Garantir Node APIs no preload (require/fs)
        // Manter webSecurity em false para desenvolvimento/local fallback; revert em produção
        webSecurity: false,
        allowRunningInsecureContent: true,
        experimentalFeatures: false,
        backgroundThrottling: false,
        // Configurações para resolver problemas de GPU/cache no Windows
        offscreen: false,
        spellcheck: false
    };
    // Sempre injetar o preload (expor electronAPI é obrigatório para o renderer funcionar corretamente)
    // Permitimos desabilitar o preload via env var DISABLE_PRELOAD=1 para diagnosticar problemas de injeção
    if (!process.env['DISABLE_PRELOAD'] || process.env['DISABLE_PRELOAD'] !== '1') {
        webPrefs.preload = path.join(__dirname, 'preload.js');
    } else {
        safeLog('⚠️ DISABLE_PRELOAD=1 -> preload not injected for diagnostic purposes');
    }

    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 800,
        minHeight: 600,
        webPreferences: webPrefs,
        show: false, // Não mostrar a janela até que o frontend carregue
        titleBarStyle: 'default',
        title: 'LOL Matchmaking - Carregando...',
        backgroundColor: '#ffffff',
        // Configurações de janela para melhor compatibilidade no Windows
        useContentSize: true,
        center: true,
        resizable: true,
        movable: true,
        minimizable: true,
        maximizable: true,
        closable: true,
        focusable: true,
        alwaysOnTop: false,
        fullscreenable: true,
        skipTaskbar: false,
        // Configurações críticas para Windows
        frame: true,
        transparent: false,
        hasShadow: true,
        roundedCorners: true,
        thickFrame: true
        // Removidas propriedades problemáticas: vibrancy e visualEffectState
    });

    safeLog('✅ Janela BrowserWindow criada');

    // A janela será exibida apenas quando o frontend estiver carregado (ready-to-show / dom-ready handlers)

    // Configurar handlers IPC
    setupIpcHandlers();

    // Escolher URL do backend a partir de variáveis de ambiente (BACKEND_URL ou BACKEND_HOST/PORT), com fallback local
    let baseUrl = process.env['BACKEND_URL'] || (process.env['BACKEND_HOST'] ? `http://${process.env['BACKEND_HOST']}:${process.env['BACKEND_PORT'] || '8080'}` : 'http://127.0.0.1:8080');

    // Normalizar a baseUrl: remover '/api' final e barras repetidas para evitar carregar a API no lugar do frontend
    try {
        baseUrl = String(baseUrl).replace(/\/api\/?$/i, '').replace(/\/+$/i, '');
    }
    catch (e) {
        // se algo der errado, log e fallback para localhost
        safeLog('⚠️ Falha ao normalizar baseUrl, usando fallback http://127.0.0.1:8080:', (e as Error)?.message || String(e));
        baseUrl = 'http://127.0.0.1:8080';
    }

    // Forçar resolver localhost para 127.0.0.1 — evita problemas onde 'localhost' resolve para ::1 e o servidor não escuta em IPv6
    try {
        // substituir "localhost" por 127.0.0.1 de forma segura (captura a porta em $1 se houver)
        baseUrl = baseUrl.replace(/:\/\/localhost(?::(\d+))?/i, '://127.0.0.1$1');
    }
    catch (e) {
        safeLog('⚠️ Falha ao normalizar baseUrl:', (e as Error)?.message || String(e));
    }

    const startUrl = `${baseUrl}/`; // Frontend é servido na raiz / pelo Spring Boot
    safeLog('🔧 Backend URL base (normalizada):', baseUrl);
    safeLog('🔧 Frontend URL selecionada:', startUrl);

    // Expor a URL do backend para o renderer: usar base com barra no final (sem /api)
    process.env['BACKEND_URL'] = `${baseUrl}/`;

    // Realizar checagem robusta do /actuator/health antes de carregar o frontend.
    // Se o health não responder com 2xx após tentativas, mostrar a página de erro (sem fallback silencioso).
    const healthRetries = 5;
    const healthDelayMs = 1000;

    (async function runHealthChecksAndLoad() {
        for (let i = 1; i <= healthRetries; i++) {
            safeLog(`🔎 Health check attempt ${i}/${healthRetries} -> ${baseUrl}/actuator/health`);
            const ok = await testBackendReachable(startUrl, 2000);
            if (ok) {
                safeLog('✅ Backend health OK, carregando frontend em', startUrl);
                loadFrontendWithRetry(startUrl);
                return;
            }
            safeLog('⚠️ Health check failed, retrying in', healthDelayMs, 'ms');
            await new Promise(r => setTimeout(r, healthDelayMs));
        }

        safeLog('❌ Backend não respondeu após tentativas, exibindo página de erro sem fallback.');
        try { loadErrorPage(); } catch (e) { safeLog('❌ Falha ao carregar página de erro:', (e as Error)?.message || String(e)); }
    })();

    safeLog('🚀 Electron iniciando...');
    safeLog('📡 Carregando URL:', startUrl);
    safeLog('🔧 Preload script:', path.join(__dirname, 'preload.js'));

    // Connectivity check is handled by runHealthChecksAndLoad() above.
    // Avoid calling loadFrontendWithRetry from multiple concurrent places to prevent navigation aborts.

    // Configurar interceptação completa de logs do WebContents
    if (enableInterceptors) {
        setupWebContentsLogging();
    } else {
        safeLog('🔕 WebContents network interceptors disabled (ENABLE_ELECTRON_INTERCEPTORS != 1)');
    }

    // Event handlers com tratamento robusto de erros
    mainWindow.once('ready-to-show', (): void => {
        safeLog('📱 Janela pronta para exibir');
        if (mainWindow && !mainWindow.isDestroyed()) {
            try {
                mainWindow.show();
                mainWindow.focus();
                mainWindow.setTitle('LOL Matchmaking');
                // Auto-open DevTools when interceptors are enabled for diagnostics
                if (enableInterceptors && typeof mainWindow.webContents.openDevTools === 'function') {
                    try { mainWindow.webContents.openDevTools({ mode: 'right' }); } catch { }
                }
            } catch (showError: unknown) {
                const error = showError as Error;
                safeLog('❌ Erro ao mostrar janela:', error.message);
            }
        }
    });

    mainWindow.webContents.once('dom-ready', (): void => {
        safeLog('🌐 DOM carregado - conteúdo pronto!');
        if (mainWindow && !mainWindow.isDestroyed()) {
            try {
                // Mostrar a janela somente após pequenas verificações
                mainWindow.show();
                mainWindow.focus();

                mainWindow.webContents.executeJavaScript(`
                    console.log('🎮 Frontend carregado no Electron!');
                    console.log('URL atual:', window.location.href);
                    console.log('DOM pronto:', document.readyState);
                    // Attempt proxy request from renderer to validate IPC proxy
                    (async function(){
                        try {
                            const hasProxy = !!(window as any).electronAPI && typeof (window as any).electronAPI.proxyRequest === 'function';
                            const hasElectronAPI = !!(window as any).electronAPI;
                            try { if (window.electronAPI && window.electronAPI.sendLog) window.electronAPI.sendLog('info', '🔎 renderer has electronAPI ? ' + hasElectronAPI + ' has proxy ? ' + hasProxy); } catch {}
                            // Test native fetch
                            try {
                                const f = await fetch('http://127.0.0.1:8080/actuator/health', { mode: 'cors' });
                                try { if (window.electronAPI && window.electronAPI.sendLog) window.electronAPI.sendLog('info', 'fetch ok ' + f.status); } catch {}
                            } catch(fetchErr) {
                                try { if (window.electronAPI && window.electronAPI.sendLog) window.electronAPI.sendLog('warn', 'fetch failed ' + (fetchErr && fetchErr.message ? fetchErr.message : fetchErr)); } catch {}
                            }
                            if (hasProxy) {
                                try {
                                    const res = await (window as any).electronAPI.proxyRequest({ url: 'http://127.0.0.1:8080/actuator/health', method: 'GET', timeoutMs: 3000 });
                                    try { if (window.electronAPI && window.electronAPI.sendLog) window.electronAPI.sendLog('info', '🧪 proxyRequest result: ' + (res && res.status) + ' ' + (res && res.body ? (typeof res.body === 'string' ? res.body.substring(0,200) : JSON.stringify(res.body).substring(0,200)) : '')); } catch {}
                                } catch (e) {
                                    try { if (window.electronAPI && window.electronAPI.sendLog) window.electronAPI.sendLog('error', '❌ proxyRequest failed: ' + String(e)); } catch {}
                                }
                            }
                        } catch (e) {
                            try { if (window.electronAPI && window.electronAPI.sendLog) window.electronAPI.sendLog('error', '❌ renderer probe failed: ' + String(e)); } catch {}
                        }
                    })();
                `).catch((jsError: Error): void => {
                    safeLog('⚠️ Erro ao executar JavaScript:', jsError.message);
                });
            } catch (domError: unknown) {
                const error = domError as Error;
                safeLog('❌ Erro no evento dom-ready:', error.message);
            }
        }
    });

    mainWindow.on('closed', (): void => {
        safeLog('🗂️ Janela fechada');
        mainWindow = null;
    });

    mainWindow.on('unresponsive', (): void => {
        safeLog('⚠️ Janela não está respondendo');
    });

    mainWindow.on('responsive', (): void => {
        safeLog('✅ Janela voltou a responder');
    });

    mainWindow.webContents.setWindowOpenHandler(({ url }): { action: 'deny' } => {
        safeLog('🔗 Link externo detectado:', url);
        // Usar Promise.resolve para tratar shell.openExternal corretamente
        Promise.resolve(shell.openExternal(url)).catch((shellError: Error): void => {
            safeLog('❌ Erro ao abrir link externo:', shellError.message);
        });
        return { action: 'deny' };
    });

    // Additional navigation/network diagnostics to help trace net::ERR_ABORTED
    mainWindow.webContents.on('did-start-navigation', (...args: any[]) => {
        try { safeLog('🔎 did-start-navigation args:', args); } catch { }
    });

    mainWindow.webContents.on('will-redirect', (_event, navigationUrl) => {
        try { safeLog('🔁 will-redirect to:', navigationUrl); } catch { }
    });

    // Improve webRequest onErrorOccurred detail logging (use any for details to match runtime shape)
    try {
        const sessionForDiag = mainWindow.webContents.session;
        sessionForDiag.webRequest.onErrorOccurred({ urls: ['*://*/*'] }, (details: any) => {
            try {
                const statusCode = details && (details as any).statusCode ? (details as any).statusCode : 'N/A';
                logger.log('Network', 'error', `NETWORK_ERROR DETAILED: ${details.method} ${details.url} - ${details.error} ; fromCache=${details.fromCache} ; statusCode=${statusCode} ; timestamp=${new Date().toISOString()}`);
            } catch (e) {
                logger.log('Network', 'error', `NETWORK_ERROR DETAILED (failed to stringify): ${String(e)}`);
            }
        });
    } catch (e) {
        safeLog('⚠️ Não foi possível instalar webRequest diagnostic handler:', (e as Error)?.message || String(e));
    }

    mainWindow.webContents.on('did-start-loading', (): void => {
        safeLog('⏳ Iniciando carregamento...');
    });

    mainWindow.webContents.on('did-finish-load', (): void => {
        safeLog('✅ Carregamento finalizado');
        if (mainWindow && !mainWindow.isDestroyed()) {
            try {
                mainWindow.show();
            } catch (showError: unknown) {
                const error = showError as Error;
                safeLog('❌ Erro ao mostrar janela após carregamento:', error.message);
            }
        }
    });

    mainWindow.webContents.on('did-fail-load', (
        _event: Event,
        errorCode: number,
        errorDescription: string,
        validatedURL: string
    ): void => {
        safeLog('❌ Falha no carregamento:', errorCode, errorDescription, validatedURL);
        const retryTimeout = setTimeout((): void => {
            if (mainWindow && !mainWindow.isDestroyed()) {
                try {
                    loadFrontendWithRetry(startUrl);
                } catch (retryError: unknown) {
                    const error = retryError as Error;
                    safeLog('❌ Erro ao tentar recarregar:', error.message);
                }
            }
        }, 3000);

        // Evitar vazamento de memória limpando timeout
        if (mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.once('closed', (): void => {
                clearTimeout(retryTimeout);
            });
        }
    });

    mainWindow.webContents.on('render-process-gone', (_event: Event, details: any): void => {
        safeLog('💥 Render process gone:', details && details.reason, 'exitCode=', details && details.exitCode);
        if (!isQuitting && mainWindow && !mainWindow.isDestroyed()) {
            try {
                const response = dialog.showMessageBoxSync(mainWindow, {
                    type: 'error',
                    title: 'Erro no Electron',
                    message: 'A aplicação travou. Deseja reiniciar?',
                    buttons: ['Reiniciar', 'Fechar']
                });

                if (response === 0) {
                    try {
                        mainWindow.reload();
                    } catch (reloadError: unknown) {
                        const error = reloadError as Error;
                        safeLog('❌ Erro ao recarregar após crash:', error.message);
                        app.quit();
                    }
                } else {
                    app.quit();
                }
            } catch (dialogError: unknown) {
                const error = dialogError as Error;
                safeLog('❌ Erro ao mostrar diálogo de crash:', error.message);
                app.quit();
            }
        }
    });

    mainWindow.webContents.on('devtools-opened', (): void => {
        safeLog('🔧 DevTools aberto');
    });

    mainWindow.webContents.on('devtools-closed', (): void => {
        safeLog('🔧 DevTools fechado');
    });
}

// Função auxiliar para mostrar janela com tratamento de erro
// showMainWindow intentionally left unused; keep for potential future use
function showMainWindow(): void {
    if (mainWindow && !mainWindow.isDestroyed()) {
        try { mainWindow.show(); mainWindow.focus(); } catch { }
    }
}

function loadFrontendWithRetry(url: string): void {
    if (!url) { safeLog('❌ URL inválida fornecida para carregamento'); return; }
    if (!mainWindow || mainWindow.isDestroyed()) { safeLog('❌ Janela foi destruída, cancelando carregamento'); return; }

    safeLog('🔧 Carregando frontend (single attempt):', url);
    const defaultUserAgent = process.env['ELECTRON_USER_AGENT'] || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
    mainWindow.loadURL(url, { userAgent: defaultUserAgent }).then(() => {
        safeLog('✅ Frontend carregado com sucesso!');
        // Do not try fallbacks silently; dom-ready will show window
    }).catch((error: Error) => {
        safeLog('❌ mainWindow.loadURL failed:', error.message);
        // Surface explicit error page
        try { loadErrorPage(); } catch (e) { safeLog('❌ Falha ao mostrar página de erro:', String(e)); }
    });
}

// Iniciar servidor HTTP simples para servir arquivos estáticos do frontend
// startStaticServer kept for manual testing but unused in strict single-path flow
function startStaticServer(rootDir: string): Promise<string> {
    return new Promise((resolve, reject) => {
        try {
            const server = http.createServer((req: any, res: any) => {
                try {
                    const reqUrl = decodeURIComponent(req.url.split('?')[0] || '/');
                    let filePath = path.join(rootDir, reqUrl === '/' ? 'index.html' : reqUrl);
                    if (!filePath.startsWith(path.resolve(rootDir))) { res.statusCode = 403; res.end('Forbidden'); return; }
                    if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
                        const alt = path.join(rootDir, 'index.html');
                        if (fs.existsSync(alt)) filePath = alt; else { res.statusCode = 404; res.end('Not found'); return; }
                    }
                    const ext = path.extname(filePath).toLowerCase();
                    const mimeMap: any = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
                    const mimeType = mimeMap[ext] || 'application/octet-stream';
                    res.setHeader('Content-Type', mimeType);
                    const stream = fs.createReadStream(filePath);
                    stream.on('error', (_err: any) => { res.statusCode = 500; res.end('Server error'); });
                    stream.pipe(res);
                } catch (inner) { try { res.statusCode = 500; res.end('Server error'); } catch { } }
            });
            server.on('error', (_err: any) => { reject(_err); });
            server.listen(0, '127.0.0.1', () => {
                const addr: any = server.address();
                const port = addr && addr.port ? addr.port : null;
                if (!port) { reject(new Error('Failed to get server port')); return; }
                const url = `http://127.0.0.1:${port}/`;
                app.on('before-quit', () => { try { server.close(); } catch {} });
                resolve(url);
            });
        } catch (e) { reject(e); }
    });
}

// Expor helpers para evitar warnings TS6133 (funções intencionalmente mantidas para testes/uso futuro)
;(globalThis as any).__electron_helpers = Object.assign((globalThis as any).__electron_helpers || {}, {
    showMainWindow,
    startStaticServer
});

function loadErrorPage(): void {
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
        .catch((loadError: Error): void => {
            safeLog('❌ Erro ao carregar página de erro:', loadError.message);
        });

    if (!mainWindow.isDestroyed()) {
        mainWindow.show();
    }
}

// Configurar menu da aplicação
function createAppMenu(): void {
    const template: MenuItemConstructorOptions[] = [
        {
            label: 'Arquivo',
            submenu: [
                {
                    label: 'Recarregar',
                    accelerator: 'F5',
                    click: (): void => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            safeLog('🔄 Recarregando aplicação...');
                            mainWindow.reload();
                        }
                    }
                },
                {
                    label: 'DevTools',
                    accelerator: 'F12',
                    click: (): void => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            safeLog('🔧 Alternando DevTools...');
                            try {
                                if (mainWindow.webContents.isDevToolsOpened()) {
                                    mainWindow.webContents.closeDevTools();
                                } else {
                                    // Abrir DevTools encaixado à direita (não destacada)
                                    mainWindow.webContents.openDevTools({ mode: 'right' });
                                }
                            } catch (error: unknown) {
                                const err = error as Error;
                                safeLog('❌ Erro ao abrir DevTools:', err.message);
                            }
                        }
                    }
                },
                { type: 'separator' },
                {
                    label: 'Sair',
                    accelerator: process.platform === 'darwin' ? 'Cmd+Q' : 'Alt+F4',
                    click: (): void => {
                        isQuitting = true;
                        app.quit();
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
                    click: (): void => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            try {
                                const currentZoom = mainWindow.webContents.getZoomLevel();
                                mainWindow.webContents.setZoomLevel(currentZoom + 0.5);
                            } catch (error: unknown) {
                                const err = error as Error;
                                safeLog('❌ Erro ao aumentar zoom:', err.message);
                            }
                        }
                    }
                },
                {
                    label: 'Zoom Out',
                    accelerator: 'CmdOrCtrl+-',
                    click: (): void => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            try {
                                const currentZoom = mainWindow.webContents.getZoomLevel();
                                mainWindow.webContents.setZoomLevel(currentZoom - 0.5);
                            } catch (error: unknown) {
                                const err = error as Error;
                                safeLog('❌ Erro ao diminuir zoom:', err.message);
                            }
                        }
                    }
                },
                {
                    label: 'Reset Zoom',
                    accelerator: 'CmdOrCtrl+0',
                    click: (): void => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            try {
                                mainWindow.webContents.setZoomLevel(0);
                            } catch (error: unknown) {
                                const err = error as Error;
                                safeLog('❌ Erro ao resetar zoom:', err.message);
                            }
                        }
                    }
                }
            ]
        }
    ];

    try {
        const menu = Menu.buildFromTemplate(template);
        Menu.setApplicationMenu(menu);
    } catch (error: unknown) {
        const err = error as Error;
        safeLog('❌ Erro ao criar menu:', err.message);
    }
}

// Função para limpar recursos antes de sair
function cleanup(): void {
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.removeAllListeners();
        mainWindow.webContents.removeAllListeners();
    }
}

// Eventos do aplicativo
app.whenReady().then((): void => {
    safeLog('⚡ Electron App Ready!');
    try {
        createMainWindow();
        createAppMenu();
    } catch (error: unknown) {
        const err = error as Error;
        safeLog('❌ Erro ao inicializar aplicação:', err.message);
        app.quit();
    }

    app.on('activate', (): void => {
        if (BrowserWindow.getAllWindows().length === 0) {
            try {
                createMainWindow();
            } catch (error: unknown) {
                const err = error as Error;
                safeLog('❌ Erro ao recriar janela:', err.message);
            }
        }
    });
}).catch((error: Error): void => {
    safeLog('❌ Erro na inicialização do app:', error.message);
    app.quit();
});

app.on('window-all-closed', (): void => {
    safeLog('🚪 Todas as janelas fechadas');
    cleanup();
    // During diagnostics (ENABLE_ELECTRON_INTERCEPTORS=1) keep app alive so DevTools/logs can be inspected
    if (process.platform !== 'darwin') {
        if (!enableInterceptors) {
            isQuitting = true;
            app.quit();
        } else {
            safeLog('⚠️ ENABLE_ELECTRON_INTERCEPTORS=1 -> not quitting on window-all-closed for diagnostics');
        }
    }
});

app.on('before-quit', async (): Promise<void> => {
    safeLog('👋 Aplicação sendo encerrada...');
    isQuitting = true;

    // Attempt to notify renderer to cleanup intervals/heartbeats and acknowledge
    try {
        if (mainWindow && !mainWindow.isDestroyed()) {
            safeLog('📨 Enviando sinal de shutdown para renderer');
            try { mainWindow.webContents.send('app:shutdown'); } catch (e) { safeLog('⚠️ Falha ao enviar app:shutdown:', String(e)); }

            // Await ack (with timeout)
            const ackPromise = new Promise<void>((resolve) => {
                const ipc = require('electron').ipcMain as typeof ipcMain;
                let settled = false;
                const onAck = () => { if (!settled) { settled = true; try { ipc.removeListener('app:shutdown:ack', onAck); } catch {} resolve(); } };
                try { ipc.once('app:shutdown:ack', onAck); } catch (e) { safeLog('⚠️ Não foi possível registrar listener de ack:', String(e)); resolve(); }
                // Timeout fallback
                setTimeout(() => { if (!settled) { settled = true; try { ipc.removeListener('app:shutdown:ack', onAck); } catch {} resolve(); } }, Number(process.env['APP_SHUTDOWN_ACK_MS'] || 2000));
            });

            try { await ackPromise; safeLog('✅ Renderer acknowledged shutdown (or timeout)'); } catch (e) { safeLog('⚠️ Erro aguardando ack do renderer:', String(e)); }
        }
    } catch (e) {
        safeLog('⚠️ Erro durante notify renderer shutdown:', (e as Error).message || String(e));
    }

    try {
        cleanup();
    } catch (e) {
        safeLog('❌ Erro durante cleanup:', String(e));
    }

    // Flush logs synchronously before exit
    try { logger.flushSync(); } catch (e) { try { console.error('Erro flush logs sync', e); } catch {} }
});

// Also listen to renderer ack at global scope so duplicates are handled gracefully
try {
    ipcMain.on('app:shutdown:ack', () => {
        safeLog('📨 Received app:shutdown:ack from renderer');
    });
} catch (e) { safeLog('⚠️ Não foi possível instalar listener app:shutdown:ack:', String(e)); }

// Prevenir múltiplas instâncias
const gotTheLock = app.requestSingleInstanceLock();

if (!gotTheLock) {
    safeLog('❌ Já existe uma instância rodando - fechando esta instância');
    app.quit();
} else {
    app.on('second-instance', (): void => {
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
process.on('uncaughtException', (error: NodeJS.ErrnoException): void => {
    if (error.code !== 'EPIPE') {
        safeLog('💥 Erro não capturado:', error.message);
        if (error.stack) {
            safeLog('Stack trace:', error.stack);
        }

        if (!isDev) {
            // Em produção, encerrar graciosamente
            try {
                cleanup();
                app.quit();
            } catch (quitError: unknown) {
                const qe = quitError as Error;
                safeLog('❌ Erro durante cleanup/quit:', qe?.message || String(quitError));
                // tentar forçar saída se cleanup falhar
                try {
                    process.exit(1);
                } catch (exitErr: unknown) {
                    safeLog('❌ Erro ao forçar exit:', String(exitErr));
                }
            }
        }
    }
});

// Tratamento de sinal de interrupção (Ctrl+C)
process.on('SIGINT', (): void => {
    safeLog('🛑 Sinal SIGINT recebido - encerrando aplicação...');
    try {
        cleanup();
        app.quit();
    } catch (sigErr: unknown) {
        safeLog('❌ Erro ao tratar SIGINT:', String(sigErr));
        try { process.exit(1); } catch { }
    }
});

process.on('SIGTERM', (): void => {
    safeLog('🛑 Sinal SIGTERM recebido - encerrando aplicação...');
    try {
        cleanup();
        app.quit();
    } catch (sigErr: unknown) {
        safeLog('❌ Erro ao tratar SIGTERM:', String(sigErr));
        try { process.exit(1); } catch { }
    }
});

safeLog('🚀 Electron main.ts carregado com sucesso!');

// Configurações para resolver problemas de cache e GPU no Windows
 const skipElectronFlags = process.env['SKIP_ELECTRON_FLAGS'] === '1';

// If SKIP_ELECTRON_FLAGS=1 is set in env, we skip applying aggressive Chromium flags.
if (isDev && !skipElectronFlags) {
     app.commandLine.appendSwitch('--disable-gpu-cache');
     app.commandLine.appendSwitch('--disable-gpu-sandbox');
     app.commandLine.appendSwitch('--disable-software-rasterizer');
     app.commandLine.appendSwitch('--disable-background-timer-throttling');
     app.commandLine.appendSwitch('--disable-backgrounding-occluded-windows');
     app.commandLine.appendSwitch('--disable-renderer-backgrounding');
     app.commandLine.appendSwitch('--disable-features', 'TranslateUI');
     app.commandLine.appendSwitch('--disable-ipc-flooding-protection');
     // Dev-only aggressive flags (do NOT enable in production)
     app.commandLine.appendSwitch('--no-sandbox');
     app.commandLine.appendSwitch('--disable-web-security');
     app.commandLine.appendSwitch('--disable-features', 'NetworkService');
     app.commandLine.appendSwitch('--disable-features', 'SiteIsolationTrials');
     safeLog('🔧 Dev-only Chromium flags applied');
 } else {
     safeLog('🔧 Production run: Chromium flags left default for security');
 }

// Configurações críticas para resolver problema de janela não aparecendo no Windows
if (!skipElectronFlags) {
    app.commandLine.appendSwitch('--disable-gpu');
    app.commandLine.appendSwitch('--disable-d3d11');
    app.commandLine.appendSwitch('--disable-accelerated-2d-canvas');
    app.commandLine.appendSwitch('--disable-accelerated-jpeg-decoding');
    app.commandLine.appendSwitch('--disable-accelerated-mjpeg-decode');
    app.commandLine.appendSwitch('--disable-accelerated-video-decode');
    app.commandLine.appendSwitch('--force-cpu-draw');
    app.commandLine.appendSwitch('--disable-gpu-compositing');
    // Ensure Chromium uses the older network stack (avoid NetworkService) which helps with loopback on some Windows setups
    try { app.commandLine.appendSwitch('--disable-features', 'NetworkService'); } catch { }

    // Configurações específicas para resolver problemas de cache no Windows
    if (process.platform === 'win32') {
        app.commandLine.appendSwitch('--force-high-dpi-support', '1');
        app.commandLine.appendSwitch('--high-dpi-support', '1');
        app.commandLine.appendSwitch('--disable-gpu-process-crash-limit');
        app.commandLine.appendSwitch('--disable-gpu-watchdog');
        app.commandLine.appendSwitch('--ignore-gpu-blacklist');
        app.commandLine.appendSwitch('--disable-direct-write');
        app.commandLine.appendSwitch('--disable-directwrite-font-proxy');
        // Forçar modo de compatibilidade
        app.commandLine.appendSwitch('--use-gl', 'swiftshader');
        app.commandLine.appendSwitch('--enable-begin-frame-scheduling');
    }
} else {
    safeLog('⚠️ SKIP_ELECTRON_FLAGS=1 -> skipping Chromium/GPU flags for diagnosis');
}

// Forçar inicialização da aplicação mesmo com problemas de GPU
app.disableHardwareAcceleration();

// Configurar handlers IPC para comunicação segura entre main e renderer
function setupIpcHandlers(): void {
    // Handler para abrir links externos
    ipcMain.handle('shell:openExternal', async (_event, url: string): Promise<void> => {
        try {
            await shell.openExternal(url);
            safeLog('🔗 Link externo aberto:', url);
        } catch (error: unknown) {
            const err = error as Error;
            safeLog('❌ Erro ao abrir link externo:', err.message);
            throw err;
        }
    });

    // Handler para ping/pong de teste
    ipcMain.handle('app:ping', async (): Promise<string> => {
        safeLog('📡 Ping recebido do renderer');
        return 'pong';
    });

    // Handler para obter informações do sistema
    ipcMain.handle('app:getSystemInfo', async (): Promise<object> => {
        return {
            electronVersion: process.versions.electron,
            nodeVersion: process.versions.node,
            platform: process.platform,
            arch: process.arch
        };
    });

    // Handler para retornar userData path (onde salvar logs de usuário)
    ipcMain.handle('app:getUserDataPath', async (): Promise<string> => {
        try {
            return app.getPath('userData');
        } catch (e) {
            safeLog('❌ Erro ao obter userData path:', (e as Error).message);
            throw e;
        }
    });

    // IPC proxy: executar requisições HTTP(S) a partir do main process em nome do renderer
    ipcMain.handle('app:proxyRequest', async (_event, opts: { method?: string; url: string; headers?: Record<string, string>; body?: any; timeoutMs?: number }) => {
        const { URL } = require('url');
        return new Promise(async (resolve, reject) => {
            try {
                const u = new URL(opts.url);
                const httpMod = u.protocol === 'https:' ? require('https') : require('http');
                const timeoutMs = typeof opts.timeoutMs === 'number' ? opts.timeoutMs : 5000;

                const requestOptions: any = {
                    method: opts.method || 'GET',
                    hostname: u.hostname,
                    port: u.port || (u.protocol === 'https:' ? 443 : 80),
                    path: u.pathname + (u.search || ''),
                    headers: opts.headers || {}
                };

                const req = httpMod.request(requestOptions, (res: any) => {
                    let data = '';
                    res.setEncoding('utf8');
                    res.on('data', (chunk: any) => data += chunk);
                    res.on('end', () => {
                        resolve({ status: res.statusCode, headers: res.headers, body: data });
                    });
                });

                req.on('error', (err: any) => {
                    safeLog('🔍 Proxy request error:', String(err));
                    reject(new Error(String(err)));
                });

                req.setTimeout(timeoutMs, () => { try { req.destroy(new Error('timeout')); } catch { } });

                if (opts.body) {
                    const payload = typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body);
                    req.write(payload);
                }
                req.end();
            } catch (e) {
                reject(e);
            }
        });
    });

    safeLog('🔧 Handlers IPC configurados');
    // Receber logs do renderer e salvar em frontend.log na raiz do projeto
    ipcMain.on('renderer-log', (_event, payload: { level: string; message: string; timestamp: string }) => {
        try {
            const fs = require('fs');
            const path = require('path');
            const p = path.join(process.cwd(), 'frontend.log');
            const entry = `[${payload.timestamp}] [renderer:${payload.level}] ${payload.message}\n`;
            fs.appendFile(p, entry, (err: any) => { if (err) safeLog('❌ Falha gravar frontend.log:', err?.message || err); });
        } catch (e) {
            safeLog('❌ Erro ao manipular ipc renderer-log:', (e as Error).message);
        }
    });
}

// Configurar interceptação completa de logs do WebContents
function setupWebContentsLogging(): void {
    if (!mainWindow || mainWindow.isDestroyed()) return;

    const webContents = mainWindow.webContents;

    // Interceptar mensagens do console (console.log, console.error, etc.)
    webContents.on('console-message', (_event, level, message, line, sourceId) => {
        const levelMap: { [k: number]: string } = { 0: 'info', 1: 'warn', 2: 'error', 3: 'debug' };
        const logLevel = levelMap[level] || 'log';

        const locationInfo = sourceId && line ? ` (${sourceId}:${line})` : '';
        logger.log('Console', logLevel, `${message}${locationInfo}`);
    });

    // Interceptar erros do processo de render (substitui o uso de 'crashed', que pode estar obsoleto)
    webContents.on('render-process-gone', (_event, details) => {
        try {
            const reason = details && details.reason ? details.reason : 'unknown';
            const exitCode = details && details.exitCode ? details.exitCode : 'N/A';
            logger.log('WebContents', 'error', `Render process gone - reason: ${reason}, exitCode: ${exitCode}`);
        }
        catch (e) {
            logger.log('WebContents', 'error', `Render process gone (failed to read details): ${String(e)}`);
        }
    });

    // Interceptar requisições de rede para debugging
    const session = webContents.session;
    // Limit interceptors to http/https to avoid interfering with websocket or other protocols (wss, ws)
    const httpUrls = ['http://*/*', 'https://*/*'];

    session.webRequest.onBeforeRequest({ urls: httpUrls }, (details: any, callback: any) => {
        try {
            // Don't log chrome-extension, devtools or favicon requests
            if (!details.url.includes('chrome-extension') && !details.url.includes('devtools') && !details.url.includes('favicon.ico')) {
                logger.log('Network', 'info', `REQUEST: ${details.method} ${details.url}`);
            }
        } catch (logErr) {
            logger.log('Network', 'warn', `Erro ao logar onBeforeRequest: ${String(logErr)}`);
        }

        // Continue request unmodified
        try { if (typeof callback === 'function') callback({ cancel: false }); } catch (cbErr) { logger.log('Network', 'warn', `Falha ao chamar callback onBeforeRequest: ${String(cbErr)}`); }
    });

    try {
        // Only observe headers for http/https and never mutate them here (this is for diagnostics)
        session.webRequest.onBeforeSendHeaders({ urls: httpUrls }, (details: any, callback: any) => {
            try { logger.log('Network', 'debug', `SENDING HEADERS: ${details.method} ${details.url}`); } catch (err) { logger.log('Network', 'warn', `Erro ao logar request headers: ${String(err)}`); }
            try { callback({ cancel: false, requestHeaders: details.requestHeaders }); } catch (err) { logger.log('Network', 'warn', `Falha callback onBeforeSendHeaders: ${String(err)}`); }
        });
    } catch (e) {
        logger.log('Network', 'warn', 'onBeforeSendHeaders não suportado nesta versão', (e as Error).message);
    }

    try {
        session.webRequest.onHeadersReceived({ urls: httpUrls }, (details: any, callback: any) => {
            try { logger.log('Network', 'debug', `HEADERS RECEIVED: ${details.statusCode || ''} ${details.url}`); } catch (err) { logger.log('Network', 'warn', `Erro ao logar response headers: ${String(err)}`); }
            try { callback({ cancel: false, responseHeaders: details.responseHeaders }); } catch (err) { logger.log('Network', 'warn', `Falha callback onHeadersReceived: ${String(err)}`); }
        });
    } catch (e) {
        logger.log('Network', 'warn', 'onHeadersReceived não suportado nesta versão', (e as Error).message);
    }

    session.webRequest.onCompleted({ urls: httpUrls }, (details) => {
        try {
            if (!details.url.includes('chrome-extension') && !details.url.includes('devtools') && !details.url.includes('favicon.ico')) {
                const status = Number(details.statusCode) || 0;
                const logLevel = status >= 400 ? 'error' : status >= 300 ? 'warn' : 'info';
                logger.log('Network', logLevel, `RESPONSE: ${details.method} ${details.url} - ${status}`);
            }
        } catch (e) { /* ignore */ }
    });

    session.webRequest.onErrorOccurred({ urls: httpUrls }, (details) => { try { logger.log('Network', 'error', `NETWORK_ERROR: ${details.method} ${details.url} - ${details.error}`); } catch (e) { /* ignore */ } });

    // Interceptar navegação
    webContents.on('did-navigate', (_event, navigationUrl) => {
        logger.log('Navigation', 'info', `Navegou para: ${navigationUrl}`);
    });

    webContents.on('did-navigate-in-page', (_event, navigationUrl, isMainFrame) => {
        if (isMainFrame) {
            logger.log('Navigation', 'info', `Navegação interna: ${navigationUrl}`);
        }
    });

    // Interceptar mudanças no título da página
    webContents.on('page-title-updated', (_event, title) => {
        logger.log('Page', 'info', `Título atualizado: ${title}`);
    });

    // Interceptar quando DOM está pronto
    webContents.on('dom-ready', () => {
        logger.log('Page', 'info', 'DOM está pronto');

        // Injetar script para capturar logs mais profundos
        webContents.executeJavaScript(`
            // Interceptar todos os tipos de console
            (function() {
                const originalLog = console.log;
                const originalError = console.error;
                const originalWarn = console.warn;
                const originalInfo = console.info;
                const originalDebug = console.debug;
                
                function sendToElectron(level, args) {
                    try {
                        const message = args.map(arg => {
                            if (typeof arg === 'string') return arg;
                            if (arg === null) return 'null';
                            if (arg === undefined) return 'undefined';
                            try {
                                return JSON.stringify(arg, null, 2);
                            } catch {
                                return String(arg);
                            }
                        }).join(' ');
                        
                        if (window.electronAPI && window.electronAPI.sendLog) {
                            window.electronAPI.sendLog(level, message);
                        }
                    } catch (e) {
                        // Fallback silencioso
                    }
                }
                
                console.log = function(...args) {
                    sendToElectron('log', args);
                    return originalLog.apply(console, args);
                };
                
                console.error = function(...args) {
                    sendToElectron('error', args);
                    return originalError.apply(console, args);
                };
                
                console.warn = function(...args) {
                    sendToElectron('warn', args);
                    return originalWarn.apply(console, args);
                };
                
                console.info = function(...args) {
                    sendToElectron('info', args);
                    return originalInfo.apply(console, args);
                };
                
                console.debug = function(...args) {
                    sendToElectron('debug', args);
                    return originalDebug.apply(console, args);
                };
                
                // Capturar erros globais
                window.addEventListener('error', function(e) {
                    sendToElectron('error', [\`JavaScript Error: \${e.message} at \${e.filename}:\${e.lineno}:\${e.colno}\`]);
                });
                
                // Capturar promises rejeitadas
                window.addEventListener('unhandledrejection', function(e) {
                    sendToElectron('error', [\`Unhandled Promise Rejection: \${e.reason}\`]);
                });
                
                console.log('🔧 Sistema de logging avançado do Electron inicializado');

                // Se estivermos servindo via file://, usar proxy via electronAPI.proxyRequest para contornar bloqueios
                try {
                    if (window.location && window.location.protocol === 'file:' && window.electronAPI && typeof window.electronAPI.proxyRequest === 'function') {
                        // Substituir fetch
                        const originalFetch = window.fetch;
                        window.fetch = function(input, init){
                            try {
                                const url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                                const method = (init && init.method) || 'GET';
                                const headers = (init && init.headers) || {};
                                const body = init && init.body ? init.body : undefined;
                                return window.electronAPI.proxyRequest({ method, url, headers, body }).then((res: any) => {
                                    // criar Response-like objeto mínimo
                                    return new Response(res.body || '', { status: res.status || 200, headers: res.headers || {} });
                                });
                            } catch (e) {
                                return Promise.reject(e);
                            }
                        };

                        // Substituir XMLHttpRequest (básico)
                        const OriginalXHR = window.XMLHttpRequest;
                        function ProxyXHR() {
                            const xhr = new OriginalXHR();
                            let _method: any, _url: any, _async: any;
                            const listeners: any = {};
                            const open = xhr.open;
                            const send = xhr.send;
                            xhr.open = function(method: any, url: any, async: any){ _method = method; _url = url; _async = async; open.apply(xhr, arguments as any); };
                            xhr.send = function(body: any){
                                try {
                                    window.electronAPI.proxyRequest({ method: _method || 'GET', url: _url || '', body }).then((res: any) => {
                                        // Simular eventos onreadystatechange/onload
                                        try {
                                            Object.defineProperty(xhr, 'status', { value: res.status, writable: false });
                                        } catch {}
                                        try { if (xhr.onreadystatechange) xhr.onreadystatechange(); } catch {}
                                        try { if (xhr.onload) xhr.onload(); } catch {}
                                    }).catch((err: any) => {
                                        try { if (xhr.onerror) xhr.onerror(err); } catch {}
                                    });
                                } catch (e) {
                                    try { if (xhr.onerror) xhr.onerror(e); } catch {}
                                }
                            };
                            return xhr;
                        }
                        // @ts-ignore
                        window.XMLHttpRequest = ProxyXHR as any;
                        console.log('🔧 Fetch and XHR proxied via electronAPI for file:// context');
                    }
                } catch (proxyErr) {
                    console.warn('⚠️ Falha ao instalar proxy de rede no renderer:', proxyErr);
                }

            })();
        `).catch((error) => {
            logger.log('Injection', 'error', `Erro ao injetar script de logging: ${error.message}`);
        });
    });

    logger.log('Setup', 'info', 'Sistema completo de logging do WebContents configurado');
}
