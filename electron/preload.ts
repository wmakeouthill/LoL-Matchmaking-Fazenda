import { contextBridge, ipcRenderer } from 'electron';

// Definir tipos para as APIs expostas
interface ElectronAPI {
  openExternal: (url: string) => Promise<void>;
  getVersion: () => string;
  onWindowReady: (callback: () => void) => void;
  removeAllListeners: (channel: string) => void;
}

// Expor APIs seguras para o renderer process
const electronAPI: ElectronAPI = {
  // Abrir links externos
  openExternal: (url: string): Promise<void> =>
    ipcRenderer.invoke('shell:openExternal', url),

  // Obter versão do Electron
  getVersion: (): string => process.versions.electron || 'unknown',

  // Escutar eventos do main process
  onWindowReady: (callback: () => void): void => {
    ipcRenderer.on('window-ready', callback);
  },

  // Remover listeners
  removeAllListeners: (channel: string): void => {
    ipcRenderer.removeAllListeners(channel);
  }
};

// Expor API no contexto global do renderer de forma segura
contextBridge.exposeInMainWorld('electronAPI', electronAPI);

// Log para debug
console.log('🔧 Preload script carregado com sucesso');
console.log('🔧 Context isolation:', process.contextIsolated);
console.log('🔧 Electron version:', process.versions.electron);
console.log('🔧 Node version:', process.versions.node);

// Nota: O rewrite runtime foi removido. Se ainda houver pontos do frontend apontando para :3000,
// atualize-os diretamente no código do frontend; enquanto isso, o backend já foi atualizado onde havia referência explícita.
