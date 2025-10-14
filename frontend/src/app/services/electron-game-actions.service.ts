import { Injectable } from '@angular/core';

/**
 * ✅ SERVIÇO COMPLETO: Todas as ações de jogo validadas via Electron
 *
 * Este serviço é a interface entre o frontend Angular e o Electron
 * para todas as ações que precisam de validação de identidade.
 *
 * FLUXO:
 * 1. Frontend chama método do serviço
 * 2. Serviço chama window.electronAPI.gameActionXXX()
 * 3. Electron valida via LCU se é realmente você
 * 4. Electron envia ação validada para backend
 * 5. Backend processa com confiança total
 * 6. Frontend recebe confirmação
 */
@Injectable({
  providedIn: 'root'
})
export class ElectronGameActionsService {

  constructor() { }

  // === MATCH ACTIONS ===

  /**
   * ✅ Aceitar partida encontrada
   */
  async acceptMatch(matchData: {
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Aceitando partida:', matchData);

      if (!this.isElectron() || !window.electronAPI?.gameActionAcceptMatch) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionAcceptMatch(matchData);
      console.log('🎯 [ElectronGameActions] Resultado aceitar partida:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao aceitar partida:', error);
      return false;
    }
  }

  /**
   * ✅ Recusar partida encontrada
   */
  async declineMatch(matchData: {
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Recusando partida:', matchData);

      if (!this.isElectron() || !window.electronAPI?.gameActionDeclineMatch) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionDeclineMatch(matchData);
      console.log('🎯 [ElectronGameActions] Resultado recusar partida:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao recusar partida:', error);
      return false;
    }
  }

  /**
   * ✅ Cancelar partida
   */
  async cancelMatch(matchData: {
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Cancelando partida:', matchData);

      if (!this.isElectron() || !window.electronAPI?.gameActionCancelMatch) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionCancelMatch(matchData);
      console.log('🎯 [ElectronGameActions] Resultado cancelar partida:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao cancelar partida:', error);
      return false;
    }
  }

  // === QUEUE ACTIONS ===

  /**
   * ✅ Entrar na fila
   */
  async joinQueue(queueData: {
    region?: string;
    primaryLane?: string;
    secondaryLane?: string;
    customLp?: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Entrando na fila:', queueData);

      if (!this.isElectron() || !window.electronAPI?.gameActionJoinQueue) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionJoinQueue(queueData);
      console.log('🎯 [ElectronGameActions] Resultado entrar na fila:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao entrar na fila:', error);
      return false;
    }
  }

  /**
   * ✅ Sair da fila
   */
  async leaveQueue(queueData: {
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Saindo da fila:', queueData);

      if (!this.isElectron() || !window.electronAPI?.gameActionLeaveQueue) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionLeaveQueue(queueData);
      console.log('🎯 [ElectronGameActions] Resultado sair da fila:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao sair da fila:', error);
      return false;
    }
  }

  // === DRAFT ACTIONS ===

  /**
   * ✅ Escolher campeão no draft
   */
  async pickChampion(pickData: {
    championId: number;
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Escolhendo campeão:', pickData);

      if (!this.isElectron() || !window.electronAPI?.gameActionPickChampion) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionPickChampion(pickData);
      console.log('🎯 [ElectronGameActions] Resultado escolher campeão:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao escolher campeão:', error);
      return false;
    }
  }

  /**
   * ✅ Banir campeão no draft
   */
  async banChampion(banData: {
    championId: number;
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Banindo campeão:', banData);

      if (!this.isElectron() || !window.electronAPI?.gameActionBanChampion) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionBanChampion(banData);
      console.log('🎯 [ElectronGameActions] Resultado banir campeão:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao banir campeão:', error);
      return false;
    }
  }

  /**
   * ✅ Selecionar lane
   */
  async selectLane(laneData: {
    lane: string;
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Selecionando lane:', laneData);

      if (!this.isElectron() || !window.electronAPI?.gameActionSelectLane) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionSelectLane(laneData);
      console.log('🎯 [ElectronGameActions] Resultado selecionar lane:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao selecionar lane:', error);
      return false;
    }
  }

  /**
   * ✅ Confirmar draft final
   */
  async confirmDraft(draftData: {
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Confirmando draft:', draftData);

      if (!this.isElectron() || !window.electronAPI?.gameActionConfirmDraft) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionConfirmDraft(draftData);
      console.log('🎯 [ElectronGameActions] Resultado confirmar draft:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao confirmar draft:', error);
      return false;
    }
  }

  // === GAME IN PROGRESS ACTIONS ===

  /**
   * ✅ Votar no vencedor da partida
   */
  async voteWinner(voteData: {
    winner: 'team1' | 'team2';
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Votando vencedor:', voteData);

      if (!this.isElectron() || !window.electronAPI?.gameActionVoteWinner) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionVoteWinner(voteData);
      console.log('🎯 [ElectronGameActions] Resultado votar vencedor:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao votar vencedor:', error);
      return false;
    }
  }

  /**
   * ✅ Reportar resultado da partida
   */
  async reportResult(resultData: {
    result: 'win' | 'loss';
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Reportando resultado:', resultData);

      if (!this.isElectron() || !window.electronAPI?.gameActionReportResult) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionReportResult(resultData);
      console.log('🎯 [ElectronGameActions] Resultado reportar resultado:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao reportar resultado:', error);
      return false;
    }
  }

  /**
   * ✅ Render-se na partida
   */
  async surrender(surrenderData: {
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Rendendo-se:', surrenderData);

      if (!this.isElectron() || !window.electronAPI?.gameActionSurrender) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionSurrender(surrenderData);
      console.log('🎯 [ElectronGameActions] Resultado render-se:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao render-se:', error);
      return false;
    }
  }

  // === SPECTATOR ACTIONS ===

  /**
   * ✅ Mutar espectador
   */
  async muteSpectator(muteData: {
    discordId: string;
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Mutando espectador:', muteData);

      if (!this.isElectron() || !window.electronAPI?.gameActionMuteSpectator) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionMuteSpectator(muteData);
      console.log('🎯 [ElectronGameActions] Resultado mutar espectador:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao mutar espectador:', error);
      return false;
    }
  }

  /**
   * ✅ Desmutar espectador
   */
  async unmuteSpectator(unmuteData: {
    discordId: string;
    matchId: number;
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      console.log('🎯 [ElectronGameActions] Desmutando espectador:', unmuteData);

      if (!this.isElectron() || !window.electronAPI?.gameActionUnmuteSpectator) {
        console.warn('⚠️ [ElectronGameActions] ElectronAPI não disponível');
        return false;
      }

      const result = await window.electronAPI.gameActionUnmuteSpectator(unmuteData);
      console.log('🎯 [ElectronGameActions] Resultado desmutar espectador:', result);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro ao desmutar espectador:', error);
      return false;
    }
  }

  // === GENERAL ACTIONS ===

  /**
   * ✅ Ping do sistema
   */
  async ping(pingData: {
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      if (!this.isElectron() || !window.electronAPI?.gameActionPing) {
        return false;
      }

      const result = await window.electronAPI.gameActionPing(pingData);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro no ping:', error);
      return false;
    }
  }

  /**
   * ✅ Heartbeat da conexão
   */
  async heartbeat(heartbeatData: {
    timestamp?: number;
    [key: string]: any;
  }): Promise<boolean> {
    try {
      if (!this.isElectron() || !window.electronAPI?.gameActionHeartbeat) {
        return false;
      }

      const result = await window.electronAPI.gameActionHeartbeat(heartbeatData);
      return result;
    } catch (error) {
      console.error('❌ [ElectronGameActions] Erro no heartbeat:', error);
      return false;
    }
  }

  // === UTILITY METHODS ===

  /**
   * ✅ Verificar se está rodando no Electron
   */
  isElectron(): boolean {
    return !!window.electronAPI?.isElectron();
  }

  /**
   * ✅ Verificar se as game actions estão disponíveis
   */
  areGameActionsAvailable(): boolean {
    return this.isElectron() &&
      !!window.electronAPI?.gameActionAcceptMatch &&
      !!window.electronAPI?.gameActionDeclineMatch &&
      !!window.electronAPI?.gameActionPickChampion;
  }
}
