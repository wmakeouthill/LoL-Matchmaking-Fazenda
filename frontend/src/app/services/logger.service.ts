import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class LoggerService {
    private fs: any;
    private logPath: string;

    constructor() {
        // Usar window.electronAPI exposto pelo preload do Electron
        this.fs = (window as any).electronAPI?.fs;
        const path = (window as any).electronAPI?.path;
        const process = (window as any).electronAPI?.process;
        this.logPath = path && process ? path.join(process.cwd(), 'frontend.log') : '';
    }

    log(message: string, data?: any) {
        const logLine = `[${new Date().toISOString()}] ${message} ${data ? JSON.stringify(data) : ''}\n`;
        if (this.fs && this.logPath) {
            this.fs.appendFile(this.logPath, logLine, (err: any) => {
                if (err) {
                    console.error('[LoggerService] Erro ao salvar log:', err);
                }
            });
        }
        console.log(message, data);
    }
} 