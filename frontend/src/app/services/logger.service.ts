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

        // ✅ DESABILITADO: Hook do console (por solicitação do usuário)
        // Logs ficam apenas no DevTools sem interceptação
    }

    log(message: string, data?: any) {
        // ✅ DESABILITADO: Salvamento de logs em arquivo (por solicitação do usuário)
        // Apenas console.log para debug no DevTools (não salva em arquivo nem envia para backend)
        // O console já foi interceptado no constructor para capturar tudo
    }
}
