import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, firstValueFrom } from 'rxjs';
import { ApiService } from './api';
import { QueueStatus } from '../interfaces';

interface QueuedPlayerInfo {
  summonerName: string;
  tagLine?: string;
  primaryLane: string;
  secondaryLane: string;
  primaryLaneDisplay: string;
  secondaryLaneDisplay: string;
  mmr: number;
  queuePosition: number;
  joinTime: Date;
}

interface QueueActivity {
  id: string;
  timestamp: Date;
  type: 'player_joined' | 'player_left' | 'match_created' | 'system_update' | 'queue_cleared';
  message: string;
  playerName?: string;
  playerTag?: string;
  lane?: string;
}

export interface QueueState {
  // ✅ SIMPLIFICAÇÃO: Só existe fila Discord/MySQL
  isInQueue: boolean;
  position?: number;
  waitTime?: number;
  estimatedTime?: number;
  playersInQueue?: number;
  averageWaitTime?: number;
}

/**
 * Serviço para gerenciar o estado da fila baseado exclusivamente na tabela queue_players
 * como única fonte de verdade, seguindo as regras estabelecidas.
 */
@Injectable({
  providedIn: 'root'
})
export class QueueStateService {
  private queueStateSubject = new BehaviorSubject<QueueState>({
    isInQueue: false,
    position: undefined,
    waitTime: 0,
    estimatedTime: 0,
    playersInQueue: 0,
    averageWaitTime: 0
  });

  // Sistema de sincronização via polling para consultar a tabela queue_players
  private pollingInterval: any = null;
  private readonly POLLING_INTERVAL_MS = 15000; // Polling a cada 15 segundos
  private currentPlayerData: any = null;
  private lastIdentifiers: string[] = []; // ✅ NOVO: Para evitar spam de logs

  constructor(private apiService: ApiService) {
    console.log('🔄 QueueStateService inicializado com sincronização MySQL (tabela queue_players)');
  }

  /**
   * REGRA: Iniciar sincronização via polling para consultar a tabela queue_players
   * ✅ MUDANÇA: Só fazer polling se explicitamente solicitado
   */
  startMySQLSync(currentPlayer?: any): void {
    this.currentPlayerData = currentPlayer;

    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
    }

    // Executar sincronização imediatamente
    this.syncQueueFromDatabase();

    // ✅ MUDANÇA: Só configurar polling se explicitamente habilitado
    // O controle de polling será feito pelo componente Queue
    console.log(`🔄 [QueueState] Sincronização MySQL iniciada (sem polling automático)`);
  }

  /**
   * ✅ NOVO: Método para iniciar polling manualmente
   */
  startPolling(): void {
    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
    }

    // Configurar polling
    this.pollingInterval = setInterval(() => {
      this.syncQueueFromDatabase();
    }, this.POLLING_INTERVAL_MS);

    console.log(`🔄 [QueueState] Polling MySQL iniciado a cada ${this.POLLING_INTERVAL_MS}ms`);
  }

  stopMySQLSync(): void {
    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
      this.pollingInterval = null;
      console.log('🛑 [QueueState] Sincronização MySQL parada');
    }
  }

  /**
   * REGRA: Forçar sincronização imediata com a tabela queue_players
   */
  forceSync(): void {
    console.log('🔄 [QueueState] Forçando sincronização imediata com tabela queue_players...');

    // Apenas sincronizar dados do frontend (sem chamar force-sync que limpa a fila)
    this.syncQueueFromDatabase();
  }

  /**
   * REGRA: Sincronizar estado local com a tabela queue_players (única fonte de verdade)
   */
  private async syncQueueFromDatabase(): Promise<void> {
    try {
      // SEMPRE buscar dados DIRETAMENTE da tabela queue_players via API
      // Se temos dados do jogador atual, passar para o backend fazer a detecção
      const currentPlayerDisplayName = this.currentPlayerData?.displayName;
      const queueStatus = await firstValueFrom(this.apiService.getQueueStatus(currentPlayerDisplayName));

      // ✅ REMOÇÃO DE VALIDAÇÃO: A fila deve ser exibida SEMPRE, mesmo se vazia
      console.log('📊 [QueueState] Status da fila obtido - exibindo independente de validações:', {
        hasData: !!queueStatus,
        playersInQueue: queueStatus?.playersInQueue || 0,
        hasPlayersList: !!queueStatus?.playersInQueueList,
        playersListLength: queueStatus?.playersInQueueList?.length || 0
      });

      // Se não há resposta da API, criar estado vazio mas ainda assim exibir
      if (!queueStatus) {
        console.log('⚠️ [QueueState] Resposta nula da API - criando estado vazio para exibição');
        const emptyState: QueueState = {
          isInQueue: false,
          position: undefined,
          waitTime: 0,
          estimatedTime: 0,
          playersInQueue: 0,
          averageWaitTime: 0
        };
        this.queueStateSubject.next(emptyState);
        return;
      }

      console.log('📊 [QueueState] Dados da fila obtidos da tabela queue_players:', {
        playersInQueue: queueStatus.playersInQueue,
        hasPlayersList: !!queueStatus.playersInQueueList,
        playersListLength: queueStatus.playersInQueueList?.length || 0,
        playerNames: queueStatus.playersInQueueList?.map(p => p.summonerName) || []
      });

      // REGRA: Verificar se fila está vazia (contagem direta da tabela)
      const isQueueEmpty = !queueStatus.playersInQueue || queueStatus.playersInQueue === 0;

      if (isQueueEmpty) {
        console.log('📭 [QueueState] Fila está vazia na tabela queue_players');

        // Estado vazio confirmado pela tabela
        const emptyState: QueueState = {
          isInQueue: false,
          position: undefined,
          waitTime: queueStatus.averageWaitTime || 0,
          estimatedTime: queueStatus.estimatedMatchTime || 0,
          playersInQueue: 0,
          averageWaitTime: queueStatus.averageWaitTime || 0
        };

        console.log('🔄 [QueueState] Estado atualizado para fila vazia:', emptyState);
        this.queueStateSubject.next(emptyState);
        return;
      }

      // REGRA: Verificar se o usuário atual está na fila (priorizar backend se disponível)
      let isUserInQueue = false;
      let userPosition = 0;
      let queuedPlayer: any = null;

      // ✅ NOVO: Se o backend retornou informação sobre usuário atual, usar essa info (mais confiável)
      const queueStatusWithPlayerInfo = queueStatus as any;
      if (queueStatusWithPlayerInfo.isCurrentPlayerInQueue !== undefined) {
        isUserInQueue = queueStatusWithPlayerInfo.isCurrentPlayerInQueue;
        console.log(`🎯 [QueueState] Status de fila determinado pelo backend: ${isUserInQueue ? 'na fila' : 'fora da fila'}`);

        // Se está na fila, tentar encontrar posição na lista
        if (isUserInQueue && queueStatus.playersInQueueList && this.currentPlayerData) {
          const identifiers = this.buildPlayerIdentifiers(this.currentPlayerData);
          queuedPlayer = queueStatus.playersInQueueList.find((player: any) => {
            return this.matchPlayerIdentifiers(player, identifiers);
          });

          if (queuedPlayer) {
            userPosition = queuedPlayer.queuePosition || 0;
            console.log(`✅ [QueueState] Posição na fila encontrada: ${userPosition}`);
          }
        }
      } else if (this.currentPlayerData) {
        // ✅ FALLBACK: Se backend não retornou info, usar lógica manual (compatibilidade)
        console.log('🔄 [QueueState] Backend não retornou status do usuário, usando detecção manual...');

        // Construir diferentes formatos de identificação possíveis
        const identifiers = this.buildPlayerIdentifiers(this.currentPlayerData);

        // ✅ REDUZIR SPAM: Só logar identificadores na primeira vez ou se mudaram
        if (!this.lastIdentifiers || JSON.stringify(identifiers) !== JSON.stringify(this.lastIdentifiers)) {
          console.log('🔍 [QueueState] Identificadores do jogador atual:', identifiers);
          this.lastIdentifiers = identifiers;
        }

        // Buscar na lista de jogadores da fila (dados vindos da tabela queue_players)
        if (queueStatus.playersInQueueList && queueStatus.playersInQueueList.length > 0) {
          queuedPlayer = queueStatus.playersInQueueList.find((player: any) => {
            return this.matchPlayerIdentifiers(player, identifiers);
          });

          if (queuedPlayer) {
            isUserInQueue = true;
            userPosition = queuedPlayer.queuePosition || 0;
            // ✅ REDUZIR SPAM: Só logar se mudou de estado
            if (!this.queueStateSubject.value.isInQueue) {
              console.log(`✅ [QueueState] Usuário encontrado na fila: ${queuedPlayer.summonerName} (posição: ${userPosition})`);
            }
          } else {
            // ✅ REDUZIR SPAM: Só logar se mudou de estado
            if (this.queueStateSubject.value.isInQueue) {
              console.log('❌ [QueueState] Usuário não encontrado na tabela queue_players');
            }
          }
        }
      }

      // Atualizar estado baseado nos dados da tabela queue_players
      const newState: QueueState = {
        isInQueue: isUserInQueue,
        position: userPosition,
        waitTime: queueStatus.averageWaitTime || 0,
        estimatedTime: queueStatus.estimatedMatchTime || 0,
        playersInQueue: queueStatus.playersInQueue,
        averageWaitTime: queueStatus.averageWaitTime || 0
      };

      // ✅ REDUZIR SPAM: Só logar se o estado mudou significativamente
      const currentState = this.queueStateSubject.value;
      if (currentState.isInQueue !== newState.isInQueue ||
        currentState.position !== newState.position ||
        currentState.playersInQueue !== newState.playersInQueue) {
        console.log('🔄 [QueueState] Estado atualizado:', {
          isInQueue: `${currentState.isInQueue} → ${newState.isInQueue}`,
          position: `${currentState.position} → ${newState.position}`,
          playersInQueue: `${currentState.playersInQueue} → ${newState.playersInQueue}`
        });
      }

      this.queueStateSubject.next(newState);

    } catch (error: any) {
      console.error('❌ [QueueState] Erro ao sincronizar com tabela queue_players:', error);
      console.error('❌ [QueueState] Detalhes do erro:', {
        message: error?.message || 'N/A',
        stack: error?.stack || 'N/A',
        url: error?.url || 'N/A'
      });

      // ✅ CORREÇÃO: Verificar se erro é relacionado a URL incorreta
      if (error?.message && error.message.includes('/api/api/')) {
        console.error('🚨 [QueueState] DETECTADO ERRO DE URL DUPLICADA /api/api/ - isso não deveria acontecer após correções!');
        console.error('🔍 [QueueState] Verifique se o rebuild foi feito corretamente');
      }

      // Em caso de erro, definir estado vazio mas funcional para não travar interface
      const errorState: QueueState = {
        isInQueue: false,
        position: undefined,
        waitTime: 0,
        estimatedTime: 0,
        playersInQueue: 0,
        averageWaitTime: 0
      };

      console.log('🔄 [QueueState] Estado definido como vazio (erro):', errorState);
      this.queueStateSubject.next(errorState);
    }
  }

  /**
   * Construir identificadores possíveis para o jogador atual
   */
  private buildPlayerIdentifiers(playerData: any): string[] {
    const identifiers: string[] = [];

    // Nome completo do summoner se disponível
    if (playerData.summonerName) {
      identifiers.push(playerData.summonerName);
    }

    // Formato gameName#tagLine se disponível
    if (playerData.gameName && playerData.tagLine) {
      identifiers.push(`${playerData.gameName}#${playerData.tagLine}`);
    }

    // Apenas gameName se disponível
    if (playerData.gameName) {
      identifiers.push(playerData.gameName);
    }

    // Formato de display name se disponível
    if (playerData.displayName) {
      identifiers.push(playerData.displayName);
    }

    // ✅ REDUZIR SPAM: Só logar se os identificadores mudaram
    const filteredIdentifiers = identifiers.filter(Boolean);
    if (JSON.stringify(filteredIdentifiers) !== JSON.stringify(this.lastIdentifiers)) {
      console.log('🔍 [QueueState] Identificadores construídos:', filteredIdentifiers);
    }
    return filteredIdentifiers;
  }

  /**
   * Verificar se um jogador da fila corresponde aos identificadores do usuário atual
   */
  private matchPlayerIdentifiers(queuePlayer: any, identifiers: string[]): boolean {
    if (!queuePlayer || !identifiers.length) return false;

    // Nome completo do jogador na fila
    const queuePlayerName = queuePlayer.summonerName;

    // Verificar correspondência exata
    for (const identifier of identifiers) {
      if (queuePlayerName === identifier) {
        // ✅ REDUZIR SPAM: Só logar se não estava na fila antes
        if (!this.queueStateSubject.value.isInQueue) {
          console.log(`✅ [QueueState] Correspondência exata encontrada: ${queuePlayerName} === ${identifier}`);
        }
        return true;
      }

      // Verificar correspondência por base name (ignorando tag)
      const queueBaseName = queuePlayerName.split('#')[0];
      const identifierBaseName = identifier.split('#')[0];

      if (queueBaseName === identifierBaseName) {
        // ✅ REDUZIR SPAM: Só logar se não estava na fila antes
        if (!this.queueStateSubject.value.isInQueue) {
          console.log(`✅ [QueueState] Correspondência por base name: ${queueBaseName} === ${identifierBaseName}`);
        }
        return true;
      }
    }

    return false;
  }

  updateCurrentPlayer(playerData: any): void {
    this.currentPlayerData = playerData;
    console.log('🔄 [QueueState] Dados do jogador atual atualizados:', playerData);
  }

  getQueueState(): Observable<QueueState> {
    return this.queueStateSubject.asObservable();
  }

  getCurrentState(): QueueState {
    return this.queueStateSubject.value;
  }

  /**
   * REMOVIDO: updateCentralizedQueue e updateP2PQueue
   * Agora apenas a tabela queue_players é a fonte de verdade
   */

  resetState(): void {
    const resetState: QueueState = {
      isInQueue: false,
      position: undefined,
      waitTime: 0,
      estimatedTime: 0,
      playersInQueue: 0,
      averageWaitTime: 0
    };
    this.queueStateSubject.next(resetState);
    console.log('🔄 [QueueState] Estado resetado');
  }

  getActiveSystem(): 'centralized' | 'none' {
    return 'centralized'; // Assuming centralized system
  }

  isInQueue(): boolean {
    return this.queueStateSubject.value.isInQueue;
  }

  getQueuePosition(): number | undefined {
    return this.queueStateSubject.value.position;
  }

  getPlayersInQueue(): number | undefined {
    return this.queueStateSubject.value.playersInQueue;
  }
}

