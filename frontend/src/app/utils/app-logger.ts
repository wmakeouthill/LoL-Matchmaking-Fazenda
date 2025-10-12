/**
 * ✅ DESABILITADO: Salvamento de logs em arquivo (por solicitação do usuário)
 * Apenas logs no console para debug no DevTools
 */
export function logApp(...args: any[]) {
    // Apenas console.log, sem salvar em arquivo
    console.log('[App]', ...args);
}
