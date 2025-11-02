import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef, NgZone, signal, computed, effect, input, output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, Observable } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { Player, QueueStatus, QueuePreferences } from '../../interfaces';
import { DiscordIntegrationService } from '../../services/discord-integration.service';
import { QueueStateService } from '../../services/queue-state';
import { LaneSelectorComponent } from '../lane-selector/lane-selector';
import { ApiService } from '../../services/api';
import { ProfileIconService } from '../../services/profile-icon.service';

@Component({
  selector: 'app-queue',
  standalone: true,
  imports: [CommonModule, FormsModule, LaneSelectorComponent],
  templateUrl: './queue.html',
  styleUrl: './queue.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class QueueComponent implements OnInit, OnDestroy {
  // =============================================================================
  // DEPENDENCY INJECTION (inject())
  // =============================================================================
  public readonly discordService = inject(DiscordIntegrationService);
  private readonly queueStateService = inject(QueueStateService);
  private readonly apiService = inject(ApiService);
  private readonly profileIconService = inject(ProfileIconService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly ngZone = inject(NgZone);

  // =============================================================================
  // INPUTS & OUTPUTS (input() & output())
  // =============================================================================
  isInQueue = input<boolean>(false);
  queueStatus = input<QueueStatus>({
    playersInQueue: 0,
    averageWaitTime: 0,
    estimatedMatchTime: 0,
    isActive: true
  });
  currentPlayer = input<Player | null>(null);
  inDraftPhase = input<boolean>(false); // ✅ Evita loops de reload quando em draft
  inGamePhase = input<boolean>(false);  // ✅ Evita loops de reload quando em game

  joinQueue = output<QueuePreferences>();
  leaveQueue = output<void>();
  joinDiscordQueueWithFullData = output<{ player: Player | null, preferences: QueuePreferences }>();
  refreshData = output<void>();
  autoRefreshToggle = output<boolean>();

  // =============================================================================
  // COMPONENT STATE (signal())
  // =============================================================================
  // Timer (gerenciado pelo backend, mas exibido localmente)
  queueTimer = signal<number>(0);
  private timerInterval?: number;

  // Lane selector
  showLaneSelector = signal<boolean>(false);
  queuePreferences = signal<QueuePreferences>({
    primaryLane: '',
    secondaryLane: ''
  });

  // Discord Integration (dados vindos do backend)
  isDiscordConnected = signal<boolean>(false);
  discordUsersOnline = signal<any[]>([]);

  // ✅ Indicador de dados stale
  isDataStale = signal<boolean>(false);

  // UI state
  activeTab = signal<'queue' | 'lobby' | 'all'>('all');
  isRefreshing = signal<boolean>(false);

  // Cleanup
  private readonly destroy$ = new Subject<void>();

  // ✅ Timer para atualizar tempos dos jogadores na fila
  private playersTimeInterval?: number;

  // ✅ Scheduler para verificar partida ativa (reconexão/redirecionamento)
  private activeMatchCheckInterval?: number;
  private readonly ACTIVE_MATCH_CHECK_INTERVAL_MS = 5000; // 5 segundos

  // =============================================================================
  // COMPUTED SIGNALS (valores derivados)
  // =============================================================================
  canJoinDiscordQueueComputed = computed(() => {
    const inQueue = this.isInQueue();
    const player = this.currentPlayer();
    const connected = this.isDiscordConnected();
    const status = this.queueStatus();

    if (inQueue) return false;
    if (!player?.displayName) return false;
    if (!connected) return false;
    if (!status.isActive) return false;

    return true;
  });

  timerDisplay = computed(() => {
    const timer = this.queueTimer();
    const minutes = Math.floor(timer / 60);
    const seconds = timer % 60;
    return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
  });

  estimatedTimeText = computed(() => {
    const status = this.queueStatus();
    if (!status.estimatedMatchTime) return 'Calculando...';

    const minutes = Math.floor(status.estimatedMatchTime / 60);
    const seconds = status.estimatedMatchTime % 60;

    return minutes > 0 ? `~${minutes}m ${seconds}s` : `~${seconds}s`;
  });

  queueHealthColor = computed(() => {
    const status = this.queueStatus();
    if (!status.isActive) return '#ff4444';
    if (status.playersInQueue >= 10) return '#44ff44';
    if (status.playersInQueue >= 5) return '#ffaa44';
    return '#ff8844';
  });

  playerRankDisplay = computed(() => {
    const player = this.currentPlayer();
    return player?.customLp ? `${player.customLp} LP` : '0 LP';
  });

  playerTag = computed(() => {
    const player = this.currentPlayer();
    return player?.tagLine || '';
  });

  // =============================================================================
  // CONSTRUCTOR & EFFECTS
  // =============================================================================
  constructor() {
    // Effect 1: Reagir a mudanças em currentPlayer
    effect(() => {
      const newPlayer = this.currentPlayer();
      if (newPlayer) {
        console.log('🔄 [Queue] CurrentPlayer atualizado via effect');
        this.handleCurrentPlayerChange(newPlayer);
      }
    });

    // Effect 2: Reagir a mudanças em queueStatus
    effect(() => {
      const status = this.queueStatus();
      console.log('🔄 [Queue] QueueStatus atualizado via effect:', status);
      this.handleQueueStatusChange(status);
    });

    // Effect 3: Reagir a mudanças em isInQueue
    effect(() => {
      const inQueue = this.isInQueue();
      console.log(`🎯 [Queue] Estado isInQueue mudou para: ${inQueue}`);

      if (inQueue && !this.timerInterval) {
        this.startQueueTimer();
      } else if (!inQueue && this.timerInterval) {
        this.stopQueueTimer();
        this.queueTimer.set(0);
      }
    });

    // Effect 4: Parar verificação de active match se entrar em draft/game
    effect(() => {
      const inDraft = this.inDraftPhase();
      const inGame = this.inGamePhase();

      if (inDraft || inGame) {
        console.log('🛑 [Queue] Jogador entrou em draft/game, parando active match check');
        this.stopActiveMatchCheck();
      }
    });

    // Effect 5: Atualizar dados stale do Discord periodicamente
    effect(() => {
      const connected = this.isDiscordConnected();
      if (connected) {
        // Setup já está em setupDiscordListeners
      }
    });
  }

  // =============================================================================
  // LIFECYCLE METHODS
  // =============================================================================
  ngOnInit(): void {
    console.log('🎯 [Queue] Componente inicializado');

    // ✅ Verificar e conectar WebSocket se necessário
    if (!this.apiService.isWebSocketConnected()) {
      console.log('🔄 [Queue] WebSocket não conectado, forçando conexão...');
      this.apiService.connect().subscribe();
    }

    this.setupDiscordListeners();
    this.setupQueueStateListener();

    const inQueue = this.isInQueue();
    if (inQueue) {
      this.startQueueTimer();
    }

    // ✅ Iniciar timer para atualizar tempos dos jogadores na fila
    this.startPlayersTimeUpdate();

    // ✅ Iniciar verificação de partida ativa (reconexão automática)
    this.startActiveMatchCheck();
  }

  ngOnDestroy(): void {
    console.log('🛑 [Queue] Componente destruído');

    this.destroy$.next();
    this.destroy$.complete();
    this.cleanup();

    // ✅ Parar verificação de partida ativa
    this.stopActiveMatchCheck();

    console.log('✅ [Queue] Cleanup completo');
  }

  // =============================================================================
  // PRIVATE HANDLERS FOR EFFECTS
  // =============================================================================
  private handleCurrentPlayerChange(newPlayer: Player): void {
    console.log('🔄 [Queue] CurrentPlayer atualizado');
    this.queueStateService.updateCurrentPlayer(newPlayer);

    // Enviar dados LCU para Discord (backend gerencia a vinculação)
    if (newPlayer?.displayName) {
      console.log('🎮 [Queue] Enviando dados do LCU para identificação Discord...');
      this.discordService.sendLCUData({
        displayName: newPlayer.displayName
      });
    }
  }

  private handleQueueStatusChange(status: QueueStatus): void {
    const player = this.currentPlayer();

    // ✅ Verificar se o jogador atual está na lista da fila
    if (player?.displayName && status?.playersInQueueList) {
      const playerInQueue = status.playersInQueueList.find((p: any) =>
        p.summonerName === player?.displayName ||
        p.summonerName === player?.summonerName ||
        p.isCurrentPlayer === true ||
        (p.summonerName && player?.displayName &&
          p.summonerName.replaceAll(/\s+/g, '').toLowerCase() === player.displayName.replaceAll(/\s+/g, '').toLowerCase())
      );

      const currentInQueue = this.isInQueue();

      if (playerInQueue && !currentInQueue) {
        console.log('🎯 [Queue] Jogador encontrado na fila, atualizando estado:', playerInQueue);
        // Note: isInQueue é input(), então não podemos atribuir diretamente
        // O componente pai deve gerenciar esse estado
        this.syncTimerWithPlayerData(playerInQueue);
        this.startQueueTimer();
      } else if (!playerInQueue && currentInQueue) {
        console.log('🎯 [Queue] Jogador não encontrado na fila');
        this.stopQueueTimer();
        this.queueTimer.set(0);
      } else if (playerInQueue && currentInQueue) {
        // ✅ Se já está na fila, sincronizar timer periodicamente sem resetar
        this.syncTimerWithPlayerData(playerInQueue, false);
      }
    }
  }

  private cleanup(): void {
    this.queueStateService.stopMySQLSync();
    // ✅ stopAutoRefresh() removido - WebSocket push em tempo real não precisa de polling
    this.stopQueueTimer();
    this.stopPlayersTimeUpdate();
    this.stopActiveMatchCheck(); // ✅ NOVO: Parar verificação de partida ativa
  }

  // =============================================================================
  // SETUP METHODS
  // =============================================================================
  private setupQueueStateListener(): void {
    this.queueStateService.getQueueState().pipe(
      takeUntil(this.destroy$)
    ).subscribe(state => {
      console.log('🔄 [Queue] Estado da fila atualizado via backend:', state);

      // Note: isInQueue é input(), então o componente pai deve gerenciar esse estado
      // Aqui apenas sincronizamos o timer

      if (state.isInQueue && !this.timerInterval) {
        this.startQueueTimer();
      } else if (!state.isInQueue) {
        this.stopQueueTimer();
        this.queueTimer.set(0);
      }
    });
  }

  private setupDiscordListeners(): void {
    console.log('🔗 [Queue] Configurando listeners Discord...');

    this.discordService.onConnectionChange().pipe(
      takeUntil(this.destroy$)
    ).subscribe(connected => {
      const currentConnected = this.isDiscordConnected();
      if (currentConnected !== connected) {
        this.isDiscordConnected.set(connected);
        console.log(`🔗 [Queue] Discord connection: ${connected}`);
      }
    });

    this.discordService.onUsersUpdate().pipe(
      takeUntil(this.destroy$)
    ).subscribe(users => {
      const currentUsers = this.discordUsersOnline();
      if (JSON.stringify(currentUsers) !== JSON.stringify(users)) {
        this.discordUsersOnline.set(users);
        console.log(`👥 [Queue] Discord users: ${users.length}`);
      }
    });

    // ✅ Verificar dados stale periodicamente
    setInterval(() => {
      this.isDataStale.set(this.discordService.isDataStaleIndicator());
      this.discordUsersOnline.set(this.discordService.getUsersWithFallback());
    }, 5000); // Verificar a cada 5 segundos

    this.discordService.checkConnection();
  }


  // Método chamado pelo template (compatibilidade)
  refreshPlayersData(): void {
    console.log('🔄 [Queue] refreshPlayersData chamado - forçando atualização completa');
    console.log('🔄 [Queue] Estado atual:', {
      isInQueue: this.isInQueue(),
      currentPlayer: this.currentPlayer()?.displayName,
      playersInQueue: this.queueStatus()?.playersInQueue
    });

    if (this.isRefreshing()) {
      console.log('⚠️ [Queue] Refresh já em andamento, ignorando...');
      return;
    }

    this.isRefreshing.set(true);
    console.log('🔄 [Queue] Iniciando refresh completo...');

    // ✅ NOVO: Sincronizar cache do backend com o banco
    this.apiService.refreshQueueCache().subscribe({
      next: (response: any) => {
        console.log('✅ [Queue] Cache do backend sincronizado:', response);
      },
      error: (err: any) => {
        console.error('❌ [Queue] Erro ao sincronizar cache:', err);
      }
    });

    // ✅ NOVO: Feedback visual imediato
    console.log('🔄 [Queue] Solicitando atualização completa do estado da fila e Discord ao componente pai...');
    this.refreshData.emit();

    // ✅ NOVO: Notificação visual para o usuário
    console.log('🔄 [Queue] Atualizando dados da fila e Discord...');

    // ✅ NOVO: Forçar atualização do QueueStateService
    const currentPlayerValue = this.currentPlayer();
    if (currentPlayerValue?.displayName) {
      console.log('🔄 [Queue] Atualizando QueueStateService com jogador atual...');
      this.queueStateService.updateCurrentPlayer(currentPlayerValue);
      this.queueStateService.forceSync();
    }

    // ✅ NOVO: Forçar detecção de mudanças imediatamente
    this.cdr.detectChanges();

    // Parar o indicador de loading após 3 segundos (tempo suficiente para backend responder)
    setTimeout(() => {
      this.isRefreshing.set(false);
      this.cdr.detectChanges();
      console.log('✅ [Queue] Refresh completo finalizado');
    }, 3000);
  }

  // =============================================================================
  // TIMER METHODS (UI apenas - backend gerencia tempo real na fila)
  // =============================================================================
  private startQueueTimer(): void {
    this.stopQueueTimer();

    console.log('⏱️ [Queue] Iniciando timer da fila');
    this.timerInterval = this.ngZone.runOutsideAngular(() => {
      return globalThis.setInterval(() => {
        this.ngZone.run(() => {
          // ✅ CORRIGIDO: Calcular tempo real baseado no joinTime do jogador atual
          this.updateQueueTimerFromCurrentPlayer();
          this.cdr.detectChanges();
        });
      }, 1000);
    });
  }

  private stopQueueTimer(): void {
    if (this.timerInterval) {
      console.log('⏱️ [Queue] Parando timer da fila');
      clearInterval(this.timerInterval);
      this.timerInterval = undefined;
    }
  }

  // ✅ NOVO: Atualizar timer baseado no tempo real do jogador atual na fila
  private updateQueueTimerFromCurrentPlayer(): void {
    const currentPlayerValue = this.currentPlayer();
    const queueStatusValue = this.queueStatus();

    if (!currentPlayerValue?.displayName || !queueStatusValue?.playersInQueueList) {
      return;
    }

    // Encontrar o jogador atual na lista da fila
    const currentPlayerInQueue = queueStatusValue.playersInQueueList.find((player: any) =>
      player.isCurrentPlayer === true ||
      player.summonerName === currentPlayerValue?.displayName ||
      (player.tagLine ? `${player.summonerName}#${player.tagLine}` : player.summonerName) === currentPlayerValue?.displayName
    );

    if (currentPlayerInQueue?.joinTime) {
      // ✅ CORRIGIDO: Calcular tempo baseado no joinTime do servidor
      const timeData = this.calculateTimeInQueue(currentPlayerInQueue.joinTime);
      this.queueTimer.set(timeData.seconds);
      console.log(`⏱️ [Queue] Timer atualizado: ${this.getTimerDisplay()} (${timeData.seconds}s)`);
    } else {
      // Se não encontrou o jogador na fila, incrementar timer local
      this.queueTimer.update(v => v + 1);
    }
  }

  getTimerDisplay(): string {
    const timer = this.queueTimer();
    const minutes = Math.floor(timer / 60);
    const seconds = timer % 60;
    return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
  }

  // =============================================================================
  // QUEUE ACTIONS (backend gerencia a lógica da fila)
  // =============================================================================
  onJoinQueue(): void {
    const queueStatusValue = this.queueStatus();
    if (!queueStatusValue.isActive) return;
    console.log('🎮 [Queue] Abrindo seletor de lanes...');
    console.log('🎮 [Queue] Estado antes de abrir modal:', {
      showLaneSelector: this.showLaneSelector(),
      isVisible: this.showLaneSelector()
    });
    this.showLaneSelector.set(true);
    console.log('🎮 [Queue] Modal definido como visível:', this.showLaneSelector());
    this.cdr.detectChanges(); // ✅ Forçar detecção de mudanças
  }

  // Métodos Discord chamados pelo template
  onJoinDiscordQueue(): void {
    console.log('🔍 [Queue] Entrada Discord solicitada');
    console.log('🔍 [Queue] Estado atual antes da entrada:', {
      isInQueue: this.isInQueue(),
      currentPlayer: this.currentPlayer()?.displayName,
      isDiscordConnected: this.isDiscordConnected(),
      canJoinQueue: this.canJoinDiscordQueue()
    });

    if (!this.canJoinDiscordQueue()) {
      console.warn('⚠️ [Queue] Não é possível entrar na fila - condições não atendidas');
      return;
    }

    console.log('🎮 [Queue] Todas as condições atendidas - abrindo seletor de lanes...');
    this.onJoinQueue();
  }

  /**
   * Verifica se o jogador pode entrar na fila Discord
   * Condições: LCU conectado + Discord bot ativo + Jogador no canal monitorado
   * ✅ CORRIGIDO: Método agora apenas verifica condições, não executa ações
   */
  canJoinDiscordQueue(): boolean {
    // Verificar se não está já na fila
    if (this.isInQueue()) {
      return false;
    }

    // Verificar se tem jogador atual
    const currentPlayerValue = this.currentPlayer();
    if (!currentPlayerValue?.displayName) {
      return false;
    }

    // Verificar se Discord está conectado
    if (!this.isDiscordConnected()) {
      return false;
    }

    // Verificar se sistema está ativo
    const queueStatusValue = this.queueStatus();
    if (!queueStatusValue.isActive) {
      return false;
    }

    // Adicionar verificação de canal monitorado quando implementado
    // Por enquanto, retorna true se todas as outras condições forem atendidas
    return true;
  }

  onLeaveDiscordQueue(): void {
    console.log('🔍 [Queue] Saída Discord solicitada');
    const currentPlayerValue = this.currentPlayer();
    console.log('🔍 [Queue] Estado atual antes da saída:', {
      isInQueue: this.isInQueue(),
      currentPlayer: currentPlayerValue?.displayName,
      isDiscordConnected: this.isDiscordConnected()
    });

    if (!currentPlayerValue?.displayName) {
      console.error('❌ [Queue] Dados do jogador não disponíveis para sair da fila');
      return;
    }

    console.log('✅ [Queue] Delegando saída da fila para o componente pai');
    this.onLeaveQueue();
  }

  onConfirmDiscordQueue(preferences: QueuePreferences): void {
    console.log('✅ [Queue] Confirmação Discord recebida');
    this.onConfirmJoinQueue(preferences);
  }

  onConfirmJoinQueue(preferences: QueuePreferences): void {
    console.log('✅ [Queue] Confirmando entrada na fila');
    const currentPlayerValue = this.currentPlayer();
    console.log('✅ [Queue] Estado atual na confirmação:', {
      isInQueue: this.isInQueue(),
      currentPlayer: currentPlayerValue?.displayName,
      preferences: preferences
    });

    this.queuePreferences.set(preferences);
    this.showLaneSelector.set(false);

    // Validações básicas (backend fará validações completas)
    if (!currentPlayerValue?.displayName) {
      alert('Erro: Dados do Riot ID não disponíveis. Certifique-se de que o League of Legends está aberto.');
      return;
    }

    if (!this.isDiscordConnected()) {
      alert('Erro: Discord não conectado. Verifique a conexão.');
      return;
    }

    // Usar o novo sistema de fila centralizado
    console.log('✅ [Queue] Entrando na fila via novo sistema centralizado');
    this.joinQueue.emit(preferences);

    console.log('✅ [Queue] Emissão de entrada na fila concluída');
  }

  onCloseLaneSelector(): void {
    this.showLaneSelector.set(false);
    this.cdr.detectChanges(); // ✅ Forçar detecção de mudanças
  }

  onLeaveQueue(): void {
    console.log('🔍 [Queue] Saindo da fila');
    this.leaveQueue.emit();
    this.stopQueueTimer();
    this.queueTimer.set(0);
  }

  // =============================================================================
  // DISPLAY UTILITIES (funções puras para UI)
  // =============================================================================
  getEstimatedTimeText(): string {
    const queueStatusValue = this.queueStatus();
    if (!queueStatusValue.estimatedMatchTime) return 'Calculando...';

    const minutes = Math.floor(queueStatusValue.estimatedMatchTime / 60);
    const seconds = queueStatusValue.estimatedMatchTime % 60;

    return minutes > 0 ? `~${minutes}m ${seconds}s` : `~${seconds}s`;
  }

  getQueueHealthColor(): string {
    const queueStatusValue = this.queueStatus();
    if (!queueStatusValue.isActive) return '#ff4444';
    if (queueStatusValue.playersInQueue >= 10) return '#44ff44';
    if (queueStatusValue.playersInQueue >= 5) return '#ffaa44';
    return '#ff8844';
  }

  getLaneName(laneId: string): string {
    const lanes: { [key: string]: string } = {
      'top': 'Topo',
      'jungle': 'Selva',
      'mid': 'Meio',
      'bot': 'Atirador',
      'adc': 'Atirador',
      'support': 'Suporte',
      'fill': 'Qualquer'
    };
    return lanes[laneId] || 'Qualquer';
  }

  getLaneIcon(laneId: string): string {
    const icons: { [key: string]: string } = {
      'top': '⚔️',
      'jungle': '🌲',
      'mid': '⭐',
      'bot': '🏹',
      'adc': '🏹',
      'support': '🛡️',
      'fill': '🎲'
    };
    return icons[laneId] || '🎲';
  }

  getPlayerRankDisplay(): string {
    const currentPlayerValue = this.currentPlayer();
    return currentPlayerValue?.customLp ? `${currentPlayerValue.customLp} LP` : '0 LP';
  }

  getPlayerTag(): string {
    const currentPlayerValue = this.currentPlayer();
    return currentPlayerValue?.tagLine || '';
  }

  getProfileIconUrl(player?: any): Observable<string> {
    const currentPlayerValue = this.currentPlayer();
    const identifier = player?.summonerName || currentPlayerValue?.displayName || '';
    // O serviço já lida com o caso de identificador vazio, retornando o ícone padrão.
    return this.profileIconService.getProfileIconUrl(identifier);
  }

  private fetchProfileIconForCurrentPlayer(): void {
    const currentPlayerValue = this.currentPlayer();
    if (currentPlayerValue?.displayName) {
      const identifier = currentPlayerValue.displayName;
      this.profileIconService.getOrFetchProfileIcon(identifier).subscribe({
        next: (iconId) => {
          if (iconId) {
            console.log(`[Queue] Ícone ${iconId} para ${identifier} carregado/buscado.`);
            this.cdr.detectChanges(); // Forçar atualização
          }
        },
        error: (err) => console.error(`[Queue] Erro ao buscar ícone para ${identifier}`, err)
      });
    }
  }

  onProfileIconError(event: Event): void {
    const target = event.target as HTMLImageElement;
    if (target) {
      // ✅ CORRIGIDO: Usar o método do ProfileIconService para fallbacks
      const currentPlayerValue = this.currentPlayer();
      const profileIconId = currentPlayerValue?.profileIconId ? Number(currentPlayerValue.profileIconId) : undefined;
      this.profileIconService.onProfileIconError(event, profileIconId);
    }
  }

  onProfileIconLoad(event: Event): void {
    console.log('✅ [Queue] Profile icon carregado com sucesso');
  }

  // =============================================================================
  // TABLE UTILITIES
  // =============================================================================
  setActiveTab(tab: 'queue' | 'lobby' | 'all'): void {
    this.activeTab.set(tab);
    this.cdr.detectChanges();
  }

  trackByPlayerId(index: number, player: any): string {
    return player?.id?.toString() || index.toString();
  }

  trackByDiscordUserId(index: number, user: any): string {
    return user?.id?.toString() || index.toString();
  }

  isCurrentPlayer(player: any): boolean {
    // ✅ PRIORIDADE 1: Usar campo isCurrentPlayer do backend (mais confiável)
    if (player?.isCurrentPlayer === true) {
      return true;
    }

    // ✅ FALLBACK: Comparação manual como backup
    const currentPlayerValue = this.currentPlayer();
    if (!currentPlayerValue?.displayName) {
      return false;
    }

    const currentDisplayName = currentPlayerValue.displayName;
    const playerFullName = player.tagLine ? `${player.summonerName}#${player.tagLine}` : player.summonerName;

    return playerFullName === currentDisplayName ||
      player.summonerName === currentDisplayName ||
      playerFullName.toLowerCase() === currentDisplayName.toLowerCase() ||
      player.summonerName.toLowerCase() === currentDisplayName.toLowerCase();
  }

  // ✅ NOVO: Método auxiliar para calcular tempo na fila (usado tanto pelo timer quanto pela tabela)
  private calculateTimeInQueue(joinTime: string | Date): { seconds: number, display: string } {
    try {
      const joinTimeDate = typeof joinTime === 'string' ? new Date(joinTime) : joinTime;
      const now = new Date();
      const diffMs = now.getTime() - joinTimeDate.getTime();

      if (diffMs < 0) {
        return { seconds: 0, display: '0s' };
      }

      const diffSeconds = Math.floor(diffMs / 1000);
      const minutes = Math.floor(diffSeconds / 60);
      const seconds = diffSeconds % 60;

      const display = minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;

      return { seconds: diffSeconds, display };
    } catch (error) {
      console.warn('⚠️ [Queue] Erro ao calcular tempo na fila:', error);
      return { seconds: 0, display: '0s' };
    }
  }

  getTimeInQueue(player: any): string {
    if (!player?.joinTime) {
      return '0s';
    }

    // ✅ CORRIGIDO: Usar método auxiliar para garantir consistência
    return this.calculateTimeInQueue(player.joinTime).display;
  }

  isUserInQueue(user: any): boolean {
    // ✅ CORRIGIDO: Verificar se o usuário Discord vinculado está na fila
    const queueStatusValue = this.queueStatus();
    if (!queueStatusValue?.playersInQueueList || !this.hasLinkedNickname(user)) {
      return false;
    }

    const linkedNickname = this.getLinkedNickname(user);
    if (!linkedNickname) {
      return false;
    }

    // ✅ PRIORIDADE 1: Se o usuário atual é o mesmo que está sendo checado e tem isCurrentPlayer=true
    const currentPlayerValue = this.currentPlayer();
    if (currentPlayerValue?.displayName &&
      (linkedNickname === currentPlayerValue.displayName ||
        linkedNickname.toLowerCase() === currentPlayerValue.displayName.toLowerCase())) {
      const currentPlayerInQueue = queueStatusValue.playersInQueueList.find((player: any) =>
        player.isCurrentPlayer === true
      );
      if (currentPlayerInQueue) {
        return true;
      }
    }

    // ✅ PRIORIDADE 2: Buscar o usuário na lista da fila usando o linkedNickname
    const playerInQueue = queueStatusValue.playersInQueueList.find((player: any) => {
      const playerFullName = player.tagLine ? `${player.summonerName}#${player.tagLine}` : player.summonerName;

      // Comparar diferentes formatos
      return playerFullName === linkedNickname ||
        player.summonerName === linkedNickname ||
        playerFullName.toLowerCase() === linkedNickname.toLowerCase() ||
        player.summonerName.toLowerCase() === linkedNickname.toLowerCase() ||
        (playerFullName.includes('#') && linkedNickname.includes('#') && playerFullName === linkedNickname) ||
        (playerFullName.includes('#') && !linkedNickname.includes('#') && playerFullName.startsWith(linkedNickname + '#')) ||
        (!playerFullName.includes('#') && linkedNickname.includes('#') && linkedNickname.startsWith(playerFullName + '#'));
    });

    const inQueue = !!playerInQueue;

    // ✅ DEBUG: Log apenas quando muda de estado
    if (user.lastQueueStatus !== inQueue) {
      console.log(`🔍 [Queue] Discord user ${user.username} queue status:`, {
        linkedNickname: linkedNickname,
        inQueue: inQueue,
        playerFound: playerInQueue ? playerInQueue.summonerName : 'none',
        isCurrentPlayer: playerInQueue?.isCurrentPlayer || false
      });
      user.lastQueueStatus = inQueue;
    }

    return inQueue;
  }

  // =============================================================================
  // DISCORD UTILITIES (backend gerencia vinculação)
  // =============================================================================
  hasLinkedNickname(user: any): boolean {
    // Verificar se tem linkedNickname (string ou objeto)
    if (user?.linkedNickname) {
      if (typeof user.linkedNickname === 'string') {
        return true;
      }
      if (typeof user.linkedNickname === 'object' &&
        user.linkedNickname.gameName &&
        user.linkedNickname.tagLine) {
        return true;
      }
    }

    // Verificar novo formato linkedDisplayName
    if (user?.linkedDisplayName && typeof user.linkedDisplayName === 'string') {
      return true;
    }

    return false;
  }

  getLinkedNickname(user: any): string {
    if (!user?.linkedNickname) {
      return '';
    }

    // Se for string (displayName direto), retornar
    if (typeof user.linkedNickname === 'string') {
      return user.linkedNickname;
    }

    // Se for objeto com {gameName, tagLine}, montar displayName
    if (typeof user.linkedNickname === 'object' &&
      user.linkedNickname.gameName &&
      user.linkedNickname.tagLine) {
      // ✅ CORREÇÃO: Verificar se tagLine já contém # para evitar duplicação
      const tagLine = user.linkedNickname.tagLine.startsWith('#')
        ? user.linkedNickname.tagLine
        : `#${user.linkedNickname.tagLine}`;
      return `${user.linkedNickname.gameName}${tagLine}`;
    }

    // Verificar se tem linkedDisplayName (novo formato)
    if (user.linkedDisplayName && typeof user.linkedDisplayName === 'string') {
      return user.linkedDisplayName;
    }

    // Fallback para casos inesperados
    console.warn('⚠️ [Queue] linkedNickname em formato inesperado:', user.linkedNickname);
    return '[Vinculado]';
  }

  // Métodos simplificados - backend gerencia convites e vinculação
  inviteToLink(user: any): void {
    console.log('🔗 [Queue] Use !vincular no Discord:', user.username);
  }

  inviteToQueue(user: any): void {
    console.log('📝 [Queue] Backend gerencia convites:', user.username);
  }

  // ✅ NOVO: Iniciar timer para atualizar tempos dos jogadores na fila
  private startPlayersTimeUpdate(): void {
    // Atualizar a cada 1 segundo para mostrar tempos em tempo real
    this.playersTimeInterval = setInterval(() => {
      // Forçar detecção de mudanças para atualizar os tempos exibidos
      this.cdr.detectChanges();
    }, 1000);

    console.log('🔄 [Queue] Timer de atualização de tempos dos jogadores iniciado');
  }

  // ✅ NOVO: Parar timer de atualização de tempos
  private stopPlayersTimeUpdate(): void {
    if (this.playersTimeInterval) {
      clearInterval(this.playersTimeInterval);
      this.playersTimeInterval = undefined;
      console.log('🛑 [Queue] Timer de atualização de tempos dos jogadores parado');
    }
  }

  // =============================================================================
  // ✅ NOVO: VERIFICAÇÃO DE PARTIDA ATIVA (RECONEXÃO AUTOMÁTICA)
  // =============================================================================

  /**
   * ✅ NOVO: Inicia verificação periódica de partida ativa
   * Usado para reconectar player que fechou Electron durante draft/game
   */
  private startActiveMatchCheck(): void {
    const currentPlayerValue = this.currentPlayer();
    if (!currentPlayerValue?.displayName && !currentPlayerValue?.summonerName) {
      console.warn('⚠️ [Queue] currentPlayer não definido, scheduler não iniciado');
      return;
    }

    // ✅ CRÍTICO: NÃO iniciar se já estamos em draft/game!
    if (this.inDraftPhase() || this.inGamePhase()) {
      console.log('⏭️ [Queue] JÁ em draft/game, não iniciando active match check');
      return;
    }

    const summonerName = currentPlayerValue.displayName || currentPlayerValue.summonerName;
    console.log('✅ [Queue] Iniciando verificação de partida ativa (5s) para:', summonerName);

    // ✅ CRÍTICO: DELAY na primeira verificação para garantir que @Input() foram definidos!
    // Isso evita que o check execute ANTES de inDraftPhase/inGamePhase serem passados do pai
    setTimeout(() => {
      this.checkForActiveMatch();
    }, 500); // 500ms de delay para inputs serem definidos

    // ✅ Verificações periódicas
    this.activeMatchCheckInterval = globalThis.setInterval(() => {
      this.checkForActiveMatch();
    }, this.ACTIVE_MATCH_CHECK_INTERVAL_MS);
  }

  /**
   * ✅ NOVO: Para verificação periódica
   */
  private stopActiveMatchCheck(): void {
    if (this.activeMatchCheckInterval) {
      clearInterval(this.activeMatchCheckInterval);
      this.activeMatchCheckInterval = undefined;
      console.log('🛑 [Queue] Verificação de partida ativa interrompida');
    }
  }

  /**
   * ✅ NOVO: Verifica se player tem partida ativa e redireciona automaticamente
   */
  private async checkForActiveMatch(): Promise<void> {
    const currentPlayerValue = this.currentPlayer();
    if (!currentPlayerValue?.displayName && !currentPlayerValue?.summonerName) {
      return;
    }

    // ✅ CRÍTICO: NÃO verificar se já estamos em draft/game!
    // Isso evita loops de reload e crashes quando o componente Queue está em segundo plano
    if (this.inDraftPhase() || this.inGamePhase()) {
      console.debug('⏭️ [Queue] JÁ em draft/game, pulando verificação de active match');
      return;
    }

    try {
      const summonerName = currentPlayerValue.displayName || currentPlayerValue.summonerName;

      // ✅ Chamar endpoint backend com validação e Redis
      const response = await fetch(
        `/api/queue/my-active-match?summonerName=${encodeURIComponent(summonerName)}`,
        {
          method: 'GET',
          headers: {
            'X-Summoner-Name': summonerName,
            'Content-Type': 'application/json'
          }
        }
      );

      if (response.status === 404) {
        // ✅ Nenhuma partida ativa, tudo OK
        return;
      }

      if (!response.ok) {
        console.warn('⚠️ [Queue] Erro ao verificar partida ativa:', response.status);
        return;
      }

      const activeMatch = await response.json();

      if (activeMatch?.id) {
        console.log('🎮 [Queue] PARTIDA ATIVA DETECTADA:', activeMatch);

        // ✅ Redirecionar baseado no status
        this.redirectToActiveMatch(activeMatch);
      }

    } catch (error) {
      // ✅ Silenciar erro para não poluir console (rede pode estar instável)
      console.debug('❌ [Queue] Erro ao verificar partida ativa:', error);
    }
  }

  /**
   * ✅ NOVO: Redireciona para a tela correta baseado no status da partida
   * ⚠️ CRÍTICO: NÃO dá reload! O app.ts gerencia a restauração via checkAndRestoreActiveMatch
   */
  private redirectToActiveMatch(match: any): void {
    const status = match.status?.toUpperCase();
    const matchId = match.id;

    console.log(`🚀 [Queue] Partida ativa detectada: ${matchId} (status: ${status})`);
    console.log('ℹ️ [Queue] A restauração será gerenciada pelo app.ts via my-active-match');

    // ✅ Parar verificação (não precisa mais)
    this.stopActiveMatchCheck();

    // ✅ NÃO dar reload! O app.ts já tem lógica de restauração via:
    // - identifyCurrentPlayerOnConnect() → checkAndRestoreActiveMatch()
    // - Isso evita loops de reload e crashes

    // Apenas logar para debug
    if (status === 'MATCH_FOUND' || status === 'ACCEPTING') {
      console.log('→ [Queue] Match found detectado, deixando app.ts gerenciar');
    } else if (status === 'ACCEPTED') {
      console.log('→ [Queue] Match aceito, transição para draft em andamento, deixando app.ts gerenciar');
    } else if (status === 'DRAFT' || status === 'DRAFTING') {
      console.log('→ [Queue] Draft detectado, deixando app.ts gerenciar');
    } else if (status === 'IN_PROGRESS' || status === 'GAME') {
      console.log('→ [Queue] Game in progress detectado, deixando app.ts gerenciar');
    } else {
      console.warn(`⚠️ [Queue] Status desconhecido: ${status}`);
    }
  }

  // ✅ NOVO: Sincronizar timer com dados do jogador na fila
  private syncTimerWithPlayerData(playerData: any, resetTimer: boolean = true): void {
    if (!playerData?.joinTime) {
      return;
    }

    // ✅ CORRIGIDO: Usar método auxiliar para garantir consistência
    const timeData = this.calculateTimeInQueue(playerData.joinTime);

    if (resetTimer) {
      // ✅ SIMPLIFICADO: Apenas inicializar timer na primeira vez
      this.queueTimer.set(timeData.seconds);
      console.log(`🔄 [Queue] Timer inicializado: ${timeData.seconds}s (${this.getTimerDisplay()})`);
    } else {
      // ✅ REMOVIDO: Lógica de ajuste - o timer agora se auto-atualiza com tempo real
      console.log(`🔄 [Queue] Timer auto-atualizado: ${this.queueTimer()}s (servidor: ${timeData.seconds}s)`);
    }
  }
}
