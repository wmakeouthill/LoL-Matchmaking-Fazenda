import { Component, OnInit, OnDestroy, ChangeDetectorRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Subject, takeUntil, take, firstValueFrom, timeout, catchError, of } from 'rxjs';

import { DashboardComponent } from './components/dashboard/dashboard';
import { QueueComponent } from './components/queue/queue';
import { MatchHistoryComponent } from './components/match-history/match-history';
import { LeaderboardComponent } from './components/leaderboard/leaderboard';
import { MatchFoundComponent, MatchFoundData } from './components/match-found/match-found';
import { DraftPickBanComponent } from './components/draft/draft-pick-ban';
import { GameInProgressComponent } from './components/game-in-progress/game-in-progress';
import { AdjustLpModalComponent } from './components/settings/adjust-lp-modal';
import { ChampionshipModalComponent } from './components/settings/championship-modal';
import { ApiService } from './services/api';
import { QueueStateService } from './services/queue-state';
import { DiscordIntegrationService } from './services/discord-integration.service';
import { BotService } from './services/bot.service';
import { CurrentSummonerService } from './services/current-summoner.service';
import { Player, QueueStatus, LCUStatus, QueuePreferences } from './interfaces';
import type { Notification } from './interfaces';
import { logApp } from './utils/app-logger';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    DashboardComponent,
    QueueComponent,
    MatchHistoryComponent,
    LeaderboardComponent,
    MatchFoundComponent,
    DraftPickBanComponent,
    GameInProgressComponent,
    AdjustLpModalComponent,
    ChampionshipModalComponent
  ],
  templateUrl: './app-simple.html',
  styleUrl: './app.scss'
})
export class App implements OnInit, OnDestroy {
  protected title = 'LoL Matchmaking';

  // ✅ MANTIDO: Estado da aplicação (interface)
  currentView: 'dashboard' | 'queue' | 'history' | 'leaderboard' | 'settings' = 'dashboard';
  isElectron = false;
  isConnected = false;
  isInQueue: boolean = false;

  // ✅ MANTIDO: Dados do jogador (com setter para sincronizar com CurrentSummonerService)
  private _currentPlayer: Player | null = null;
  get currentPlayer(): Player | null {
    return this._currentPlayer;
  }
  set currentPlayer(value: Player | null) {
    this._currentPlayer = value;
    this.updateCurrentSummonerService();
  }

  // ✅ MANTIDO: Status da fila e do LCU (para exibição)
  queueStatus: QueueStatus = {
    playersInQueue: 0,
    averageWaitTime: 0,
    estimatedMatchTime: 0,
    isActive: true
  };
  lcuStatus: LCUStatus = { isConnected: false };

  // ✅ MANTIDO: Estados das fases (interface)
  matchFoundData: MatchFoundData | null = null;
  showMatchFound = false;
  inDraftPhase = false;
  draftData: any = null;
  draftTimer: number = 30; // ✅ TIMER SEPARADO

  // ✅ DEBUG: Propriedade com setter para rastrear mudanças
  private _inGamePhase = false;
  get inGamePhase() {
    return this._inGamePhase;
  }
  set inGamePhase(value: boolean) {
    if (this._inGamePhase !== value) {
      console.log(`🔄 [App] ⚠️ inGamePhase mudando de ${this._inGamePhase} para ${value}`);
      console.trace('Stack trace da mudança:');
    }
    this._inGamePhase = value;
  }

  gameData: any = null;
  gameResult: any = null;

  // ✅ NOVO: Flag para indicar que o jogo foi restaurado
  isRestoredMatch = false;

  // ✅ MANTIDO: Interface (sem lógica)
  notifications: Notification[] = [];
  settingsForm = {
    summonerName: '',
    region: 'br1',
    riotApiKey: '',
    discordBotToken: '',
    discordChannel: ''
  };
  // Lista de usuários especiais carregada do backend (settings.special_users)
  private specialUsers: string[] = [];
  discordStatus = {
    isConnected: false,
    botUsername: '',
    queueSize: 0,
    activeMatches: 0,
    inChannel: false
  };

  // ✅ NOVO: Controle dos modais de Special Users
  showAdjustLpModal: boolean = false;
  showChampionshipModal: boolean = false;

  private readonly destroy$ = new Subject<void>();
  private lastIgnoreLogTime = 0;
  private lastTimerUpdate = 0; // ✅ NOVO: Throttle para timer updates
  private lastMatchId: number | null = null; // ✅ NOVO: Rastrear última partida processada
  private lastMessageTimestamp = 0; // ✅ NOVO: Throttle para mensagens backend

  // ✅ NOVO: Controle de auto-refresh para sincronizar com o queue component
  private autoRefreshEnabled = false;

  // ✅ NOVO: Controle para priorizar backend sobre QueueStateService
  private hasRecentBackendQueueStatus = false;

  private lcuCheckInterval: any; // ✅ REMOVIDO readonly para permitir reatribuição
  private readonly LCU_CHECK_INTERVAL = 5000; // Intervalo de verificação do status do LCU

  // Update the notifications array and addNotification method
  private readonly maxNotifications = 2; // Limit to 2 visible notifications
  private readonly notificationQueue: Notification[] = []; // Queue for pending notifications

  // ✅ NOVO: Sistema de polling inteligente para sincronização
  private pollingInterval: any = null;
  private lastPollingStatus: string | null = null;
  private readonly POLLING_INTERVAL_MS = 10000; // 10 segundos - reduzir frequência
  private lastCacheInvalidation = 0; // ✅ NOVO: Controle de invalidação de cache
  private readonly CACHE_INVALIDATION_COOLDOWN = 3000; // ✅ NOVO: 3 segundos entre invalidações
  private lastBackendAction = 0; // ✅ NOVO: Controle de ações do backend
  private readonly BACKEND_ACTION_COOLDOWN = 1500; // ✅ NOVO: 1.5 segundos após ação do backend

  @ViewChild(DraftPickBanComponent) draftComponentRef: DraftPickBanComponent | undefined;

  private lcuTelemetryInterval: any = null;
  private readonly LCU_TELEMETRY_INTERVAL_MS = 5000;

  constructor(
    private readonly http: HttpClient,
    private readonly apiService: ApiService,
    private readonly queueStateService: QueueStateService,
    private readonly discordService: DiscordIntegrationService,
    private readonly botService: BotService,
    private readonly currentSummonerService: CurrentSummonerService,
    private readonly cdr: ChangeDetectorRef
  ) {
    console.log(`[App] Constructor`);

    // ✅ REMOVIDO: Não criar interval duplicado aqui (será criado em ngOnInit)
    // this.lcuCheckInterval = setInterval(() => this.startLCUStatusCheck(), this.LCU_CHECK_INTERVAL);

    this.isElectron = !!(window as any).electronAPI;
  }

  ngOnInit(): void {
    // ✅ EXPOR appComponent no window para componentes filhos acessarem
    (window as any).appComponent = this;

    // ✅ NOVO: Teste inicial da função logApp
    logApp('🚀 [App] === INICIALIZAÇÃO DO APP ===');
    logApp('🚀 [App] Verificando se logApp está funcionando...');
    logApp('🚀 [App] Timestamp:', new Date().toISOString());
    logApp('🚀 [App] Electron API disponível:', !!(window as any).electronAPI);
    logApp('🚀 [App] FS disponível:', !!(window as any).electronAPI?.fs);
    logApp('🚀 [App] Path disponível:', !!(window as any).electronAPI?.path);
    logApp('🚀 [App] Process disponível:', !!(window as any).electronAPI?.process);

    // ✅ NOVO: Configurar handlers globais de erro
    window.onerror = function (message, source, lineno, colno, error) {
      logApp('❌ [GLOBAL ERROR]', message, source, lineno, colno, error);
    };
    window.onunhandledrejection = function (event) {
      logApp('❌ [UNHANDLED PROMISE REJECTION]', event.reason);
    };

    console.log('🚀 [App] Inicializando frontend como interface para backend...');

    // ✅ NOVO: Sequência de inicialização corrigida
    this.initializeAppSequence();
  }

  // ✅ NOVO: Sequência de inicialização estruturada para evitar race conditions
  private async initializeAppSequence(): Promise<void> {
    try {
      console.log('🔄 [App] === INÍCIO DA SEQUÊNCIA DE INICIALIZAÇÃO ===');

      // 1. Configurações básicas (não dependem de conexões)
      console.log('🔄 [App] Passo 1: Configurações básicas...');
      this.setupDiscordStatusListener();
      this.startLCUStatusCheck();

      // 2. Verificar se backend está acessível
      console.log('🔄 [App] Passo 2: Verificando backend...');
      await this.ensureBackendIsReady();

      // 3. Configurar comunicação WebSocket
      console.log('🔄 [App] Passo 3: Configurando WebSocket...');
      await this.setupBackendCommunication();

      // ✅ REMOVIDO: Passos 4 e 5 agora são executados APENAS quando evento lcu_connection_registered chegar
      // Isso garante que o summonerName esteja disponível ANTES de qualquer chamada HTTP
      // 4. Carregar dados do jogador - AGORA FEITO NO EVENTO lcu_connection_registered
      // 5. Identificar jogador no WebSocket - AGORA FEITO NO EVENTO lcu_connection_registered

      // 6. Buscar status inicial da fila
      console.log('🔄 [App] Passo 6: Buscando status da fila...');
      this.refreshQueueStatus();

      // 7. Carregar configurações do banco
      console.log('🔄 [App] Passo 7: Carregando configurações...');
      this.loadConfigFromDatabase();

      // 8. Iniciar atualizações periódicas
      console.log('🔄 [App] Passo 8: Iniciando atualizações periódicas...');
      this.startPeriodicUpdates();

      // 9. Iniciar polling inteligente para sincronização
      console.log('🔄 [App] Passo 9: Iniciando polling inteligente...');
      this.startIntelligentPolling();

      // ✅ REMOVIDO: Verificação movida para identifyCurrentPlayerOnConnect()
      // (será executada 5 segundos APÓS o player ser carregado do LCU)

      console.log('✅ [App] === INICIALIZAÇÃO COMPLETA ===');
      this.isConnected = true;

    } catch (error) {
      console.error('❌ [App] Erro na sequência de inicialização:', error);
      this.handleInitializationError(error);
    }
  }

  // ✅ NOVO: Garantir que backend está pronto antes de prosseguir
  private async ensureBackendIsReady(): Promise<void> {
    const maxAttempts = 10;
    const delayBetweenAttempts = 2000; // 2 segundos

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        await firstValueFrom(this.apiService.checkHealth());
        console.log(`✅ [App] Backend está pronto (tentativa ${attempt}/${maxAttempts})`);
        return;
      } catch (error) {
        console.log(`⏳ [App] Backend não está pronto (tentativa ${attempt}/${maxAttempts}):`, error);

        if (attempt === maxAttempts) {
          throw new Error('Backend não ficou pronto após múltiplas tentativas');
        }

        await new Promise(resolve => setTimeout(resolve, delayBetweenAttempts));
      }
    }
  }

  // ✅ CORRIGIDO: Configurar comunicação com backend de forma assíncrona
  private async setupBackendCommunication(): Promise<void> {
    console.log('🔗 [App] Configurando comunicação com backend...');

    // Configurar listener de mensagens WebSocket
    this.apiService.onWebSocketMessage().pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (message: any) => {
        console.log('📨 [App] Mensagem do backend:', message);
        this.handleBackendMessage(message);
      },
      error: (error: any) => {
        console.error('❌ [App] Erro na comunicação:', error);
        this.isConnected = false;
      },
      complete: () => {
        console.log('🔌 [App] Conexão WebSocket fechada');
        this.isConnected = false;
      }
    });

    // ✅ OTIMIZADO: Carregar dados IMEDIATAMENTE sem esperar eventos
    // Removi o timeout de 10 segundos que estava bloqueando a UI
    console.log('⚡ [App] Carregando dados do LCU imediatamente (sem espera)...');

    // ✅ CRÍTICO: Carregar dados imediatamente, não esperar eventos
    this.tryGetCurrentPlayerDetails();

    // ✅ OPCIONAL: Escutar evento LCU em paralelo para atualizar se necessário
    this.apiService.onLcuRegistered().pipe(
      take(1), // Apenas o primeiro evento
      takeUntil(this.destroy$)
    ).subscribe({
      next: async (summonerName: string) => {
        console.log('✅ [App] LCU registrado para:', summonerName);
        // Armazenar summonerName no ApiService ANTES de qualquer chamada HTTP
        this.apiService.setCurrentSummonerName(summonerName);
        console.log(`✅ [App] SummonerName configurado: ${summonerName}`);

        // Se ainda não carregou dados, carregar agora
        if (!this.currentPlayer) {
          console.log('🔄 [App] Carregando dados do jogador após evento LCU...');
          await this.tryLoadPlayerData(summonerName);
        }
      },
      error: (error: any) => {
        console.warn('⚠️ [App] Erro ao receber evento LCU:', error);
      }
    });

    // ✅ NOVO: Aguardar explicitamente que WebSocket esteja pronto
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error('Timeout aguardando WebSocket conectar'));
      }, 15000); // 15 segundos de timeout

      this.apiService.onWebSocketReady()
        .pipe(
          take(1)
        )
        .subscribe({
          next: () => {
            clearTimeout(timeout);
            console.log('✅ [App] WebSocket está pronto para comunicação');
            // Iniciar telemetria do LCU apenas no Electron
            this.startLcuTelemetry();
            resolve();
          },
          error: (error: any) => {
            clearTimeout(timeout);
            reject(error instanceof Error ? error : new Error(String(error)));
          }
        });
    });
  }

  private startLcuTelemetry(): void {
    const electronAPI = (window as any).electronAPI;
    if (!this.isElectron || !electronAPI?.lcu) {
      console.log('ℹ️ [App] Telemetria LCU desabilitada (não-Electron ou sem conector)');
      return;
    }

    if (this.lcuTelemetryInterval) {
      clearInterval(this.lcuTelemetryInterval);
    }

    const tick = async () => {
      try {
        const [summoner, phase, session] = await Promise.all([
          electronAPI.lcu.getCurrentSummoner().catch(() => null),
          electronAPI.lcu.getGameflowPhase().catch(() => null),
          electronAPI.lcu.getSession().catch(() => null)
        ]);

        const payload = {
          timestamp: new Date().toISOString(),
          phase,
          session: session ? { gameId: session?.gameData?.gameId, mapId: session?.mapId, queueId: session?.gameData?.queue?.id } : null,
          summoner: summoner ? {
            gameName: summoner.gameName ?? summoner.displayName,
            tagLine: summoner.tagLine,
            puuid: summoner.puuid,
            summonerId: summoner.summonerId,
            summonerLevel: summoner.summonerLevel,
            profileIconId: summoner.profileIconId
          } : null
        };

        // Atualizar localmente o status do LCU para a UI
        this.lcuStatus = {
          isConnected: !!(summoner || session || phase),
          gameflowPhase: phase,
          summoner: payload.summoner,
          lobby: undefined
        } as any;

        this.apiService.sendWebSocketMessage({ type: 'lcu_status', data: payload });
      } catch (e) {
        console.warn('⚠️ [App] Falha ao coletar telemetria LCU:', e);
      }
    };

    // ✅ OTIMIZADO: Usar polling mais lento quando jogador já identificado
    const intervalTime = this.currentPlayer ? 15000 : 5000;

    // Disparar imediatamente e depois repetir no intervalo
    tick();
    this.lcuTelemetryInterval = setInterval(tick, intervalTime);
    console.log(`📡 [App] Telemetria LCU iniciada com intervalo de ${intervalTime}ms`);
  }

  // Simplificar para evitar referências indefinidas
  private startGameInProgressFromSimulation(simulationData: any): void {
    try {
      const matchId = simulationData?.matchId ?? null;
      const team1 = simulationData?.team1 ?? [];
      const team2 = simulationData?.team2 ?? [];
      this.gameData = {
        matchId,
        team1,
        team2,
        status: 'in_progress',
        startedAt: new Date(),
        estimatedDuration: 1800,
        pickBanData: simulationData?.pickBanData ?? {}
      };
      this.inGamePhase = true;
      this.inDraftPhase = false;
      this.showMatchFound = false;
      this.isInQueue = false;
      this.lastMatchId = matchId;
      this.addNotification('success', 'Jogo Iniciado!', 'A partida começou.');
    } catch (e) {
      console.error('❌ [App] Erro ao iniciar jogo pela simulação:', e);
    }
  }

  // ===== Missing helper methods (minimal implementations) =====
  private async loadPlayerDataWithRetry(): Promise<void> {
    return new Promise((resolve) => {
      this.loadPlayerData();
      resolve();
    });
  }

  private async identifyPlayerSafely(): Promise<void> {
    console.log('🔍 [App] identifyPlayerSafely() chamado, currentPlayer:', !!this.currentPlayer);
    if (!this.currentPlayer) {
      console.warn('⚠️ [App] identifyPlayerSafely: Sem currentPlayer');
      return;
    }

    const data = {
      type: 'identify_player',
      playerData: {
        displayName: this.currentPlayer.displayName || this.currentPlayer.summonerName,
        summonerName: this.currentPlayer.summonerName,
        gameName: this.currentPlayer.gameName,
        tagLine: this.currentPlayer.tagLine,
        id: this.currentPlayer.id,
        puuid: this.currentPlayer.puuid,
        region: this.currentPlayer.region,
        profileIconId: this.currentPlayer.profileIconId,
        summonerLevel: this.currentPlayer.summonerLevel
      }
    };

    console.log('📤 [App] Enviando identificação do jogador via WebSocket:', data.playerData.displayName);
    this.apiService.sendWebSocketMessage(data);
  }


  private handleInitializationError(error: any): void {
    console.error('❌ [App] Erro na inicialização:', error);
    this.addNotification('error', 'Erro de Inicialização', String(error?.message || error));
  }

  /**
   * ✅ NOVO: Verifica se jogador tem partida ativa e restaura o estado
   * Chamado ao iniciar app para restaurar draft/game em andamento
   */
  private async checkAndRestoreActiveMatch(): Promise<void> {
    console.log('🔍 [App] ========== checkAndRestoreActiveMatch() INICIADO ==========');
    try {
      // Verificar se temos dados do jogador
      if (!this.currentPlayer) {
        console.log('⚠️ [App] Sem dados do jogador para verificar partida ativa');
        return;
      }

      console.log('👤 [App] currentPlayer disponível:', {
        displayName: this.currentPlayer.displayName,
        summonerName: this.currentPlayer.summonerName,
        gameName: this.currentPlayer.gameName,
        tagLine: this.currentPlayer.tagLine
      });

      // ✅ CORRIGIDO: Construir nome completo gameName#tagLine
      let playerName: string;

      if (this.currentPlayer.gameName && this.currentPlayer.tagLine) {
        // Formato correto: gameName#tagLine
        playerName = `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`;
      } else if (this.currentPlayer.displayName) {
        playerName = this.currentPlayer.displayName;
      } else if (this.currentPlayer.summonerName) {
        playerName = this.currentPlayer.summonerName;
      } else {
        console.log('⚠️ [App] Player não tem nome válido');
        return;
      }

      console.log('🔍 [App] Verificando se jogador tem partida ativa:', playerName);
      console.log('📡 [App] Chamando apiService.getMyActiveMatch()...');

      const activeMatch: any = await firstValueFrom(
        this.apiService.getMyActiveMatch(playerName)
      );

      console.log('📥 [App] Resposta do backend recebida:', activeMatch);

      if (!activeMatch || !activeMatch.id) {
        console.log('✅ [App] Nenhuma partida ativa encontrada (activeMatch vazio ou sem ID)');
        return;
      }

      console.log('🎮 [App] ✅ PARTIDA ATIVA ENCONTRADA!', {
        id: activeMatch.id,
        status: activeMatch.status,
        title: activeMatch.title,
        type: activeMatch.type,
        matchId: activeMatch.matchId
      });

      // Redirecionar baseado no status
      if (activeMatch.status === 'draft') {
        console.log('🎯 [App] Restaurando estado de DRAFT...');
        this.isRestoredMatch = true; // ✅ MARCAR COMO RESTAURADO
        this.inDraftPhase = true;
        this.inGamePhase = false;
        this.showMatchFound = false;

        // Montar dados do draft
        this.draftData = {
          matchId: activeMatch.matchId || activeMatch.id,
          state: activeMatch.draftState,
          team1: activeMatch.team1,
          team2: activeMatch.team2
        };

        console.log('✅ [App] Estado de draft restaurado:', this.draftData);
        this.cdr.detectChanges();

      } else if (activeMatch.status === 'in_progress') {
        console.log('🎯 [App] Restaurando estado de GAME IN PROGRESS...');
        console.log('🔍 [App] ANTES: inGamePhase =', this.inGamePhase, ', gameData =', this.gameData);

        this.isRestoredMatch = true; // ✅ MARCAR COMO RESTAURADO
        this.inGamePhase = true;
        this.inDraftPhase = false;
        this.showMatchFound = false;

        console.log('🔍 [App] APÓS FLAGS: inGamePhase =', this.inGamePhase, ', isRestoredMatch =', this.isRestoredMatch);

        // Montar dados do game
        this.gameData = {
          matchId: activeMatch.matchId || activeMatch.id,
          team1: Array.isArray(activeMatch.team1) ? activeMatch.team1 : [],
          team2: Array.isArray(activeMatch.team2) ? activeMatch.team2 : [],
          pickBanData: activeMatch.pickBanData,
          sessionId: activeMatch.sessionId || `restored-${activeMatch.id}`,
          gameId: activeMatch.gameId || String(activeMatch.id),
          startTime: activeMatch.startTime ? new Date(activeMatch.startTime) : new Date(),
          isCustomGame: activeMatch.isCustomGame !== false
        };

        console.log('✅ [App] Estado de game in progress restaurado:', {
          matchId: this.gameData.matchId,
          team1Count: this.gameData.team1?.length || 0,
          team2Count: this.gameData.team2?.length || 0,
          hasPickBanData: !!this.gameData.pickBanData,
          team1Sample: this.gameData.team1?.[0],
          team2Sample: this.gameData.team2?.[0],
          pickBanDataStructure: this.gameData.pickBanData ? Object.keys(this.gameData.pickBanData) : null
        });

        console.log('🔍 [App] FINAL: inGamePhase =', this.inGamePhase, ', gameData exists =', !!this.gameData);
        console.log('🔄 [App] Chamando detectChanges para forçar atualização da view...');
        this.cdr.detectChanges();
        console.log('✅ [App] detectChanges concluído');
      }

    } catch (error: any) {
      console.error('❌ [App] Erro ao verificar partida ativa:', error);
      console.error('❌ [App] Status do erro:', error.status);
      console.error('❌ [App] Mensagem do erro:', error.message);

      if (error.status === 404) {
        console.log('✅ [App] Nenhuma partida ativa (404)');
      } else {
        console.error('❌ [App] Erro ao verificar partida ativa:', error);
      }
    }
  }

  private handleBackendMessage(message: any): void {
    // Processar mensagens básicas; expandir conforme necessário
    if (!message || !message.type) return;

    // ✅ LOG DETALHADO: Mostrar TODOS os eventos que chegam
    console.log(`📡 [App] ========================================`);
    console.log(`📡 [App] WebSocket Event: ${message.type}`);
    console.log(`📡 [App] Message completo:`, JSON.stringify(message, null, 2));
    console.log(`📡 [App] Estado atual antes do evento:`, {
      inGamePhase: this.inGamePhase,
      inDraftPhase: this.inDraftPhase,
      isRestoredMatch: this.isRestoredMatch,
      showMatchFound: this.showMatchFound,
      draftDataMatchId: this.draftData?.matchId
    });
    console.log(`📡 [App] ========================================`);

    // ✅ CORREÇÃO: Filtrar mensagens de ACK para não despachar eventos desnecessários
    // Mensagens "_ack" são apenas confirmações internas e não devem ser processadas como eventos
    const isAckMessage = message.type && message.type.endsWith('_ack');
    if (!isAckMessage) {
      // ✅ NOVO: Despachar evento customizado para o document (para listeners em outros componentes)
      const customEvent = new CustomEvent(message.type, { detail: message });
      document.dispatchEvent(customEvent);
    } else {
      console.log(`🔕 [App] Mensagem ACK ignorada (não despachada como evento): ${message.type}`);
    }

    switch (message.type) {
      case 'queue_status':
        if (message.status) {
          this.queueStatus = message.status;
        }
        break;

      case 'queue_update':
        // ✅ NOVO: Processar queue_update do Redis Pub/Sub
        console.log('📊 [App] queue_update recebido via Pub/Sub:', message);
        if (message.data && Array.isArray(message.data)) {
          // Atualizar lista de jogadores na fila
          this.queueStatus = {
            ...this.queueStatus,
            playersInQueue: message.data.length,
            playersInQueueList: message.data,
            isActive: true
          };
          console.log('✅ [App] queueStatus atualizado via queue_update:', {
            playersInQueue: this.queueStatus.playersInQueue,
            playersCount: message.data.length
          });
        }
        break;

      case 'backend_connection_success':
        console.log('🔌 [App] Backend conectado');
        break;
      case 'match_found':
        console.log('🎮 [App] Match found recebido:', message);
        console.log('🎮 [App] Match found JSON:', JSON.stringify(message, null, 2));
        this.handleMatchFound(message);
        break;
      case 'acceptance_progress':
        console.log('📊 [App] Progresso de aceitação:', message);
        // Atualizar dados do MatchFoundComponent se estiver visível
        if (this.showMatchFound && this.matchFoundData) {
          const progressData = message.data || message;
          this.matchFoundData.acceptedCount = progressData.acceptedCount || 0;
          this.matchFoundData.totalPlayers = progressData.totalPlayers || 10;
          this.cdr.detectChanges();
        }
        break;
      case 'match_cancelled':
        console.log('❌ [App] Match cancelado:', message);

        // ✅ CORREÇÃO: NÃO resetar se for uma partida restaurada
        if (this.isRestoredMatch) {
          console.log('⚠️ [App] Ignorando match_cancelled pois partida foi restaurada');
          break;
        }

        // Limpar estado de match found
        this.showMatchFound = false;
        this.matchFoundData = null;

        // ✅ NOVO: Limpar estado de draft e game in progress
        if (this.inDraftPhase || this.inGamePhase) {
          console.log('❌ [App] Redirecionando para tela inicial (partida cancelada)');
          this.inDraftPhase = false;
          this.inGamePhase = false;
          this.draftData = null;
          this.gameData = null;
          alert('Partida cancelada. Você foi redirecionado para a tela inicial.');
        }

        this.cdr.detectChanges();
        break;
      case 'draft_update': // ✅ APENAS Timer (leve, a cada 1s)
        console.log('⏰ [App] draft_update (TIMER) recebido:', message);

        // ✅ PROCESSAR APENAS TIMER - SEPARADO de draftData!
        if (this.inDraftPhase && this.draftData) {
          const timerData = message.data || message;
          this.draftTimer = timerData.timeRemaining !== undefined
            ? timerData.timeRemaining
            : 30;

          console.log(`⏰ [App] Timer atualizado: ${this.draftTimer}s`);
          this.cdr.detectChanges();
        }
        break;

      case 'draft_updated': // ✅ Picks/Bans/Ações (JSON completo)
        console.log('📋 [App] draft_updated (AÇÕES) recebido:', message);
        console.log('📋 [App] Estado atual:', {
          inDraftPhase: this.inDraftPhase,
          hasDraftData: !!this.draftData,
          draftDataMatchId: this.draftData?.matchId,
          messageMatchId: message.matchId || message.data?.matchId
        });

        // ✅ Atualizar draftData com as informações recebidas
        if (this.inDraftPhase && this.draftData) {
          const updateData = message.data || message;

          console.log('📋📋📋 [App] ESTRUTURA DA MENSAGEM:', {
            'message.type': message.type,
            'message.data exists': !!message.data,
            'message.data.timeRemaining': message.data?.timeRemaining,
            'message.data.matchId': message.data?.matchId,
            'message.timeRemaining (direto)': message.timeRemaining,
            'message.matchId (direto)': message.matchId,
            'updateData (usado)': updateData
          });

          console.log('📋 [App] updateData extraído:', {
            hasPhases: !!updateData.phases,
            phasesLength: updateData.phases?.length || 0,
            hasActions: !!updateData.actions,
            actionsLength: updateData.actions?.length || 0,
            currentAction: updateData.currentAction,
            currentIndex: updateData.currentIndex,
            currentPlayer: updateData.currentPlayer,
            timeRemaining: updateData.timeRemaining, // ✅ LOG CRÍTICO
            matchId: updateData.matchId,
            id: updateData.id,
            remainingMs: updateData.remainingMs,
            timeRemainingMs: updateData.timeRemainingMs
          });

          // ✅ CRÍTICO: Criar NOVO objeto para disparar ngOnChanges (OnPush detection)
          const newPhases = (updateData.phases && updateData.phases.length > 0) ? updateData.phases :
            (updateData.actions && updateData.actions.length > 0) ? updateData.actions :
              this.draftData.phases;

          const newCurrentAction = updateData.currentAction !== undefined ? updateData.currentAction :
            updateData.currentIndex !== undefined ? updateData.currentIndex :
              this.draftData.currentAction;

          const newCurrentPlayer = updateData.currentPlayer !== undefined ? updateData.currentPlayer : this.draftData.currentPlayer;

          // ❌ REMOVIDO: Timer NÃO vem mais no draft_updated!
          // O timer agora vem SEPARADO via draft_update
          // const newTimeRemaining = ...

          console.log('📋 [App] Dados extraídos do draft_updated (SEM TIMER):', {
            currentPlayer: newCurrentPlayer,
            currentAction: newCurrentAction,
            phasesLength: newPhases?.length || 0
          });

          // ✅ NOVA ESTRUTURA HIERÁRQUICA: Processar teams.blue/red
          console.log('🔨 [App] Processando estrutura hierárquica:', {
            hasTeams: !!updateData.teams,
            hasTeamsBlue: !!updateData.teams?.blue,
            hasTeamsRed: !!updateData.teams?.red,
            bluePlayers: updateData.teams?.blue?.players?.length || 0,
            redPlayers: updateData.teams?.red?.players?.length || 0,
            currentPhase: updateData.currentPhase,
            currentTeam: updateData.currentTeam
          });

          // ✅ CRÍTICO: MatchId pode vir como "id" ou "matchId"
          const effectiveMatchId = updateData.matchId || updateData.id || this.draftData.matchId;

          // ✅ CRÍTICO: Criar NOVO objeto SEMPRE (para OnPush detectar)
          const oldDraftData = this.draftData;
          this.draftData = {
            ...this.draftData,
            matchId: effectiveMatchId, // ✅ CRÍTICO: Usar fallback consistente
            phases: newPhases,
            actions: newPhases,
            currentAction: newCurrentAction,
            currentIndex: newCurrentAction,
            currentPlayer: newCurrentPlayer,
            // ❌ REMOVIDO: timeRemaining NÃO vem mais no draft_updated!
            // timeRemaining: newTimeRemaining,

            // ✅ NOVA ESTRUTURA HIERÁRQUICA
            teams: updateData.teams || this.draftData.teams, // teams.blue/red com players e actions
            currentPhase: updateData.currentPhase || this.draftData.currentPhase, // ban1/pick1/ban2/pick2
            currentTeam: updateData.currentTeam || this.draftData.currentTeam, // blue/red
            currentActionType: updateData.currentActionType || this.draftData.currentActionType, // ban/pick

            _updateTimestamp: Date.now() // ✅ FORÇA mudança de referência
          };

          console.log(`✅ [App] Draft atualizado (AÇÕES): currentAction=${this.draftData.currentAction}, currentPlayer=${this.draftData.currentPlayer}, phases=${this.draftData.phases?.length}, teams=${!!this.draftData.teams}`);
          console.log(`🔍 [App] Referência mudou:`, {
            old: oldDraftData,
            new: this.draftData,
            referenceChanged: oldDraftData !== this.draftData
          });

          // ✅ CRÍTICO: Log detalhado do matchId antes de disparar evento
          console.log('🔑 [App] Verificando matchId antes de disparar eventos:', {
            'this.draftData.matchId': this.draftData.matchId,
            'typeof': typeof this.draftData.matchId,
            'oldDraftData.matchId': oldDraftData?.matchId,
            'updateData.matchId': updateData.matchId,
            'message.matchId': message.matchId
          });

          // ✅ Despachar evento customizado para o DraftPickBanComponent (AÇÕES, SEM TIMER)
          console.log('📤📤📤 [App] Disparando draftUpdate (APENAS AÇÕES):', {
            matchId: this.draftData.matchId,
            matchIdType: typeof this.draftData.matchId,
            currentPlayer: updateData.currentPlayer,
            currentAction: newCurrentAction,
            timestamp: Date.now()
          });
          document.dispatchEvent(new CustomEvent('draftUpdate', {
            detail: {
              matchId: this.draftData.matchId,
              ...updateData
              // ❌ REMOVIDO: timeRemaining não vem mais no draft_updated!
            }
          }));

          // ✅ CRÍTICO: Marcar para verificação E forçar detecção de mudanças
          this.cdr.markForCheck();
          this.cdr.detectChanges();
        }
        break;
      case 'all_players_accepted':
        console.log('✅ [App] Todos jogadores aceitaram:', message);
        // ✅ Esconder modal de match found mas MANTER os dados para o draft
        this.showMatchFound = false;
        this.cdr.detectChanges();
        // Nota: matchFoundData será usado quando draft_started chegar
        console.log('⏳ [App] Aguardando mensagem draft_started do backend...');
        break;
      case 'draft_started':
      case 'draft_starting':
        console.log('🎯 [App] Draft iniciando:', message);
        const draftData = message.data || message;

        // ✅ CORREÇÃO CRÍTICA: Backend envia "actions", não "phases"!
        const phases = (draftData.phases && draftData.phases.length > 0) ? draftData.phases :
          (draftData.actions && draftData.actions.length > 0) ? draftData.actions : [];

        const currentAction = draftData.currentAction !== undefined ? draftData.currentAction :
          draftData.currentIndex !== undefined ? draftData.currentIndex : 0;

        console.log('🎯 [App] Extraindo dados do draft:', {
          hasPhasesInMessage: !!draftData.phases,
          phasesLength: draftData.phases?.length,
          hasActionsInMessage: !!draftData.actions,
          actionsLength: draftData.actions?.length,
          extractedPhasesLength: phases.length,
          currentAction: currentAction
        });

        // ✅ CRÍTICO: Verificar se backend enviou timer
        if (!draftData.timeRemaining && draftData.timeRemaining !== 0) {
          console.warn('⚠️ [App] Backend NÃO enviou timeRemaining no draft_started! Usando fallback.');
        }

        // ✅ CRÍTICO: Usar fallback consistente (matchId || id)
        const effectiveMatchId = draftData.matchId || draftData.id || this.matchFoundData?.matchId;
        console.log('🔑 [App] MatchId para draft:', {
          'draftData.matchId': draftData.matchId,
          'draftData.id': draftData.id,
          'matchFoundData.matchId': this.matchFoundData?.matchId,
          'effectiveMatchId': effectiveMatchId
        });

        // ✅ Preparar dados do draft com informações completas dos times
        this.draftData = {
          matchId: effectiveMatchId,
          team1: draftData.team1 || this.matchFoundData?.teammates || [],
          team2: draftData.team2 || this.matchFoundData?.enemies || [],
          phases: phases,  // ✅ Usar o array extraído corretamente
          actions: phases,  // ✅ Adicionar também como "actions" para compatibilidade
          currentAction: currentAction,  // ✅ Passar currentAction explicitamente
          currentIndex: currentAction,  // ✅ Adicionar também como "currentIndex" para compatibilidade
          currentPlayer: draftData.currentPlayer,  // ✅ CRÍTICO: Jogador da VEZ (do backend), não jogador logado
          timeRemaining: draftData.timeRemaining !== undefined ? draftData.timeRemaining : 30,  // ✅ CORREÇÃO: Usar undefined check
          averageMMR: draftData.averageMMR || this.matchFoundData?.averageMMR,
          balanceQuality: draftData.balanceQuality,
          autofillCount: draftData.autofillCount
        };

        console.log('🎯 [App] Dados do draft preparados:', {
          matchId: this.draftData.matchId,
          team1Length: this.draftData.team1?.length || 0,
          team2Length: this.draftData.team2?.length || 0,
          phasesLength: this.draftData.phases?.length || 0,
          currentAction: this.draftData.currentAction,
          currentPlayer: this.draftData.currentPlayer,
          timeRemaining: this.draftData.timeRemaining,
          timeRemainingFromBackend: draftData.timeRemaining,
          usedFallback: draftData.timeRemaining === undefined
        });

        // Entrar no draft
        this.inDraftPhase = true;
        this.draftTimer = 30; // ✅ RESETAR timer ao iniciar draft
        this.matchFoundData = null; // Agora sim limpar
        this.cdr.detectChanges();
        break;
      case 'acceptance_timer':
        // Atualizar timer do MatchFoundComponent
        if (this.showMatchFound && this.matchFoundData) {
          const timerData = message.data || message;
          this.matchFoundData.acceptanceTimer = timerData.secondsRemaining || timerData.timeLeft || timerData.secondsLeft || 30;
          console.log('⏰ [App] Timer atualizado:', this.matchFoundData.acceptanceTimer, 's');
          this.cdr.detectChanges();

          // Disparar evento para o MatchFoundComponent
          const timeLeft = this.matchFoundData.acceptanceTimer || 30;
          const event = new CustomEvent('matchTimerUpdate', {
            detail: {
              matchId: timerData.matchId || this.matchFoundData.matchId,
              timeLeft: timeLeft,
              isUrgent: timeLeft <= 10
            }
          });
          document.dispatchEvent(event);
        }
        break;
      case 'game_started':
        console.log('🎮 [App] Game started recebido:', message);
        const gameData = message.gameData || message.data || message;

        console.log('🎮 [App] Extraindo gameData:', {
          matchId: gameData.matchId,
          status: gameData.status,
          team1Length: gameData.team1?.length || 0,
          team2Length: gameData.team2?.length || 0,
          hasPickBanData: !!gameData.pickBanData
        });

        // ✅ Atualizar estado para game in progress
        this.gameData = gameData;
        this.inGamePhase = true;
        this.inDraftPhase = false;
        this.showMatchFound = false;
        this.matchFoundData = null;
        this.draftData = null;

        console.log('✅ [App] Estado atualizado para game in progress:', {
          inGamePhase: this.inGamePhase,
          inDraftPhase: this.inDraftPhase,
          gameDataMatchId: this.gameData?.matchId
        });

        this.cdr.detectChanges();

        this.addNotification('success', 'Jogo Iniciado!', 'A partida está em andamento');
        break;
      default:
        // outras mensagens tratadas por componentes específicos
        break;
    }

    // ✅ LOG FINAL: Estado após processar evento
    console.log(`📡 [App] Estado após processar ${message.type}:`, {
      inGamePhase: this.inGamePhase,
      inDraftPhase: this.inDraftPhase,
      isRestoredMatch: this.isRestoredMatch,
      showMatchFound: this.showMatchFound,
      hasGameData: !!this.gameData,
      hasDraftData: !!this.draftData
    });
    console.log(`📡 [App] ========================================\n`);
  }

  private handleMatchFound(message: any): void {
    console.log('🎯 [App] Processando match found:', message);

    // ✅ CORREÇÃO: Dados vêm em message.data
    const data = message.data || message;

    console.log('🎯 [App] matchId recebido:', data.matchId);
    console.log('🎯 [App] team1:', data.team1?.length, 'jogadores');
    console.log('🎯 [App] team2:', data.team2?.length, 'jogadores');

    // ✅ Converter formato do backend para formato do componente
    const team1 = data.team1 || [];
    const team2 = data.team2 || [];

    // Determinar qual time o jogador está
    const currentPlayerName = this.currentPlayer?.displayName || this.currentPlayer?.summonerName || '';
    const isInTeam1 = team1.some((p: any) => p.summonerName === currentPlayerName);

    const teammates = isInTeam1 ? team1 : team2;
    const enemies = isInTeam1 ? team2 : team1;
    const playerSide: 'blue' | 'red' = isInTeam1 ? 'blue' : 'red';

    this.matchFoundData = {
      matchId: data.matchId,
      playerSide,
      teammates: teammates.map((p: any, index: number) => ({
        id: p.playerId || p.id || index,
        summonerName: p.summonerName || p.name || 'Desconhecido',
        mmr: p.customLp || p.mmr || 1200,
        primaryLane: p.primaryLane || 'fill',
        secondaryLane: p.secondaryLane || 'fill',
        assignedLane: p.assignedLane || p.primaryLane || 'fill',
        teamIndex: p.teamIndex !== undefined ? p.teamIndex : (isInTeam1 ? index : (index + 5)),
        isAutofill: p.isAutofill || false,
        profileIconId: p.profileIconId || 29
      })),
      enemies: enemies.map((p: any, index: number) => ({
        id: p.playerId || p.id || (index + 100),
        summonerName: p.summonerName || p.name || 'Desconhecido',
        mmr: p.customLp || p.mmr || 1200,
        primaryLane: p.primaryLane || 'fill',
        secondaryLane: p.secondaryLane || 'fill',
        assignedLane: p.assignedLane || p.primaryLane || 'fill',
        teamIndex: p.teamIndex !== undefined ? p.teamIndex : (isInTeam1 ? (index + 5) : index),
        isAutofill: p.isAutofill || false,
        profileIconId: p.profileIconId || 29
      })),
      averageMMR: {
        yourTeam: isInTeam1 ? (data.averageMmrTeam1 || 0) : (data.averageMmrTeam2 || 0),
        enemyTeam: isInTeam1 ? (data.averageMmrTeam2 || 0) : (data.averageMmrTeam1 || 0)
      },
      estimatedGameDuration: 30,
      phase: 'accept',
      acceptanceTimer: data.timeoutSeconds || 30,
      acceptedCount: 0,
      totalPlayers: 10
    } as any;

    this.showMatchFound = true;

    if (this.matchFoundData) {
      console.log('✅ [App] matchFoundData criado:', {
        matchId: this.matchFoundData.matchId,
        phase: this.matchFoundData.phase,
        teammatesCount: this.matchFoundData.teammates?.length || 0,
        enemiesCount: this.matchFoundData.enemies?.length || 0
      });
    }

    this.cdr.detectChanges();

    console.log('✅ [App] Modal Match Found exibido (showMatchFound =', this.showMatchFound, ')');
  }

  private stopDraftPolling(): void {
    // Placeholder: implementar se houver polling específico do draft
  }

  private forceDraftSyncImmediate(_matchId: number, _reason: string): void {
    // Placeholder: enviar uma mensagem ao backend se necessário
  }

  private async savePlayerData(player: any): Promise<void> {
    const summonerName = player.summonerName || player.displayName || '';

    if (this.isElectron && (window as any).electronAPI?.storage && summonerName) {
      try {
        const result = await (window as any).electronAPI.storage.savePlayerData(summonerName, player);
        console.log(`💾 [Electron] Dados salvos para: ${summonerName}`, result);
      } catch (error) {
        console.error('❌ [Electron] Erro ao salvar via Electron storage:', error);
        // Fallback para localStorage em caso de erro
        try { localStorage.setItem('currentPlayer', JSON.stringify(player)); } catch { }
      }
    } else {
      // Fallback para web ou se Electron storage não disponível
      try { localStorage.setItem('currentPlayer', JSON.stringify(player)); } catch { }
      console.log('💾 [Web] Dados salvos no localStorage');
    }
  }

  /**
   * ✅ NOVO: Aguarda o LCU estar realmente pronto fazendo retry até conseguir dados válidos
   */
  private async waitForLCUReady(maxAttempts: number = 10, delayMs: number = 750): Promise<void> {
    console.log('🔄 [App] Verificando se LCU está pronto (até 7.5s de retry)...');

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        console.log(`🔄 [App] Tentativa ${attempt}/${maxAttempts} de verificar LCU...`);

        // Tentar obter dados básicos do summoner
        const result = await firstValueFrom(
          this.apiService.getCurrentSummonerFromLCU().pipe(
            timeout(3000) // 3 segundos de timeout por tentativa
          )
        );

        // Verificar se recebemos dados válidos
        if (result && result.gameName && result.summonerId) {
          console.log('✅ [App] LCU está pronto! Dados válidos recebidos:', {
            gameName: result.gameName,
            tagLine: result.tagLine,
            summonerId: result.summonerId
          });
          return; // Sucesso!
        }

        console.log(`⚠️ [App] Tentativa ${attempt}: Dados incompletos, tentando novamente...`);
      } catch (error) {
        console.log(`⚠️ [App] Tentativa ${attempt}: Erro ao verificar LCU:`, error);
      }

      // Aguardar antes da próxima tentativa (exceto na última)
      if (attempt < maxAttempts) {
        await new Promise(resolve => setTimeout(resolve, delayMs));
      }
    }

    // Se chegou aqui, esgotou as tentativas
    throw new Error(`LCU não respondeu após ${maxAttempts} tentativas`);
  }

  private identifyCurrentPlayerOnConnect(): void {
    console.log('🔍 [App] identifyCurrentPlayerOnConnect() chamado');
    this.identifyPlayerSafely().catch(() => { });

    // ✅ NOVO: Após identificar player, aguardar 5 segundos e verificar partida ativa
    setTimeout(() => {
      console.log('⏰ [App] 5 segundos após identificação - verificando partida ativa...');
      console.log('👤 [App] currentPlayer atual:', this.currentPlayer);
      this.checkAndRestoreActiveMatch();
    }, 5000);
  }

  private getCurrentPlayerIdentifiers(): string[] {
    const ids: string[] = [];
    if (this.currentPlayer?.displayName) ids.push(this.currentPlayer.displayName);
    if (this.currentPlayer?.summonerName) ids.push(this.currentPlayer.summonerName);
    if (this.currentPlayer?.gameName && this.currentPlayer?.tagLine) ids.push(`${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`);
    return ids;
  }

  // ===== Helpers: Profile icon URL for settings preview (same strategy as dashboard) =====
  getCurrentPlayerProfileIconUrl(): string {
    const iconId = this.currentPlayer?.profileIconId || 29;
    // Prefer Community Dragon (stable)
    return `https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/profile-icons/${iconId}.jpg`;
  }

  private namesMatch(a: string, b: string): boolean {
    if (!a || !b) return false;
    const norm = (s: string) => s.trim().toLowerCase();
    if (norm(a) === norm(b)) return true;
    // comparar apenas gameName se houver tag
    const ga = a.includes('#') ? a.split('#')[0] : a;
    const gb = b.includes('#') ? b.split('#')[0] : b;
    return norm(ga) === norm(gb);
  }

  private convertBasicPlayersToPlayerInfo(names: string[]): any[] {
    if (!Array.isArray(names)) return [];
    return names.map((n, idx) => ({
      summonerName: n,
      name: n,
      id: null,
      champion: null,
      championName: null,
      championId: null,
      lane: 'unknown',
      assignedLane: 'unknown',
      teamIndex: idx,
      mmr: 1000,
      primaryLane: 'unknown',
      secondaryLane: 'unknown',
      isAutofill: false
    }));
  }
  // ===== end of helper stubs =====

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    clearInterval(this.lcuCheckInterval);

    // ✅ PARAR: Polling inteligente
    this.stopIntelligentPolling();

    // ✅ PARAR: Polling do draft
    this.stopDraftPolling();

    if (this.lcuTelemetryInterval) {
      clearInterval(this.lcuTelemetryInterval);
      this.lcuTelemetryInterval = null;
    }
  }

  // ✅ MANTIDO: Métodos de interface
  setCurrentView(view: 'dashboard' | 'queue' | 'history' | 'leaderboard' | 'settings'): void {
    this.currentView = view;
    // Ao entrar em Configurações, sempre sincronizar os dados via gateway Electron (como no dashboard)
    if (view === 'settings') {
      // ✅ CORREÇÃO: NÃO sobrescrever currentPlayer ao entrar em configurações
      // Apenas atualizar o formulário com os dados que já temos
      if (this.currentPlayer) {
        console.log('⚙️ [App] Entrando em configurações - preservando dados do player');
        this.updateSettingsForm();
      }

      // Carregar lista de special users do backend para habilitar ferramentas
      this.loadSpecialUsersFromSettings();

      // Carregar configurações do Discord
      this.loadDiscordSettings();
    }
  }

  // Carrega a lista de special users da tabela settings (key/value)
  private loadSpecialUsersFromSettings(): void {
    console.log('🔐 [App] Carregando special users do backend...');

    this.apiService.getConfigSettings().subscribe({
      next: (resp: any) => {
        try {
          console.log('🔐 [App] Resposta do backend para settings:', resp);

          // Backend retorna: { settings: { "special_users": "[\"FZD Ratoso#fzd\"]", ... } }
          if (resp?.settings && typeof resp.settings === 'object') {
            const specialUsersValue = resp.settings['special_users'];
            console.log('🔐 [App] Valor de special_users encontrado:', specialUsersValue);

            if (specialUsersValue && typeof specialUsersValue === 'string') {
              const parsed = JSON.parse(specialUsersValue);
              console.log('🔐 [App] Special users parseados:', parsed);

              if (Array.isArray(parsed)) {
                this.specialUsers = parsed.map((s: any) => String(s).toLowerCase().trim());
                console.log('🔐 [App] Special users normalizados:', this.specialUsers);
              }
            }
          }
          // Fallback: formato antigo com array de objetos
          else if (Array.isArray(resp) || resp?.settings) {
            const entries: any[] = Array.isArray(resp) ? resp : (resp?.settings || resp?.data || []);
            console.log('🔐 [App] Entradas encontradas (formato antigo):', entries);

            const special = entries.find((e: any) => (e?.key || e?.name) === 'special_users');
            console.log('🔐 [App] Configuração special_users encontrada:', special);

            if (special && typeof special.value === 'string') {
              const parsed = JSON.parse(special.value);
              console.log('🔐 [App] Special users parseados (formato antigo):', parsed);

              if (Array.isArray(parsed)) {
                this.specialUsers = parsed.map((s: any) => String(s).toLowerCase().trim());
                console.log('🔐 [App] Special users normalizados (formato antigo):', this.specialUsers);
              }
            }
          }

          console.log('🔐 [App] Special users final:', this.specialUsers);
          this.cdr.detectChanges();
        } catch (err) {
          console.warn('⚠️ [App] Falha ao parsear special_users:', err);
          this.specialUsers = [];
        }
      },
      error: (err) => {
        console.warn('⚠️ [App] Falha ao carregar settings:', err);
        this.specialUsers = [];
      }
    });
  }

  // Checa se o jogador atual está na lista de special users (gameName#tagLine)
  isSpecialUser(): boolean {
    const id = this.normalizePlayerIdentifier(this.currentPlayer);
    const isSpecial = !!id && this.specialUsers.includes(id);

    console.log('🔐 [App] Verificando special user:', {
      currentPlayer: this.currentPlayer,
      normalizedId: id,
      specialUsers: this.specialUsers,
      isSpecial: isSpecial
    });

    return isSpecial;
  }

  private normalizePlayerIdentifier(playerInfo: any): string {
    if (!playerInfo) return '';
    if (playerInfo.gameName && playerInfo.tagLine) {
      return `${playerInfo.gameName}#${playerInfo.tagLine}`.toLowerCase().trim();
    }
    if (playerInfo.displayName && playerInfo.displayName.includes('#')) {
      return String(playerInfo.displayName).toLowerCase().trim();
    }
    if (playerInfo.summonerName) {
      return String(playerInfo.summonerName).toLowerCase().trim();
    }
    if ((playerInfo as any).name) {
      return String((playerInfo as any).name).toLowerCase().trim();
    }
    return '';
  }

  // ✅ NOVO: Métodos para controlar modais de Special Users

  openAdjustLpModal(): void {
    // ✅ FIX: Validar se currentPlayer está disponível antes de abrir modal
    if (!this.currentPlayer) {
      console.warn('⚠️ [App] Cannot open Adjust LP Modal: currentPlayer not available');
      alert('Por favor, aguarde enquanto o sistema se conecta ao League of Legends.');
      return;
    }

    console.log('💰 [App] Abrindo modal de ajustar LP');
    this.showAdjustLpModal = true;
  }

  closeAdjustLpModal(): void {
    console.log('💰 [App] Fechando modal de ajustar LP');
    this.showAdjustLpModal = false;
  }

  onLpAdjusted(event: any): void {
    console.log('✅ [App] LP ajustado com sucesso:', event);
    // Pode adicionar lógica adicional aqui, como atualizar leaderboard
  }

  openChampionshipModal(): void {
    // ✅ FIX: Validar se currentPlayer está disponível antes de abrir modal
    if (!this.currentPlayer) {
      console.warn('⚠️ [App] Cannot open Championship Modal: currentPlayer not available');
      alert('Por favor, aguarde enquanto o sistema se conecta ao League of Legends.');
      return;
    }

    console.log('🏆 [App] Abrindo modal de campeonatos');
    this.showChampionshipModal = true;
  }

  closeChampionshipModal(): void {
    console.log('🏆 [App] Fechando modal de campeonatos');
    this.showChampionshipModal = false;
  }

  onChampionshipAwarded(event: any): void {
    console.log('✅ [App] Campeonato premiado com sucesso:', event);
    // Pode adicionar lógica adicional aqui, como atualizar leaderboard
  }

  // ✅ SIMPLIFICADO: Apenas comunicar com backend
  async joinQueue(preferences?: QueuePreferences): Promise<void> {
    console.log('📞 [App] Solicitando entrada na fila ao backend...');

    if (!this.currentPlayer) {
      this.addNotification('error', 'Erro', 'Dados do jogador não disponíveis');
      return;
    }

    try {
      await firstValueFrom(this.apiService.joinQueue(this.currentPlayer, preferences));
      console.log('✅ [App] Solicitação de entrada na fila enviada');

      // ✅ NOVO: Atualizar imediatamente o estado local para mostrar entrada na fila
      this.isInQueue = true;
      this.markBackendAction(); // Marcar que acabamos de fazer uma ação no backend

      // ✅ NOVO: Adicionar jogador à tabela local imediatamente (otimista)
      if (preferences) {
        this.updateQueueTableOptimistically(this.currentPlayer, preferences);
      }

      this.addNotification('success', 'Fila Ingressada!', 'Você entrou na fila com sucesso');

      // ✅ NOVO: Atualizar status real após 2 segundos para confirmar
      setTimeout(() => {
        this.refreshQueueStatus();
      }, 2000);
    } catch (error) {
      console.error('❌ [App] Erro ao entrar na fila:', error);
      this.addNotification('error', 'Erro', 'Falha ao entrar na fila');
    }
  }

  async joinDiscordQueueWithFullData(data: { player: Player | null, preferences: QueuePreferences }): Promise<void> {
    console.log('📞 [App] Solicitando entrada na fila Discord ao backend...', data);

    if (!data.player) {
      console.error('❌ [App] Dados do jogador não disponíveis');
      this.addNotification('error', 'Erro', 'Dados do jogador não disponíveis');
      return;
    }

    // Usar o novo sistema de fila centralizado
    try {
      await firstValueFrom(this.apiService.joinQueue(data.player, data.preferences));
      console.log('✅ [App] Solicitação de entrada na fila enviada via novo sistema');

      // ✅ NOVO: Atualizar imediatamente o estado local para mostrar entrada na fila
      this.isInQueue = true;
      this.markBackendAction(); // Marcar que acabamos de fazer uma ação no backend

      // ✅ NOVO: Adicionar jogador à tabela local imediatamente (otimista)
      this.updateQueueTableOptimistically(data.player, data.preferences);

      this.addNotification('success', 'Fila Discord', 'Você entrou na fila com sucesso');

      // Atualizar status após 3 segundos para confirmar
      setTimeout(() => {
        this.refreshQueueStatus();
      }, 3000);
    } catch (error) {
      console.error('❌ [App] Erro ao entrar na fila:', error);
      this.addNotification('error', 'Erro', 'Falha ao entrar na fila');
    }
  }

  async leaveQueue(): Promise<void> {
    console.log('📞 [App] Solicitando saída da fila ao backend...');
    console.log('📞 [App] Dados do jogador atual:', {
      id: this.currentPlayer?.id,
      summonerName: this.currentPlayer?.summonerName,
      displayName: this.currentPlayer?.displayName
    });

    if (!this.currentPlayer?.summonerName && !this.currentPlayer?.displayName) {
      console.error('❌ [App] Nenhum identificador do jogador disponível');
      this.addNotification('error', 'Erro', 'Dados do jogador não disponíveis para sair da fila');
      return;
    }

    try {
      // ✅ USAR displayName como prioridade
      const playerIdentifier = this.currentPlayer.displayName || this.currentPlayer.summonerName;
      console.log('📞 [App] Usando identificador:', playerIdentifier);

      // ✅ CORRIGIDO: Priorizar summonerName/displayName ao invés de playerId
      await firstValueFrom(this.apiService.leaveQueue(undefined, playerIdentifier));
      console.log('✅ [App] Solicitação de saída da fila enviada');

      // ✅ CORRIGIDO: Marcar estado como fora da fila imediatamente
      this.isInQueue = false;
      this.hasRecentBackendQueueStatus = true;

      this.addNotification('success', 'Saiu da Fila', 'Você saiu da fila com sucesso');

      // Atualizar status após 2 segundos para confirmar
      setTimeout(() => {
        this.refreshQueueStatus();
      }, 2000);
    } catch (error: any) {
      console.error('❌ [App] Erro ao sair da fila:', error);
      console.error('❌ [App] Detalhes do erro:', {
        status: error.status,
        message: error.message,
        error: error.error
      });

      let errorMessage = 'Falha ao sair da fila';
      if (error.error?.message) {
        errorMessage += `: ${error.error.message}`;
      } else if (error.message) {
        errorMessage += `: ${error.message}`;
      }

      this.addNotification('error', 'Erro', errorMessage);
    }
  }

  async acceptMatch(): Promise<void> {
    console.log('📞 [App] Enviando aceitação ao backend...');
    logApp('📞 [App] ACCEPT_MATCH_REQUEST', {
      matchId: this.matchFoundData?.matchId,
      playerName: this.currentPlayer?.summonerName,
      timestamp: new Date().toISOString()
    });

    if (!this.matchFoundData?.matchId || !this.currentPlayer?.summonerName) {
      console.error('❌ [App] Dados insuficientes para aceitar partida');
      this.addNotification('error', 'Erro', 'Dados da partida não disponíveis');
      return;
    }

    try {
      await firstValueFrom(this.apiService.acceptMatch(this.matchFoundData.matchId, this.currentPlayer.id || 0, this.currentPlayer.summonerName));
      console.log('✅ [App] Aceitação enviada com sucesso');
      logApp('✅ [App] ACCEPT_MATCH_SUCCESS', {
        matchId: this.matchFoundData.matchId,
        playerName: this.currentPlayer.summonerName,
        timestamp: new Date().toISOString()
      });

      // ✅ NOVO: Não alterar estado local - deixar backend gerenciar
      this.addNotification('success', 'Partida Aceita!', 'Aguardando outros jogadores...');
    } catch (error: any) {
      console.error('❌ [App] Erro ao aceitar partida:', error);
      logApp('❌ [App] ACCEPT_MATCH_ERROR', {
        matchId: this.matchFoundData?.matchId,
        playerName: this.currentPlayer?.summonerName,
        error: error.message || error,
        timestamp: new Date().toISOString()
      });
      this.addNotification('error', 'Erro', error.message || 'Falha ao aceitar partida');
    }
  }

  // ✅ NOVO: Método para confirmar pick do jogador humano com sincronização forçada
  async confirmPlayerPick(championId: number, actionId: number): Promise<void> {
    console.log('🎯 [App] === CONFIRMANDO PICK DO JOGADOR ===');
    logApp('🎯 [App] PLAYER_PICK_CONFIRM', {
      championId,
      actionId,
      matchId: this.draftData?.matchId,
      playerName: this.currentPlayer?.summonerName,
      timestamp: new Date().toISOString()
    });

    if (!this.draftData?.matchId || !this.currentPlayer?.summonerName) {
      console.error('❌ [App] Dados insuficientes para confirmar pick');
      this.addNotification('error', 'Erro', 'Dados do draft não disponíveis');
      return;
    }

    try {
      // ✅ Enviar pick para o backend via WebSocket
      this.apiService.sendWebSocketMessage({
        type: 'draft_action',
        data: {
          matchId: this.draftData.matchId,
          playerId: this.currentPlayer.summonerName,
          action: 'pick',
          championId: championId,
          actionId: actionId,
          timestamp: Date.now()
        }
      });

      console.log('✅ [App] Pick confirmado no backend');
      logApp('✅ [App] PLAYER_PICK_SUCCESS', {
        championId,
        actionId,
        matchId: this.draftData.matchId,
        playerName: this.currentPlayer.summonerName,
        timestamp: new Date().toISOString()
      });

      // ✅ FORÇAR SINCRONIZAÇÃO IMEDIATA após confirmação
      console.log('🔄 [App] Forçando sincronização imediata após pick do jogador');
      setTimeout(() => {
        this.forceDraftSyncImmediate(this.draftData.matchId, 'player_pick_confirmed');
      }, 100); // Pequeno delay para garantir que o backend processou

      this.addNotification('success', 'Pick Confirmado!', 'Seu campeão foi escolhido.');

    } catch (error: any) {
      console.error('❌ [App] Erro ao confirmar pick:', error);
      logApp('❌ [App] PLAYER_PICK_ERROR', {
        championId,
        actionId,
        matchId: this.draftData?.matchId,
        playerName: this.currentPlayer?.summonerName,
        error: error.message || error,
        timestamp: new Date().toISOString()
      });
      this.addNotification('error', 'Erro', error.message || 'Falha ao confirmar pick');
    }
  }

  // ✅ NOVO: Método para confirmar ban do jogador humano com sincronização forçada
  async confirmPlayerBan(championId: number, actionId: number): Promise<void> {
    console.log('🚫 [App] === CONFIRMANDO BAN DO JOGADOR ===');
    logApp('🚫 [App] PLAYER_BAN_CONFIRM', {
      championId,
      actionId,
      matchId: this.draftData?.matchId,
      playerName: this.currentPlayer?.summonerName,
      timestamp: new Date().toISOString()
    });

    if (!this.draftData?.matchId || !this.currentPlayer?.summonerName) {
      console.error('❌ [App] Dados insuficientes para confirmar ban');
      this.addNotification('error', 'Erro', 'Dados do draft não disponíveis');
      return;
    }

    try {
      // ✅ Enviar ban para o backend via WebSocket
      this.apiService.sendWebSocketMessage({
        type: 'draft_action',
        data: {
          matchId: this.draftData.matchId,
          playerId: this.currentPlayer.summonerName,
          action: 'ban',
          championId: championId,
          actionId: actionId,
          timestamp: Date.now()
        }
      });

      console.log('✅ [App] Ban confirmado no backend');
      logApp('✅ [App] PLAYER_BAN_SUCCESS', {
        championId,
        actionId,
        matchId: this.draftData.matchId,
        playerName: this.currentPlayer.summonerName,
        timestamp: new Date().toISOString()
      });

      // ✅ FORÇAR SINCRONIZAÇÃO IMEDIATA após confirmação
      console.log('🔄 [App] Forçando sincronização imediata após ban do jogador');
      setTimeout(() => {
        this.forceDraftSyncImmediate(this.draftData.matchId, 'player_ban_confirmed');
      }, 100); // Pequeno delay para garantir que o backend processou

      this.addNotification('success', 'Ban Confirmado!', 'Seu banimento foi realizado.');

    } catch (error: any) {
      console.error('❌ [App] Erro ao confirmar ban:', error);
      logApp('❌ [App] PLAYER_BAN_ERROR', {
        championId,
        actionId,
        matchId: this.draftData?.matchId,
        playerName: this.currentPlayer?.summonerName,
        error: error.message || error,
        timestamp: new Date().toISOString()
      });
      this.addNotification('error', 'Erro', error.message || 'Falha ao confirmar ban');
    }
  }

  async declineMatch(): Promise<void> {
    console.log('📞 [App] === INÍCIO DA RECUSA DA PARTIDA ===');
    console.log('📞 [App] Enviando recusa ao backend...');
    console.log('📞 [App] Estado atual:', {
      matchId: this.matchFoundData?.matchId,
      currentPlayer: this.currentPlayer?.summonerName,
      isInQueue: this.isInQueue,
      showMatchFound: this.showMatchFound
    });

    if (!this.matchFoundData?.matchId || !this.currentPlayer?.summonerName) {
      console.error('❌ [App] Dados insuficientes para recusa');
      this.addNotification('error', 'Erro', 'Dados da partida não disponíveis');
      return;
    }

    try {
      // ✅ CORREÇÃO: Enviar recusa ao backend
      await firstValueFrom(this.apiService.declineMatch(
        this.matchFoundData.matchId,
        this.currentPlayer.id,
        this.currentPlayer.summonerName
      ));

      console.log('✅ [App] Recusa enviada ao backend com sucesso');

      // ✅ CORREÇÃO: Atualizar estado local imediatamente
      this.lastMatchId = null; // ✅ NOVO: Limpar controle de partida
      this.showMatchFound = false;
      this.matchFoundData = null;
      this.isInQueue = false;

      // ✅ NOVO: Marcar que temos uma resposta recente do backend
      this.hasRecentBackendQueueStatus = true;

      console.log('✅ [App] Estado atualizado:', {
        showMatchFound: this.showMatchFound,
        matchFoundData: this.matchFoundData,
        isInQueue: this.isInQueue
      });

      this.addNotification('success', 'Partida Recusada', 'Você recusou a partida e saiu da fila.');

      // ✅ CORREÇÃO: Aguardar 2 segundos e atualizar status para confirmar
      setTimeout(() => {
        console.log('🔄 [App] Confirmando status da fila após recusa...');
        this.refreshQueueStatus();
      }, 2000);

    } catch (error: any) {
      console.error('❌ [App] Erro ao recusar partida:', error);
      console.error('❌ [App] Detalhes do erro:', {
        status: error.status,
        message: error.message,
        error: error.error
      });

      let errorMessage = 'Falha ao recusar partida';

      if (error.status === 404) {
        errorMessage = 'Partida não encontrada ou já expirada';
        console.log('⚠️ [App] Partida não encontrada - forçando saída da fila');

        // ✅ CORREÇÃO: Se partida não existe, forçar saída da interface
        this.lastMatchId = null; // ✅ NOVO: Limpar controle de partida
        this.showMatchFound = false;
        this.matchFoundData = null;
        this.isInQueue = false;
        this.hasRecentBackendQueueStatus = true;

        // ✅ NOVO: Tentar sair da fila explicitamente
        setTimeout(() => {
          console.log('🔄 [App] Tentando sair da fila explicitamente...');
          this.leaveQueue().catch(err => {
            console.warn('⚠️ [App] Erro ao sair da fila após recusa:', err);
          });
        }, 1000);

      } else if (error.status === 409) {
        errorMessage = 'Partida já foi aceita ou cancelada';
        // ✅ CORREÇÃO: Mesmo com erro 409, sair da interface
        this.showMatchFound = false;
        this.matchFoundData = null;
        this.isInQueue = false;
      } else if (error.error?.message) {
        errorMessage = error.error.message;
      }

      this.addNotification('error', 'Erro na Recusa', errorMessage);
    }

    console.log('📞 [App] === FIM DA RECUSA DA PARTIDA ===');
  }

  // Wrappers para eventos do template
  onAcceptMatch(_event?: any): void {
    this.acceptMatch().catch(() => { });
  }

  onDeclineMatch(_event?: any): void {
    this.declineMatch().catch(() => { });
  }

  // ✅ ENHANCED: Métodos de notificação com animações suaves
  private addNotification(type: 'success' | 'error' | 'warning' | 'info', title: string, message: string): void {
    const notification: Notification = {
      id: Date.now().toString() + Math.random().toString(36).substring(2, 11), // More unique ID
      type,
      title,
      message,
      timestamp: new Date(),
      isVisible: false,
      isHiding: false
    };

    // Add to notifications array
    if (this.notifications.length < this.maxNotifications) {
      this.notifications.push(notification);
    } else {
      // Remove oldest notification and add new one
      this.notifications.shift();
      this.notifications.push(notification);
    }

    // Trigger change detection for initial render
    this.cdr.detectChanges();

    // Show animation after a brief delay
    setTimeout(() => {
      notification.isVisible = true;
      this.cdr.detectChanges();
    }, 50);

    // Auto-hide after 4 seconds (reduced from 5)
    const autoHideTimeout = setTimeout(() => {
      this.dismissNotification(notification.id);
    }, 4000);

    notification.autoHideTimeout = autoHideTimeout;

    // Process queue if there are pending notifications
    this.processNotificationQueue();
  }

  dismissNotification(id: string): void {
    const notification = this.notifications.find(n => n.id === id);
    if (notification) {
      // Clear auto-hide timeout if exists
      if (notification.autoHideTimeout) {
        clearTimeout(notification.autoHideTimeout);
      }

      // Start hide animation
      notification.isHiding = true;
      this.cdr.detectChanges();

      // Remove from array after animation completes
      setTimeout(() => {
        this.notifications = this.notifications.filter(n => n.id !== id);
        this.cdr.detectChanges();
      }, 400); // Match CSS transition duration
    }
  }

  private processNotificationQueue(): void {
    // This method can be used for future queue processing if needed
    // For now, we're using a simpler approach with direct replacement
    if (this.notificationQueue.length > 0 && this.notifications.length < this.maxNotifications) {
      const next = this.notificationQueue.shift();
      if (next) {
        this.notifications.push(next);
        this.cdr.detectChanges();
      }
    }
  }

  trackNotification(index: number, notification: Notification): string {
    return notification.id;
  }

  // ✅ CORRIGIDO: Métodos necessários para carregamento de dados
  private loadPlayerData(): void {
    console.log('👤 [App] Carregando dados do jogador...');

    // Strategy 1: Try to get player from LCU (best option if LoL is running)
    this.apiService.getPlayerFromLCU().subscribe({
      next: (player: Player) => {
        console.log('✅ [App] Dados do jogador carregados do LCU:', player);
        this.currentPlayer = player;

        // ✅ CRÍTICO: Armazenar summonerName no ApiService ANTES de qualquer API call
        const playerName = player.gameName && player.tagLine
          ? `${player.gameName}#${player.tagLine}`
          : player.displayName || player.summonerName || '';

        if (playerName) {
          this.apiService.setCurrentSummonerName(playerName);
          console.log(`✅ [App] SummonerName configurado no ApiService: ${playerName}`);
        } else {
          console.error('❌ [App] Não foi possível extrair summonerName do player:', player);
        }

        this.savePlayerData(player).catch(err => console.error('Erro ao salvar dados:', err));
        this.updateSettingsForm();

        // ✅ NOVO: Identificar jogador no WebSocket após carregar dados
        this.identifyCurrentPlayerOnConnect();

        this.addNotification('success', 'Dados Carregados', 'Dados do jogador carregados do League of Legends');
      },
      error: (error) => {
        console.warn('⚠️ [App] Erro ao carregar do LCU:', error);
        console.log('🔄 [App] Tentando carregar do localStorage como fallback...');

        // Fallback to localStorage if LCU fails
        this.tryLoadFromLocalStorage();
      }
    });
  }

  private async tryGetCurrentPlayerDetails(): Promise<void> {
    console.log('🔄 [App] Tentando carregar dados via getCurrentPlayerDetails...');

    // ✅ CRÍTICO: Fazer retry até conseguir dados válidos
    const maxRetries = 3; // ✅ REDUZIDO: 3 tentativas são suficientes
    const retryDelay = 500; // ✅ REDUZIDO: 500ms entre tentativas

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        console.log(`🔄 [App] Tentativa ${attempt}/${maxRetries} de carregar getCurrentPlayerDetails...`);

        const response = await firstValueFrom(this.apiService.getCurrentPlayerDetails().pipe(timeout(5000)));

        console.log('✅ [App] Resposta getCurrentPlayerDetails:', response);
        console.log('📊 [DEBUG] response.data:', JSON.stringify(response.data, null, 2));

        // ✅ CORRIGIDO: Verificar response.data.lcu (Electron) OU response.player (backend)
        const lcuData = response.data?.lcu;
        const playerData = response.player;

        if (lcuData && lcuData.summonerId) {
          console.log('✅ [App] Dados LCU VÁLIDOS! Processando...');
          await this.processPlayerData(response);
          return; // Sucesso!
        } else if (playerData && Object.keys(playerData).length > 0 && playerData.summonerId) {
          console.log('✅ [App] response.player VÁLIDO! Processando...');
          await this.processPlayerData(response);
          return; // Sucesso!
        }

        console.log(`⚠️ [App] Tentativa ${attempt}: dados inválidos ou vazios, tentando novamente...`);

        // Aguardar antes da próxima tentativa
        if (attempt < maxRetries) {
          await new Promise(resolve => setTimeout(resolve, retryDelay));
        }
      } catch (error) {
        console.log(`⚠️ [App] Tentativa ${attempt}: Erro:`, error);
        if (attempt < maxRetries) {
          await new Promise(resolve => setTimeout(resolve, retryDelay));
        }
      }
    }

    // ✅ CORREÇÃO: Se chegou aqui MAS já temos currentPlayer (do localStorage), não mostrar erro
    if (this.currentPlayer && this.currentPlayer.summonerId) {
      console.log('✅ [App] Dados do localStorage já disponíveis, ignorando erro de retry');
      return;
    }

    // Se chegou aqui sem dados, mostrar erro
    console.error('❌ [App] Esgotadas as tentativas de getCurrentPlayerDetails');
    this.addNotification('error', 'Erro', 'Não foi possível carregar dados do jogador');
    this.tryLoadFromLocalStorage();
  }

  /**
   * ✅ NOVO: Processa os dados do jogador após validação
   */
  private async processPlayerData(response: any): Promise<void> {
    if (response.success && response.data) {
      const data = response.data;

      // ✅ CRÍTICO: player vem no ROOT do response, NÃO dentro de data!
      const playerData = response.player || {};

      // Mapear dados do LCU para Player
      const lcuData = data.lcu || {};
      const riotAccount = data.riotAccount || {};

      const gameName = riotAccount.gameName || lcuData.gameName;
      const tagLine = riotAccount.tagLine || lcuData.tagLine;

      // ✅ CORRIGIDO: Usar displayName do backend se disponível
      let displayName = '';

      // Verificar se o backend já forneceu displayName
      if (playerData.displayName) {
        displayName = playerData.displayName;
        console.log('✅ [App] Usando displayName do playerData:', displayName);
      } else if (lcuData.displayName) {
        displayName = lcuData.displayName;
        console.log('✅ [App] Usando displayName do lcuData:', displayName);
      } else if (gameName && tagLine) {
        displayName = `${gameName}#${tagLine}`;
        console.log('✅ [App] DisplayName construído como fallback:', displayName);
      } else {
        console.warn('⚠️ [App] Dados incompletos via getCurrentPlayerDetails:', {
          gameName, tagLine, lcuDisplayName: lcuData.displayName
        });
        this.addNotification('warning', 'Dados Incompletos', 'Não foi possível obter gameName#tagLine');
        return;
      }

      // Garantir que displayName não seja vazio
      if (!displayName) {
        this.addNotification('warning', 'Dados Incompletos', 'Não foi possível obter displayName');
        return;
      }

      // ✅ CORREÇÃO: Extrair wins/losses e dados ranqueados do playerData (root) e lcuData (fallback)
      const wins = playerData.wins || lcuData.wins || 0;
      const losses = playerData.losses || lcuData.losses || 0;
      const rankData = playerData.rank || lcuData.rank || null;

      // ✅ NOVO: Estruturar rankedData para o frontend
      console.log('🔥 BUILD TIMESTAMP:', new Date().toISOString(), 'VERSÃO: 5.0 - DASHBOARD COM RETRY');
      console.log('📊 [DEBUG] response.player (root):', JSON.stringify(playerData, null, 2));
      console.log('📊 [DEBUG] data.lcu:', JSON.stringify(lcuData, null, 2));
      console.log('📊 [DEBUG] Valores extraídos:', {
        wins,
        losses,
        rankData,
        displayName
      });

      let rankedData: { soloQueue?: any; flexQueue?: any; } | undefined = undefined;
      if (rankData && rankData.tier && rankData.tier !== 'UNRANKED') {
        rankedData = {
          soloQueue: {
            tier: rankData.tier || 'UNRANKED',
            rank: rankData.rank || '',
            leaguePoints: rankData.lp || 0,
            wins: rankData.wins || wins || 0,
            losses: rankData.losses || losses || 0
          }
        };
      }
      console.log('📊 [DEBUG] rankedData final:', JSON.stringify(rankedData, null, 2));

      // ✅ PRIORIDADE: custom_mmr do backend > currentMMR do LCU > padrão 1200
      let calculatedMMR = playerData.customMmr || lcuData.currentMMR || 1200;
      console.log(`🔢 [App] MMR exibido: ${calculatedMMR} (backend: ${playerData.customMmr}, lcu: ${lcuData.currentMMR})`);

      // ✅ CRÍTICO: gameName e tagLine separados NÃO servem para identificação
      // SEMPRE usar displayName (gameName#tagLine) como identificador único
      const player: Player = {
        id: lcuData.summonerId || 0,
        summonerName: displayName, // ✅ SEMPRE gameName#tagLine
        displayName: displayName,   // ✅ SEMPRE gameName#tagLine
        gameName: gameName,         // ⚠️ Apenas para exibição separada (se necessário)
        tagLine: tagLine,           // ⚠️ Apenas para exibição separada (se necessário)
        summonerId: (lcuData.summonerId || 0).toString(),
        puuid: riotAccount.puuid || lcuData.puuid || '',
        profileIconId: lcuData.profileIconId || 29,
        summonerLevel: lcuData.summonerLevel || 30,
        region: 'br1',
        currentMMR: calculatedMMR,
        customLp: calculatedMMR,
        // ✅ ADICIONADO: Incluir dados do ranked
        wins: wins,
        losses: losses,
        rankedData: rankedData,
        rank: rankData ? {
          tier: rankData.tier || 'UNRANKED',
          rank: rankData.rank || '',
          display: rankData.tier && rankData.rank ? `${rankData.tier} ${rankData.rank}` : 'Unranked',
          lp: rankData.lp || 0
        } : undefined
      };

      console.log('📊 [DEBUG] Player object final:', JSON.stringify(player, null, 2));

      this.currentPlayer = player;

      // ✅ CRÍTICO: Armazenar summonerName no ApiService ANTES de qualquer API call
      if (displayName) {
        this.apiService.setCurrentSummonerName(displayName);
        console.log(`✅ [App] SummonerName configurado no ApiService (getCurrentPlayerDetails): ${displayName}`);
      }

      localStorage.setItem('currentPlayer', JSON.stringify(player));

      // ✅ ADICIONADO: Atualizar formulário de configurações
      this.updateSettingsForm();

      console.log('✅ [App] Dados do jogador mapeados com sucesso:', player.summonerName, 'displayName:', player.displayName);
      console.log('📊 [DEBUG] currentPlayer after assignment:', JSON.stringify(this.currentPlayer, null, 2));

      // ✅ CRÍTICO: Forçar detecção de mudanças para Dashboard atualizar
      this.cdr.markForCheck();
      this.cdr.detectChanges();
      console.log('🔄 [App] Change detection forçado - Dashboard deve atualizar agora');

      this.addNotification('success', 'Jogador Detectado', `Logado como: ${player.summonerName}`);

      // ✅ NOVO: Identificar jogador no WebSocket após carregar dados
      this.identifyCurrentPlayerOnConnect();

      // ✅ Sincronizar maestria/picks em background (NÃO sobrescreve MMR)
      setTimeout(() => {
        this.syncPlayerDataInBackground(displayName);
      }, 2000); // Aguardar 2s para backend salvar MMR primeiro
    }
  }

  /**
   * ✅ NOVO: Sincroniza dados do jogador com backend em segundo plano
   * Atualiza MMR customizado, maestria e picks mais utilizados
   * NÃO BLOQUEIA A UI - executa de forma assíncrona
   */
  private syncPlayerDataInBackground(summonerName: string): void {
    console.log('🔄 [App] Iniciando sincronização em background para:', summonerName);

    // Fire-and-forget: não espera resposta, não bloqueia UI
    this.apiService.syncPlayerWithBackend(summonerName).subscribe({
      next: (result) => {
        console.log('✅ [App] Sincronização em background concluída:', result);
        // Se o backend retornou dados atualizados, atualizar currentPlayer
        if (result?.player && this.currentPlayer) {
          const p = result.player;

          // ✅ CRÍTICO: Só atualizar MMR se backend retornou valor MAIOR que o atual
          // (evita sobrescrever 2101 com 1200 padrão do banco)
          if (p.customMmr !== undefined && p.customMmr > (this.currentPlayer.customLp || 0)) {
            this.currentPlayer.customLp = p.customMmr;
            this.currentPlayer.currentMMR = p.customMmr;
            console.log('✅ [App] custom_mmr atualizado:', p.customMmr);
          } else {
            console.log('⚠️ [App] custom_mmr do backend ignorado (menor ou igual ao atual):', p.customMmr, 'vs', this.currentPlayer.customLp);
          }

          // Atualizar wins/losses sempre
          if (p.wins !== undefined) this.currentPlayer.wins = p.wins;
          if (p.losses !== undefined) this.currentPlayer.losses = p.losses;

          // Atualizar localStorage
          localStorage.setItem('currentPlayer', JSON.stringify(this.currentPlayer));
          this.cdr.detectChanges();
        }
      },
      error: (err) => {
        console.warn('⚠️ [App] Erro na sincronização em background (não crítico):', err);
        // Não mostrar erro para o usuário - é um processo em background
      }
    });
  }

  private async tryLoadPlayerData(summonerName?: string): Promise<void> {
    console.log('🔍 [App] Tentando carregar dados do jogador...', summonerName ? `para: ${summonerName}` : 'sem summonerName');

    // Se estamos no Electron e temos um summonerName, tentar carregar do Electron storage
    if (this.isElectron && (window as any).electronAPI?.storage && summonerName) {
      try {
        const result = await (window as any).electronAPI.storage.loadPlayerData(summonerName);
        if (result.success && result.data) {
          this.currentPlayer = result.data;

          // ✅ Garantir que displayName seja definido se ausente
          if (this.currentPlayer && !this.currentPlayer.displayName) {
            if (this.currentPlayer.gameName && this.currentPlayer.tagLine) {
              this.currentPlayer.displayName = `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`;
              console.log('🔧 [App] DisplayName construído:', this.currentPlayer.displayName);
            } else if (this.currentPlayer.summonerName?.includes('#')) {
              this.currentPlayer.displayName = this.currentPlayer.summonerName;
              console.log('🔧 [App] DisplayName definido como summonerName:', this.currentPlayer.displayName);
            }
          }

          console.log(`✅ [Electron] Dados do jogador carregados: ${summonerName}`, result.path);
          return;
        } else {
          console.log(`ℹ️ [Electron] Nenhum dado salvo encontrado para: ${summonerName}`);
        }
      } catch (error) {
        console.warn('⚠️ [Electron] Erro ao carregar via Electron storage:', error);
      }
    }

    // Fallback: tentar localStorage (para web ou se Electron storage falhar)
    const stored = localStorage.getItem('currentPlayer');
    if (stored) {
      try {
        this.currentPlayer = JSON.parse(stored);

        // ✅ Garantir que displayName seja definido se ausente
        if (this.currentPlayer && !this.currentPlayer.displayName) {
          if (this.currentPlayer.gameName && this.currentPlayer.tagLine) {
            this.currentPlayer.displayName = `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`;
            console.log('🔧 [App] DisplayName construído do localStorage:', this.currentPlayer.displayName);
          } else if (this.currentPlayer.summonerName?.includes('#')) {
            this.currentPlayer.displayName = this.currentPlayer.summonerName;
            console.log('🔧 [App] DisplayName definido como summonerName do localStorage:', this.currentPlayer.displayName);
          }
        }

        console.log('✅ [Web] Dados do jogador carregados do localStorage, displayName:', this.currentPlayer?.displayName);
      } catch (error) {
        console.warn('⚠️ [App] Erro ao carregar do localStorage:', error);
      }
    } else {
      console.log('ℹ️ [App] Nenhum dado encontrado no localStorage');
    }
  }

  private tryLoadFromLocalStorage(): void {
    const stored = localStorage.getItem('currentPlayer');
    if (stored) {
      try {
        this.currentPlayer = JSON.parse(stored);

        // ✅ NOVA CORREÇÃO: Garantir que displayName seja definido se ausente
        if (this.currentPlayer && !this.currentPlayer.displayName) {
          if (this.currentPlayer.gameName && this.currentPlayer.tagLine) {
            this.currentPlayer.displayName = `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`;
            console.log('🔧 [App] DisplayName construído do localStorage:', this.currentPlayer.displayName);
          } else if (this.currentPlayer.summonerName?.includes('#')) {
            this.currentPlayer.displayName = this.currentPlayer.summonerName;
            console.log('🔧 [App] DisplayName definido como summonerName do localStorage:', this.currentPlayer.displayName);
          }
        }

        // ❌ REMOVIDO: NÃO setar summonerName do localStorage no ApiService!
        // O summonerName DEVE vir APENAS do evento WebSocket 'lcu_connection_registered'
        // para garantir que seja o jogador correto conectado no LCU agora.
        // Setar do localStorage causava confusão quando testando em múltiplos PCs
        // pois o localStorage pode ter dados de sessões antigas/outros jogadores.

        // if (this.currentPlayer) {
        //   const playerName = this.currentPlayer.displayName || this.currentPlayer.summonerName || '';
        //   if (playerName) {
        //     this.apiService.setCurrentSummonerName(playerName);
        //     console.log(`✅ [App] SummonerName configurado no ApiService (localStorage): ${playerName}`);
        //   } else {
        //     console.error('❌ [App] Não foi possível extrair summonerName do localStorage:', this.currentPlayer);
        //   }
        // }

        console.log('✅ [App] Dados do jogador carregados do localStorage, displayName:', this.currentPlayer?.displayName);
        console.log('⏳ [App] SummonerName será definido quando evento WebSocket "lcu_connection_registered" chegar');
      } catch (error) {
        console.warn('⚠️ [App] Erro ao carregar do localStorage:', error);
      }
    }
  }

  /**
   * 🆕 Helper para atualizar o CurrentSummonerService sempre que currentPlayer mudar
   * Deve ser chamado toda vez que this.currentPlayer for modificado
   */
  private updateCurrentSummonerService(): void {
    if (!this.currentPlayer) {
      this.currentSummonerService.clearSummoner();
      return;
    }

    // Construir displayName se ausente
    let displayName = this.currentPlayer.displayName;
    if (!displayName) {
      if (this.currentPlayer.gameName && this.currentPlayer.tagLine) {
        displayName = `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`;
      } else if (this.currentPlayer.summonerName) {
        displayName = this.currentPlayer.summonerName;
      }
    }

    // Converter profileIconId para number se for string
    let profileIconId: number | undefined;
    if (typeof this.currentPlayer.profileIconId === 'string') {
      profileIconId = parseInt(this.currentPlayer.profileIconId, 10);
    } else {
      profileIconId = this.currentPlayer.profileIconId;
    }

    this.currentSummonerService.setSummoner({
      summonerName: this.currentPlayer.summonerName || '',
      gameName: this.currentPlayer.gameName || '',
      tagLine: this.currentPlayer.tagLine || '',
      displayName: displayName || '',
      id: this.currentPlayer.id,
      puuid: this.currentPlayer.puuid,
      profileIconId: profileIconId,
      summonerLevel: this.currentPlayer.summonerLevel
    });
  }

  private refreshQueueStatus(): void {
    // Se temos o jogador atual, passar seu displayName para detecção no backend
    const currentPlayerDisplayName = this.currentPlayer?.displayName;

    console.log('📊 [App] === REFRESH QUEUE STATUS ===');
    console.log('📊 [App] refreshQueueStatus chamado:', {
      currentPlayerDisplayName: currentPlayerDisplayName,
      currentIsInQueue: this.isInQueue,
      hasRecentBackendQueueStatus: this.hasRecentBackendQueueStatus,
      refreshInProgress: this.refreshInProgress
    });

    // Marcar como em progresso
    this.refreshInProgress = true;

    this.apiService.getQueueStatus(currentPlayerDisplayName).subscribe({
      next: (status) => {
        console.log('📊 [App] Status da fila recebido do backend:', status);

        // ✅ CORREÇÃO: Marcar que temos uma resposta recente do backend
        this.hasRecentBackendQueueStatus = true;

        // ✅ NOVO: Verificar se o backend retornou informação específica sobre o jogador
        const statusWithPlayerInfo = status as any;

        if (statusWithPlayerInfo.isCurrentPlayerInQueue !== undefined) {
          const previousState = this.isInQueue;
          this.isInQueue = statusWithPlayerInfo.isCurrentPlayerInQueue;

          console.log(`✅ [App] Estado da fila atualizado pelo backend: ${previousState} → ${this.isInQueue}`);

          // ✅ NOVO: Se o estado mudou, notificar
          if (previousState !== this.isInQueue) {
            const statusMessage = this.isInQueue ? 'Você está na fila' : 'Você não está na fila';
            console.log(`🔄 [App] Status da fila mudou: ${statusMessage}`);
          }
        } else {
          // ✅ NOVO: Se backend não retornou info específica, manter estado atual
          console.log('⚠️ [App] Backend não retornou isCurrentPlayerInQueue - mantendo estado atual');
        }

        console.log(`📊 [App] Jogadores na fila: ${status.playersInQueue}`);
        console.log(`📊 [App] Lista de jogadores:`, status.playersInQueueList?.map(p => p.summonerName) || []);

        // ✅ CORREÇÃO: Converter joinTime de Date para string se necessário
        this.queueStatus = {
          ...status,
          playersInQueueList: status.playersInQueueList?.map(player => ({
            ...player,
            joinTime: typeof player.joinTime === 'string' ? player.joinTime : new Date(player.joinTime).toISOString()
          }))
        };

        // ✅ NOVO: Limpar flag após 5 segundos para permitir atualizações do QueueStateService
        setTimeout(() => {
          this.hasRecentBackendQueueStatus = false;
          console.log('🔄 [App] Flag de backend recente limpa, permitindo atualizações do QueueStateService');
        }, 5000);

        console.log('📊 [App] === FIM DO REFRESH QUEUE STATUS ===');

        // Marcar como finalizado
        this.refreshInProgress = false;
      },
      error: (error) => {
        console.warn('⚠️ [App] Erro ao atualizar status da fila:', error);
        this.hasRecentBackendQueueStatus = false;

        // Marcar como finalizado mesmo em caso de erro
        this.refreshInProgress = false;
      }
    });
  }

  private setupDiscordStatusListener(): void {
    // ✅ CORRIGIDO: Usar observables em tempo real em vez de polling
    this.discordService.onConnectionChange().pipe(
      takeUntil(this.destroy$)
    ).subscribe(isConnected => {
      console.log(`🤖 [App] Discord status atualizado:`, isConnected);
      this.discordStatus.isConnected = isConnected;

      if (isConnected) {
        this.discordStatus.botUsername = 'LoL Matchmaking Bot';
      } else {
        this.discordStatus.botUsername = '';
      }
    });

    // Solicitar status inicial UMA VEZ apenas
    this.discordService.checkConnection();

    // ✅ NOVO: Forçar verificação inicial do status do Discord
    setTimeout(() => {
      this.discordService.requestDiscordStatus();
    }, 2000);

    // ✅ NOVO: Verificação periódica do status do Discord
    setInterval(() => {
      this.discordService.requestDiscordStatus();
    }, 30000); // A cada 30 segundos
  }

  private startLCUStatusCheck(): void {
    // ✅ DESABILITADO: Polling HTTP causando oscilação no status do LCU
    // O WebSocket já envia atualizações de status via 'lcu_connection_registered' e outros eventos
    // Manter polling causa race conditions e sobrescreve dados do WebSocket
    console.warn('⚠️ [App] LCU polling desabilitado - usando apenas WebSocket para status');
    return;

    /* CÓDIGO DESABILITADO - Causando oscilação no status do LCU
    // ✅ OTIMIZADO: Se já temos jogador identificado, reduzir frequência de polling
    // Limpar interval anterior se existir
    if (this.lcuCheckInterval) {
      clearInterval(this.lcuCheckInterval);
      this.lcuCheckInterval = null;
    }

    // Função para fazer uma única verificação
    const checkOnce = () => {
      this.apiService.getLCUStatus().subscribe({
        next: (status) => {
          const isConnected = !!(status && (status as any).isConnected);
          const details = (status as any).status || {};
          this.lcuStatus = {
            isConnected,
            gameflowPhase: details.gameflowPhase || this.lcuStatus.gameflowPhase,
            summoner: details.summoner || this.lcuStatus.summoner
          } as any;
        },
        error: () => this.lcuStatus = { isConnected: false }
      });
    };

    // Verificar imediatamente uma vez
    checkOnce();

    // ✅ Se já temos jogador identificado e conectado, usar polling mais lento (20s)
    // ✅ Se não temos jogador, usar polling rápido (5s) para identificar logo
    const intervalTime = this.currentPlayer ? 20000 : 5000;

    this.lcuCheckInterval = setInterval(checkOnce, intervalTime);
    console.log(`🔄 [App] LCU status check iniciado com intervalo de ${intervalTime}ms`);
    */
  }

  // ✅ NOVO: Método para simular partida personalizada
  async simulateCustomMatch(): Promise<void> {
    console.log('🎮 [App] Iniciando simulação de partida personalizada...');

    if (!this.currentPlayer) {
      this.addNotification('error', 'Erro', 'Jogador não identificado');
      return;
    }

    // Criar dados de partida simulada
    const simulatedMatch = this.createSimulatedMatchData(this.currentPlayer);

    try {
      // Enviar dados da partida simulada para o backend
      await firstValueFrom(this.apiService.simulateMatch(simulatedMatch));
      console.log('✅ [App] Partida simulada com sucesso');

      this.addNotification('success', 'Simulação Completa', 'A partida personalizada foi simulada com sucesso');
    } catch (error) {
      console.error('❌ [App] Erro ao simular partida:', error);
      this.addNotification('error', 'Erro na Simulação', 'Não foi possível simular a partida');
    }
  }

  // ✅ NOVO: Criar dados simulados para uma partida personalizada
  private createSimulatedMatchData(player: Player): any {
    const currentTime = new Date();
    const matchId = `custom_${currentTime.getTime()}`;

    // Simular dados básicos
    const simulatedData: any = {
      matchId: matchId,
      gameId: matchId,
      region: player.region,
      queueId: 440, // RANKED_FLEX_SR
      playerCount: 10,
      duration: 1800,
      startTime: currentTime.toISOString(),
      endTime: new Date(currentTime.getTime() + 1800 * 1000).toISOString(),
      team1: [] as any[],
      team2: [] as any[],
      pickBanData: this.createSimulatedPickBanData(player)
    };

    // Adicionar jogadores simulados (5v5)
    for (let i = 0; i < 5; i++) {
      simulatedData.team1.push(this.createSimulatedPlayerData(`blue_player${i + 1}`, 'blue', player.region));
      simulatedData.team2.push(this.createSimulatedPlayerData(`red_player${i + 1}`, 'red', player.region));
    }

    return simulatedData;
  }

  // ✅ NOVO: Criar dados simulados de pick/ban para a partida
  private createSimulatedPickBanData(player: Player): any {
    return {
      phases: [
        {
          id: 1,
          type: 'ban',
          team: 'blue',
          champion: { id: 1, name: 'Champion1' },
          player: player.summonerName,
          timestamp: Date.now()
        },
        {
          id: 2,
          type: 'pick',
          team: 'blue',
          champion: { id: 2, name: 'Champion2' },
          player: player.summonerName,
          timestamp: Date.now() + 5000
        },
        {
          id: 3,
          type: 'ban',
          team: 'red',
          champion: { id: 3, name: 'Champion3' },
          player: `red_player1`,
          timestamp: Date.now() + 10000
        },
        {
          id: 4,
          type: 'pick',
          team: 'red',
          champion: { id: 4, name: 'Champion4' },
          player: `red_player1`,
          timestamp: Date.now() + 15000
        }
      ]
    };
  }

  // ✅ NOVO: Criar dados simulados para um jogador
  private createSimulatedPlayerData(name: string, team: 'blue' | 'red', region: string): any {
    return {
      summonerName: name,
      displayName: name,
      puuid: `${name}-puuid`,
      profileIconId: 29,
      summonerLevel: 30,
      region: region,
      currentMMR: 1200,
      customLp: 1200,
      team: team
    };
  }

  // ✅ NOVO: Método para simular partida personalizada com base em dados do LCU
  async simulateLCUBasedMatch(): Promise<void> {
    console.log('🎮 [App] Iniciando simulação de partida baseada no LCU...');

    if (!this.currentPlayer) {
      this.addNotification('error', 'Erro', 'Jogador não identificado');
      return;
    }

    try {
      // Buscar dados da última partida do LCU
      const matchHistory = await firstValueFrom(this.apiService.getLCUMatchHistoryAll(0, 1, false));
      const lastMatch = matchHistory?.matches?.[0];

      if (!lastMatch) {
        this.addNotification('error', 'Erro na Simulação', 'Nenhuma partida encontrada no histórico do LCU');
        return;
      }

      console.log('✅ [App] Última partida do LCU encontrada:', lastMatch);

      // Criar dados de partida simulada com base no último jogo
      const simulatedMatch = this.createSimulatedMatchDataFromLCU(lastMatch, this.currentPlayer);

      // Enviar dados da partida simulada para o backend
      await firstValueFrom(this.apiService.simulateMatch(simulatedMatch));
      console.log('✅ [App] Partida simulada com sucesso');

      this.addNotification('success', 'Simulação Completa', 'A partida personalizada foi simulada com sucesso');
    } catch (error) {
      console.error('❌ [App] Erro ao simular partida:', error);
      this.addNotification('error', 'Erro na Simulação', 'Não foi possível simular a partida');
    }
  }

  // ✅ NOVO: Criar dados simulados para uma partida com base nos dados do LCU
  private createSimulatedMatchDataFromLCU(matchData: any, player: Player): any {
    const currentTime = new Date();
    const matchId = `custom_${currentTime.getTime()}`;

    // Simular dados básicos
    const simulatedData: any = {
      matchId: matchId,
      gameId: matchId,
      region: player.region,
      queueId: matchData.queueId,
      playerCount: 10,
      duration: matchData.duration || 1800,
      startTime: currentTime.toISOString(),
      endTime: new Date(currentTime.getTime() + (matchData.duration || 1800) * 1000).toISOString(),
      team1: [] as any[],
      team2: [] as any[],
      pickBanData: matchData.pickBanData || this.createSimulatedPickBanData(player)
    };

    // Adicionar jogadores simulados (5v5)
    for (let i = 0; i < 5; i++) {
      simulatedData.team1.push(this.createSimulatedPlayerData(`blue_player${i + 1}`, 'blue', player.region));
      simulatedData.team2.push(this.createSimulatedPlayerData(`red_player${i + 1}`, 'red', player.region));
    }

    return simulatedData;
  }

  // REMOVIDO: Duplicata tardia de startGameInProgressFromSimulation para evitar TS2393
  // private startGameInProgressFromSimulation(simulationData: any): void { ... }
  // ✅ NOVO: Método auxiliar para extrair dados dos times da partida
  private extractTeamFromMatchData(matchData: any, teamNumber: number): any[] {
    try {
      logApp(`[simular game] Extraindo time ${teamNumber} de:`, matchData);

      // ✅ NOVO: Tentar extrair dados do pick_ban_data primeiro
      let pickBanData = null;
      try {
        if (matchData.pick_ban_data) {
          pickBanData = typeof matchData.pick_ban_data === 'string'
            ? JSON.parse(matchData.pick_ban_data)
            : matchData.pick_ban_data;
          logApp(`[simular game] Pick/ban data encontrada para time ${teamNumber}:`, pickBanData);
        }
      } catch (parseError) {
        logApp(`[simular game] Erro ao parsear pick_ban_data:`, parseError);
      }

      // ✅ NOVO: Se temos dados de pick/ban, usar eles para extrair campeões e lanes
      if (pickBanData?.team1Picks && pickBanData?.team2Picks) {
        const picks = teamNumber === 1 ? pickBanData.team1Picks : pickBanData.team2Picks;
        logApp(`[simular game] Picks encontrados para time ${teamNumber}:`, picks);

        if (Array.isArray(picks) && picks.length > 0) {
          // ✅ NOVO: Criar estrutura compatível com GameInProgress
          return picks.map((pick: any, index: number) => {
            // ✅ CORREÇÃO: Usar championName processado pelo backend como prioridade
            let championName = null;
            if (pick.championName) {
              // ✅ PRIORIDADE 1: championName já processado pelo backend
              championName = pick.championName;
            } else if (pick.champion) {
              // ✅ PRIORIDADE 2: champion (pode ser nome ou ID)
              championName = pick.champion;
            } else if (pick.championId) {
              // ✅ PRIORIDADE 3: championId - usar fallback
              championName = `Champion${pick.championId}`;
            }

            // ✅ NOVO: Mapear lanes corretamente
            const mapLane = (lane: string): string => {
              if (!lane || lane === 'UNKNOWN' || lane === 'unknown') {
                // ✅ FALLBACK: Tentar determinar lane baseada no índice ou campeão
                const laneByIndex = ['top', 'jungle', 'mid', 'adc', 'support'][index] || 'unknown';
                console.log(`[simular game] Lane UNKNOWN, usando lane por índice ${index}: ${laneByIndex}`);
                return laneByIndex;
              }

              // Mapear valores do Riot API para valores do nosso sistema
              const laneMap: { [key: string]: string } = {
                'TOP': 'top',
                'JUNGLE': 'jungle',
                'MIDDLE': 'mid',
                'BOTTOM': 'adc',
                'UTILITY': 'support',
                'NONE': 'unknown'
              };

              return laneMap[lane.toUpperCase()] || lane.toLowerCase() || 'unknown';
            };

            const mappedLane = mapLane(pick.lane);
            const teamIndex = teamNumber === 1 ? index : index + 5; // 0-4 para team1, 5-9 para team2

            logApp(`[simular game] Criando jogador: ${pick.player} (${mappedLane}) - champion: ${championName} - teamIndex: ${teamIndex}`);

            return {
              summonerName: pick.player || pick.summonerName || 'Unknown',
              name: pick.player || pick.summonerName || 'Unknown',
              id: null,
              champion: championName, // ✅ CORREÇÃO: Usar championName resolvido
              championName: championName, // ✅ ADICIONADO: Para compatibilidade
              championId: pick.championId || null,
              lane: mappedLane,
              assignedLane: mappedLane,
              // ✅ NOVO: Adicionar campos necessários para GameInProgress
              teamIndex: teamIndex,
              mmr: 1000, // MMR padrão para simulação
              primaryLane: mappedLane,
              secondaryLane: 'unknown',
              isAutofill: false
            };
          });
        }
      }

      // ✅ FALLBACK: Usar dados básicos dos jogadores se não houver pick/ban data
      const teamPlayers = teamNumber === 1 ? matchData.team1_players : matchData.team2_players;
      logApp(`[simular game] Usando fallback - teamPlayers para time ${teamNumber}:`, teamPlayers);

      if (typeof teamPlayers === 'string') {
        const players = JSON.parse(teamPlayers);
        return players.map((playerName: string, index: number) => {
          const teamIndex = teamNumber === 1 ? index : index + 5;
          const laneByIndex = ['top', 'jungle', 'mid', 'adc', 'support'][index] || 'unknown';

          return {
            summonerName: playerName,
            name: playerName,
            id: null,
            champion: null,
            championName: null, // ✅ ADICIONADO: Para compatibilidade
            championId: null,
            lane: laneByIndex,
            assignedLane: laneByIndex,
            teamIndex: teamIndex,
            mmr: 1000,
            primaryLane: laneByIndex,
            secondaryLane: 'unknown',
            isAutofill: false
          };
        });
      } else if (Array.isArray(teamPlayers)) {
        return teamPlayers.map((playerName: string, index: number) => {
          const teamIndex = teamNumber === 1 ? index : index + 5;
          const laneByIndex = ['top', 'jungle', 'mid', 'adc', 'support'][index] || 'unknown';

          return {
            summonerName: playerName,
            name: playerName,
            id: null,
            champion: null,
            championName: null, // ✅ ADICIONADO: Para compatibilidade
            championId: null,
            lane: laneByIndex,
            assignedLane: laneByIndex,
            teamIndex: teamIndex,
            mmr: 1000,
            primaryLane: laneByIndex,
            secondaryLane: 'unknown',
            isAutofill: false
          };
        });
      }

      return [];
    } catch (error) {
      logApp(`❌ [App] Erro ao extrair time ${teamNumber}:`, error);
      return [];
    }
  }

  // Métodos para Special User Tools
  addBotToQueue(): void {
    console.log('🤖 [App] Adicionando bot à fila');

    this.apiService.addBotToQueue().subscribe({
      next: (response) => {
        console.log('✅ [App] Bot adicionado à fila:', response);
        this.addNotification('success', 'Bot Adicionado', 'Bot foi adicionado à fila com sucesso');
      },
      error: (error) => {
        console.error('❌ [App] Erro ao adicionar bot à fila:', error);
        this.addNotification('error', 'Erro ao Adicionar Bot', 'Falha ao adicionar bot à fila');
      }
    });
  }

  resetBotCounter(): void {
    console.log('🔄 [App] Resetando contador de bots');

    this.apiService.resetBotCounter().subscribe({
      next: (response) => {
        console.log('✅ [App] Contador de bots resetado:', response);
        this.addNotification('success', 'Contador Resetado', 'Contador de bots foi resetado com sucesso');
      },
      error: (error) => {
        console.error('❌ [App] Erro ao resetar contador de bots:', error);
        this.addNotification('error', 'Erro ao Resetar', 'Falha ao resetar contador de bots');
      }
    });
  }

  async simulateLastMatch(): Promise<void> {
    console.log('🎮 [App] Simulando última partida PERSONALIZADA do LCU...');

    try {
      // 1. Buscar histórico completo do LCU (limite maior para encontrar personalizadas)
      const response: any = await firstValueFrom(this.apiService.getLCUMatchHistoryAll(0, 20, false));

      if (!response?.success || !response?.matches || response.matches.length === 0) {
        this.addNotification('error', 'Sem Partidas', 'Nenhuma partida encontrada no histórico do LCU. Certifique-se de que o League of Legends está aberto.');
        console.error('❌ [App] Nenhuma partida encontrada:', response);
        return;
      }

      // 2. Filtrar apenas partidas PERSONALIZADAS (CUSTOM_GAME)
      const customMatches = response.matches.filter((m: any) => m.gameType === 'CUSTOM_GAME');
      console.log(`🔍 [App] Encontradas ${customMatches.length} partidas personalizadas de ${response.matches.length} totais`);

      if (customMatches.length === 0) {
        this.addNotification('error', 'Sem Partidas Personalizadas', 'Nenhuma partida personalizada encontrada no histórico. Jogue uma partida personalizada primeiro!');
        console.error('❌ [App] Nenhuma partida personalizada encontrada');
        return;
      }

      // 3. Pegar a primeira (última) partida personalizada
      const lastMatchSummary = customMatches[0];
      console.log('✅ [App] Última partida personalizada encontrada (resumo):', lastMatchSummary);
      console.log('🔍 [App] GameId:', lastMatchSummary.gameId);

      // 4. Buscar detalhes COMPLETOS da partida usando gameId
      console.log('📡 [App] Buscando detalhes completos da partida...');
      const lastMatch: any = await firstValueFrom(this.apiService.getLCUGameDetails(lastMatchSummary.gameId));

      if (!lastMatch || !lastMatch.participants || lastMatch.participants.length !== 10) {
        console.error('❌ [App] Detalhes da partida inválidos:', lastMatch);
        this.addNotification('error', 'Erro nos Detalhes',
          `Não foi possível buscar detalhes completos da partida. Participantes: ${lastMatch?.participants?.length || 0}`);
        return;
      }

      console.log('✅ [App] Detalhes completos da partida carregados:', {
        gameId: lastMatch.gameId,
        participantsCount: lastMatch.participants?.length,
        participantIdentitiesCount: lastMatch.participantIdentities?.length
      });

      // 5. Enviar para backend simular como partida IN_PROGRESS
      console.log('📡 [App] Enviando partida para backend criar como IN_PROGRESS...');
      const simulateResponse: any = await firstValueFrom(this.apiService.simulateLastLcuMatch(lastMatch));

      if (simulateResponse?.success) {
        this.addNotification(
          'success',
          'Entrando na Partida!',
          `Partida simulada criada. Redirecionando para Game In Progress...`
        );
        console.log('✅ [App] Partida simulada criada - aguardando broadcast game_started:', simulateResponse);
        // O WebSocket receberá game_started e redirecionará automaticamente
      } else {
        this.addNotification('error', 'Erro na Simulação', simulateResponse?.error || 'Erro desconhecido');
        console.error('❌ [App] Erro na simulação:', simulateResponse);
      }

    } catch (error) {
      console.error('❌ [App] Erro ao simular partida:', error);
      this.addNotification('error', 'Erro na Simulação', 'Falha ao simular última partida. Verifique os logs.');
    }
  }

  cleanupTestMatches(): void {
    console.log('🧹 [App] Limpando partidas de teste');

    this.apiService.cleanupTestMatches().subscribe({
      next: (response) => {
        console.log('✅ [App] Partidas de teste limpas:', response);
        this.addNotification('success', 'Limpeza Completa', `${response.deletedCount || 0} partidas de teste removidas`);
      },
      error: (error) => {
        console.error('❌ [App] Erro ao limpar partidas de teste:', error);
        this.addNotification('error', 'Erro Limpeza', 'Não foi possível limpar as partidas de teste');
      }
    });
  }

  // Métodos para configuração do Discord Bot
  updateDiscordBotToken(): void {
    if (!this.settingsForm.discordBotToken?.trim()) {
      this.addNotification('warning', 'Token Vazio', 'Por favor, insira um token do Discord Bot válido');
      return;
    }

    console.log('🤖 [App] Configurando token do Discord Bot...');

    this.apiService.setDiscordBotToken(this.settingsForm.discordBotToken.trim()).subscribe({
      next: (response) => {
        console.log('✅ [App] Token do Discord Bot configurado:', response);
        this.addNotification('success', 'Token Configurado', 'Token do Discord Bot foi configurado com sucesso');
        this.loadDiscordSettings(); // Recarregar configurações
      },
      error: (error) => {
        console.error('❌ [App] Erro ao configurar token do Discord Bot:', error);
        this.addNotification('error', 'Erro na Configuração', 'Falha ao configurar token do Discord Bot');
      }
    });
  }

  updateDiscordChannel(): void {
    if (!this.settingsForm.discordChannel?.trim()) {
      this.addNotification('warning', 'Canal Vazio', 'Por favor, insira um nome de canal válido');
      return;
    }

    console.log('🎯 [App] Configurando canal do Discord...');

    this.apiService.setDiscordChannel(this.settingsForm.discordChannel.trim()).subscribe({
      next: (response) => {
        console.log('✅ [App] Canal do Discord configurado:', response);
        this.addNotification('success', 'Canal Configurado', 'Canal do Discord foi configurado com sucesso');
        this.loadDiscordSettings(); // Recarregar configurações
      },
      error: (error) => {
        console.error('❌ [App] Erro ao configurar canal do Discord:', error);

        // Verificar se o erro é um 500 - pode ser um "falso positivo" se o dado foi salvo
        if (error.status === 500) {
          console.log('🔄 [App] Erro 500 - verificando se canal foi salvo no banco...');

          // Salvar o valor que tentamos enviar para comparação
          const attemptedChannelId = this.settingsForm.discordChannel.trim();

          // Aguardar um pouco e tentar recarregar as configurações
          setTimeout(() => {
            this.loadDiscordSettings();

            // Verificar se o canal foi carregado corretamente
            setTimeout(() => {
              const currentChannelId = this.settingsForm.discordChannel;
              console.log('🔍 [App] Verificação pós-erro 500:', {
                attempted: attemptedChannelId,
                loaded: currentChannelId,
                match: attemptedChannelId === currentChannelId
              });

              if (currentChannelId && currentChannelId === attemptedChannelId) {
                console.log('✅ [App] Canal foi salvo corretamente apesar do erro 500');
                this.addNotification('success', 'Canal Configurado', 'Canal foi configurado com sucesso (salvo no banco)');
              } else {
                console.log('❌ [App] Canal não foi salvo - erro real');
                this.addNotification('error', 'Erro na Configuração', 'Falha ao configurar canal do Discord. Tente novamente.');
              }
            }, 1500);
          }, 1000);
        } else {
          this.addNotification('error', 'Erro na Configuração', 'Falha ao configurar canal do Discord');
        }
      }
    });
  }

  // Carrega configurações do Discord do backend
  private loadDiscordSettings(): void {
    console.log('🤖 [App] Carregando configurações do Discord...');

    this.apiService.getConfigSettings().subscribe({
      next: (resp: any) => {
        try {
          if (resp?.settings) {
            const oldToken = this.settingsForm.discordBotToken;
            const oldChannel = this.settingsForm.discordChannel;

            // Carregar token do Discord (se existir)
            if (resp.settings['discord_token']) {
              this.settingsForm.discordBotToken = resp.settings['discord_token'];
            }

            // Carregar canal do Discord (se existir)
            if (resp.settings['discord_channel_id']) {
              this.settingsForm.discordChannel = resp.settings['discord_channel_id'];
            }

            console.log('🤖 [App] Configurações do Discord carregadas:', {
              hasToken: !!resp.settings['discord_token'],
              hasChannel: !!resp.settings['discord_channel_id'],
              tokenChanged: oldToken !== this.settingsForm.discordBotToken,
              channelChanged: oldChannel !== this.settingsForm.discordChannel,
              currentChannel: this.settingsForm.discordChannel
            });
          }
          this.cdr.detectChanges();
        } catch (err) {
          console.warn('⚠️ [App] Erro ao carregar configurações do Discord:', err);
        }
      },
      error: (err) => {
        console.warn('⚠️ [App] Falha ao carregar configurações do Discord:', err);
      }
    });
  }

  // ✅ ADICIONADO: Atualizar formulário com dados do jogador atual
  private updateSettingsForm(): void {
    if (this.currentPlayer) {
      this.settingsForm.summonerName = this.currentPlayer.summonerName;
      this.settingsForm.region = this.currentPlayer.region;
      console.log('✅ [App] Formulário de configurações atualizado:', this.settingsForm);
    }
  }

  // ✅ ADICIONADO: Propriedades faltantes para o template
  get currentMatchData(): any {
    return this.draftData || this.gameData || null;
  }

  // ✅ NOVO: Verificar se jogador atual é bot
  isCurrentPlayerBot(): boolean {
    return this.currentPlayer ? this.botService.isBot(this.currentPlayer) : false;
  }

  // ✅ NOVO: Iniciar polling inteligente
  private startIntelligentPolling(): void {
    console.log('🔄 [App] Iniciando polling inteligente para sincronização...');

    this.pollingInterval = setInterval(async () => {
      await this.checkSyncStatus();
    }, this.POLLING_INTERVAL_MS);
  }

  // ✅ NOVO: Parar polling
  private stopIntelligentPolling(): void {
    if (this.pollingInterval) {
      clearInterval(this.pollingInterval);
      this.pollingInterval = null;
      console.log('🛑 [App] Polling inteligente parado');
    }
  }

  // ✅ NOVO: Verificar status de sincronização com controle de timing
  private async checkSyncStatus(): Promise<void> {
    if (!this.currentPlayer?.displayName) {
      return; // Sem jogador identificado
    }

    // ✅ NOVO: Verificar se há ação recente do backend
    const now = Date.now();
    const timeSinceLastBackendAction = now - this.lastBackendAction;

    if (timeSinceLastBackendAction < this.BACKEND_ACTION_COOLDOWN) {
      console.log(`⏳ [App] Aguardando backend processar (${this.BACKEND_ACTION_COOLDOWN - timeSinceLastBackendAction}ms restantes)`);
      return;
    }

    try {
      const response = await firstValueFrom(this.apiService.checkSyncStatus(this.currentPlayer.displayName));
      const currentStatus = response.status;

      console.log(`🔄 [App] Polling status: ${currentStatus} (anterior: ${this.lastPollingStatus})`);

      // ✅ VERIFICAR: Se o status mudou desde a última verificação
      if (currentStatus !== this.lastPollingStatus) {
        console.log(`🔄 [App] Mudança de status detectada: ${this.lastPollingStatus} → ${currentStatus}`);
        await this.handleStatusChange(currentStatus, response);
        this.lastPollingStatus = currentStatus;

        // ✅ NOVO: Invalidar cache apenas quando há mudança real
        this.invalidateCacheIntelligently();
      }
    } catch (error) {
      console.error('❌ [App] Erro no polling de sincronização:', error);
    }
  }

  // ✅ NOVO: Invalidar cache de forma inteligente (com cooldown)
  private invalidateCacheIntelligently(): void {
    const now = Date.now();
    const timeSinceLastInvalidation = now - this.lastCacheInvalidation;

    if (timeSinceLastInvalidation < this.CACHE_INVALIDATION_COOLDOWN) {
      console.log(`⏳ [App] Cache invalidation throttled - aguardando ${this.CACHE_INVALIDATION_COOLDOWN - timeSinceLastInvalidation}ms`);
      return;
    }

    console.log('🔄 [App] Invalidando cache (mudança de status detectada)');
    this.lastCacheInvalidation = now;

    // ✅ NOVO: Forçar atualização da interface apenas quando necessário
    this.cdr.detectChanges();
  }

  // ✅ NOVO: Marcar ação do backend para controle de timing
  private markBackendAction(): void {
    this.lastBackendAction = Date.now();
    console.log('🔄 [App] Ação do backend marcada - aguardando processamento');
  }

  // ✅ NOVO: Método auxiliar para obter nome de exibição da lane
  private getLaneDisplayName(laneId: string): string {
    const laneNames: { [key: string]: string } = {
      'top': 'Topo',
      'jungle': 'Selva',
      'mid': 'Meio',
      'bot': 'Atirador',
      'adc': 'Atirador',
      'support': 'Suporte',
      'fill': 'Qualquer'
    };
    return laneNames[laneId] || 'Qualquer';
  }

  // ✅ NOVO: Atualizar tabela de jogadores otimisticamente quando alguém entra na fila
  private updateQueueTableOptimistically(player: Player, preferences: QueuePreferences): void {
    console.log('📊 [App] Atualizando tabela de jogadores otimisticamente...', {
      playerName: player.displayName || player.summonerName,
      preferences: preferences
    });

    // Criar entrada do jogador na fila
    const queuePlayer = {
      summonerName: player.displayName || player.summonerName || '',
      tagLine: player.tagLine || '',
      primaryLane: preferences.primaryLane || '',
      secondaryLane: preferences.secondaryLane || '',
      primaryLaneDisplay: this.getLaneDisplayName(preferences.primaryLane || ''),
      secondaryLaneDisplay: this.getLaneDisplayName(preferences.secondaryLane || ''),
      mmr: player.currentMMR || 1200,
      queuePosition: (this.queueStatus.playersInQueue || 0) + 1,
      joinTime: new Date().toISOString(),
      isCurrentPlayer: true // Marcar como jogador atual
    };

    // Adicionar à lista existente ou criar nova
    if (!this.queueStatus.playersInQueueList) {
      this.queueStatus.playersInQueueList = [];
    }

    // Remover jogador da lista se já estiver lá (atualização)
    this.queueStatus.playersInQueueList = this.queueStatus.playersInQueueList.filter(
      p => !p.isCurrentPlayer && p.summonerName !== queuePlayer.summonerName
    );

    // Adicionar no início da lista
    this.queueStatus.playersInQueueList.unshift(queuePlayer);

    // Atualizar contador
    this.queueStatus.playersInQueue = this.queueStatus.playersInQueueList.length;

    console.log('✅ [App] Tabela atualizada otimisticamente:', {
      playersCount: this.queueStatus.playersInQueue,
      playersListLength: this.queueStatus.playersInQueueList?.length
    });

    // Forçar detecção de mudanças
    this.cdr.detectChanges();
  }

  // ✅ NOVO: Lidar com mudança de status detectada via polling
  private async handleStatusChange(newStatus: string, response: any): Promise<void> {
    console.log(`🔄 [App] === PROCESSANDO MUDANÇA DE STATUS ===`);
    console.log(`🔄 [App] Novo status: ${newStatus}`);
    console.log(`🔄 [App] Dados completos da resposta:`, JSON.stringify(response, null, 2));
    console.log('🔄 [App] Estado atual:', {
      inGamePhase: this.inGamePhase,
      inDraftPhase: this.inDraftPhase,
      showMatchFound: this.showMatchFound,
      isInQueue: this.isInQueue,
      lastMatchId: this.lastMatchId
    });

    switch (newStatus) {
      case 'match_found':
        await this.handleMatchFoundFromPolling(response);
        break;
      case 'draft':
        await this.handleDraftFromPolling(response);
        break;
      case 'game_in_progress':
        await this.handleGameInProgressFromPolling(response);
        break;
      case 'none':
        await this.handleNoStatusFromPolling();
        break;
      default:
        console.warn(`⚠️ [App] Status desconhecido: ${newStatus}`);
    }
  }

  // ✅ NOVO: Processar match_found detectado via polling
  private async handleMatchFoundFromPolling(response: any): Promise<void> {
    console.log('🎮 [App] Match found detectado via polling!');

    // ✅ VERIFICAR: Se já estamos mostrando match_found
    if (this.showMatchFound && this.matchFoundData?.matchId === response.matchId) {
      console.log('✅ [App] Match found já está sendo exibido, ignorando');
      return;
    }

    // ✅ PROCESSAR: Dados do match_found
    const matchData = response.match;
    if (!matchData) {
      console.error('❌ [App] Dados do match não encontrados na resposta');
      return;
    }

    // ✅ CONSTRUIR: Dados estruturados para o frontend
    let team1Players: string[] = [];
    let team2Players: string[] = [];

    try {
      team1Players = typeof matchData.team1_players === 'string'
        ? JSON.parse(matchData.team1_players)
        : (matchData.team1_players || []);
      team2Players = typeof matchData.team2_players === 'string'
        ? JSON.parse(matchData.team2_players)
        : (matchData.team2_players || []);
    } catch (error) {
      console.error('❌ [App] Erro ao parsear dados dos times:', error);
      return;
    }

    // ✅ IDENTIFICAR: Time do jogador atual
    const currentPlayerIdentifiers = this.getCurrentPlayerIdentifiers();
    const isInTeam1 = team1Players.some((name: string) =>
      currentPlayerIdentifiers.some(id => this.namesMatch(id, name))
    );

    // ✅ CONSTRUIR: Dados do match_found
    const matchFoundData: MatchFoundData = {
      matchId: response.matchId,
      playerSide: isInTeam1 ? 'blue' : 'red',
      teammates: this.convertBasicPlayersToPlayerInfo(isInTeam1 ? team1Players : team2Players),
      enemies: this.convertBasicPlayersToPlayerInfo(isInTeam1 ? team2Players : team1Players),
      averageMMR: { yourTeam: 1200, enemyTeam: 1200 },
      estimatedGameDuration: 25,
      phase: 'accept',
      acceptTimeout: 30,
      acceptanceTimer: 30
    };

    // ✅ ATUALIZAR: Estado local
    this.matchFoundData = matchFoundData;
    this.isInQueue = false;
    this.showMatchFound = true;
    this.lastMatchId = response.matchId;

    console.log('✅ [App] Match found processado via polling:', {
      matchId: response.matchId,
      playerSide: matchFoundData.playerSide,
      teammatesCount: matchFoundData.teammates.length,
      enemiesCount: matchFoundData.enemies.length
    });

    this.addNotification('success', 'Partida Encontrada!', 'Você tem 30 segundos para aceitar.');
  }

  // ✅ NOVO: Processar draft detectado via polling
  private async handleDraftFromPolling(response: any): Promise<void> {
    console.log('🎯 [App] Draft detectado via polling!');

    // ✅ VERIFICAR: Se já estamos em draft
    if (this.inDraftPhase && this.draftData?.matchId === response.matchId) {
      console.log('✅ [App] Draft já está ativo, ignorando');
      return;
    }

    // ✅ PROCESSAR: Dados do draft
    const matchData = response.match;
    if (!matchData) {
      console.error('❌ [App] Dados do draft não encontrados na resposta');
      return;
    }

    // ✅ NOVO: Se vier pick_ban_data do backend, usar diretamente
    if (response.pick_ban_data) {
      console.log('✅ [App] Usando pick_ban_data do backend para inicializar draft:', response.pick_ban_data);

      // ✅ CORREÇÃO: Mapear team1/team2 para blueTeam/redTeam conforme esperado pelo frontend
      const pickBanData = typeof response.pick_ban_data === 'string'
        ? JSON.parse(response.pick_ban_data)
        : response.pick_ban_data;

      this.draftData = {
        matchId: response.matchId,
        blueTeam: pickBanData.team1 || [], // Sempre: blueTeam = team1
        redTeam: pickBanData.team2 || [],  // Sempre: redTeam = team2
        phases: pickBanData.phases || [],
        currentAction: pickBanData.currentAction || 0,
        phase: pickBanData.phase || 'bans',
        actions: pickBanData.actions || [],
        team1Picks: pickBanData.team1Picks || [],
        team1Bans: pickBanData.team1Bans || [],
        team2Picks: pickBanData.team2Picks || [],
        team2Bans: pickBanData.team2Bans || []
      };

      this.inDraftPhase = true;
      this.showMatchFound = false;
      this.isInQueue = false;
      this.lastMatchId = response.matchId;
      this.addNotification('success', 'Draft Iniciado!', 'A fase de seleção de campeões começou.');
      return;
    }

    // ✅ CONSTRUIR: Dados do draft (fallback antigo)
    let team1Players: string[] = [];
    let team2Players: string[] = [];

    try {
      team1Players = typeof matchData.team1_players === 'string'
        ? JSON.parse(matchData.team1_players)
        : (matchData.team1_players || []);
      team2Players = typeof matchData.team2_players === 'string'
        ? JSON.parse(matchData.team2_players)
        : (matchData.team2_players || []);
    } catch (error) {
      console.error('❌ [App] Erro ao parsear dados dos times do draft:', error);
      return;
    }

    // ✅ IDENTIFICAR: Time do jogador atual
    const currentPlayerIdentifiers = this.getCurrentPlayerIdentifiers();
    const isInTeam1 = team1Players.some((name: string) =>
      currentPlayerIdentifiers.some(id => this.namesMatch(id, name))
    );

    // ✅ CONSTRUIR: Dados estruturados do draft
    const draftData = {
      matchId: response.matchId,
      teammates: this.convertBasicPlayersToPlayerInfo(isInTeam1 ? team1Players : team2Players),
      enemies: this.convertBasicPlayersToPlayerInfo(isInTeam1 ? team2Players : team1Players),
      blueTeam: this.convertBasicPlayersToPlayerInfo(team1Players), // ✅ CORREÇÃO: Mapear para blueTeam
      redTeam: this.convertBasicPlayersToPlayerInfo(team2Players), // ✅ CORREÇÃO: Mapear para redTeam
      phase: 'draft',
      phases: [],
      currentAction: 0
    };

    // ✅ ATUALIZAR: Estado local
    this.draftData = draftData;
    this.inDraftPhase = true;
    this.showMatchFound = false;
    this.isInQueue = false;
    this.lastMatchId = response.matchId;

    console.log('✅ [App] Draft processado via polling:', {
      matchId: response.matchId,
      teammatesCount: draftData.teammates.length,
      enemiesCount: draftData.enemies.length
    });

    this.addNotification('success', 'Draft Iniciado!', 'A fase de seleção de campeões começou.');
  }

  // ✅ NOVO: Handler para conclusão do draft (todos os 10 jogadores confirmaram)
  onPickBanComplete(event: any): void {
    logApp('🎮 [App] onPickBanComplete chamado:', event);

    if (!event) {
      logApp('❌ [App] Evento vazio recebido em onPickBanComplete');
      return;
    }

    // ✅ NOVO: Se recebemos gameData do backend, usar diretamente
    if (event.gameData) {
      logApp('✅ [App] gameData recebido do backend:', event.gameData);

      this.gameData = event.gameData;
      this.inGamePhase = true;
      this.inDraftPhase = false;
      this.showMatchFound = false;
      this.isInQueue = false;

      if (event.gameData.matchId) {
        this.lastMatchId = event.gameData.matchId;
      }

      this.addNotification('success', 'Jogo Iniciado!', 'A partida começou com todos os jogadores prontos.');
      this.cdr.detectChanges();
      return;
    }

    // ✅ FALLBACK: Processar confirmationData se gameData não estiver disponível
    logApp('⚠️ [App] gameData não disponível, tentando processar confirmationData...');

    if (event.status === 'in_progress') {
      this.inGamePhase = true;
      this.inDraftPhase = false;
      this.showMatchFound = false;
      this.isInQueue = false;

      // Tentar construir gameData do session/confirmationData
      if (event.confirmationData || event.session) {
        const pickBanData = event.confirmationData || event.session;
        this.gameData = {
          matchId: event.matchData?.matchId || event.session?.id,
          team1: pickBanData.teams?.blue?.players || [],
          team2: pickBanData.teams?.red?.players || [],
          status: 'in_progress',
          startedAt: new Date()
        };
      }

      this.addNotification('success', 'Jogo Iniciado!', 'A partida começou.');
      this.cdr.detectChanges();
    }
  }

  // ✅ NOVO: Processar game_in_progress detectado via polling
  private async handleGameInProgressFromPolling(response: any): Promise<void> {
    console.log('🎮 [App] === GAME IN PROGRESS DETECTADO VIA POLLING ===');
    console.log('🎮 [App] Response completo:', response);
    console.log('🎮 [App] Estado atual antes da mudança:', {
      inGamePhase: this.inGamePhase,
      inDraftPhase: this.inDraftPhase,
      showMatchFound: this.showMatchFound,
      isInQueue: this.isInQueue,
      gameDataExists: !!this.gameData,
      lastMatchId: this.lastMatchId
    });

    // ✅ VERIFICAR: Se já estamos em game
    if (this.inGamePhase && this.gameData?.matchId === response.matchId) {
      console.log('✅ [App] Game já está ativo, ignorando');
      return;
    }

    // ✅ PROCESSAR: Dados do game
    const matchData = response.match;
    if (!matchData) {
      console.error('❌ [App] Dados do game não encontrados na resposta');
      return;
    }

    // ✅ CONSTRUIR: Dados do game COM informações do draft
    let team1: any[] = [];
    let team2: any[] = [];

    // ✅ PRIMEIRO: Tentar usar dados completos do draft da resposta
    if (response.pick_ban_data?.draftResults) {
      team1 = response.pick_ban_data.draftResults.team1 || [];
      team2 = response.pick_ban_data.draftResults.team2 || [];
      console.log('✅ [App] Usando dados completos do draft:', {
        team1Count: team1.length,
        team2Count: team2.length,
        team1Sample: team1[0],
        team2Sample: team2[0]
      });
    } else {
      // ✅ FALLBACK: Usar dados básicos se não há draft completo
      let team1Players: string[] = [];
      let team2Players: string[] = [];

      try {
        team1Players = typeof matchData.team1_players === 'string'
          ? JSON.parse(matchData.team1_players)
          : (matchData.team1_players || []);
        team2Players = typeof matchData.team2_players === 'string'
          ? JSON.parse(matchData.team2_players)
          : (matchData.team2_players || []);
      } catch (error) {
        console.error('❌ [App] Erro ao parsear dados dos times do game:', error);
        return;
      }

      team1 = this.convertBasicPlayersToPlayerInfo(team1Players);
      team2 = this.convertBasicPlayersToPlayerInfo(team2Players);
      console.log('⚠️ [App] Usando dados básicos (sem draft):', {
        team1Count: team1.length,
        team2Count: team2.length
      });
    }

    // ✅ CONSTRUIR: Dados do game
    const gameData = {
      matchId: response.matchId,
      team1: team1,
      team2: team2,
      status: 'in_progress',
      startedAt: new Date(),
      estimatedDuration: 1800,
      pickBanData: response.pick_ban_data // ✅ INCLUIR dados do draft
    };

    // ✅ ATUALIZAR: Estado local
    this.gameData = gameData;
    this.inGamePhase = true;
    this.inDraftPhase = false;
    this.showMatchFound = false;
    this.isInQueue = false;
    this.lastMatchId = response.matchId;

    console.log('✅ [App] Game in progress processado via polling:', {
      matchId: response.matchId,
      team1Count: gameData.team1.length,
      team2Count: gameData.team2.length
    });

    this.addNotification('success', 'Jogo Iniciado!', 'A partida começou.');
  }

  // ✅ NOVO: Processar status 'none' detectado via polling
  private async handleNoStatusFromPolling(): Promise<void> {
    console.log('🔄 [App] Status "none" detectado via polling');

    // 🛡️ PROTEÇÃO: Não limpar estados se for uma partida restaurada
    if (this.isRestoredMatch && (this.inDraftPhase || this.inGamePhase)) {
      console.log('🛡️ [App] Partida restaurada detectada - ignorando status "none" do polling');
      console.log('🛡️ [App] Estado preservado:', {
        inDraftPhase: this.inDraftPhase,
        inGamePhase: this.inGamePhase,
        isRestoredMatch: this.isRestoredMatch
      });
      return;
    }

    if (this.hasActiveStates()) {
      console.log('🔄 [App] Estados ativos detectados, verificando se devem ser limpos...');

      const shouldContinueCleanup = await this.waitAndVerifyStatusIfNeeded();
      if (!shouldContinueCleanup) {
        return;
      }

      this.clearActiveStates();
      this.ensureInQueue();
    }
  }

  // ✅ NOVO: Verificar se há estados ativos que precisam ser limpos
  private hasActiveStates(): boolean {
    return this.showMatchFound || this.inDraftPhase || this.inGamePhase;
  }

  // ✅ NOVO: Aguardar e verificar status se necessário
  private async waitAndVerifyStatusIfNeeded(): Promise<boolean> {
    if (!this.inDraftPhase) {
      return true; // Não precisa aguardar
    }

    console.log('🔄 [App] Aguardando 5 segundos antes de limpar estado do draft...');
    await new Promise(resolve => setTimeout(resolve, 5000));

    return await this.verifyCurrentStatus();
  }

  // ✅ NOVO: Verificar status atual do jogador
  private async verifyCurrentStatus(): Promise<boolean> {
    try {
      const currentPlayer = this.currentPlayer?.displayName || this.currentPlayer?.summonerName;
      if (!currentPlayer) {
        return true; // Continuar limpeza se não há jogador
      }

      const response = await firstValueFrom(this.apiService.checkSyncStatus(currentPlayer));
      if (response && response.status !== 'none') {
        console.log('🔄 [App] Status recuperado durante espera, não limpando estado');
        return false; // Não continuar limpeza
      }
    } catch (error) {
      console.log('🔄 [App] Erro ao verificar status durante espera:', error);
    }

    return true; // Continuar limpeza
  }

  // ✅ NOVO: Limpar todos os estados ativos
  private clearActiveStates(): void {
    console.log('🔄 [App] === LIMPANDO ESTADOS ATIVOS ===');
    console.log('🔄 [App] Estado antes da limpeza:', {
      showMatchFound: this.showMatchFound,
      inDraftPhase: this.inDraftPhase,
      inGamePhase: this.inGamePhase,
      matchFoundData: !!this.matchFoundData,
      draftData: !!this.draftData,
      gameData: !!this.gameData,
      lastMatchId: this.lastMatchId,
      isRestoredMatch: this.isRestoredMatch
    });

    this.showMatchFound = false;
    this.inDraftPhase = false;
    this.inGamePhase = false;
    this.matchFoundData = null;
    this.draftData = null;
    this.gameData = null;
    this.lastMatchId = null;
    this.isRestoredMatch = false; // ✅ RESETAR FLAG

    console.log('🔄 [App] Estados limpos com sucesso');
  }

  /**
   * ✅ NOVO: Handler para cancelar draft
   * Chama endpoint backend para cancelar partida, deletar canais e mover jogadores
   */
  async exitDraft(): Promise<void> {
    console.log('❌ [App] exitDraft() chamado - cancelando draft');

    const matchId = this.draftData?.matchId || this.matchFoundData?.matchId;

    if (!matchId) {
      console.warn('⚠️ [App] Nenhum matchId disponível para cancelar');
      this.clearActiveStates();
      this.cdr.detectChanges();
      return;
    }

    try {
      console.log(`🔄 [App] Cancelando partida ${matchId}...`);

      const response: any = await firstValueFrom(
        this.http.delete(`${this.apiService.getBaseUrl()}/match/${matchId}/cancel`)
      );

      if (response.success) {
        console.log('✅ [App] Partida cancelada com sucesso:', response.message);
        this.addNotification('success', 'Draft Cancelado', 'A partida foi cancelada e os canais foram deletados');
      } else {
        console.error('❌ [App] Erro ao cancelar partida:', response.error);
        this.addNotification('error', 'Erro ao Cancelar', response.error || 'Erro desconhecido');
      }
    } catch (error: any) {
      console.error('❌ [App] Erro ao cancelar draft:', error);
      this.addNotification('error', 'Erro ao Cancelar', error.message || 'Erro ao comunicar com servidor');
    } finally {
      // Limpar estados independentemente do resultado
      this.clearActiveStates();
      this.cdr.detectChanges();
    }
  }

  // ✅ NOVO: Handler para cancelamento de jogo
  onGameCancel(): void {
    console.log('❌ [App] onGameCancel() chamado - usuário cancelou o jogo');
    this.clearActiveStates();
    this.cdr.detectChanges();
  }

  // ✅ NOVO: Handler para conclusão de jogo
  onGameComplete(result: any): void {
    console.log('✅ [App] onGameComplete() chamado:', result);
    this.clearActiveStates();
    this.addNotification('success', 'Jogo Concluído!', 'A partida foi finalizada com sucesso');
    this.cdr.detectChanges();
  }

  // ✅ NOVO: Garantir que está na fila
  private ensureInQueue(): void {
    if (!this.isInQueue) {
      this.isInQueue = true;
      console.log('🔄 [App] Voltando para fila');
    }
  }

  // Stub seguro: carregar configurações do backend (opcional)
  private loadConfigFromDatabase(): void {
    try {
      this.apiService.getConfigSettings().subscribe({
        next: (cfg: any) => {
          // Atualizar partes relevantes das configurações locais
          if (cfg) {
            this.settingsForm.riotApiKey = cfg.riotApiKey || this.settingsForm.riotApiKey;
            this.settingsForm.discordChannel = cfg.discordChannel || this.settingsForm.discordChannel;
          }
        },
        error: () => {
          // Ignorar em ambientes onde endpoint não existe
        }
      });
    } catch {
      // ignore
    }
  }

  // =============================================================================
  // QUEUE REFRESH METHODS
  // =============================================================================

  // Controle de chamadas para evitar spam
  private refreshInProgress = false;
  private lastRefreshTime = 0;
  private readonly MIN_REFRESH_INTERVAL = 3000; // 3 segundos mínimo entre refreshes
  private periodicUpdateInterval?: number;

  onRefreshData(): void {
    console.log('🔄 [App] onRefreshData chamado - atualizando dados da fila e Discord');

    // ✅ NOVO: Atualizar status da fila (MySQL)
    this.refreshQueueStatus();

    // ✅ NOVO: Atualizar status do Discord (canal e usuários)
    this.refreshDiscordStatus();
  }

  // ✅ NOVO: Método específico para atualizar status do Discord
  private refreshDiscordStatus(): void {
    console.log('🤖 [App] Atualizando status do Discord...');

    // Solicitar status do Discord bot
    this.discordService.requestDiscordStatus();

    // Verificar conexão do Discord
    this.discordService.checkConnection();

    console.log('✅ [App] Atualização do Discord solicitada');
  }

  onAutoRefreshToggle(enabled: boolean): void {
    console.log(`🔄 [App] Auto-refresh ${enabled ? 'habilitado' : 'desabilitado'}`);

    if (enabled) {
      // Iniciar atualização automática a cada 5 segundos
      this.startPeriodicUpdates();
    } else {
      // Parar atualizações automáticas
      this.stopPeriodicUpdates();
    }
  }

  private startPeriodicUpdates(): void {
    console.log('🔄 [App] Iniciando atualizações periódicas...');

    // Limpar intervalo anterior se existir
    this.stopPeriodicUpdates();

    // Atualizar a cada 5 segundos com controle de spam
    this.periodicUpdateInterval = setInterval(() => {
      this.refreshQueueStatusWithDebounce();
    }, 5000);
  }

  private stopPeriodicUpdates(): void {
    console.log('🔄 [App] Parando atualizações periódicas...');
    if (this.periodicUpdateInterval) {
      clearInterval(this.periodicUpdateInterval);
      this.periodicUpdateInterval = undefined;
    }
  }

  private refreshQueueStatusWithDebounce(): void {
    const now = Date.now();

    // Verificar se já está em progresso ou se passou tempo suficiente
    if (this.refreshInProgress) {
      console.log('🔄 [App] Refresh já em progresso, ignorando...');
      return;
    }

    if (now - this.lastRefreshTime < this.MIN_REFRESH_INTERVAL) {
      console.log('🔄 [App] Refresh muito recente, ignorando...');
      return;
    }

    this.lastRefreshTime = now;
    this.refreshQueueStatus();
  }
}

// Handler global para erros e rejeições não tratadas
window.onerror = function (message, source, lineno, colno, error) {
  logApp('❌ [GLOBAL ERROR]', message, source, lineno, colno, error);
};
window.onunhandledrejection = function (event) {
  logApp('❌ [UNHANDLED PROMISE REJECTION]', event.reason);
};
