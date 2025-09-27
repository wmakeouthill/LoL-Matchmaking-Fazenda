import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class LoggerService {
    private fs: any;
    private logPath: string;
    private originalConsoleLog: any;
    private originalConsoleWarn: any;
    private originalConsoleError: any;

    constructor() {
        // Usar window.electronAPI exposto pelo preload do Electron
        this.fs = (window as any).electronAPI?.fs;
        const pathApi = (window as any).electronAPI?.path;
        const processApi = (window as any).electronAPI?.process;

        this.logPath = '';

        // If running in Electron, prefer writing to process.cwd() (dev mode root). Fallback to userData if needed.
        try {
            if (pathApi && processApi) {
                this.logPath = pathApi.join(processApi.cwd(), 'frontend.log');
            }
            const getUserDataPath = (window as any).electronAPI?.getUserDataPath;
            if ((!this.logPath || this.logPath.length === 0) && typeof getUserDataPath === 'function') {
                getUserDataPath().then((userDataDir: string) => {
                    try {
                        if (pathApi) this.logPath = pathApi.join(userDataDir, 'frontend.log');
                        else this.logPath = userDataDir + '/frontend.log';
                    } catch {
                        this.logPath = userDataDir + '/frontend.log';
                    }
                }).catch(() => { /* ignore */ });
            }
        } catch {
            // ignore and leave logPath empty
        }

        // Hook console methods only when running in Electron to capture DevTools logs
        try {
            if (window && (window as any).electronAPI) {
                this.originalConsoleLog = console.log;
                this.originalConsoleWarn = console.warn;
                this.originalConsoleError = console.error;

                console.log = (...args: any[]) => {
                    try { this.log(String(args.map(a => typeof a === 'string' ? a : JSON.stringify(a)).join(' ')), null); } catch { }
                    this.originalConsoleLog.apply(console, args);
                };
                console.warn = (...args: any[]) => {
                    try { this.log(String(args.map(a => typeof a === 'string' ? a : JSON.stringify(a)).join(' ')), null); } catch { }
                    this.originalConsoleWarn.apply(console, args);
                };
                console.error = (...args: any[]) => {
                    try { this.log(String(args.map(a => typeof a === 'string' ? a : JSON.stringify(a)).join(' ')), null); } catch { }
                    this.originalConsoleError.apply(console, args);
                };
            }
        } catch {
            // ignore
        }
    }

    log(message: string, data?: any) {
        const logLine = `[${new Date().toISOString()}] ${message} ${data ? JSON.stringify(data) : ''}\n`;
        if (this.fs && this.logPath) {
            this.fs.appendFile(this.logPath, logLine, (err: any) => {
                if (err) {
                    console.error('[LoggerService] Erro ao salvar log:', err);
                    try {
                        const backendBase = (window as any).electronAPI?.getBackendUrl ? (window as any).electronAPI.getBackendUrl() : '';
                        // backendBase já é a raiz da API (termina com '/api') quando vindo do Electron
                        const url = backendBase ? `${backendBase}/internal/logs/frontend` : '/api/internal/logs/frontend';
                        fetch(url, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ logs: logLine, level: 'error' })
                        }).catch(() => { });
                    } catch { }
                }
            });
        }
        else {
            // sem fs disponível (provavelmente não Electron) => enviar para backend
            try {
                const backendBase = (window as any).electronAPI?.getBackendUrl ? (window as any).electronAPI.getBackendUrl() : '';
                // backendBase já é a raiz da API (termina com '/api') quando vindo do Electron
                const url = backendBase ? `${backendBase}/internal/logs/frontend` : '/api/internal/logs/frontend';
                fetch(url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ logs: logLine, level: 'info' })
                }).catch(() => { });
            } catch { }
            console.log(message, data);
        }
    }
}
