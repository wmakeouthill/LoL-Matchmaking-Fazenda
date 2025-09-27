const { app, BrowserWindow } = require('electron');
const path = require('path');

const backend = process.env.BACKEND_URL || 'http://127.0.0.1:8080/';
const startUrl = backend.endsWith('/') ? backend : backend + '/';

function safeLog(...args) { console.log('[diag]', ...args); }

async function createWindow() {
  safeLog('Starting minimal diagnostic Electron');

  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webSecurity: true // default
    }
  });

  win.webContents.on('did-start-loading', () => safeLog('did-start-loading'));
  win.webContents.on('did-stop-loading', () => safeLog('did-stop-loading'));
  win.webContents.on('did-finish-load', () => safeLog('did-finish-load ->', win.webContents.getURL()));
  win.webContents.on('did-fail-load', (_e, errorCode, errorDesc, validatedURL) => safeLog('did-fail-load', errorCode, errorDesc, validatedURL));
  win.webContents.on('render-process-gone', (_e, details) => safeLog('render-process-gone', details));
  win.webContents.on('console-message', (_e, level, message, line, sourceId) => safeLog('console-message', level, message, sourceId + ':' + line));

  win.webContents.openDevTools({ mode: 'right' });

  try {
    await win.loadURL(startUrl, { userAgent: 'Mozilla/5.0 (diag)' });
    safeLog('loadURL promise resolved');
  } catch (err) {
    safeLog('loadURL threw', String(err));
  }
}

app.whenReady().then(createWindow).catch(err => { safeLog('app.whenReady error', err); app.quit(); });

app.on('window-all-closed', () => { app.quit(); });

