/**
 * Função de log para gravar no app.log separado do draft
 */
export function logApp(...args: any[]) {
    const fs = (window as any).electronAPI?.fs;
    const path = (window as any).electronAPI?.path;
    const process = (window as any).electronAPI?.process;

    // Usar caminho relativo se electronAPI não estiver disponível
    let logPath = '';
    if (path && process) {
        logPath = path.join(process.cwd(), 'app.log');
    } else {
        // Fallback para ambiente web
        logPath = 'app.log';
    }

    const logLine = `[${new Date().toISOString()}] [App] ` + args.map(a => (typeof a === 'object' ? JSON.stringify(a) : a)).join(' ') + '\n';

    if (fs && logPath) {
        fs.appendFile(logPath, logLine, (err: any) => {
            if (err) {
                console.error('[App] Erro ao escrever log:', err);
            }
        });
    } else {
        // FALLBACK: Se não conseguir escrever no arquivo, pelo menos log no console
        console.log('[App] ElectronAPI não disponível, log apenas no console');
    }

    console.log('[App]', ...args);
}
