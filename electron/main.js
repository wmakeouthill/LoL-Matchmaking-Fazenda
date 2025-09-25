const { app, BrowserWindow, Menu, shell, dialog } = require('electron');

const isDev = process.env.NODE_ENV === 'development';

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
    } catch (error) {
        // Tratamento específico para erros de logging
        // EPIPE pode ocorrer quando o pipe de saída é fechado
        if (!error || error.code !== 'EPIPE') {
            // Em desenvolvimento, mostrar outros erros que não sejam EPIPE
            if (isDev) {
                try {
                    console.error('Erro no logging:', error?.message || 'Erro desconhecido');
                } catch (secondaryError) {
                    // Se nem console.error funcionar, não há mais o que fazer
                    // Logging não é crítico para o funcionamento da aplicação
                    // Não deixar catch vazio - documentar a razão
                }
            }
        }
        // Ignorar silenciosamente erros EPIPE - são esperados em algumas situações
    }
}

function createMainWindow() {
    safeLog('🚀 Criando janela principal do Electron...');

    // Criar a janela principal do Electron
    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 800,
        minHeight: 600,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            enableRemoteModule: false,
            webSecurity: false, // Desabilitar para desenvolvimento
            allowRunningInsecureContent: true,
            experimentalFeatures: false,
            backgroundThrottling: false
        },
        show: false,
        titleBarStyle: 'default',
        title: 'LOL Matchmaking - Carregando...',
        backgroundColor: '#1e3c72'
    });

    const startUrl = 'http://localhost:8080';

    safeLog('🚀 Electron iniciando...');
    safeLog('📡 Carregando URL:', startUrl);

    loadFrontendWithRetry(startUrl);

    // Event handlers com tratamento robusto de erros
    mainWindow.once('ready-to-show', () => {
        safeLog('📱 Janela pronta para exibir');
        if (mainWindow && !mainWindow.isDestroyed()) {
            try {
                mainWindow.show();
                mainWindow.focus();
                mainWindow.setTitle('LOL Matchmaking');
            } catch (showError) {
                safeLog('❌ Erro ao mostrar janela:', showError.message);
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
            } catch (domError) {
                safeLog('❌ Erro no evento dom-ready:', domError.message);
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
        shell.openExternal(url).catch((shellError) => {
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
            } catch (showError) {
                safeLog('❌ Erro ao mostrar janela após carregamento:', showError.message);
            }
        }
    });

    mainWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription, validatedURL) => {
        safeLog('❌ Falha no carregamento:', errorCode, errorDescription, validatedURL);
        const retryTimeout = setTimeout(() => {
            if (mainWindow && !mainWindow.isDestroyed()) {
                try {
                    loadFrontendWithRetry(startUrl);
                } catch (retryError) {
                    safeLog('❌ Erro ao tentar recarregar:', retryError.message);
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

    mainWindow.webContents.on('crashed', (event, killed) => {
        safeLog('💥 WebContents travou! Killed:', killed);
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
                    } catch (reloadError) {
                        safeLog('❌ Erro ao recarregar após crash:', reloadError.message);
                        app.quit();
                    }
                } else {
                    app.quit();
                }
            } catch (dialogError) {
                safeLog('❌ Erro ao mostrar diálogo de crash:', dialogError.message);
                app.quit();
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
        } catch (showError) {
            safeLog('❌ Erro ao mostrar janela:', showError.message);
        }
    }
}

function loadFrontendWithRetry(url, attempt = 1, maxAttempts = 5) {
    // Validar parâmetros de entrada
    if (!url || typeof url !== 'string') {
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
                } catch (retryError) {
                    safeLog('❌ Erro na nova tentativa:', retryError?.message || 'Erro desconhecido');
                }
            }, delay);

            // Limpar timeout se janela for destruída
            if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.once('closed', () => {
                    clearTimeout(retryTimeout);
                });
            }
        } else {
            safeLog('❌ Todas as tentativas falharam, carregando página de erro');
            try {
                loadErrorPage();
            } catch (errorPageError) {
                safeLog('❌ Erro ao carregar página de erro:', errorPageError?.message || 'Erro desconhecido');
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
                // Removido uso inseguro de require('electron') no renderer
                // O main process irá lidar com links externos automaticamente
                console.log('Tentando abrir navegador externo...');
                try {
                    window.open('http://localhost:8080', '_blank');
                } catch (error) {
                    console.error('Erro ao abrir navegador externo:', error);
                }
            }
            
            // Auto-retry a cada 10 segundos
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
                                } else {
                                    mainWindow.webContents.openDevTools({ mode: 'detach' });
                                }
                            } catch (error) {
                                safeLog('❌ Erro ao abrir DevTools:', error.message);
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
                    click: () => {
                        if (mainWindow && !mainWindow.isDestroyed()) {
                            try {
                                const currentZoom = mainWindow.webContents.getZoomLevel();
                                mainWindow.webContents.setZoomLevel(currentZoom + 0.5);
                            } catch (error) {
                                safeLog('❌ Erro ao aumentar zoom:', error.message);
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
                            } catch (error) {
                                safeLog('❌ Erro ao diminuir zoom:', error.message);
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
                            } catch (error) {
                                safeLog('❌ Erro ao resetar zoom:', error.message);
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
    } catch (error) {
        safeLog('❌ Erro ao criar menu:', error.message);
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
app.whenReady().then(() => {
    safeLog('⚡ Electron App Ready!');
    try {
        createMainWindow();
        createAppMenu();
    } catch (error) {
        safeLog('❌ Erro ao inicializar aplicação:', error.message);
        app.quit();
    }

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) {
            try {
                createMainWindow();
            } catch (error) {
                safeLog('❌ Erro ao recriar janela:', error.message);
            }
        }
    });
}).catch((error) => {
    safeLog('❌ Erro na inicialização do app:', error.message);
    app.quit();
});

app.on('window-all-closed', () => {
    safeLog('🚪 Todas as janelas fechadas');
    cleanup();
    if (process.platform !== 'darwin') {
        isQuitting = true;
        app.quit();
    }
});

app.on('before-quit', () => {
    safeLog('👋 Aplicação sendo encerrada...');
    isQuitting = true;
    cleanup();
});

// Prevenir múltiplas instâncias
const gotTheLock = app.requestSingleInstanceLock();

if (!gotTheLock) {
    safeLog('❌ Já existe uma instância rodando - fechando esta instância');
    app.quit();
} else {
    app.on('second-instance', () => {
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
                app.quit();
            } catch (quitError) {
                // Forçar saída se cleanup falhar
                process.exit(1);
            }
        }
    }
});

process.on('unhandledRejection', (reason, promise) => {
    safeLog('💥 Promise rejeitada não tratada:', reason);
    safeLog('Promise:', promise);

    if (!isDev) {
        // Em produção, registrar e considerar encerramento
        safeLog('Aplicação continuará executando, mas isso deve ser investigado');
    }
});

// Tratamento de sinal de interrupção (Ctrl+C)
process.on('SIGINT', () => {
    safeLog('🛑 Sinal SIGINT recebido - encerrando aplicação...');
    cleanup();
    app.quit();
});

process.on('SIGTERM', () => {
    safeLog('🛑 Sinal SIGTERM recebido - encerrando aplicação...');
    cleanup();
    app.quit();
});

safeLog('🚀 Electron main.js carregado com sucesso!');