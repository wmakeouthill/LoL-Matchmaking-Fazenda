import { Component, OnInit, OnDestroy, ChangeDetectorRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, takeUntil, filter, take, firstValueFrom } from 'rxjs';
import { Router } from '@angular/router';

import { DashboardComponent } from './components/dashboard/dashboard';
import { QueueComponent } from './components/queue/queue';
import { MatchHistoryComponent } from './components/match-history/match-history';
import { LeaderboardComponent } from './components/leaderboard/leaderboard';
import { MatchFoundComponent, MatchFoundData } from './components/match-found/match-found';
import { DraftPickBanComponent } from './components/draft/draft-pick-ban';
import { GameInProgressComponent } from './components/game-in-progress/game-in-progress';
import { ApiService } from './services/api';
import { QueueStateService } from './services/queue-state';
import { DiscordIntegrationService } from './services/discord-integration.service';
import { BotService } from './services/bot.service';
import { Player, QueueStatus, LCUStatus, QueuePreferences } from './interfaces';
import type { Notification } from './interfaces';

// ✅ Função de log para gravar no app.log separado do draft
function logApp(...args: any[]) {
  const fs = (window as any).electronAPI?.fs;
  const path = (window as any).electronAPI?.path;
  const process = (window as any).electronAPI?.process;

  // ✅ CORREÇÃO: Usar caminho relativo se electronAPI não estiver disponível
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
    // ✅ FALLBACK: Se não conseguir escrever no arquivo, pelo menos log no console
    console.log('[App] ElectronAPI não disponível, log apenas no console');
  }

  console.log('[App]', ...args);
}

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
    GameInProgressComponent
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

  // ✅ MANTIDO: Dados do jogador
  currentPlayer: Player | null = null;

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
  inGamePhase = false;
  gameData: any = null;
  gameResult: any = null;

  // ✅ MANTIDO: Interface (sem lógica)
  notifications: Notification[] = [];
  settingsForm = {
    summonerName: '',
    region: 'br1',
    riotApiKey: '',
    discordBotToken: '',
    discordChannel: ''
  };
  discordStatus = {
    isConnected: false,
    botUsername: '',
    queueSize: 0,
    activeMatches: 0,
    inChannel: false
  };

  private readonly destroy$ = new Subject<void>();
  private lastIgnoreLogTime = 0;
  private lastTimerUpdate = 0; // ✅ NOVO: Throttle para timer updates
  private lastMatchId: number | null = null; // ✅ NOVO: Rastrear última partida processada
  private lastMessageTimestamp = 0; // ✅ NOVO: Throttle para mensagens backend

  // ✅ NOVO: Controle de auto-refresh para sincronizar com o queue component
  private autoRefreshEnabled = false;

  // ✅ NOVO: Controle para priorizar backend sobre QueueStateService
  private hasRecentBackendQueueStatus = false;

  private readonly lcuCheckInterval: any;
  private readonly LCU_CHECK_INTERVAL = 5000; // Intervalo de verificação do status do LCU

  // Update the notifications array and addNotification method
  private readonly maxNotifications = 2; // Limit to 2 visible notifications
  private readonly notificationQueue: Notification[] = []; // Queue for pending notifications

  // ✅ NOVO: Sistema de polling inteligente para sincronização
  private pollingInterval: any = null;
  private lastPollingStatus: string | null = null;
  private readonly POLLING_INTERVAL_MS = 2000; // 2 segundos - aguardar backend processar
  private lastCacheInvalidation = 0; // ✅ NOVO: Controle de invalidação de cache
  private readonly CACHE_INVALIDATION_COOLDOWN = 3000; // ✅ NOVO: 3 segundos entre invalidações
  private lastBackendAction = 0; // ✅ NOVO: Controle de ações do backend
  private readonly BACKEND_ACTION_COOLDOWN = 1500; // ✅ NOVO: 1.5 segundos após ação do backend

  @ViewChild(DraftPickBanComponent) draftComponentRef: DraftPickBanComponent | undefined;

  constructor(
    private readonly apiService: ApiService,
    private readonly queueStateService: QueueStateService,
    private readonly discordService: DiscordIntegrationService,
    private readonly botService: BotService,
    private readonly router: Router,
    private readonly cdr: ChangeDetectorRef
  ) {
    console.log(`[App] Constructor`);

    // Inicialização da verificação de status do LCU
    this.lcuCheckInterval = setInterval(() => this.startLCUStatusCheck(), this.LCU_CHECK_INTERVAL);

    this.isElectron = !!(window as any).electronAPI;
  }

  ngOnInit(): void {
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

      // 4. Carregar dados do jogador
      console.log('🔄 [App] Passo 4: Carregando dados do jogador...');
      await this.loadPlayerDataWithRetry();

      // 5. Identificar jogador no WebSocket (agora que temos os dados)
      console.log('🔄 [App] Passo 5: Identificando jogador...');
      await this.identifyPlayerSafely();

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

    // ✅ NOVO: Aguardar explicitamente que WebSocket esteja pronto
    await new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error('Timeout aguardando WebSocket conectar'));
      }, 15000); // 15 segundos de timeout

      this.apiService.onWebSocketReady().pipe(
        filter(isReady => isReady),
        take(1)
      ).subscribe({
        next: () => {
          clearTimeout(timeout);
          console.log('✅ [App] WebSocket está pronto para comunicação');
          resolve();
        },
        error: (error) => {
          clearTimeout(timeout);
          reject(error instanceof Error ? error : new Error(String(error)));
        }
      });
    });
  }

  // ✅ NOVO: Carregar dados do jogador com retry
  private async loadPlayerDataWithRetry(): Promise<void> {
    const maxAttempts = 3;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        console.log(`🔄 [App] Tentativa ${attempt}/${maxAttempts} de carregar dados do jogador...`);

        await new Promise<void>((resolve, reject) => {
          this.apiService.getPlayerFromLCU().subscribe({
            next: (player: Player) => {
              console.log('✅ [App] Dados do jogador carregados do LCU:', player);
              this.currentPlayer = player;
              this.savePlayerData(player);
              this.updateSettingsForm();
              resolve();
            },
            error: (error) => {
              console.warn(`⚠️ [App] Tentativa ${attempt} falhou:`, error);
              if (attempt === maxAttempts) {
                // Última tentativa - tentar localStorage
                this.tryLoadFromLocalStorage();
                if (this.currentPlayer) {
                  resolve();
                } else {
                  reject(new Error('Não foi possível carregar dados do jogador'));
                }
              } else {
                reject(error instanceof Error ? error : new Error(String(error)));
              }
            }
          });
        });

        // Se chegou até aqui, dados foram carregados com sucesso
        console.log('✅ [App] Dados do jogador carregados com sucesso');
        return;

      } catch (error) {
        console.warn(`⚠️ [App] Tentativa ${attempt} de carregar dados falhou:`, error);

        if (attempt < maxAttempts) {
          // Aguardar antes da próxima tentativa
          await new Promise(resolve => setTimeout(resolve, 2000));
        }
      }
    }

    // Se todas as tentativas falharam
    console.warn('⚠️ [App] Todas as tentativas de carregar dados falharam, usando dados padrão se disponíveis');
  }

  // ✅ MELHORADO: Identificar jogador de forma segura
  private async identifyPlayerSafely(): Promise<void> {
    if (!this.currentPlayer) {
      console.warn('⚠️ [App] Nenhum jogador disponível para identificação');
      return;
    }

    const playerIdentifier = this.buildPlayerIdentifier(this.currentPlayer);
    if (!playerIdentifier) {
      console.error('❌ [App] Não foi possível construir identificador único para identificação');
      return;
    }

    console.log('🆔 [App] Iniciando identificação com identificador único:', playerIdentifier);

    const maxAttempts = 3;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        console.log(`🆔 [App] Tentativa ${attempt}/${maxAttempts} de identificação...`);

        await new Promise<void>((resolve, reject) => {
          this.apiService.identifyPlayer(this.currentPlayer).subscribe({
            next: (response: any) => {
              if (response.success) {
                console.log('✅ [App] Jogador identificado com sucesso no backend:', playerIdentifier);
                resolve();
              } else {
                reject(new Error(response.error || 'Erro desconhecido na identificação'));
              }
            },
            error: (error: any) => {
              reject(error instanceof Error ? error : new Error(String(error)));
            }
          });
        });

        // Se chegou até aqui, identificação foi bem-sucedida
        console.log('✅ [App] Identificação do jogador completa:', playerIdentifier);
        return;

      } catch (error) {
        console.error(`❌ [App] Tentativa ${attempt} de identificação falhou:`, error);

        if (attempt < maxAttempts) {
          // Aguardar antes da próxima tentativa
          await new Promise(resolve => setTimeout(resolve, 2000));
        }
      }
    }

    console.warn('⚠️ [App] Todas as tentativas de identificação falharam, mas continuando...');
  }

  // ✅ NOVO: Iniciar atualizações periódicas
  private startPeriodicUpdates(): void {
    // Atualização periódica da fila a cada 10 segundos
    setInterval(() => {
      if (this.currentPlayer?.displayName) {
        console.log('🔄 [App] Atualização periódica do status da fila');
        this.refreshQueueStatus();
      }
    }, 10000);
  }

  // ✅ NOVO: Lidar com erros de inicialização
  private handleInitializationError(error: any): void {
    console.error('❌ [App] Erro crítico na inicialização:', error);

    // Marcar como conectado mesmo com erros para permitir funcionalidade básica
    this.isConnected = true;

    // Notificar usuário sobre problemas
    this.addNotification('warning', 'Inicialização Parcial',
      'Algumas funcionalidades podem não estar disponíveis. Verifique a conexão com o backend.');

    // Tentar reconectar após um tempo
    setTimeout(() => {
      console.log('🔄 [App] Tentando reinicializar após erro...');
      this.initializeAppSequence();
    }, 30000); // Tentar novamente em 30 segundos
  }

  // ✅ NOVO: Salvar dados do jogador
  private savePlayerData(player: Player): void {
    console.log('💾 [App] === SALVANDO DADOS DO JOGADOR ===');
    console.log('💾 [App] Dados originais do player:', {
      id: player.id,
      summonerName: player.summonerName,
      gameName: player.gameName,
      tagLine: player.tagLine,
      displayName: player.displayName
    });

    // ✅ PADRONIZAÇÃO COMPLETA: Sempre usar gameName#tagLine como identificador único
    const playerIdentifier = this.buildPlayerIdentifier(player);

    if (playerIdentifier) {
      player.displayName = playerIdentifier;
      player.summonerName = playerIdentifier;
      console.log('✅ [App] Identificador único padronizado:', playerIdentifier);
    } else {
      console.warn('⚠️ [App] Não foi possível construir identificador único:', {
        gameName: player.gameName,
        tagLine: player.tagLine,
        summonerName: player.summonerName,
        displayName: player.displayName
      });

      // Fallback: usar dados disponíveis
      if (player.displayName) {
        player.summonerName = player.displayName;
      } else if (player.gameName && player.tagLine) {
        player.displayName = `${player.gameName}#${player.tagLine}`;
        player.summonerName = player.displayName;
      }
    }

    // Adicionar propriedade customLp se não existir
    if (!player.customLp) {
      player.customLp = player.currentMMR || 1200;
    }

    // Salvar no localStorage para backup
    localStorage.setItem('currentPlayer', JSON.stringify(player));

    console.log('✅ [App] Jogador salvo com identificador único:', player.displayName);
    console.log('💾 [App] Dados finais do player:', {
      id: player.id,
      summonerName: player.summonerName,
      gameName: player.gameName,
      tagLine: player.tagLine,
      displayName: player.displayName
    });
    console.log('💾 [App] === FIM DO SALVAMENTO ===');
  }

  // ✅ NOVO: Construir identificador único padronizado
  private buildPlayerIdentifier(player: Player): string | null {
    // ✅ PRIORIDADE 1: gameName#tagLine (padrão)
    if (player.gameName && player.tagLine) {
      return `${player.gameName}#${player.tagLine}`;
    }

    // ✅ PRIORIDADE 2: displayName (se já está no formato correto)
    if (player.displayName?.includes('#')) {
      return player.displayName;
    }

    // ✅ PRIORIDADE 3: summonerName (fallback)
    if (player.summonerName) {
      return player.summonerName;
    }

    return null;
  }

  // ✅ NOVO: Processar mensagens do backend
  // ✅ OTIMIZADO: Método simplificado para reduzir complexidade cognitiva (SonarQube)
  private handleBackendMessage(message: any): void {
    // Validação básica consolidada
    if (!this.isValidBackendMessage(message)) return;

    // Throttle otimizado
    if (!this.shouldProcessBackendMessage(message)) return;

    this.lastMessageTimestamp = Date.now();
    console.log(`📡 [App] ${message.type}:`, message.data);

    // Roteamento simplificado por tipo
    this.routeBackendMessage(message);
  }

  // ✅ NOVO: Validação consolidada
  private isValidBackendMessage(message: any): boolean {
    return message?.type;
  }

  // ✅ NOVO: Verificação de throttle otimizada
  private shouldProcessBackendMessage(message: any): boolean {
    const criticalMessages = ['draft_action', 'draftUpdate', 'draft_data_sync', 'draft_timer_update', 'draft_player_change', 'draft_pick_completed'];
    const shouldSkipThrottle = criticalMessages.includes(message.type);

    if (!shouldSkipThrottle && Date.now() - this.lastMessageTimestamp < 100) {
      console.log('📡 [App] ⚠️ Throttle aplicado');
      return false;
    }
    return true;
  }

  // ✅ NOVO: Roteamento simplificado
  private routeBackendMessage(message: any): void {
    const { type, data } = message;

    // Draft messages
    if (this.isDraftMessageType(type)) {
      this.handleDraftMessageByType(type, data);
      return;
    }

    // Match messages
    if (this.isMatchMessageType(type)) {
      this.handleMatchMessageByType(type, data);
      return;
    }

    // System messages
    this.handleSystemMessageByType(type, data);
  }

  // ✅ NOVO: Verificadores de tipo otimizados
  private isDraftMessageType(type: string): boolean {
    return type.includes('draft') || type === 'draftUpdate';
  }

  private isMatchMessageType(type: string): boolean {
    return type.includes('match') || type === 'game_started';
  }

  // ✅ NOVO: Handlers por categoria
  private handleDraftMessageByType(type: string, data: any): void {
    if (!data) {
      console.error(`❌ [App] ${type}: data é null/undefined`);
      return;
    }

    const draftHandlers: { [key: string]: () => void } = {
      'draft_timer_update': () => {
        logApp('⏰ [App] DRAFT_TIMER_UPDATE', { matchId: data.matchId, timeRemaining: data.timeRemaining, timestamp: new Date().toISOString() });
        this.handleDraftTimerUpdate(data);
      },
      'draft_player_change': () => {
        logApp('🔄 [App] DRAFT_PLAYER_CHANGE', { matchId: data.matchId, currentAction: data.currentAction, timestamp: new Date().toISOString() });
        this.handleDraftPlayerChange(data);
      },
      'draftUpdate': () => {
        logApp('🔄 [App] DRAFT_UPDATE', { matchId: data.matchId, currentAction: data.currentAction, timestamp: new Date().toISOString() });
        this.handleDraftAction(data);
      },
      'draft_pick_completed': () => {
        logApp('🎯 [App] DRAFT_PICK_COMPLETED', { matchId: data.matchId, playerId: data.playerId, timestamp: new Date().toISOString() });
        this.handleDraftPickCompleted(data);
      },
      'draft_action': () => this.handleDraftAction(data),
      'draft_data_sync': () => this.handleDraftDataSync(data),
      'draft_started': () => this.handleDraftStarted(data),
      'draft_cancelled': () => this.handleDraftCancelled(data)
    };

    const handler = draftHandlers[type];
    if (handler) {
      handler();
    }
  }

  private handleMatchMessageByType(type: string, data: any): void {
    const matchHandlers: { [key: string]: () => void } = {
      'match_found': () => this.handleMatchFound(data),
      // Chamada assíncrona encapsulada para manter assinatura () => void
      'match_found_fallback': () => { void this.handleMatchFoundFallback(data); },
      'match_acceptance_progress': () => this.handleAcceptanceProgress(data),
      'match_fully_accepted': () => this.handleMatchFullyAccepted(data),
      'match_timer_update': () => this.handleMatchTimerUpdate(data),
      'game_started': () => this.handleGameStarted(data)
    };

    const handler = matchHandlers[type];
    if (handler) {
      handler();
    }
  }

  private handleSystemMessageByType(type: string, data: any): void {
    const systemHandlers: { [key: string]: () => void } = {
      'queue_update': () => this.handleQueueUpdate(data),
      'backend_connection_success': () => {
        if (this.currentPlayer) {
          this.identifyPlayerSafely();
        }
      }
    };

    const handler = systemHandlers[type];
    if (handler) {
      handler();
    } else {
      console.log('📡 [App] Mensagem não reconhecida:', type);
    }
  }

  // ✅ MANTIDO: Compatibilidade para métodos legacy
  // ✅ MANTIDO: Compatibilidade para métodos legacy
  private identifyCurrentPlayerOnConnect(): void {
    console.log('🔄 [App] Método legacy - redirecionando para identifyPlayerSafely()');
    this.identifyPlayerSafely();
  }

  // ✅ OTIMIZAÇÃO: Métodos auxiliares para reduzir complexidade cognitiva
  private isDraftMessage(type: string): boolean {
    return ['draft_timer_update', 'draft_player_change', 'draftUpdate', 'draft_pick_completed', 'draft_action', 'draft_data_sync', 'draft_started', 'draft_cancelled'].includes(type);
  }

  private isMatchMessage(type: string): boolean {
    return ['match_found', 'match_found_fallback', 'match_acceptance_progress', 'match_fully_accepted', 'match_timer_update'].includes(type);
  }

  private processDraftMessage(type: string, data: any): void {
    if (!data) {
      console.error(`❌ [App] ${type}: data é null/undefined`);
      return;
    }

    switch (type) {
      case 'draft_timer_update':
        logApp('⏰ [App] DRAFT_TIMER_UPDATE', { matchId: data.matchId, timeRemaining: data.timeRemaining, timestamp: new Date().toISOString() });
        this.handleDraftTimerUpdate(data);
        break;
      case 'draft_player_change':
        logApp('🔄 [App] DRAFT_PLAYER_CHANGE', { matchId: data.matchId, currentAction: data.currentAction, timestamp: new Date().toISOString() });
        this.handleDraftPlayerChange(data);
        break;
      case 'draftUpdate':
        logApp('🔄 [App] DRAFT_UPDATE', { matchId: data.matchId, currentAction: data.currentAction, timestamp: new Date().toISOString() });
        this.handleDraftAction(data);
        break;
      case 'draft_pick_completed':
        logApp('🎯 [App] DRAFT_PICK_COMPLETED', { matchId: data.matchId, playerId: data.playerId, timestamp: new Date().toISOString() });
        this.handleDraftPickCompleted(data);
        break;
      case 'draft_action':
        this.handleDraftAction(data);
        break;
      case 'draft_data_sync':
        this.handleDraftDataSync(data);
        break;
      case 'draft_started':
        this.handleDraftStarted(data);
        break;
      case 'draft_cancelled':
        this.handleDraftCancelled(data);
        break;
    }
  }

  private processMatchMessage(type: string, data: any): void {
    switch (type) {
      case 'match_found':
        this.handleMatchFound(data);
        break;
      case 'match_acceptance_progress':
        this.handleAcceptanceProgress(data);
        break;
      case 'match_fully_accepted':
        this.handleMatchFullyAccepted(data);
        break;
      case 'match_timer_update':
        this.handleMatchTimerUpdate(data);
        break;
    }
  }

  private processSystemMessage(type: string, data: any): void {
    switch (type) {
      case 'queue_update':
        this.handleQueueUpdate(data);
        break;
      case 'backend_connection_success':
        if (this.currentPlayer) {
          this.identifyPlayerSafely();
        }
        break;
      default:
        console.log('📡 [App] Mensagem não reconhecida:', type);
    }
  }

  // ✅ NOVO: Configurar listener do componente queue
  private setupQueueComponentListener(): void {
    document.addEventListener('matchFound', (event: any) => {
      console.log('🎮 [App] Match found do componente queue:', event.detail);
    });
  }

  // ✅ SIMPLIFICADO: Handlers apenas atualizam interface
  private handleMatchFound(data: any): void {
    console.log('🎮 [App] === MATCH FOUND RECEBIDO ===');
    console.log('🎮 [App] MatchId recebido:', data?.matchId);
    console.log('🎮 [App] Última partida processada:', this.lastMatchId);

    // ✅ Verificar se deve processar esta partida
    if (!this.shouldProcessMatch(data)) {
      return;
    }

    // ✅ Processar dados da partida
    const matchFoundData = this.processMatchData(data);
    if (!matchFoundData) {
      return;
    }

    // ✅ Finalizar configuração da partida
    this.finalizeMatchSetup(matchFoundData);
  }

  // ✅ NOVO: Handler para fallback de match_found (broadcast geral)
  private async handleMatchFoundFallback(data: any): Promise<void> {
    console.log('📢 [App] match_found_fallback recebido:', data);

    // Evitar duplicidade se já estamos exibindo a mesma partida
    if (this.showMatchFound && this.matchFoundData?.matchId === data?.matchId) {
      console.log('📢 [App] Fallback ignorado: partida já exibida');
      return;
    }

    // Força uma verificação de status para obter os detalhes corretos do backend/MySQL
    try {
      await this.checkSyncStatus();
      // Se depois do polling não abriu, exibir um aviso ao usuário
      if (!this.showMatchFound) {
        this.addNotification('info', 'Partida encontrada', 'Sincronizando detalhes da partida...');
      }
    } catch (err) {
      console.warn('⚠️ [App] Erro ao processar fallback do match_found:', err);
    }
  }

  // ✅ NOVO: Verificar se deve processar a partida
  private shouldProcessMatch(data: any): boolean {
    // Verificar se já processamos esta partida
    if (this.lastMatchId === data?.matchId) {
      console.log('🎮 [App] ❌ PARTIDA JÁ PROCESSADA - ignorando duplicata');
      return false;
    }

    // Verificar se já temos esta partida ativa
    if (this.matchFoundData && this.matchFoundData.matchId === data.matchId) {
      console.log('🎮 [App] ❌ PARTIDA JÁ ESTÁ ATIVA - ignorando duplicata');
      return false;
    }

    // Verificar se já estamos mostrando uma partida
    if (this.showMatchFound && this.matchFoundData) {
      console.log('🎮 [App] ❌ JÁ EXISTE UMA PARTIDA ATIVA - ignorando nova');
      return false;
    }

    console.log('🎮 [App] ✅ PROCESSANDO PARTIDA RECEBIDA DO BACKEND:', data.matchId);
    return true;
  }

  // ✅ NOVO: Processar dados da partida
  private processMatchData(data: any): MatchFoundData | null {
    // Marcar esta partida como processada
    this.lastMatchId = data.matchId;

    // Criar estrutura base da partida
    const matchFoundData: MatchFoundData = {
      matchId: data.matchId,
      playerSide: 'blue',
      teammates: [],
      enemies: [],
      averageMMR: { yourTeam: 1200, enemyTeam: 1200 },
      estimatedGameDuration: 25,
      phase: 'accept',
      acceptTimeout: data.acceptTimeout || data.acceptanceTimer || 30,
      acceptanceTimer: data.acceptanceTimer || data.acceptTimeout || 30,
      acceptanceDeadline: data.acceptanceDeadline,
      teamStats: data.teamStats,
      balancingInfo: data.balancingInfo
    };

    // Processar dados específicos do tipo de partida
    if (!this.populateMatchTeams(data, matchFoundData)) {
      return null;
    }

    return matchFoundData;
  }

  // ✅ NOVO: Popular times da partida
  private populateMatchTeams(data: any, matchFoundData: MatchFoundData): boolean {
    if (data.teammates && data.enemies) {
      return this.processStructuredMatchData(data, matchFoundData);
    } else if (data.team1 && data.team2) {
      return this.processBasicMatchData(data, matchFoundData);
    } else {
      console.error('🎮 [App] ❌ ERRO: Formato de dados não reconhecido');
      console.log('🎮 [App] Dados recebidos:', data);
      return false;
    }
  }

  // ✅ NOVO: Processar dados estruturados do MatchmakingService
  private processStructuredMatchData(data: any, matchFoundData: MatchFoundData): boolean {
    console.log('🎮 [App] Usando dados estruturados do MatchmakingService');

    const currentPlayerIdentifiers = this.getCurrentPlayerIdentifiers();
    const isInTeammates = this.isPlayerInTeam(currentPlayerIdentifiers, data.teammates);

    matchFoundData.playerSide = isInTeammates ? 'blue' : 'red';
    matchFoundData.teammates = this.convertPlayersToPlayerInfo(data.teammates);
    matchFoundData.enemies = this.convertPlayersToPlayerInfo(data.enemies);

    // Usar teamStats do backend se disponível
    if (data.teamStats) {
      matchFoundData.averageMMR = {
        yourTeam: isInTeammates ? data.teamStats.team1.averageMMR : data.teamStats.team2.averageMMR,
        enemyTeam: isInTeammates ? data.teamStats.team2.averageMMR : data.teamStats.team1.averageMMR
      };
    }

    return true;
  }

  // ✅ NOVO: Processar dados básicos team1/team2
  private processBasicMatchData(data: any, matchFoundData: MatchFoundData): boolean {
    console.log('🎮 [App] Usando dados básicos do MatchFoundService');

    const currentPlayerIdentifiers = this.getCurrentPlayerIdentifiers();
    const isInTeam1 = data.team1.some((name: string) =>
      currentPlayerIdentifiers.some(id => this.namesMatch(id, name))
    );

    matchFoundData.playerSide = isInTeam1 ? 'blue' : 'red';
    matchFoundData.teammates = this.convertBasicPlayersToPlayerInfo(isInTeam1 ? data.team1 : data.team2);
    matchFoundData.enemies = this.convertBasicPlayersToPlayerInfo(isInTeam1 ? data.team2 : data.team1);

    return true;
  }

  // ✅ NOVO: Finalizar configuração da partida
  private finalizeMatchSetup(matchFoundData: MatchFoundData): void {
    this.matchFoundData = matchFoundData;
    this.isInQueue = false;

    console.log('🎯 [App] === EXIBINDO MATCH FOUND ===');

    // Modal só deve ser exibido para jogadores humanos
    if (this.isCurrentPlayerBot()) {
      console.log('🎯 [App] Jogador atual é bot - não exibindo modal');
      return;
    }

    this.showMatchFound = true;
    this.addNotification('success', 'Partida Encontrada!', 'Você tem 30 segundos para aceitar.');
    this.playMatchFoundSound();

    console.log('🎯 [App] Estado final:', {
      showMatchFound: this.showMatchFound,
      matchFoundData: !!this.matchFoundData,
      isInQueue: this.isInQueue
    });
  }

  // ✅ NOVO: Reproduzir som de notificação
  private playMatchFoundSound(): void {
    try {
      const audio = new Audio('assets/sounds/match-found.mp3');
      audio.play().catch(() => {
        console.warn('⚠️ [App] Não foi possível reproduzir som de notificação');
      });
    } catch (error) {
      console.warn('⚠️ [App] Erro ao criar elemento de áudio:', error);
    }
  }

  // ✅ NOVO: Comparar se dois nomes coincidem (com diferentes formatos)
  private namesMatch(name1: string, name2: string): boolean {
    if (name1 === name2) return true;

    // Comparação por gameName (ignorando tag)
    if (name1.includes('#') && name2.includes('#')) {
      const gameName1 = name1.split('#')[0];
      const gameName2 = name2.split('#')[0];
      return gameName1 === gameName2;
    }

    if (name1.includes('#')) {
      const gameName1 = name1.split('#')[0];
      return gameName1 === name2;
    }

    if (name2.includes('#')) {
      const gameName2 = name2.split('#')[0];
      return name1 === gameName2;
    }

    return false;
  }

  // ✅ NOVO: Converter jogadores básicos (apenas nomes) para PlayerInfo
  private convertBasicPlayersToPlayerInfo(playerNames: string[]): any[] {
    return playerNames.map((name: string, index: number) => ({
      id: index,
      summonerName: name,
      mmr: 1200, // MMR padrão
      primaryLane: 'fill',
      secondaryLane: 'fill',
      assignedLane: 'FILL',
      teamIndex: index,
      isAutofill: false,
      riotIdGameName: name.includes('#') ? name.split('#')[0] : name,
      riotIdTagline: name.includes('#') ? name.split('#')[1] : undefined,
      profileIconId: 1
    }));
  }

  // ✅ NOVO: Obter identificadores do jogador atual
  private getCurrentPlayerIdentifiers(): string[] {
    if (!this.currentPlayer) return [];

    const identifiers = [];

    // Adicionar todas as possíveis variações do nome
    if (this.currentPlayer.displayName) {
      identifiers.push(this.currentPlayer.displayName);
    }
    if (this.currentPlayer.summonerName) {
      identifiers.push(this.currentPlayer.summonerName);
    }
    if (this.currentPlayer.gameName) {
      identifiers.push(this.currentPlayer.gameName);
      // Adicionar com tag se tiver
      if (this.currentPlayer.tagLine) {
        identifiers.push(`${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`);
      }
    }

    // Remover duplicatas
    return [...new Set(identifiers)];
  }

  // ✅ NOVO: Verificar se um jogador está em um time
  private isPlayerInTeam(playerIdentifiers: string[], team: any[]): boolean {
    if (!playerIdentifiers.length || !team.length) return false;

    console.log('🔍 [App] Verificando se jogador está no time:', {
      playerIdentifiers,
      teamPlayers: team.map(p => p.summonerName || p.name)
    });

    return team.some(player => {
      const playerName = player.summonerName || player.name || '';

      // Verificar se algum identificador do jogador atual coincide
      return playerIdentifiers.some((identifier: string) => {
        // Comparação exata
        if (identifier === playerName) {
          console.log(`✅ [App] Match exato encontrado: ${identifier} === ${playerName}`);
          return true;
        }

        // Comparação sem tag (gameName vs gameName#tagLine)
        if (identifier.includes('#') && playerName.includes('#')) {
          const identifierGameName = identifier.split('#')[0];
          const playerGameName = playerName.split('#')[0];
          if (identifierGameName === playerGameName) {
            console.log(`✅ [App] Match por gameName encontrado: ${identifierGameName} === ${playerGameName}`);
            return true;
          }
        }

        // Comparação de gameName com nome completo
        if (identifier.includes('#')) {
          const identifierGameName = identifier.split('#')[0];
          if (identifierGameName === playerName) {
            console.log(`✅ [App] Match por gameName vs nome completo: ${identifierGameName} === ${playerName}`);
            return true;
          }
        }

        if (playerName.includes('#')) {
          const playerGameName = playerName.split('#')[0];
          if (identifier === playerGameName) {
            console.log(`✅ [App] Match por nome vs gameName: ${identifier} === ${playerGameName}`);
            return true;
          }
        }

        return false;
      });
    });
  }

  // ✅ NOVO: Converter dados do backend para PlayerInfo
  private convertPlayersToPlayerInfo(players: any[]): any[] {
    console.log('🔄 [App] === CONVERTENDO PLAYERS PARA PLAYERINFO ===');
    console.log('🔄 [App] Players recebidos:', players);
    console.log('🔄 [App] Dados brutos dos players:', players.map(p => ({
      summonerName: p.summonerName,
      assignedLane: p.assignedLane,
      primaryLane: p.primaryLane,
      secondaryLane: p.secondaryLane,
      teamIndex: p.teamIndex,
      isAutofill: p.isAutofill,
      mmr: p.mmr
    })));

    const convertedPlayers = players.map((player: any, index: number) => {
      const playerInfo = {
        id: player.teamIndex || index, // ✅ USAR teamIndex do backend
        summonerName: player.summonerName,
        mmr: player.mmr || 1200,
        primaryLane: player.primaryLane || 'fill',
        secondaryLane: player.secondaryLane || 'fill',
        assignedLane: player.assignedLane || 'fill', // ✅ CORREÇÃO: Usar 'fill' em vez de 'FILL'
        teamIndex: player.teamIndex || index, // ✅ Índice correto do backend
        isAutofill: player.isAutofill || false,
        riotIdGameName: player.gameName,
        riotIdTagline: player.tagLine,
        profileIconId: player.profileIconId
      };

      console.log(`🔄 [App] Player ${index} convertido:`, {
        name: playerInfo.summonerName,
        lane: playerInfo.assignedLane,
        teamIndex: playerInfo.teamIndex,
        autofill: playerInfo.isAutofill,
        originalAssignedLane: player.assignedLane,
        primaryLane: playerInfo.primaryLane,
        secondaryLane: playerInfo.secondaryLane
      });

      return playerInfo;
    });

    console.log('🔄 [App] === RESULTADO DA CONVERSÃO ===');
    console.log('🔄 [App] Players convertidos:', convertedPlayers.map(p => ({
      name: p.summonerName,
      assignedLane: p.assignedLane,
      teamIndex: p.teamIndex,
      isAutofill: p.isAutofill
    })));

    return convertedPlayers;
  }

  private handleAcceptanceProgress(data: any): void {
    console.log('📊 [App] Progresso de aceitação:', data);
    // Atualizar UI de progresso se necessário
  }

  private handleMatchFullyAccepted(data: any): void {
    console.log('✅ [App] Partida totalmente aceita:', data);
    this.addNotification('success', 'Partida Aceita!', 'Todos os jogadores aceitaram. Preparando draft...');
  }

  private handleDraftStarted(data: any): void {
    console.log('🎯 [App] Iniciando draft:', data);
    logApp('🎯 [App] DRAFT_STARTED', {
      matchId: data?.matchId,
      timestamp: new Date().toISOString()
    });

    // ✅ NOVO: Limpar controle de partida
    this.lastMatchId = null;
    this.showMatchFound = false;
    this.matchFoundData = null;
    this.inDraftPhase = true;
    this.draftData = data;

    // ✅ NOVO: Iniciar polling agressivo para sincronização durante o draft
    this.startDraftPolling(data?.matchId);

    console.log('🎯 [App] Estado limpo para draft');
    this.addNotification('success', 'Draft Iniciado!', 'A fase de draft começou.');
  }

  // ✅ NOVO: Sistema de polling agressivo durante o draft
  private draftPollingInterval: any = null;
  private readonly DRAFT_POLLING_INTERVAL = 1000; // 1 segundo durante draft

  private startDraftPolling(matchId: number): void {
    if (this.draftPollingInterval) {
      clearInterval(this.draftPollingInterval);
    }

    console.log('🔄 [App] Iniciando polling agressivo para draft:', matchId);
    logApp('🔄 [App] DRAFT_POLLING_STARTED', {
      matchId,
      interval: this.DRAFT_POLLING_INTERVAL,
      timestamp: new Date().toISOString()
    });

    this.draftPollingInterval = setInterval(() => {
      if (this.inDraftPhase && this.draftData?.matchId === matchId && this.currentPlayer?.summonerName) {
        console.log('🔄 [App] Polling de sincronização do draft...');

        this.apiService.checkSyncStatus(this.currentPlayer.summonerName).subscribe({
          next: (response: any) => {
            if (response?.status === 'draft' && response?.matchId === matchId) {
              // Verificar se há mudanças nos dados
              const hasChanges = this.hasSignificantDraftChanges(response);
              if (hasChanges) {
                console.log('🔄 [App] Mudanças detectadas no polling - atualizando interface');
                logApp('🔄 [App] DRAFT_POLLING_UPDATE', {
                  matchId,
                  totalActions: response.totalActions,
                  currentAction: response.currentAction,
                  timestamp: new Date().toISOString()
                });
                this.handleDraftDataSync(response);
              }
            }
          },
          error: (error: any) => {
            console.error('❌ [App] Erro no polling do draft:', error);
          }
        });
      } else {
        console.log('🛑 [App] Parando polling - não estamos mais no draft');
        this.stopDraftPolling();
      }
    }, this.DRAFT_POLLING_INTERVAL);
  }

  private stopDraftPolling(): void {
    if (this.draftPollingInterval) {
      clearInterval(this.draftPollingInterval);
      this.draftPollingInterval = null;
      console.log('🛑 [App] Polling do draft parado');
      logApp('🛑 [App] DRAFT_POLLING_STOPPED', {
        timestamp: new Date().toISOString()
      });
    }
  }

  // ✅ NOVO: Verificar se há mudanças significativas nos dados do draft
  private hasSignificantDraftChanges(newData: any): boolean {
    if (!this.draftData) return true;

    // Verificar mudanças importantes
    const currentTotalActions = this.draftData.totalActions || 0;
    const newTotalActions = newData.totalActions || 0;

    const currentAction = this.draftData.currentAction || 0;
    const newCurrentAction = newData.currentAction || 0;

    const hasActionChange = currentTotalActions !== newTotalActions;
    const hasCurrentActionChange = currentAction !== newCurrentAction;

    // Verificar mudanças nos picks/bans
    const currentLastSyncTime = this.draftData.lastSyncTime || 0;
    const timeSinceLastSync = Date.now() - currentLastSyncTime;
    const shouldSyncByTime = timeSinceLastSync > 3000; // Forçar sync a cada 3 segundos

    return hasActionChange || hasCurrentActionChange || shouldSyncByTime;
  }

  private handleDraftCancelled(data: any): void {
    console.log('🚫 [App] Draft cancelado pelo backend');
    logApp('🚫 [App] === DRAFT CANCELADO ===', {
      matchId: data?.matchId,
      reason: data?.reason,
      currentDraftData: !!this.draftData,
      inDraftPhase: this.inDraftPhase,
      timestamp: new Date().toISOString()
    });

    // ✅ NOVO: Parar polling do draft
    this.stopDraftPolling();

    // ✅ NOVO: Preservar dados do draft por 30 segundos para possível reconexão
    if (this.draftData && data?.reason !== 'completed') {
      logApp('🔄 [App] Preservando dados do draft por 30s para possível reconexão');

      const preservedDraftData = { ...this.draftData };

      setTimeout(() => {
        // Só limpar se ainda não retornou ao draft
        if (!this.inDraftPhase && this.draftData === preservedDraftData) {
          logApp('⏰ [App] Timeout de preservação expirou - limpando dados do draft');
          this.draftData = null;
          this.cdr.detectChanges();
        }
      }, 30000);
    } else {
      // Limpar imediatamente se o draft foi completado ou não há dados
      this.draftData = null;
    }

    // Sair da fase de draft mas manter dados temporariamente
    this.inDraftPhase = false;
    this.currentView = 'dashboard';

    // Mostrar notificação com mais detalhes
    const reason = data?.reason || 'O draft foi cancelado.';
    const detailedReason = data?.matchId ? `Match ${data.matchId}: ${reason}` : reason;

    this.addNotification('warning', 'Draft Cancelado', detailedReason);

    logApp('✅ [App] handleDraftCancelled concluído', {
      preservedData: !!this.draftData,
      inDraftPhase: this.inDraftPhase,
      currentView: this.currentView
    });
  }



  // ✅ NOVO: Handler específico para picks completados - atualização imediata
  private handleDraftPickCompleted(data: any): void {
    console.log('🎯 [App] === HANDLE DRAFT PICK COMPLETED ===');
    console.log('🎯 [App] Pick completado no backend:', data);
    logApp('🎯 [App] DRAFT_PICK_COMPLETED_HANDLER', {
      matchId: data?.matchId,
      playerId: data?.playerId,
      championId: data?.championId,
      action: data?.action,
      currentAction: data?.currentAction,
      timestamp: new Date().toISOString()
    });

    // Verificar se estamos na fase de draft
    if (!this.inDraftPhase || !this.draftData) {
      console.log('⚠️ [App] Pick completado ignorado - não estamos na fase de draft');
      return;
    }

    // Verificar se é a partida correta
    if (data.matchId && this.draftData.matchId !== data.matchId) {
      console.log('⚠️ [App] Pick completado ignorado - partida diferente');
      return;
    }

    console.log('✅ [App] Pick completado válido, atualizando interface imediatamente');

    // ✅ FORÇAR SINCRONIZAÇÃO IMEDIATA após pick completado
    this.forceDraftSyncImmediate(data.matchId, 'pick_completed');

    // ✅ Notificar componente de draft sobre pick completado
    if (this.draftComponentRef?.handleDraftAction) {
      console.log('🎯 [App] Notificando componente de draft sobre pick completado');
      try {
        this.draftComponentRef.handleDraftAction(data);
      } catch (error) {
        console.error('❌ [App] Erro ao notificar componente sobre pick completado:', error);
      }
    }

    // ✅ Emitir evento customizado para atualização imediata
    const pickCompletedEvent = new CustomEvent('draftPickCompleted', {
      detail: {
        matchId: data.matchId,
        playerId: data.playerId,
        championId: data.championId,
        action: data.action,
        currentAction: data.currentAction,
        timestamp: Date.now()
      }
    });
    document.dispatchEvent(pickCompletedEvent);

    // Forçar atualização da interface
    this.cdr.detectChanges();
    console.log('🎯 [App] Pick completado processado com atualização imediata');
  }

  // ✅ CORREÇÃO: Handler para atualizações do draft - APENAS EXIBIÇÃO
  private handleDraftAction(data: any): void {
    console.log('🔄 [App] === HANDLE DRAFT ACTION ===');
    console.log('🔄 [App] Dados da ação do draft:', data);
    logApp('🔄 [App] DRAFT_ACTION_HANDLER', {
      matchId: data?.matchId,
      currentAction: data?.currentAction,
      lastAction: data?.lastAction,
      championId: data?.championId,
      action: data?.action,
      playerId: data?.playerId,
      timestamp: new Date().toISOString()
    });

    // ✅ CORREÇÃO: Verificar se data é válido
    if (!data) {
      console.error('❌ [App] handleDraftAction: data é null/undefined');
      return;
    }

    // Verificar se estamos na fase de draft
    if (!this.inDraftPhase || !this.draftData) {
      console.log('⚠️ [App] Ação do draft ignorada - não estamos na fase de draft');
      return;
    }

    // ✅ CORREÇÃO: Verificar se data.matchId existe antes de usar
    if (data.matchId && this.draftData.matchId !== data.matchId) {
      console.log('⚠️ [App] Ação do draft ignorada - partida diferente');
      return;
    }

    console.log('✅ [App] Ação do draft válida, processando:', {
      currentAction: data.currentAction,
      lastAction: data.lastAction,
      championId: data.championId,
      action: data.action,
      matchId: data.matchId
    });

    // ✅ CORREÇÃO: ATUALIZAR O DRAFTDATA COM OS DADOS DO BACKEND
    this.draftData = {
      ...this.draftData,
      currentAction: data.currentAction,
      lastAction: data.lastAction
    };

    // ✅ NOVO: Se há championId, forçar sincronização imediata para atualizar a tela
    if (data.championId || data.action === 'pick' || data.action === 'ban') {
      console.log('🎯 [App] Ação de pick/ban detectada - forçando sincronização imediata');
      logApp('🎯 [App] PICK_BAN_ACTION_DETECTED', {
        matchId: data.matchId,
        championId: data.championId,
        action: data.action,
        currentAction: data.currentAction,
        timestamp: new Date().toISOString()
      });

      // Forçar sincronização após pequeno delay para garantir que o backend salvou
      setTimeout(() => {
        this.forceDraftSync(data.matchId);
      }, 200);
    }

    // ✅ CORREÇÃO: Enviar evento customizado para o componente de draft
    const draftActionEvent = new CustomEvent('draft_action', {
      detail: {
        matchId: data.matchId,
        currentAction: data.currentAction,
        lastAction: data.lastAction,
        championId: data.championId,
        action: data.action,
        playerId: data.playerId,
        timestamp: Date.now()
      }
    });
    document.dispatchEvent(draftActionEvent);
    console.log('🔄 [App] Evento draft_action enviado:', draftActionEvent.detail);

    // ✅ CORREÇÃO: Notificar componente de draft sobre ação
    if (this.draftComponentRef?.handleDraftAction) {
      console.log('🔄 [App] Notificando componente de draft sobre ação');
      try {
        this.draftComponentRef.handleDraftAction(data);
        console.log('✅ [App] Componente de draft notificado com sucesso');
      } catch (error) {
        console.error('❌ [App] Erro ao notificar componente de draft:', error);
      }
    }

    // Forçar atualização da interface
    this.cdr.detectChanges();
    console.log('🔄 [App] Ação do draft processada com sucesso');
  }

  // ✅ NOVO: Método para forçar sincronização imediata do draft
  private async forceDraftSyncImmediate(matchId: number, reason: string = 'manual'): Promise<void> {
    try {
      console.log(`🔄 [App] === SINCRONIZAÇÃO IMEDIATA DO DRAFT (${reason}) ===`);
      logApp('🔄 [App] FORCE_DRAFT_SYNC_IMMEDIATE', {
        matchId,
        reason,
        currentPlayer: this.currentPlayer?.summonerName,
        timestamp: new Date().toISOString()
      });

      // ✅ USAR método existente checkSyncStatus para forçar sincronização
      if (!this.currentPlayer?.summonerName) {
        console.warn('⚠️ [App] Não é possível sincronizar - jogador não identificado');
        return;
      }

      this.apiService.checkSyncStatus(this.currentPlayer.summonerName).subscribe({
        next: (response: any) => {
          if (response?.status === 'draft' && response?.matchId === matchId) {
            console.log('✅ [App] Sincronização imediata bem-sucedida:', response);
            logApp('✅ [App] SYNC_SUCCESS', {
              matchId,
              reason,
              totalActions: response.totalActions,
              currentAction: response.currentAction,
              timestamp: new Date().toISOString()
            });

            // Processar dados sincronizados
            this.handleDraftDataSync(response);
          } else {
            console.warn('⚠️ [App] Sincronização retornou dados inesperados:', response);
            logApp('⚠️ [App] SYNC_UNEXPECTED', {
              matchId,
              reason,
              responseStatus: response?.status,
              responseMatchId: response?.matchId,
              timestamp: new Date().toISOString()
            });
          }
        },
        error: (error: any) => {
          console.error('❌ [App] Erro na sincronização imediata:', error);
          logApp('❌ [App] SYNC_ERROR', {
            matchId,
            reason,
            error: error.message || error,
            timestamp: new Date().toISOString()
          });
        }
      });
    } catch (error) {
      console.error('❌ [App] Erro na sincronização imediata:', error);
      logApp('❌ [App] SYNC_IMMEDIATE_ERROR', {
        matchId,
        reason,
        error: error instanceof Error ? error.message : error,
        timestamp: new Date().toISOString()
      });
    }
  }

  // ✅ NOVO: Fallback para verificar status do draft
  private checkDraftStatusFallback(matchId: number): void {
    if (!this.currentPlayer?.summonerName) return;

    this.apiService.checkSyncStatus(this.currentPlayer.summonerName).subscribe({
      next: (response: any) => {
        if (response?.status === 'draft' && response?.matchId === matchId) {
          console.log('🔄 [App] Fallback de verificação bem-sucedido');
          this.handleDraftDataSync(response);
        }
      },
      error: (error: any) => {
        console.error('❌ [App] Erro no fallback de verificação:', error);
      }
    });
  }

  // ✅ NOVO: Método para forçar sincronização do draft
  private async forceDraftSync(matchId: number): Promise<void> {
    try {
      console.log('🔄 [App] Forçando sincronização do draft após ação de bot');

      this.apiService.checkSyncStatus(this.currentPlayer?.summonerName || '').subscribe({
        next: (response) => {
          if (response && response.status === 'draft' && response.matchId === matchId) {
            console.log('🔄 [App] Sincronização forçada bem-sucedida');
            this.handleDraftDataSync(response);
          }
        },
        error: (error) => {
          console.error('❌ [App] Erro na sincronização forçada:', error);
        }
      });
    } catch (error) {
      console.error('❌ [App] Erro na sincronização forçada:', error);
    }
  }

  // ✅ NOVO: Handler para sincronização de dados do draft
  private handleDraftDataSync(data: any): void {
    console.log('🔄 [App] Sincronizando dados do draft:', data);

    // Verificar se estamos na fase de draft e se é a partida correta
    if (!this.inDraftPhase || !this.draftData || this.draftData.matchId !== data.matchId) {
      console.log('⚠️ [App] Sincronização ignorada - não estamos no draft desta partida');
      return;
    }

    // Atualizar dados do draft com informações sincronizadas
    if (data.pickBanData) {
      console.log('🔄 [App] Atualizando pickBanData:', {
        totalActions: data.totalActions,
        totalPicks: data.totalPicks,
        totalBans: data.totalBans,
        lastAction: data.lastAction?.action || 'none'
      });

      // ✅ CORREÇÃO: Mapear team1/team2 para blueTeam/redTeam conforme esperado pelo frontend
      const pickBanData = typeof data.pickBanData === 'string'
        ? JSON.parse(data.pickBanData)
        : data.pickBanData;

      // Sempre: blueTeam = team1, redTeam = team2
      this.draftData = {
        ...this.draftData,
        blueTeam: pickBanData.team1 || [],
        redTeam: pickBanData.team2 || [],
        phases: pickBanData.phases || this.draftData.phases || [],
        currentAction: pickBanData.currentAction || this.draftData.currentAction || 0,
        phase: pickBanData.phase || this.draftData.phase || 'bans',
        actions: pickBanData.actions || this.draftData.actions || [],
        team1Picks: pickBanData.team1Picks || this.draftData.team1Picks || [],
        team1Bans: pickBanData.team1Bans || this.draftData.team1Bans || [],
        team2Picks: pickBanData.team2Picks || this.draftData.team2Picks || [],
        team2Bans: pickBanData.team2Bans || this.draftData.team2Bans || [],
        totalActions: data.totalActions,
        totalPicks: data.totalPicks,
        totalBans: data.totalBans,
        team1Stats: data.team1Stats,
        team2Stats: data.team2Stats,
        lastAction: data.lastAction,
        lastSyncTime: Date.now()
      };

      // ✅ NOVO: Notificar componente de draft sobre a sincronização
      if (this.draftComponentRef?.handleDraftDataSync) {
        console.log('🔄 [App] Notificando componente de draft sobre sincronização');
        this.draftComponentRef.handleDraftDataSync(data);
      } else {
        console.log('⚠️ [App] Componente de draft não encontrado ou não suporta sincronização');
      }

      // ✅ NOVO: Forçar atualização da interface
      this.cdr.detectChanges();

      console.log('✅ [App] Dados do draft sincronizados com sucesso');
    }
  }

  // ✅ CORREÇÃO: Handler para atualizações de timer do draft - APENAS EXIBIÇÃO
  private handleDraftTimerUpdate(data: any): void {
    console.log('⏰ [App] === HANDLE DRAFT TIMER UPDATE ===');
    console.log('⏰ [App] Dados do timer:', data);

    // Verificar se estamos na fase de draft
    if (!this.inDraftPhase || !this.draftData) {
      console.log('⚠️ [App] Timer de draft ignorado - não estamos na fase de draft');
      return;
    }

    // Verificar se é a partida correta
    if (data.matchId && this.draftData.matchId !== data.matchId) {
      console.log('⚠️ [App] Timer de draft ignorado - partida diferente');
      return;
    }

    console.log('✅ [App] Timer de draft válido, processando:', {
      timeRemaining: data.timeRemaining,
      isUrgent: data.isUrgent,
      matchId: data.matchId
    });

    // ✅ CORREÇÃO: APENAS ATUALIZAR O TIMER NO DRAFTDATA - sem lógica local
    this.draftData = {
      ...this.draftData,
      timeRemaining: data.timeRemaining,
      isUrgent: data.isUrgent
      // ✅ IMPORTANTE: NÃO atualizar currentAction aqui - isso deve vir apenas do backend
    };

    // ✅ CORREÇÃO: Enviar evento customizado para o componente de draft
    const timerUpdateEvent = new CustomEvent('draftTimerUpdate', {
      detail: {
        matchId: data.matchId,
        timeRemaining: data.timeRemaining,
        isUrgent: data.isUrgent,
        timestamp: Date.now()
      }
    });
    document.dispatchEvent(timerUpdateEvent);
    console.log('⏰ [App] Evento draftTimerUpdate enviado:', timerUpdateEvent.detail);

    // ✅ CORREÇÃO: Notificar componente de draft sobre atualização de timer
    if (this.draftComponentRef?.updateTimerFromBackend) {
      console.log('⏰ [App] Notificando componente de draft sobre atualização de timer');
      try {
        this.draftComponentRef.updateTimerFromBackend(data);
        console.log('✅ [App] Componente de draft notificado com sucesso');
      } catch (error) {
        console.error('❌ [App] Erro ao notificar componente de draft:', error);
      }
    }

    // Forçar atualização da interface
    this.cdr.detectChanges();
    console.log('⏰ [App] Timer de draft processado com sucesso');
  }

  // ✅ CORREÇÃO: Handler para mudanças de jogador do draft - APENAS EXIBIÇÃO
  private handleDraftPlayerChange(data: any): void {
    console.log('🔄 [App] === HANDLE DRAFT PLAYER CHANGE ===');
    console.log('🔄 [App] Dados da mudança de jogador:', data);

    // Verificar se estamos na fase de draft
    if (!this.inDraftPhase || !this.draftData) {
      console.log('⚠️ [App] Mudança de jogador ignorada - não estamos na fase de draft');
      return;
    }

    // Verificar se é a partida correta
    if (data.matchId && this.draftData.matchId !== data.matchId) {
      console.log('⚠️ [App] Mudança de jogador ignorada - partida diferente');
      return;
    }

    console.log('✅ [App] Mudança de jogador válida, processando:', {
      currentAction: data.currentAction,
      previousAction: data.previousAction,
      matchId: data.matchId
    });

    // ✅ CORREÇÃO: NÃO atualizar currentAction aqui - deixar o backend ser a fonte de verdade
    // this.draftData = {
    //   ...this.draftData,
    //   currentAction: data.currentAction
    // };

    // ✅ CORREÇÃO: Enviar evento customizado para o componente de draft
    const playerChangeEvent = new CustomEvent('draftPlayerChange', {
      detail: {
        matchId: data.matchId,
        currentAction: data.currentAction,
        previousAction: data.previousAction,
        timestamp: Date.now()
      }
    });
    document.dispatchEvent(playerChangeEvent);
    console.log('🔄 [App] Evento draftPlayerChange enviado:', playerChangeEvent.detail);

    // ✅ CORREÇÃO: Notificar componente de draft sobre mudança de jogador
    if (this.draftComponentRef?.handlePlayerChange) {
      console.log('🔄 [App] Notificando componente de draft sobre mudança de jogador');
      try {
        this.draftComponentRef.handlePlayerChange(data);
        console.log('✅ [App] Componente de draft notificado com sucesso');
      } catch (error) {
        console.error('❌ [App] Erro ao notificar componente de draft:', error);
      }
    }

    // Forçar atualização da interface
    this.cdr.detectChanges();
    console.log('🔄 [App] Mudança de jogador processada com sucesso');
  }

  // ✅ NOVO: Método de teste para simular mensagens de timer do draft
  testDraftTimerMessages(): void {
    logApp('🧪 [App] === TESTE DE FUNÇÃO LOGAPP ===');
    logApp('🧪 [App] Verificando se logApp está funcionando...');
    logApp('🧪 [App] Timestamp:', new Date().toISOString());

    console.log('🧪 [App] === TESTE DE MENSAGENS DE TIMER DO DRAFT ===');

    // Simular mensagem de timer update
    const timerUpdateMessage = {
      type: 'draft_timer_update',
      data: {
        matchId: this.draftData?.matchId || 1066,
        timeRemaining: 25,
        isUrgent: false
      }
    };

    console.log('🧪 [App] Simulando mensagem de timer update:', timerUpdateMessage);
    this.handleBackendMessage(timerUpdateMessage);

    // Simular mensagem de timeout
    setTimeout(() => {
      const timeoutMessage = {
        type: 'draft_timeout',
        data: {
          matchId: this.draftData?.matchId || 1066,
          playerId: this.currentPlayer?.puuid || this.currentPlayer?.summonerName || 'test_player',
          timestamp: Date.now()
        }
      };

      console.log('🧪 [App] Simulando mensagem de timeout:', timeoutMessage);
      this.handleBackendMessage(timeoutMessage);
    }, 2000);

    console.log('🧪 [App] Teste de mensagens de timer concluído');
  }

  private handleDraftTimeout(data: any): void {
    console.log('⏰ [App] === HANDLE DRAFT TIMEOUT ===');
    console.log('⏰ [App] Dados do timeout:', data);
    console.log('⏰ [App] Estado atual:', {
      inDraftPhase: this.inDraftPhase,
      hasDraftData: !!this.draftData,
      draftMatchId: this.draftData?.matchId,
      receivedMatchId: data?.matchId,
      currentPlayerId: this.currentPlayer?.puuid || this.currentPlayer?.summonerName || this.currentPlayer?.displayName,
      timeoutPlayerId: data?.playerId,
      timestamp: new Date().toISOString()
    });

    // Verificar se estamos na fase de draft
    if (!this.inDraftPhase || !this.draftData) {
      console.log('⚠️ [App] Timeout de draft ignorado - não estamos na fase de draft');
      return;
    }

    // Verificar se é a partida correta
    if (data.matchId && this.draftData.matchId !== data.matchId) {
      console.log('⚠️ [App] Timeout de draft ignorado - partida diferente');
      console.log('⚠️ [App] Draft matchId:', this.draftData.matchId, 'Timeout matchId:', data.matchId);
      return;
    }

    console.log('✅ [App] Timeout de draft válido, processando:', {
      playerId: data.playerId,
      matchId: data.matchId,
      isCurrentPlayer: data.playerId === (this.currentPlayer?.displayName || this.currentPlayer?.summonerName)
    });

    // ✅ CORREÇÃO: Enviar evento customizado para o componente de draft
    const timeoutEvent = new CustomEvent('draftTimeout', {
      detail: {
        matchId: data.matchId,
        playerId: data.playerId,
        timestamp: Date.now()
      }
    });
    document.dispatchEvent(timeoutEvent);
    console.log('⏰ [App] Evento draftTimeout enviado:', timeoutEvent.detail);

    // ✅ CORREÇÃO: Notificar componente de draft sobre timeout
    if (this.draftComponentRef?.handleDraftTimeout) {
      console.log('⏰ [App] Notificando componente de draft sobre timeout');
      try {
        this.draftComponentRef.handleDraftTimeout(data);
        console.log('✅ [App] Componente de draft notificado sobre timeout com sucesso');
      } catch (error) {
        console.error('❌ [App] Erro ao notificar componente de draft sobre timeout:', error);
      }
    } else {
      console.warn('⚠️ [App] Componente de draft não encontrado ou método handleDraftTimeout não disponível');
    }

    // Verificar se é timeout para o jogador atual
    const isTimeoutForCurrentPlayer = data.playerId && this.currentPlayer &&
      (data.playerId === this.currentPlayer.displayName ||
        data.playerId === this.currentPlayer.summonerName);

    if (isTimeoutForCurrentPlayer) {
      console.log('⏰ [App] Timeout para o jogador atual - fechando modais se necessário');
      // O componente de draft irá lidar com o fechamento dos modais
    } else {
      console.log('⏰ [App] Timeout para outro jogador - mantendo interface');
    }

    // Forçar atualização da interface
    this.cdr.detectChanges();

    console.log('⏰ [App] Timeout de draft processado com sucesso');
  }

  private handleGameStarting(data: any): void {
    console.log('🎮 [App] ========== INÍCIO DO handleGameStarting ==========');
    console.log('🎮 [App] Jogo iniciando:', data);
    console.log('🔍 [App] DEBUG - gameData originalMatchId:', data.originalMatchId);
    console.log('🔍 [App] DEBUG - gameData matchId:', data.matchId);
    console.log('🔍 [App] DEBUG - gameData completo:', JSON.stringify(data, null, 2));
    console.log('🔍 [App] DEBUG - gameData.gameData:', data.gameData);
    console.log('🔍 [App] DEBUG - gameData.gameData?.matchId:', data.gameData?.matchId);
    console.log('🎮 [App] ========== FIM DO handleGameStarting ==========');

    // ✅ CORREÇÃO: Verificar se os dados dos times estão presentes
    if (!data.team1 || !data.team2) {
      console.error('❌ [App] Dados dos times ausentes no evento game_starting:', {
        hasTeam1: !!data.team1,
        hasTeam2: !!data.team2,
        team1Length: data.team1?.length || 0,
        team2Length: data.team2?.length || 0,
        dataKeys: Object.keys(data)
      });
    } else {
      console.log('✅ [App] Dados dos times recebidos:', {
        team1Length: data.team1.length,
        team2Length: data.team2.length,
        team1Players: data.team1.map((p: any) => p.summonerName || p.name),
        team2Players: data.team2.map((p: any) => p.summonerName || p.name)
      });
    }

    this.inDraftPhase = false;
    this.draftData = null;
    this.inGamePhase = true;
    this.gameData = data;

    this.addNotification('success', 'Jogo Iniciado!', 'A partida começou.');
  }

  // ✅ NOVO: Handler para mensagem game_started do WebSocket
  private handleGameStarted(data: any): void {
    console.log('🎮 [App] ===== GAME STARTED RECEBIDO =====');
    console.log('🎮 [App] Dados recebidos:', data);

    if (!data?.gameData) {
      console.error('❌ [App] Dados inválidos para game_started:', data);
      return;
    }

    // ✅ TRANSIÇÃO: Limpar estados anteriores
    this.inDraftPhase = false;
    this.draftData = null;
    this.showMatchFound = false;
    this.matchFoundData = null;
    this.isInQueue = false;

    // ✅ ATIVAR: Game in progress
    this.inGamePhase = true;
    // ✅ NORMALIZAR: Garantir que pickBanData esteja presente (mapeando de draftResults)
    const incomingGameData = data.gameData || {};
    const normalizedGameData = {
      ...incomingGameData,
      pickBanData: (() => {
        try {
          // Se já veio pronto
          if (incomingGameData.pickBanData) {
            return typeof incomingGameData.pickBanData === 'string'
              ? JSON.parse(incomingGameData.pickBanData)
              : incomingGameData.pickBanData;
          }
          // Fallback para draftResults enviado pelo backend
          if (incomingGameData.draftResults) {
            return typeof incomingGameData.draftResults === 'string'
              ? JSON.parse(incomingGameData.draftResults)
              : incomingGameData.draftResults;
          }
        } catch (e) {
          console.warn('⚠️ [App] Erro ao parsear pickBanData/draftResults no gameStarted:', e);
        }
        return null;
      })()
    };

    this.gameData = normalizedGameData;
    this.lastMatchId = data.matchId || data.originalMatchId;

    console.log('🎮 [App] Estado atualizado para game in progress:', {
      matchId: this.lastMatchId,
      inGamePhase: this.inGamePhase,
      inDraftPhase: this.inDraftPhase,
      showMatchFound: this.showMatchFound
    });

    console.log('🎯 [App] pickBanData normalizado disponível?', {
      hasPickBanData: !!this.gameData?.pickBanData,
      pickBanKeys: this.gameData?.pickBanData ? Object.keys(this.gameData.pickBanData) : []
    });

    this.addNotification('success', 'Jogo Iniciado!', 'A partida começou.');
  }

  private handleMatchCancelled(data: any): void {
    console.log('❌ [App] Partida cancelada pelo backend');

    // ✅ NOVO: Limpar controle de partida
    this.lastMatchId = null;
    this.showMatchFound = false;
    this.matchFoundData = null;
    this.inDraftPhase = false;
    this.draftData = null;
    this.isInQueue = true; // Voltar para fila

    console.log('❌ [App] Estado limpo após cancelamento');
    this.addNotification('info', 'Partida Cancelada', data.message || 'A partida foi cancelada.');
  }

  private handleMatchTimerUpdate(data: any): void {
    console.log('⏰ [App] === handleMatchTimerUpdate ===');
    console.log('⏰ [App] Timer atualizado:', data);
    console.log('⏰ [App] Verificando condições:', {
      showMatchFound: this.showMatchFound,
      hasMatchFoundData: !!this.matchFoundData,
      matchDataId: this.matchFoundData?.matchId,
      timerDataId: data.matchId,
      idsMatch: this.matchFoundData?.matchId === data.matchId
    });

    // ✅ CORREÇÃO: Verificar se devemos processar esta atualização
    if (!this.showMatchFound || !this.matchFoundData) {
      console.log('⏰ [App] Match não está visível - ignorando timer');
      return;
    }

    if (this.matchFoundData.matchId !== data.matchId) {
      console.log('⏰ [App] Timer para partida diferente - ignorando');
      return;
    }

    // ✅ NOVO: Throttle para evitar atualizações excessivas
    const now = Date.now();
    const timeSinceLastUpdate = now - (this.lastTimerUpdate || 0);

    if (timeSinceLastUpdate < 500) { // Máximo 2 atualizações por segundo
      console.log('⏰ [App] Throttling timer update - muito frequente');
      return;
    }

    this.lastTimerUpdate = now;

    console.log('⏰ [App] Condições atendidas - emitindo evento para componente');

    // ✅ CORREÇÃO: Emitir evento apenas quando necessário
    try {
      document.dispatchEvent(new CustomEvent('matchTimerUpdate', {
        detail: {
          matchId: data.matchId,
          timeLeft: data.timeLeft,
          isUrgent: data.isUrgent || data.timeLeft <= 10
        }
      }));
      console.log('⏰ [App] Evento matchTimerUpdate emitido com sucesso');
    } catch (error) {
      console.error('❌ [App] Erro ao emitir evento matchTimerUpdate:', error);
    }
  }

  private handleQueueUpdate(data: any): void {
    // ✅ NOVO: Guarda de proteção para dados inválidos
    if (!data) {
      console.warn('⚠️ [App] handleQueueUpdate recebeu dados nulos, ignorando.');
      return;
    }

    // ✅ VERIFICAR SE AUTO-REFRESH ESTÁ HABILITADO ANTES DE PROCESSAR
    if (!this.autoRefreshEnabled) {
      // Só processar atualizações críticas mesmo com auto-refresh desabilitado
      const currentPlayerCount = this.queueStatus?.playersInQueue || 0;
      const newPlayerCount = data?.playersInQueue || 0;
      const isCriticalUpdate = newPlayerCount >= 10 && currentPlayerCount < 10; // Matchmaking threshold

      if (!isCriticalUpdate && !data?.critical) {
        // ✅ IGNORAR: Auto-refresh desabilitado e não é atualização crítica
        const timeSinceLastIgnoreLog = Date.now() - (this.lastIgnoreLogTime || 0);
        if (timeSinceLastIgnoreLog > 30000) { // Log apenas a cada 30 segundos
          console.log('⏭️ [App] Atualizações da fila ignoradas - auto-refresh desabilitado');
          this.lastIgnoreLogTime = Date.now();
        }
        return;
      }
    }

    // ✅ FILTROS MÚLTIPLOS: Só atualizar em casos específicos e necessários
    const currentPlayerCount = this.queueStatus?.playersInQueue || 0;
    const newPlayerCount = data?.playersInQueue || 0;

    // 1. Verificar se há mudança no número de jogadores
    const hasPlayerCountChange = currentPlayerCount !== newPlayerCount;

    // 2. Verificar se há mudança no status ativo da fila
    const currentIsActive = this.queueStatus?.isActive || false;
    const newIsActive = data?.isActive !== undefined ? data.isActive : currentIsActive;
    const hasActiveStatusChange = currentIsActive !== newIsActive;

    // 3. Verificar se é uma mudança crítica (10+ jogadores = matchmaking)
    const isCriticalThreshold = newPlayerCount >= 10 && currentPlayerCount < 10;

    // ✅ SÓ ATUALIZAR SE HOUVER MUDANÇAS SIGNIFICATIVAS
    if (hasPlayerCountChange || hasActiveStatusChange || isCriticalThreshold) {
      console.log(`📊 [App] Status da fila atualizado:`, {
        playersInQueue: `${currentPlayerCount} → ${newPlayerCount}`,
        isActive: `${currentIsActive} → ${newIsActive}`,
        isCritical: isCriticalThreshold,
        autoRefreshEnabled: this.autoRefreshEnabled
      });
      this.queueStatus = data;
    } else {
      // ✅ IGNORAR: Log apenas quando necessário, evitar spam
      const timeSinceLastIgnoreLog = Date.now() - (this.lastIgnoreLogTime || 0);
      if (timeSinceLastIgnoreLog > 5000) { // Log apenas a cada 5 segundos
        console.log('⏭️ [App] Atualizações da fila ignoradas - sem mudanças significativas');
        this.lastIgnoreLogTime = Date.now();
      }
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    clearInterval(this.lcuCheckInterval);

    // ✅ PARAR: Polling inteligente
    this.stopIntelligentPolling();

    // ✅ PARAR: Polling do draft
    this.stopDraftPolling();
  }

  // ✅ MANTIDO: Métodos de interface
  setCurrentView(view: 'dashboard' | 'queue' | 'history' | 'leaderboard' | 'settings'): void {
    this.currentView = view;
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

    if (!data.player.gameName || !data.player.tagLine) {
      console.error('❌ [App] gameName ou tagLine não disponíveis');
      this.addNotification('error', 'Erro', 'Dados do jogador incompletos (gameName/tagLine)');
      return;
    }

    try {
      // ✅ CORRIGIDO: Usar discordService.joinDiscordQueue para entrada via Discord
      const success = this.discordService.joinDiscordQueue(
        data.preferences.primaryLane,
        data.preferences.secondaryLane,
        data.player.summonerName,
        {
          gameName: data.player.gameName,
          tagLine: data.player.tagLine
        }
      );

      if (success) {
        console.log('✅ [App] Solicitação de entrada na fila Discord enviada via WebSocket');
        this.addNotification('success', 'Fila Discord', 'Entrando na fila via Discord...');

        // ✅ CORRIGIDO: Marcar estado como na fila imediatamente
        this.isInQueue = true;
        this.hasRecentBackendQueueStatus = true;

        // Atualizar status após 3 segundos para confirmar
        setTimeout(() => {
          this.refreshQueueStatus();
        }, 3000);
      } else {
        console.error('❌ [App] Falha ao enviar solicitação via Discord WebSocket');
        this.addNotification('error', 'Erro', 'Falha ao conectar com Discord');
      }
    } catch (error) {
      console.error('❌ [App] Erro ao entrar na fila Discord:', error);
      this.addNotification('error', 'Erro', 'Falha ao entrar na fila Discord');
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
        this.savePlayerData(player);
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

  private tryGetCurrentPlayerDetails(): void {
    console.log('🔄 [App] Tentando carregar dados via getCurrentPlayerDetails...');

    this.apiService.getCurrentPlayerDetails().subscribe({
      next: (response) => {
        console.log('✅ [App] Resposta getCurrentPlayerDetails:', response);

        if (response.success && response.data) {
          const data = response.data;

          // Mapear dados do LCU para Player
          const lcuData = data.lcu || {};
          const riotAccount = data.riotAccount || {};

          const gameName = riotAccount.gameName || lcuData.gameName;
          const tagLine = riotAccount.tagLine || lcuData.tagLine;

          // ✅ CORRIGIDO: Usar displayName do backend se disponível
          let displayName = '';

          // Verificar se o backend já forneceu displayName
          if (lcuData.displayName) {
            displayName = lcuData.displayName;
            console.log('✅ [App] Usando displayName do backend:', displayName);
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

          const player: Player = {
            id: lcuData.summonerId || 0,
            summonerName: displayName, // Use displayName as summonerName
            displayName: displayName, // ✅ ADICIONADO: Definir displayName corretamente (já verificado acima)
            gameName: gameName,
            tagLine: tagLine,
            summonerId: (lcuData.summonerId || 0).toString(),
            puuid: riotAccount.puuid || lcuData.puuid || '',
            profileIconId: lcuData.profileIconId || 29,
            summonerLevel: lcuData.summonerLevel || 30,
            region: 'br1',
            currentMMR: 1200,
            customLp: 1200
          };

          this.currentPlayer = player;
          localStorage.setItem('currentPlayer', JSON.stringify(player));

          // ✅ ADICIONADO: Atualizar formulário de configurações
          this.updateSettingsForm();

          console.log('✅ [App] Dados do jogador mapeados com sucesso:', player.summonerName, 'displayName:', player.displayName);
          this.addNotification('success', 'Jogador Detectado', `Logado como: ${player.summonerName}`);
        }
      },
      error: (error) => {
        console.error('❌ [App] Erro ao carregar getCurrentPlayerDetails:', error);
        this.addNotification('error', 'Erro API', 'Falha ao carregar dados do jogador');
        this.tryLoadFromLocalStorage();
      }
    });
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

        console.log('✅ [App] Dados do jogador carregados do localStorage, displayName:', this.currentPlayer?.displayName);
      } catch (error) {
        console.warn('⚠️ [App] Erro ao carregar do localStorage:', error);
      }
    }
  }

  private refreshQueueStatus(): void {
    // Se temos o jogador atual, passar seu displayName para detecção no backend
    const currentPlayerDisplayName = this.currentPlayer?.displayName;

    console.log('📊 [App] === REFRESH QUEUE STATUS ===');
    console.log('📊 [App] refreshQueueStatus chamado:', {
      currentPlayerDisplayName: currentPlayerDisplayName,
      currentIsInQueue: this.isInQueue,
      hasRecentBackendQueueStatus: this.hasRecentBackendQueueStatus
    });

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
      },
      error: (error) => {
        console.warn('⚠️ [App] Erro ao atualizar status da fila:', error);
        this.hasRecentBackendQueueStatus = false;
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
  }

  private startLCUStatusCheck(): void {
    setInterval(() => {
      this.apiService.getLCUStatus().subscribe({
        next: (status) => this.lcuStatus = status,
        error: () => this.lcuStatus = { isConnected: false }
      });
    }, 5000);
  }

  private checkBackendConnection(): void {
    this.apiService.checkHealth().subscribe({
      next: () => {
        this.isConnected = true;
        console.log('✅ [App] Conectado ao backend');
      },
      error: () => {
        this.isConnected = false;
        console.warn('❌ [App] Backend desconectado');
      }
    });
  }

  private loadConfigFromDatabase(): void {
    this.apiService.getConfigSettings().subscribe({
      next: (config) => {
        console.log('⚙️ [App] Configurações carregadas:', config);
        if (config) {
          this.settingsForm = { ...this.settingsForm, ...config };
        }
      },
      error: (error) => {
        console.warn('⚠️ [App] Erro ao carregar configurações:', error);
      }
    });
  }

  // ✅ MANTIDO: Métodos básicos de interface (MANUAL APENAS)
  onRefreshData(): void {
    console.log('🔄 [App] Refresh MANUAL solicitado pelo usuário');
    this.refreshQueueStatus();
    this.loadPlayerData();
  }

  // ✅ NOVO: Método para o queue component informar sobre mudanças no auto-refresh
  onAutoRefreshToggle(enabled: boolean): void {
    this.autoRefreshEnabled = enabled;
    console.log(`🔄 [App] Auto-refresh ${enabled ? 'habilitado' : 'desabilitado'} - atualizações de fila serão ${enabled ? 'processadas' : 'filtradas'}`);
  }

  // ✅ MANTIDO: Métodos auxiliares para bots (admin)
  async addBotToQueue(): Promise<void> {
    try {
      await firstValueFrom(this.apiService.addBotToQueue());
      this.addNotification('success', 'Bot Adicionado', 'Bot adicionado à fila com sucesso');
    } catch (error) {
      console.error('❌ [App] Erro ao adicionar bot:', error);
      this.addNotification('error', 'Erro', 'Falha ao adicionar bot');
    }
  }

  // ✅ NOVO: Resetar contador de bots
  async resetBotCounter(): Promise<void> {
    try {
      await firstValueFrom(this.apiService.resetBotCounter());
      this.addNotification('success', 'Contador Resetado', 'Contador de bots resetado com sucesso');
    } catch (error) {
      console.error('❌ [App] Erro ao resetar contador de bots:', error);
      this.addNotification('error', 'Erro', 'Falha ao resetar contador de bots');
    }
  }

  // ✅ MANTIDO: Métodos do Electron
  minimizeWindow(): void {
    if (this.isElectron && (window as any).electronAPI) {
      (window as any).electronAPI.minimizeWindow();
    }
  }

  maximizeWindow(): void {
    if (this.isElectron && (window as any).electronAPI) {
      (window as any).electronAPI.maximizeWindow();
    }
  }

  closeWindow(): void {
    if (this.isElectron && (window as any).electronAPI) {
      (window as any).electronAPI.closeWindow();
    }
  }

  // ✅ ADICIONADO: Métodos faltantes para o template
  onAcceptMatch(event: any): void {
    this.acceptMatch();
  }

  onDeclineMatch(event: any): void {
    this.declineMatch();
  }

  onPickBanComplete(event: any): void {
    console.log('🎯 [App] Draft completado:', event);
    console.log('🔍 [App] DEBUG - Criando gameData a partir do event:', JSON.stringify(event, null, 2));

    // ✅ CORREÇÃO: Extrair picks de cada jogador das phases
    const blueTeamWithChampions = this.assignChampionsToTeam(event.blueTeam || [], event.session, 'blue');
    const redTeamWithChampions = this.assignChampionsToTeam(event.redTeam || [], event.session, 'red');

    console.log('🔍 [App] Times com campeões atribuídos:', {
      blueTeam: blueTeamWithChampions,
      redTeam: redTeamWithChampions
    });

    // ✅ CORREÇÃO: Criar gameData corretamente a partir dos dados do draft
    const gameData = {
      sessionId: `game_${event.session?.id || Date.now()}`,
      gameId: `custom_${event.session?.id || Date.now()}`,
      team1: blueTeamWithChampions,
      team2: redTeamWithChampions,
      startTime: new Date(),
      pickBanData: event.session || {},
      isCustomGame: true,
      originalMatchId: event.session?.id || null,
      originalMatchData: event.session || null,
      riotId: null
    };

    console.log('✅ [App] gameData criado com campeões:', gameData);

    this.draftData = event;
    this.gameData = gameData; // ✅ CORREÇÃO: Definir gameData
    this.inDraftPhase = false;
    this.inGamePhase = true;
  }

  // ✅ NOVO: Método para atribuir campeões aos jogadores baseado nas phases
  private assignChampionsToTeam(team: any[], session: any, teamSide: 'blue' | 'red'): any[] {
    if (!session?.phases || !Array.isArray(session.phases)) {
      console.warn('⚠️ [App] Sessão não tem phases, retornando time original');
      return team;
    }

    console.log(`🎯 [App] Atribuindo campeões ao time ${teamSide}:`, {
      teamPlayersCount: team.length,
      phasesCount: session.phases.length,
      teamPlayers: team.map((p: any) => ({ name: p.summonerName || p.name, id: p.id }))
    });

    // Obter picks do time
    const teamPicks = session.phases
      .filter((phase: any) =>
        phase.action === 'pick' &&
        phase.team === teamSide &&
        phase.champion &&
        phase.locked
      )
      .map((phase: any) => ({
        championId: phase.champion.id,
        championName: phase.champion.name,
        champion: phase.champion
      }));

    console.log(`✅ [App] Picks encontrados para time ${teamSide}:`, teamPicks);

    // Atribuir campeões aos jogadores (assumindo ordem)
    return team.map((player: any, index: number) => {
      const pick = teamPicks[index]; // Por ordem (pode ser melhorado com lógica mais específica)

      const playerWithChampion = {
        ...player,
        champion: pick?.champion || null,
        championId: pick?.championId || null,
        championName: pick?.championName || null
      };

      console.log(`🎯 [App] Jogador ${player.summonerName || player.name} recebeu campeão:`, {
        championName: pick?.championName || 'Nenhum',
        hasChampion: !!pick?.champion
      });

      return playerWithChampion;
    });
  }

  exitDraft(): void {
    console.log('🚪 [App] Saindo do draft');

    // ✅ CORREÇÃO: Notificar backend sobre cancelamento antes de limpar estado
    if (this.draftData?.matchId) {
      console.log(`📤 [App] Enviando cancelamento de draft para backend: ${this.draftData.matchId}`);

      this.apiService.sendWebSocketMessage({
        type: 'cancel_draft',
        data: {
          matchId: this.draftData.matchId,
          reason: 'Cancelado pelo usuário'
        }
      });
    }

    // Limpar estado local
    this.inDraftPhase = false;
    this.draftData = null;
    this.currentView = 'dashboard';

    // Adicionar notificação
    this.addNotification('info', 'Draft Cancelado', 'O draft foi cancelado e você retornará à fila.');
  }

  onGameComplete(event: any): void {
    console.log('🏁 [App] Jogo completado:', event);
    this.gameResult = event;

    // ✅ NOVO: Salvar resultado no banco de dados
    this.saveGameResultToDatabase(event);

    // Limpar estado
    this.inGamePhase = false;
    this.gameData = null;
    this.currentView = 'dashboard';
    this.addNotification('success', 'Jogo Concluído!', 'Resultado salvo com sucesso');
  }

  // ✅ NOVO: Método para salvar resultado no banco
  private saveGameResultToDatabase(gameResult: any): void {
    console.log('💾 [App] Salvando resultado no banco:', gameResult);

    try {
      // Preparar dados para salvar
      // ✅ CORREÇÃO: Extrair lógica de ternário aninhado
      let winnerTeam: number | null = null;
      if (gameResult.winner === 'blue') {
        winnerTeam = 1;
      } else if (gameResult.winner === 'red') {
        winnerTeam = 2;
      }

      const matchData = {
        title: gameResult.originalMatchId ? `Partida Simulada ${gameResult.originalMatchId}` : 'Partida Customizada',
        description: gameResult.detectedByLCU ? 'Partida detectada via LCU' : 'Partida manual',
        team1Players: gameResult.team1.map((p: any) => p.summonerName || p.name),
        team2Players: gameResult.team2.map((p: any) => p.summonerName || p.name),
        createdBy: this.currentPlayer?.summonerName || 'Sistema',
        matchLeader: 'popcorn seller#coup', // ✅ SEMPRE popcorn seller#coup para partidas simuladas
        gameMode: '5v5',
        winnerTeam,
        duration: gameResult.duration,
        pickBanData: gameResult.pickBanData,
        participantsData: gameResult.originalMatchData?.participants || [],
        riotGameId: gameResult.originalMatchId?.toString(),
        detectedByLCU: gameResult.detectedByLCU,
        status: 'completed'
      };

      console.log('💾 [App] Dados preparados para salvar:', matchData);

      // Salvar via API
      this.apiService.saveCustomMatch(matchData).subscribe({
        next: (response) => {
          console.log('✅ [App] Resultado salvo no banco:', response);
          this.addNotification('success', 'Resultado Salvo', 'Dados da partida foram salvos no histórico');
        },
        error: (error) => {
          console.error('❌ [App] Erro ao salvar resultado:', error);
          this.addNotification('error', 'Erro ao Salvar', 'Não foi possível salvar o resultado da partida');
        }
      });

    } catch (error) {
      console.error('❌ [App] Erro ao preparar dados para salvar:', error);
      this.addNotification('error', 'Erro Interno', 'Erro ao processar dados da partida');
    }
  }

  onGameCancel(): void {
    console.log('🚪 [App] ========== INÍCIO DO onGameCancel ==========');
    console.log('🚪 [App] Jogo cancelado - MÉTODO CORRETO CHAMADO');
    console.log('🚪 [App] ========== VERIFICANDO SE ESTE LOG APARECE ==========');
    console.log('🔍 [App] DEBUG - gameData:', this.gameData);
    console.log('🔍 [App] DEBUG - gameData.originalMatchId:', this.gameData?.originalMatchId);
    console.log('🔍 [App] DEBUG - gameData.matchId:', this.gameData?.matchId);
    console.log('🔍 [App] DEBUG - gameData.gameData:', this.gameData?.gameData);
    console.log('🔍 [App] DEBUG - gameData.gameData?.matchId:', this.gameData?.gameData?.matchId);
    console.log('🔍 [App] DEBUG - gameData.data:', this.gameData?.data);
    console.log('🔍 [App] DEBUG - gameData.data?.matchId:', this.gameData?.data?.matchId);
    console.log('🔍 [App] DEBUG - gameData completo:', JSON.stringify(this.gameData, null, 2));

    // ✅ CORREÇÃO: Notificar backend sobre cancelamento ANTES de limpar estado
    let matchIdToUse = null;

    // ✅ PRIORIDADE 1: originalMatchId (mais confiável)
    if (this.gameData?.originalMatchId) {
      matchIdToUse = this.gameData.originalMatchId;
      console.log(`📤 [App] Usando originalMatchId: ${matchIdToUse}`);
    }
    // ✅ PRIORIDADE 2: matchId direto
    else if (this.gameData?.matchId) {
      matchIdToUse = this.gameData.matchId;
      console.log(`📤 [App] FALLBACK: Usando matchId: ${matchIdToUse}`);
    }
    // ✅ PRIORIDADE 3: matchId aninhado em gameData
    else if (this.gameData?.gameData?.matchId) {
      matchIdToUse = this.gameData.gameData.matchId;
      console.log(`📤 [App] FALLBACK: Usando gameData.matchId: ${matchIdToUse}`);
    }
    // ✅ PRIORIDADE 4: matchId do gameData do backend
    else if (this.gameData?.data?.matchId) {
      matchIdToUse = this.gameData.data.matchId;
      console.log(`📤 [App] FALLBACK: Usando data.matchId: ${matchIdToUse}`);
    }
    // ✅ PRIORIDADE 5: Busca profunda em todos os objetos aninhados
    else {
      console.log(`🔍 [App] Busca profunda por matchId...`);
      const deepSearch = (obj: any, path: string = ''): any => {
        if (!obj || typeof obj !== 'object') return null;

        // Verificar se este objeto tem matchId
        if (obj.matchId !== undefined) {
          console.log(`🔍 [App] MatchId encontrado em ${path}: ${obj.matchId}`);
          return obj.matchId;
        }

        // Verificar se este objeto tem id
        if (obj.id !== undefined && typeof obj.id === 'number') {
          console.log(`🔍 [App] ID encontrado em ${path}: ${obj.id}`);
          return obj.id;
        }

        // Buscar recursivamente em todas as propriedades
        for (const [key, value] of Object.entries(obj)) {
          if (typeof value === 'object' && value !== null) {
            const result = deepSearch(value, `${path}.${key}`);
            if (result !== null) return result;
          }
        }

        return null;
      };

      const deepMatchId = deepSearch(this.gameData, 'gameData');
      if (deepMatchId !== null) {
        matchIdToUse = deepMatchId;
        console.log(`📤 [App] FALLBACK: Usando matchId da busca profunda: ${matchIdToUse}`);
      }
    }

    if (matchIdToUse) {
      console.log(`📤 [App] Enviando cancelamento de jogo para backend: ${matchIdToUse}`);

      // ✅ CORREÇÃO: Enviar mensagem WebSocket para cancelar jogo
      this.apiService.sendWebSocketMessage({
        type: 'cancel_game_in_progress',
        data: {
          matchId: matchIdToUse,
          reason: 'Cancelado pelo usuário'
        }
      });

      console.log(`✅ [App] Mensagem de cancelamento enviada para backend`);
    } else {
      console.error('❌ [App] Nenhum ID de partida disponível para cancelamento');
      console.error('❌ [App] gameData é null ou não tem IDs válidos');
      console.error('❌ [App] Estrutura do gameData:', {
        hasGameData: !!this.gameData,
        hasOriginalMatchId: !!this.gameData?.originalMatchId,
        hasMatchId: !!this.gameData?.matchId,
        hasGameDataMatchId: !!this.gameData?.gameData?.matchId,
        hasDataMatchId: !!this.gameData?.data?.matchId
      });

      // ✅ CORREÇÃO: Tentar usar o último matchId conhecido como fallback
      if (this.lastMatchId) {
        console.log(`📤 [App] FALLBACK FINAL: Usando lastMatchId: ${this.lastMatchId}`);
        this.apiService.sendWebSocketMessage({
          type: 'cancel_game_in_progress',
          data: {
            matchId: this.lastMatchId,
            reason: 'Cancelado pelo usuário (fallback)'
          }
        });
        console.log(`✅ [App] Mensagem de cancelamento enviada com fallback`);
      } else {
        this.addNotification('error', 'Erro', 'Não foi possível cancelar o jogo - ID da partida não encontrado');
      }
    }

    // ✅ CORREÇÃO: Aguardar um pouco antes de limpar o estado para garantir que a mensagem seja enviada
    setTimeout(() => {
      // Limpar estado local
      this.inGamePhase = false;
      this.gameData = null;
      this.currentView = 'dashboard';

      // Adicionar notificação
      this.addNotification('info', 'Jogo Cancelado', 'O jogo foi cancelado e você retornará à fila.');
    }, 100);
  }

  refreshLCUConnection(): void {
    console.log('🔄 [App] Atualizando conexão LCU');
    this.startLCUStatusCheck();
  }

  savePlayerSettings(): void {
    console.log('💾 [App] Salvando configurações do jogador:', this.settingsForm);

    if (!this.currentPlayer) {
      this.addNotification('warning', 'Nenhum Jogador', 'Carregue os dados do jogador primeiro');
      return;
    }

    // Atualizar dados do jogador atual
    if (this.settingsForm.summonerName) {
      // Se o nome foi editado manualmente, usar como está
      this.currentPlayer.summonerName = this.settingsForm.summonerName;
    }

    if (this.settingsForm.region) {
      this.currentPlayer.region = this.settingsForm.region;
    }

    // Salvar configurações no backend
    this.apiService.saveSettings({
      summonerName: this.currentPlayer.summonerName,
      region: this.currentPlayer.region,
      gameName: this.currentPlayer.gameName,
      tagLine: this.currentPlayer.tagLine
    }).subscribe({
      next: () => {
        // Salvar no localStorage também
        localStorage.setItem('currentPlayer', JSON.stringify(this.currentPlayer));
        this.addNotification('success', 'Configurações Salvas', 'Suas preferências foram atualizadas no backend');
      },
      error: (error) => {
        console.error('❌ [App] Erro ao salvar configurações:', error);
        this.addNotification('error', 'Erro ao Salvar', 'Não foi possível salvar as configurações');
      }
    });
  }

  onProfileIconError(event: Event): void {
    console.warn('⚠️ [App] Erro ao carregar ícone de perfil:', event);
  }

  refreshPlayerData(): void {
    console.log('🔄 [App] Atualizando dados do jogador');
    this.currentPlayer = null; // Limpar dados antigos
    this.loadPlayerData();
    this.addNotification('info', 'Dados Atualizados', 'Dados do jogador foram recarregados do LCU');
  }

  clearPlayerData(): void {
    console.log('🗑️ [App] Limpando dados do jogador');
    this.currentPlayer = null;
    localStorage.removeItem('currentPlayer');
    this.addNotification('info', 'Dados Limpos', 'Dados do jogador foram removidos');
  }

  updateRiotApiKey(): void {
    console.log('🔑 [App] Atualizando Riot API Key:', this.settingsForm.riotApiKey);

    if (!this.settingsForm.riotApiKey || this.settingsForm.riotApiKey.trim() === '') {
      this.addNotification('warning', 'API Key Vazia', 'Digite uma API Key válida');
      return;
    }

    this.apiService.setRiotApiKey(this.settingsForm.riotApiKey).subscribe({
      next: (response) => {
        console.log('✅ [App] Riot API Key atualizada:', response);
        this.addNotification('success', 'API Key Configurada', 'Riot API Key foi salva no backend');
      },
      error: (error) => {
        console.error('❌ [App] Erro ao configurar API Key:', error);
        this.addNotification('error', 'Erro API Key', 'Não foi possível salvar a API Key');
      }
    });
  }

  updateDiscordBotToken(): void {
    console.log('🤖 [App] Atualizando Discord Bot Token:', this.settingsForm.discordBotToken);

    if (!this.settingsForm.discordBotToken || this.settingsForm.discordBotToken.trim() === '') {
      this.addNotification('warning', 'Token Vazio', 'Digite um token do Discord Bot válido');
      return;
    }

    this.apiService.setDiscordBotToken(this.settingsForm.discordBotToken).subscribe({
      next: (response) => {
        console.log('✅ [App] Discord Bot Token atualizado:', response);
        this.addNotification('success', 'Bot Configurado', 'Discord Bot Token foi salvo e o bot está sendo reiniciado');

        // Atualizar status do Discord após um delay
        setTimeout(() => {
          this.setupDiscordStatusListener();
        }, 3000);
      },
      error: (error) => {
        console.error('❌ [App] Erro ao configurar Discord Bot:', error);
        this.addNotification('error', 'Erro Discord Bot', 'Não foi possível salvar o token do bot');
      }
    });
  }

  updateDiscordChannel(): void {
    console.log('📢 [App] Atualizando canal do Discord:', this.settingsForm.discordChannel);

    if (!this.settingsForm.discordChannel || this.settingsForm.discordChannel.trim() === '') {
      this.addNotification('warning', 'Canal Vazio', 'Digite o nome de um canal válido');
      return;
    }

    this.apiService.setDiscordChannel(this.settingsForm.discordChannel).subscribe({
      next: (response) => {
        console.log('✅ [App] Canal do Discord atualizado:', response);
        this.addNotification('success', 'Canal Configurado', `Canal '${this.settingsForm.discordChannel}' foi configurado para matchmaking`);
      },
      error: (error) => {
        console.error('❌ [App] Erro ao configurar canal:', error);
        this.addNotification('error', 'Erro Canal', 'Não foi possível configurar o canal');
      }
    });
  }

  isSpecialUser(): boolean {
    // Usuários especiais que têm acesso às ferramentas de desenvolvimento
    const specialUsers = [
      'Admin',
      'wcaco#BR1',
      'developer#DEV',
      'test#TEST',
      'popcorn seller#coup',
      'popcorn seller',  // Variação sem tag
      'popcorn seller#COUP'  // Variação com tag maiúscula
    ];

    if (this.currentPlayer) {
      // ✅ CORREÇÃO: Verificar múltiplas variações do nome
      const playerIdentifiers = this.getCurrentPlayerIdentifiers();

      const isSpecial = specialUsers.some(specialUser =>
        playerIdentifiers.some(identifier => {
          // Comparação exata
          if (identifier === specialUser) return true;

          // Comparação case-insensitive
          if (identifier.toLowerCase() === specialUser.toLowerCase()) return true;

          // Comparação por gameName (ignorando tag)
          if (identifier.includes('#') && specialUser.includes('#')) {
            const gameName1 = identifier.split('#')[0].toLowerCase();
            const gameName2 = specialUser.split('#')[0].toLowerCase();
            return gameName1 === gameName2;
          }

          // Comparação de gameName com nome completo
          if (identifier.includes('#')) {
            const gameName = identifier.split('#')[0].toLowerCase();
            return gameName === specialUser.toLowerCase();
          }

          return false;
        })
      );

      console.log(`🔍 [App] Verificação de usuário especial:`, {
        currentPlayerName: this.currentPlayer.summonerName,
        playerIdentifiers,
        isSpecialUser: isSpecial,
        specialUsers: specialUsers
      });
      return isSpecial;
    }

    return false;
  }

  simulateLastMatch(): void {
    logApp('[simular game] 🎮 Iniciando simulação da última partida ranqueada');
    logApp('[simular game] Current player:', this.currentPlayer);

    if (!this.currentPlayer) {
      this.addNotification('warning', 'Nenhum Jogador', 'Carregue os dados do jogador primeiro');
      return;
    }

    // ✅ Buscar histórico e processar partida
    this.fetchAndProcessMatchHistory();
  }

  // ✅ NOVO: Buscar histórico de partidas do LCU
  private fetchAndProcessMatchHistory(): void {
    logApp('[simular game] Chamando getLCUMatchHistoryAll com customOnly=false...');

    this.apiService.getLCUMatchHistoryAll(0, 20, false).subscribe({
      next: (response) => {
        logApp('[simular game] Resposta completa do LCU Match History All:', JSON.stringify(response, null, 2));
        this.processMatchHistoryResponse(response);
      },
      error: (error) => {
        logApp('❌ [App] Erro ao buscar histórico de partidas:', error);
        this.addNotification('error', 'Erro na Simulação', 'Não foi possível buscar o histórico de partidas');
      }
    });
  }

  // ✅ NOVO: Processar resposta do histórico de partidas
  private processMatchHistoryResponse(response: any): void {
    const matches = response?.matches || response?.games || [];
    logApp('[simular game] Matches encontrados:', matches.length);

    if (!matches || matches.length === 0) {
      this.addNotification('warning', 'Nenhuma Partida', 'Nenhuma partida encontrada no histórico');
      return;
    }

    // Buscar partida ranqueada ou usar a última disponível
    const targetMatch = this.findBestMatchToSimulate(matches);
    this.createSimulationFromMatch(targetMatch, matches.length);
  }

  // ✅ NOVO: Encontrar melhor partida para simular
  private findBestMatchToSimulate(matches: any[]): any {
    // Priorizar partidas ranqueadas
    const rankedMatch = matches.find((game: any) =>
      game.queueId === 440 || // RANKED_FLEX_SR
      game.queueId === 420    // RANKED_SOLO_5x5
    );

    if (rankedMatch) {
      logApp('[simular game] Partida ranqueada encontrada:', rankedMatch);
      return rankedMatch;
    }

    // Fallback para última partida
    const lastMatch = matches[0];
    logApp('🎮 [App] Nenhuma partida ranqueada encontrada, usando última partida:', lastMatch);
    return lastMatch;
  }

  // ✅ NOVO: Criar simulação a partir da partida escolhida
  private createSimulationFromMatch(match: any, totalMatches: number): void {
    const isRanked = match.queueId === 440 || match.queueId === 420;

    // ✅ CORREÇÃO: Extrair lógica de ternário aninhado
    let matchType: string;
    if (isRanked) {
      matchType = match.queueId === 440 ? 'Flex' : 'Solo/Duo';
    } else {
      matchType = 'Normal';
    }

    logApp(`[simular game] Simulando partida ${matchType} do LCU:`, match);
    logApp(`[simular game] Total de partidas encontradas: ${totalMatches}`);

    this.addNotification('info', 'Simulação Iniciada',
      `Buscando dados da partida ${matchType} (ID: ${match.gameId})...`);

    const playerIdentifier = this.currentPlayer?.summonerName || '';
    if (!playerIdentifier) {
      this.addNotification('error', 'Erro na Simulação', 'Nome do jogador não disponível.');
      return;
    }

    this.apiService.createLCUBasedMatch({
      lcuMatchData: match,
      playerIdentifier: playerIdentifier
    }).subscribe({
      next: (createResponse) => {
        logApp('[simular game] ✅ Partida customizada criada:', createResponse);

        this.addNotification('success', 'Simulação Criada',
          `Partida ${matchType} simulada com sucesso! Match ID: ${createResponse.matchId}`);

        this.startGameInProgressFromSimulation(createResponse);
      },
      error: (createError) => {
        logApp('❌ [App] Erro ao criar partida customizada:', createError);
        this.addNotification('error', 'Erro na Simulação',
          'Não foi possível criar a partida customizada. Verifique se o LoL está aberto.');
      }
    });
  }

  // ✅ NOVO: Método para iniciar GameInProgress com dados da simulação
  private startGameInProgressFromSimulation(simulationData: any): void {
    logApp('[simular game] 🎮 Iniciando GameInProgress com dados da simulação:', simulationData);

    try {
      // Extrair dados da resposta da simulação
      const matchId = simulationData.matchId;
      const gameId = simulationData.gameId;
      const participantsCount = simulationData.participantsCount;

      // ✅ NOVO: Buscar dados completos da partida criada
      const playerName = this.currentPlayer?.summonerName || '';
      if (!playerName) {
        this.addNotification('error', 'Erro na Simulação', 'Nome do jogador não disponível.');
        return;
      }

      this.apiService.getCustomMatches(playerName, 0, 1).subscribe({
        next: (matchesResponse) => {
          logApp('[simular game] Dados da partida criada:', matchesResponse);
          logApp('[simular game] Estrutura completa da resposta:', JSON.stringify(matchesResponse, null, 2));

          const matches = matchesResponse.matches || [];
          logApp('[simular game] Matches encontrados:', matches.length);

          if (matches.length > 0) {
            const latestMatch = matches[0]; // A partida mais recente (que acabamos de criar)
            console.log('[simular game] Dados da partida mais recente:', latestMatch);
            console.log('[simular game] Pick/ban data da partida:', latestMatch.pick_ban_data);

            // ✅ NOVO: Preparar dados para o GameInProgress
            // ✅ CORREÇÃO: Extrair pickBanData da partida real
            let extractedPickBanData = {};
            try {
              if (latestMatch.pick_ban_data) {
                extractedPickBanData = typeof latestMatch.pick_ban_data === 'string'
                  ? JSON.parse(latestMatch.pick_ban_data)
                  : latestMatch.pick_ban_data;
                console.log('[simular game] Pick/ban data extraída da partida real:', extractedPickBanData);
              }
            } catch (parseError) {
              console.warn('[simular game] Erro ao parsear pick_ban_data da partida real:', parseError);
            }

            const gameData = {
              sessionId: `simulation_${matchId}`,
              gameId: gameId?.toString() || `sim_${matchId}`,
              team1: this.extractTeamFromMatchData(latestMatch, 1),
              team2: this.extractTeamFromMatchData(latestMatch, 2),
              startTime: new Date(),
              pickBanData: extractedPickBanData, // ✅ CORREÇÃO: Usar dados reais da partida
              isCustomGame: true,
              originalMatchId: matchId,
              originalMatchData: latestMatch,
              riotId: this.currentPlayer?.summonerName || ''
            };

            console.log('[simular game] Dados preparados para GameInProgress:', gameData);

            // ✅ NOVO: Iniciar fase de jogo
            console.log('[simular game] Definindo inGamePhase = true');
            this.inGamePhase = true;
            console.log('[simular game] Definindo gameData:', gameData);
            this.gameData = gameData;
            console.log('[simular game] Definindo inDraftPhase = false');
            this.inDraftPhase = false; // Garantir que não está em draft
            this.draftData = null;
            console.log('[simular game] Definindo currentView = dashboard');
            this.currentView = 'dashboard'; // Garantir que estamos na view correta

            // ✅ NOVO: Forçar detecção de mudanças
            console.log('[simular game] Forçando detecção de mudanças...');
            this.cdr.detectChanges();

            // ✅ NOVO: Usar setTimeout para garantir que a mudança seja aplicada
            setTimeout(() => {
              console.log('[simular game] ⏰ Verificação após timeout:');
              console.log('[simular game] - inGamePhase:', this.inGamePhase);
              console.log('[simular game] - inDraftPhase:', this.inDraftPhase);
              console.log('[simular game] - gameData existe:', !!this.gameData);
              this.cdr.detectChanges();
            }, 100);

            console.log('[simular game] ✅ GameInProgress iniciado com sucesso:', {
              inGamePhase: this.inGamePhase,
              inDraftPhase: this.inDraftPhase,
              hasGameData: !!this.gameData,
              team1Length: this.gameData?.team1?.length || 0,
              team2Length: this.gameData?.team2?.length || 0,
              currentView: this.currentView
            });

            // ✅ NOVO: Verificar se as condições do template estão corretas
            console.log('[simular game] 🔍 Verificação das condições do template:');
            console.log('[simular game] - inGamePhase:', this.inGamePhase);
            console.log('[simular game] - inDraftPhase:', this.inDraftPhase);
            console.log('[simular game] - !inDraftPhase && !inGamePhase:', !this.inDraftPhase && !this.inGamePhase);
            console.log('[simular game] - currentView:', this.currentView);
            console.log('[simular game] - gameData existe:', !!this.gameData);

            this.addNotification('success', 'Simulação Ativa',
              `Partida simulada iniciada! ${participantsCount} jogadores, ${gameData.team1.length} vs ${gameData.team2.length}`);

          } else {
            console.error('❌ [App] Nenhuma partida encontrada após criação');
            this.addNotification('error', 'Erro na Simulação', 'Partida criada mas não foi possível carregar os dados.');
          }
        },
        error: (matchesError) => {
          console.error('❌ [App] Erro ao buscar dados da partida criada:', matchesError);
          this.addNotification('error', 'Erro na Simulação', 'Partida criada mas não foi possível carregar os dados.');
        }
      });

    } catch (error) {
      console.error('❌ [App] Erro ao preparar dados para GameInProgress:', error);
      this.addNotification('error', 'Erro na Simulação', 'Erro interno ao preparar dados da partida.');
    }
  }

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
      lastMatchId: this.lastMatchId
    });

    this.showMatchFound = false;
    this.inDraftPhase = false;
    this.inGamePhase = false;
    this.matchFoundData = null;
    this.draftData = null;
    this.gameData = null;
    this.lastMatchId = null;

    console.log('🔄 [App] Estados limpos com sucesso');
  }

  // ✅ NOVO: Garantir que está na fila
  private ensureInQueue(): void {
    if (!this.isInQueue) {
      this.isInQueue = true;
      console.log('🔄 [App] Voltando para fila');
    }
  }
}

// Handler global para erros e rejeições não tratadas
window.onerror = function (message, source, lineno, colno, error) {
  logApp('❌ [GLOBAL ERROR]', message, source, lineno, colno, error);
};
window.onunhandledrejection = function (event) {
  logApp('❌ [UNHANDLED PROMISE REJECTION]', event.reason);
};
