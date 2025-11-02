import { Component, Input, Output, EventEmitter, OnInit, OnChanges, OnDestroy, SimpleChanges, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom, Subscription } from 'rxjs';
import { ChampionService } from '../../services/champion.service';
import { ApiService } from '../../services/api';
import { ElectronEventsService } from '../../services/electron-events.service';

// ✅ DESABILITADO: Salvamento de logs em arquivo (por solicitação do usuário)
function logConfirmationModal(...args: any[]) {
  // Apenas console.log para debug no DevTools
  console.log('[ConfirmationModal]', ...args);
}

interface PickBanPhase {
  team: 'blue' | 'red';
  action: 'ban' | 'pick';
  champion?: any;
  playerId?: string;
  playerName?: string;
  locked: boolean;
  timeRemaining: number;
}

interface CustomPickBanSession {
  id: string;
  matchId?: number | string; // ✅ NOVO: matchId opcional (pode vir do backend)
  phase: 'bans' | 'picks' | 'completed';
  currentAction: number;
  extendedTime: number;
  phases: PickBanPhase[];
  blueTeam: any[];
  redTeam: any[];
  currentPlayerIndex: number;
  actions?: any[]; // ✅ NOVO: Ações do draft com dados completos
  teams?: { // ✅ NOVO: Estrutura hierárquica
    blue: {
      players: Array<{
        summonerName: string;
        assignedLane: string;
        actions: Array<{
          type: 'ban' | 'pick';
          championId: string;
          championName?: string;
          status: 'completed' | 'pending';
        }>;
      }>;
      allBans: string[]; // ✅ NOVO: IDs dos campeões banidos
      allPicks: string[]; // ✅ NOVO: IDs dos campeões escolhidos
    };
    red: {
      players: Array<{
        summonerName: string;
        assignedLane: string;
        actions: Array<{
          type: 'ban' | 'pick';
          championId: string;
          championName?: string;
          status: 'completed' | 'pending';
        }>;
      }>;
      allBans: string[]; // ✅ NOVO: IDs dos campeões banidos
      allPicks: string[]; // ✅ NOVO: IDs dos campeões escolhidos
    };
  };
}

interface TeamSlot {
  player: any;
  champion?: any;
  phaseIndex: number;
}

@Component({
  selector: 'app-draft-confirmation-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './draft-confirmation-modal.html',
  styleUrl: './draft-confirmation-modal.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DraftConfirmationModalComponent implements OnInit, OnChanges, OnDestroy {
  @Input() session: CustomPickBanSession | null = null;
  @Input() currentPlayer: any = null;
  @Input() isVisible: boolean = false;
  @Input() confirmationData: any = null; // ✅ NOVO: Dados de confirmação dos jogadores
  @Output() onClose = new EventEmitter<void>();
  @Output() onConfirm = new EventEmitter<void>();
  @Output() onCancel = new EventEmitter<void>();
  @Output() onEditPick = new EventEmitter<{ playerId: string, phaseIndex: number }>();
  @Output() onRefreshDraft = new EventEmitter<void>(); // ✅ NOVO: Para atualizar estado do draft

  // ✅ CRÍTICO: MatchId FIXO usado para filtrar eventos draft_updated
  // Definido UMA VEZ quando o modal recebe a session inicial
  private activeMatchId: string | null = null;

  // ✅ NOVO: Estado da confirmação
  isConfirming: boolean = false;
  confirmationMessage: string = '';

  // ✅ NOVO: Status de confirmação dos jogadores
  confirmedCount: number = 0;
  totalPlayers: number = 10;

  // ✅ NOVO: WebSocket subscription para atualizações em tempo real
  private wsSubscription?: Subscription;

  // ✅ NOVO: Array para gerenciar subscrições de observables (mesma técnica do draft)
  private subscriptions: Subscription[] = [];

  // PROPRIEDADES PARA CACHE
  private _cachedBannedanys: any[] | null = null;
  private _cachedBlueTeamPicks: any[] | null = null;
  private _cachedRedTeamPicks: any[] | null = null;
  private _cachedBlueTeamByLane: TeamSlot[] | null = null;
  private _cachedRedTeamByLane: TeamSlot[] | null = null;
  private _lastCacheUpdate: number = 0;
  private readonly CACHE_DURATION = 100;

  // HTTP properties
  private readonly baseUrl: string;

  constructor(
    private readonly championService: ChampionService,
    private readonly http: HttpClient,
    private readonly apiService: ApiService,
    private readonly cdr: ChangeDetectorRef,
    private readonly electronEvents: ElectronEventsService
  ) {
    console.log('🏗️🏗️🏗️ [CONFIRMATION-MODAL] ============ CONSTRUCTOR EXECUTADO ============');
    console.log('🏗️ [CONFIRMATION-MODAL] Modal está sendo CRIADO pelo Angular!');
    this.baseUrl = this.apiService.getBaseUrl();
  }

  // ✅ NOVO: Configurar observables UMA VEZ no init (mesma técnica do draft-pick-ban)
  ngOnInit(): void {
    console.log('🎯🎯🎯 [CONFIRMATION-MODAL] ============ ngOnInit EXECUTADO ============');
    console.log('🎯 [CONFIRMATION-MODAL] Registrando listeners do WebSocket...');
    this.setupObservableListeners();
    console.log('🎯 [CONFIRMATION-MODAL] Listeners registrados com sucesso!');
  }

  // ✅ NOVO: Cleanup de subscrições (OnDestroy necessário para limpar subscrições)
  ngOnDestroy(): void {
    this.subscriptions.forEach(sub => sub.unsubscribe());
    this.subscriptions = [];
    this.cleanupWebSocketListeners();
  }

  // ✅ NOVO: Buscar campeão no cache pelo ID
  private getChampionFromCache(championId: number): any {
    logConfirmationModal(`🔍 [getChampionFromCache] Buscando campeão com ID: ${championId}`);

    const cache = (this.championService as any).championsCache as Map<string, any>;
    if (!cache) {
      logConfirmationModal(`❌ [getChampionFromCache] Cache não disponível`);
      return null;
    }

    logConfirmationModal(`🔍 [getChampionFromCache] Cache tem ${cache.size} campeões`);

    // Tentar buscar diretamente pelo key
    let champion = cache.get(championId.toString());

    if (!champion) {
      logConfirmationModal(`🔍 [getChampionFromCache] Não encontrado diretamente, tentando fallback...`);
      // FALLBACK: Buscar em todos os campeões pelo ID alternativo
      for (const [key, champ] of cache.entries()) {
        if (champ.key === championId.toString() || parseInt(champ.key, 10) === championId) {
          champion = champ;
          logConfirmationModal(`✅ [getChampionFromCache] Campeão ${championId} encontrado via fallback: ${champ.name} (key: ${key})`);
          break;
        }
      }
    } else {
      logConfirmationModal(`✅ [getChampionFromCache] Campeão ${championId} encontrado diretamente: ${champion.name}`);
      logConfirmationModal(`✅ [getChampionFromCache] Champion object:`, JSON.stringify(champion, null, 2));
    }

    if (!champion) {
      logConfirmationModal(`⚠️ [getChampionFromCache] Campeão ${championId} NÃO encontrado no cache`);
      // Log primeiros 5 campeões do cache para debug
      let count = 0;
      for (const [key, champ] of cache.entries()) {
        if (count < 5) {
          logConfirmationModal(`   Cache[${key}]: ${champ.name} (key: ${champ.key})`);
          count++;
        }
      }
    }

    return champion;
  }

  ngOnChanges(changes: SimpleChanges): void {
    // ✅ NOVO: Logs extremamente detalhados para DEBUG
    console.log('🔵🔵🔵 [CONFIRMATION-MODAL] ============ ngOnChanges CHAMADO ============');
    console.log('🔵 [CONFIRMATION-MODAL] Changes:', Object.keys(changes));
    console.log('🔵 [CONFIRMATION-MODAL] isVisible:', this.isVisible);
    console.log('🔵 [CONFIRMATION-MODAL] session exists:', !!this.session);
    console.log('🔵 [CONFIRMATION-MODAL] session.matchId:', this.session?.matchId);
    console.log('🔵 [CONFIRMATION-MODAL] session.id:', this.session?.id);
    console.log('🔵 [CONFIRMATION-MODAL] activeMatchId ATUAL:', this.activeMatchId);

    // ✅ CRÍTICO: Definir activeMatchId UMA VEZ quando receber session pela primeira vez
    if (this.session && !this.activeMatchId) {
      this.activeMatchId = String(this.session.matchId || this.session.id);
      console.log('🎯🎯🎯 [CONFIRMATION-MODAL] activeMatchId DEFINIDO:', this.activeMatchId);
      console.log('🎯 [CONFIRMATION-MODAL] Agora o listener vai filtrar APENAS eventos deste matchId!');
    }

    // ✅ CRÍTICO: Se a session mudou, SEMPRE invalidar cache IMEDIATAMENTE (antes de qualquer outra coisa)
    if (changes['session']?.currentValue && changes['session']?.currentValue !== changes['session']?.previousValue) {
      console.log('🔴🔴🔴 [CONFIRMATION-MODAL] SESSION MUDOU VIA @INPUT DO PAI!!!');
      console.log('🔴 [CONFIRMATION-MODAL] Session anterior:', changes['session']?.previousValue?.matchId);
      console.log('🔴 [CONFIRMATION-MODAL] Session nova:', changes['session']?.currentValue?.matchId);
      console.log('🔴 [CONFIRMATION-MODAL] Teams na nova session:', !!changes['session']?.currentValue?.teams);
      console.log('🔴 [CONFIRMATION-MODAL] Blue picks na nova session:', changes['session']?.currentValue?.teams?.blue?.allPicks);
      console.log('🔴 [CONFIRMATION-MODAL] Red picks na nova session:', changes['session']?.currentValue?.teams?.red?.allPicks);
      console.log('🔴 [CONFIRMATION-MODAL] INVALIDANDO CACHE IMEDIATAMENTE!');
      this.invalidateCache();

      // ✅ CRÍTICO: Se o modal está visível, forçar recálculo IMEDIATO
      if (this.isVisible) {
        console.log('🔴 [CONFIRMATION-MODAL] Modal está visível - forçando recálculo COMPLETO');
        setTimeout(() => {
          this.getTeamPicks('blue');
          this.getTeamPicks('red');
          this.getTeamBans('blue');
          this.getTeamBans('red');
          this.getTeamByLane('blue');
          this.getTeamByLane('red');
          this.cdr.markForCheck();
          this.cdr.detectChanges();
          console.log('✅ [CONFIRMATION-MODAL] Recálculo completo finalizado após mudança de session');
        }, 0);
      }
    }

    // ✅ NOVO: Identificar usuário atual quando modal abre
    if (this.isVisible) {
      console.log('🟢🟢🟢 [CONFIRMATION-MODAL] Modal ABERTO - forçando identificação e refresh');
      this.identifyCurrentUser();
      this.initializeConfirmationStatuses(); // ✅ NOVO: Inicializar status de confirmação
      this.setupWebSocketListeners(); // ✅ NOVO: Configurar listeners WebSocket

      // ✅ CRÍTICO: SEMPRE invalidar cache e forçar recálculo quando modal abre
      // Isso garante que dados estejam atualizados mesmo se session não mudou via @Input
      console.log('🔴 [CONFIRMATION-MODAL] CRITICAL: Modal aberto - invalidando cache e forçando recálculo TOTAL');
      this.invalidateCache();

      // ✅ Aguardar um tick para garantir que todas as mudanças foram propagadas
      setTimeout(() => {
        if (this.session) {
          console.log('🔴 [CONFIRMATION-MODAL] Recalculando TODOS os dados do modal...');
          // Forçar recálculo de tudo
          this.getTeamPicks('blue');
          this.getTeamPicks('red');
          this.getTeamBans('blue');
          this.getTeamBans('red');
          this.getTeamByLane('blue');
          this.getTeamByLane('red');
          this.cdr.markForCheck();
          this.cdr.detectChanges();
          console.log('✅ [CONFIRMATION-MODAL] Dados recalculados após abertura do modal');
        }
      }, 0);

      // ✅ CORREÇÃO: setupObservableListeners movido para ngOnInit (configurar UMA VEZ)
    } else {
      this.cleanupWebSocketListeners(); // ✅ NOVO: Limpar listeners quando modal fecha
      // Subscrições são limpas no ngOnDestroy
    }

    // ✅ CRÍTICO: Atualizar dados de confirmação quando confirmationData mudar
    if (changes['confirmationData']) {
      const newData = changes['confirmationData'].currentValue;
      if (newData) {
        this.confirmedCount = newData.confirmedCount || newData.confirmations?.length || 0;
        this.totalPlayers = newData.totalPlayers || 10;

        console.log('📊 [CONFIRMATION-MODAL] confirmationData atualizado:', {
          confirmedCount: this.confirmedCount,
          totalPlayers: this.totalPlayers,
          allConfirmed: newData.allConfirmed,
          confirmations: newData.confirmations
        });

        // ✅ Forçar detecção de mudanças (OnPush requer)
        this.cdr.markForCheck();
        this.cdr.detectChanges();
      }
    }

    // ✅ NOVO: Invalidar cache quando session ou isVisible mudam
    if (changes['session'] || changes['isVisible']) {
      logConfirmationModal('🔄 [ngOnChanges] Detectada mudança na session ou visibilidade');
      console.log('🔵 [CONFIRMATION-MODAL] Changes detectadas:', {
        sessionChanged: !!changes['session'],
        visibilityChanged: !!changes['isVisible'],
        newSession: changes['session']?.currentValue ? 'presente' : 'ausente',
        newVisibility: changes['isVisible']?.currentValue
      });

      logConfirmationModal('🔄 [ngOnChanges] Changes:', {
        sessionChanged: !!changes['session'],
        visibilityChanged: !!changes['isVisible'],
        newSession: changes['session']?.currentValue ? 'presente' : 'ausente',
        newVisibility: changes['isVisible']?.currentValue
      });

      // ✅ CRÍTICO: Invalidar cache quando session muda (para refletir picks editados)
      if (changes['session']) {
        const sessionChanged = changes['session'].currentValue !== changes['session'].previousValue;
        const previousSession = changes['session'].previousValue;
        const currentSession = changes['session'].currentValue;

        console.log('🔄 [CONFIRMATION-MODAL] Session mudou - invalidando cache de picks/bans', {
          referenceChanged: sessionChanged,
          hasPreviousValue: !!previousSession,
          hasCurrentValue: !!currentSession,
          sessionVisible: this.isVisible,
          previousSessionId: previousSession?.id || previousSession?.matchId,
          currentSessionId: currentSession?.id || currentSession?.matchId
        });

        // ✅ CRÍTICO: Se o modal já está visível, forçar refresh IMEDIATO
        if (this.isVisible) {
          console.log('🟢 [CONFIRMATION-MODAL] Modal já visível - forçando refresh IMEDIATO');
          console.log('🔄 [CONFIRMATION-MODAL] Session atual:', {
            sessionId: this.session?.id || this.session?.matchId,
            hasTeams: !!this.session?.teams,
            blueTeamPicks: this.session?.teams?.blue?.allPicks,
            redTeamPicks: this.session?.teams?.red?.allPicks,
            phasesCount: this.session?.phases?.length
          });
          this.forceRefresh(); // ✅ Usar forceRefresh() que já faz tudo necessário
        } else {
          // ✅ CRÍTICO: Se modal está fechado, apenas invalidar cache (será recalculado quando abrir)
          this.invalidateCache();
        }

        // ✅ CRÍTICO: Aguardar um tick e forçar refresh novamente para garantir sincronização
        setTimeout(() => {
          if (this.isVisible) {
            // ✅ CRÍTICO: Modal ainda está visível - garantir que tudo está atualizado
            this.invalidateCache();
            this.getTeamPicks('blue');
            this.getTeamPicks('red');
            this.getTeamBans('blue');
            this.getTeamBans('red');
            this.getTeamByLane('blue');
            this.getTeamByLane('red');
            this.cdr.markForCheck();
            this.cdr.detectChanges();
            console.log('✅ [CONFIRMATION-MODAL] Refresh após tick - tudo recalculado');
          }
        }, 0);
      }

      // ✅ CRÍTICO: Quando o modal reabre (isVisible muda para true), SEMPRE forçar refresh completo
      // Isso garante que mesmo se a session não tiver mudado via @Input, o modal sempre pega os dados mais recentes
      if (changes['isVisible']?.currentValue === true && this.session) {
        console.log('🔄 [CONFIRMATION-MODAL] Modal reabriu - forçando refresh completo');
        console.log('🔴 [CONFIRMATION-MODAL] CRITICAL: Detectou reabertura do modal - invalidando cache e forçando recálculo TOTAL');

        // ✅ CRÍTICO: Invalidar TODOS os caches antes de recalcular
        this.invalidateCache();

        // ✅ CRÍTICO: Aguardar um tick para garantir que o Angular processou a mudança de @Input session
        // Isso é necessário porque o modal pode estar reabrindo enquanto a session está sendo atualizada
        setTimeout(() => {
          console.log('🔴 [CONFIRMATION-MODAL] Tick executado - session atual:', {
            sessionId: this.session?.id || this.session?.matchId,
            hasTeams: !!this.session?.teams,
            bluePlayersCount: this.session?.teams?.blue?.players?.length || 0,
            redPlayersCount: this.session?.teams?.red?.players?.length || 0
          });

          // ✅ CRÍTICO: Invalidar cache novamente (pode ter sido atualizado no tick)
          this.invalidateCache();

          // ✅ CRÍTICO: Forçar recálculo de TUDO para garantir dados atualizados
          const bluePicks = this.getTeamPicks('blue');
          const redPicks = this.getTeamPicks('red');
          const blueBans = this.getTeamBans('blue');
          const redBans = this.getTeamBans('red');
          const blueByLane = this.getTeamByLane('blue');
          const redByLane = this.getTeamByLane('red');

          console.log('🔴 [CONFIRMATION-MODAL] Dados recalculados após reabertura:', {
            bluePicks: bluePicks.length,
            redPicks: redPicks.length,
            blueBans: blueBans.length,
            redBans: redBans.length,
            blueByLane: blueByLane.length,
            redByLane: redByLane.length
          });

          // ✅ CRÍTICO: Forçar detecção de mudanças (OnPush requer)
          this.cdr.markForCheck();
          this.cdr.detectChanges();

          console.log('✅ [CONFIRMATION-MODAL] Refresh completo concluído após modal reabrir');
        }, 0);
      }

      // ✅ LOG DETALHADO: Mostrar estrutura teams recebida do backend
      if (changes['session']?.currentValue) {
        const newSession = changes['session'].currentValue;
        console.log('🔵 [CONFIRMATION-MODAL] === ESTRUTURA SESSION COMPLETA ===');
        console.log('🔵 [CONFIRMATION-MODAL] Session:', JSON.stringify(newSession, null, 2));

        logConfirmationModal('📥 [ngOnChanges] === ESTRUTURA SESSION RECEBIDA ===');
        logConfirmationModal('📥 Session completa:', JSON.stringify(newSession, null, 2));

        if (newSession.teams) {
          console.log('🔵 [CONFIRMATION-MODAL] === ESTRUTURA TEAMS ===');
          console.log('🔵 [CONFIRMATION-MODAL] Teams.blue:', JSON.stringify(newSession.teams.blue, null, 2));
          console.log('🔵 [CONFIRMATION-MODAL] Teams.red:', JSON.stringify(newSession.teams.red, null, 2));

          logConfirmationModal('📥 [ngOnChanges] === ESTRUTURA TEAMS ===');
          logConfirmationModal('📥 Teams.blue:', JSON.stringify(newSession.teams.blue, null, 2));
          logConfirmationModal('📥 Teams.red:', JSON.stringify(newSession.teams.red, null, 2));

          if (newSession.teams.blue?.allBans) {
            console.log('🔵 [CONFIRMATION-MODAL] Blue allBans:', newSession.teams.blue.allBans);
            logConfirmationModal('📥 Blue allBans:', newSession.teams.blue.allBans);
          }
          if (newSession.teams.blue?.allPicks) {
            console.log('🔵 [CONFIRMATION-MODAL] Blue allPicks:', newSession.teams.blue.allPicks);
            logConfirmationModal('📥 Blue allPicks:', newSession.teams.blue.allPicks);
          }
          if (newSession.teams.red?.allBans) {
            console.log('🔵 [CONFIRMATION-MODAL] Red allBans:', newSession.teams.red.allBans);
            logConfirmationModal('📥 Red allBans:', newSession.teams.red.allBans);
          }
          if (newSession.teams.red?.allPicks) {
            console.log('🔵 [CONFIRMATION-MODAL] Red allPicks:', newSession.teams.red.allPicks);
            logConfirmationModal('📥 Red allPicks:', newSession.teams.red.allPicks);
          }
        } else {
          console.log('⚠️ [CONFIRMATION-MODAL] Session SEM estrutura teams!');
          logConfirmationModal('⚠️ [ngOnChanges] Session SEM estrutura teams!');
        }
      }

      this.forceRefresh();

      // ✅ NOVO: Auto-confirmar bots quando o modal abrir
      if (changes['isVisible']?.currentValue === true && this.session) {
        this.autoConfirmBots();
      }
    }
  }

  // ✅ NOVO: Auto-confirmar bots para facilitar testes
  private async autoConfirmBots(): Promise<void> {
    if (!this.session) {
      return;
    }

    logConfirmationModal('🤖 [autoConfirmBots] Iniciando auto-confirmação de bots...');

    // Pegar todos os jogadores (blue + red)
    const allPlayers: any[] = [];

    if (this.session.blueTeam) {
      allPlayers.push(...this.session.blueTeam);
    }
    if (this.session.redTeam) {
      allPlayers.push(...this.session.redTeam);
    }

    // Filtrar apenas bots
    const bots = allPlayers.filter(p => this.isPlayerBot(p));

    logConfirmationModal(`🤖 [autoConfirmBots] Encontrados ${bots.length} bots para auto-confirmar`);

    // Auto-confirmar cada bot com delay pequeno
    for (const bot of bots) {
      const botId = this.getPlayerIdentifier(bot);

      // Verificar se já confirmou
      if (this.confirmationData?.confirmations?.[botId]?.confirmed) {
        logConfirmationModal(`⏭️ [autoConfirmBots] Bot ${botId} já confirmou`);
        continue;
      }

      try {
        logConfirmationModal(`✅ [autoConfirmBots] Confirmando bot: ${botId}`);

        const url = `${this.baseUrl}/match/${this.session.id}/confirm-final-draft`;
        const body = { playerId: botId };

        const response = await firstValueFrom(
          this.http.post<any>(url, body, {
            headers: this.apiService.getAuthenticatedHeaders()
          })
        );

        logConfirmationModal(`✅ [autoConfirmBots] Bot ${botId} confirmado. Total: ${response.confirmedCount}/${response.totalPlayers}`);

        // Pequeno delay para não sobrecarregar
        await new Promise(resolve => setTimeout(resolve, 100));

      } catch (error: any) {
        logConfirmationModal(`❌ [autoConfirmBots] Erro ao confirmar bot ${botId}:`, error.message);
      }
    }

    logConfirmationModal('🤖 [autoConfirmBots] Auto-confirmação de bots concluída');
  }

  // ✅ NOVO: Verificar se jogador confirmou
  isPlayerConfirmed(player: any): boolean {
    if (!this.confirmationData?.confirmations) {
      return false;
    }

    // ✅ CORREÇÃO: confirmations é um array de strings (nomes de jogadores)
    const playerId = this.getPlayerIdentifier(player);
    const confirmationsArray = Array.isArray(this.confirmationData.confirmations)
      ? this.confirmationData.confirmations
      : [];

    // ✅ Verificar se o jogador está no array de confirmados (comparação case-insensitive)
    return confirmationsArray.some((confirmedPlayer: string) =>
      confirmedPlayer?.toLowerCase().trim() === playerId.toLowerCase().trim()
    );
  }

  // ✅ NOVO: Verificar se jogador é bot e foi auto-confirmado
  isPlayerAutoConfirmed(player: any): boolean {
    // ✅ Se o jogador confirmou e é bot, assume-se auto-confirmado
    return this.isPlayerBot(player) && this.isPlayerConfirmed(player);
  }

  // ✅ NOVO: Obter identificador do jogador
  private getPlayerIdentifier(player: any): string {
    if (player.gameName && player.tagLine) {
      return `${player.gameName}#${player.tagLine}`;
    }
    return player.summonerName || player.puuid || player.name || '';
  }

  // ✅ NOVO: Obter status de confirmação como texto
  getConfirmationStatus(player: any): string {
    if (this.isPlayerBot(player)) {
      return '🤖 Bot (Auto-confirmado)';
    }

    if (this.isPlayerConfirmed(player)) {
      return '✅ Confirmado';
    }

    if (this.isCurrentPlayer(player)) {
      return '⏳ Aguardando sua confirmação';
    }

    return '⏳ Aguardando confirmação';
  }

  // MÉTODOS PARA COMPARAÇÃO DE JOGADORES
  private comparePlayerWithId(player: any, targetId: string): boolean {
    if (!player || !targetId) return false;

    // ✅ CORREÇÃO: Normalizar nome do player
    const getNormalizedName = (player: any): string => {
      if (player.gameName && player.tagLine) {
        return `${player.gameName}#${player.tagLine}`;
      }
      return player.summonerName || player.name || '';
    };

    const playerId = player.id?.toString();
    const playerName = getNormalizedName(player);

    console.log('🔍 [comparePlayerWithId] Comparando:', {
      playerId: playerId,
      playerName: playerName,
      targetId: targetId,
      idMatch: playerId === targetId,
      nameMatch: playerName === targetId
    });

    // Comparar por ID
    if (playerId === targetId) {
      console.log('✅ [comparePlayerWithId] Match por ID');
      return true;
    }

    // Comparar por nome completo
    if (playerName === targetId) {
      console.log('✅ [comparePlayerWithId] Match por nome completo');
      return true;
    }

    // Comparar apenas gameName (sem tagLine)
    if (playerName.includes('#')) {
      const gameName = playerName.split('#')[0];
      if (gameName === targetId) {
        console.log('✅ [comparePlayerWithId] Match por gameName');
        return true;
      }
    }

    console.log('❌ [comparePlayerWithId] Nenhum match encontrado');
    return false;
  }

  private comparePlayers(player1: any, player2: any): boolean {
    if (!player1 || !player2) return false;

    // ✅ CORREÇÃO: Normalizar nomes para formato gameName#tagLine
    const getNormalizedName = (player: any): string => {
      if (player.gameName && player.tagLine) {
        return `${player.gameName}#${player.tagLine}`;
      }
      return player.summonerName || player.name || '';
    };

    const name1 = getNormalizedName(player1);
    const name2 = getNormalizedName(player2);
    const id1 = player1.id?.toString();
    const id2 = player2.id?.toString();

    logConfirmationModal('🔍 [comparePlayers] === COMPARANDO JOGADORES ===');
    logConfirmationModal('🔍 [comparePlayers] Comparando:', {
      player1: { id: id1, name: name1, original: player1.summonerName || player1.name },
      player2: { id: id2, name: name2, original: player2.summonerName || player2.name },
      idsMatch: id1 && id2 && id1 === id2,
      namesMatch: name1 && name2 && name1 === name2
    });

    // Comparar por ID primeiro
    if (id1 && id2 && id1 === id2) {
      logConfirmationModal('✅ [comparePlayers] Match por ID');
      return true;
    }

    // Comparar por nome normalizado
    if (name1 && name2 && name1 === name2) {
      logConfirmationModal('✅ [comparePlayers] Match por nome normalizado');
      return true;
    }

    // Comparar apenas parte do gameName (sem tagLine)
    if (name1 && name2) {
      const gameName1 = name1.includes('#') ? name1.split('#')[0] : name1;
      const gameName2 = name2.includes('#') ? name2.split('#')[0] : name2;

      if (gameName1 === gameName2) {
        logConfirmationModal('✅ [comparePlayers] Match por gameName');
        return true;
      }
    }

    logConfirmationModal('❌ [comparePlayers] Nenhum match encontrado');
    return false;
  }

  // MÉTODOS PARA VERIFICAR ESTADO DOS CAMPEÕES
  getBannedChampions(): any[] {
    if (this.isCacheValid() && this._cachedBannedanys) {
      // ✅ CRÍTICO: Mesmo retornando do cache, criar nova referência para garantir detecção (OnPush)
      return [...this._cachedBannedanys];
    }

    if (!this.session) return [];

    const bannedanys = this.session.phases
      .filter(phase => phase.action === 'ban' && phase.champion)
      .map(phase => phase.champion!)
      .filter((champion, index, self) =>
        index === self.findIndex(c => c.id === champion.id)
      );

    // ✅ CRÍTICO: Sempre retornar NOVO array para garantir detecção de mudança (OnPush)
    const newBannedanys = [...bannedanys];

    this._cachedBannedanys = newBannedanys;
    this._lastCacheUpdate = Date.now();

    return newBannedanys;
  }

  getTeamPicks(team: 'blue' | 'red'): any[] {
    console.log('🟢🟢🟢 [CONFIRMATION-MODAL] getTeamPicks chamado para:', team, {
      isVisible: this.isVisible,
      hasSession: !!this.session,
      cacheValid: this.isCacheValid(),
      sessionTimestamp: (this.session as any)?._lastUpdate || 'não definido',
      sessionReference: this.session ? 'presente' : 'ausente'
    });

    // ✅ CRÍTICO: Se o modal está visível, sempre recalcular para garantir dados atualizados
    // Isso é especialmente importante quando o modal reabre após changePick
    if (this.isVisible) {
      // Se o modal está visível, sempre recalcular (não usar cache)
      // O cache é útil apenas quando o modal está fechado
      console.log('🟢 [CONFIRMATION-MODAL] Modal visível - ignorando cache e recalculando');
    } else if (team === 'blue' && this.isCacheValid() && this._cachedBlueTeamPicks) {
      console.log('🟢 [CONFIRMATION-MODAL] Retornando cache Blue:', this._cachedBlueTeamPicks);
      // ✅ CRÍTICO: Mesmo retornando do cache, criar nova referência para garantir detecção (OnPush)
      return [...this._cachedBlueTeamPicks];
    } else if (team === 'red' && this.isCacheValid() && this._cachedRedTeamPicks) {
      console.log('🟢 [CONFIRMATION-MODAL] Retornando cache Red:', this._cachedRedTeamPicks);
      // ✅ CRÍTICO: Mesmo retornando do cache, criar nova referência para garantir detecção (OnPush)
      return [...this._cachedRedTeamPicks];
    }

    if (!this.session) {
      console.log('❌ [CONFIRMATION-MODAL] Session não existe!');
      return [];
    }

    console.log('🟢 [CONFIRMATION-MODAL] Session existe:', {
      hasTeams: !!this.session.teams,
      hasActions: !!this.session.actions,
      hasPhases: !!this.session.phases,
      blueTeamData: this.session.teams?.blue ? {
        playersCount: this.session.teams.blue.players?.length || 0,
        allPicksCount: this.session.teams.blue.allPicks?.length || 0,
        allPicks: this.session.teams.blue.allPicks
      } : 'não existe',
      redTeamData: this.session.teams?.red ? {
        playersCount: this.session.teams.red.players?.length || 0,
        allPicksCount: this.session.teams.red.allPicks?.length || 0,
        allPicks: this.session.teams.red.allPicks
      } : 'não existe'
    });

    logConfirmationModal(`🎯 [getTeamPicks] === OBTENDO PICKS DO TIME ${team.toUpperCase()} ===`);

    let teamPicks: any[] = [];

    // ✅ CORREÇÃO: Usar estrutura hierárquica (teams.blue/red.players[].actions)
    if (this.session.teams) {
      const teamData = team === 'blue' ? this.session.teams.blue : this.session.teams.red;
      console.log('🟢 [CONFIRMATION-MODAL] teamData:', teamData);

      if (teamData?.players) {
        console.log('🟢 [CONFIRMATION-MODAL] Iterando players:', teamData.players.length);
        console.log('🟢 [CONFIRMATION-MODAL] Detalhes dos players:', teamData.players.map((p: any) => ({
          name: p.summonerName || p.name,
          actionsCount: p.actions?.length || 0,
          picks: p.actions?.filter((a: any) => a.type === 'pick' && a.status === 'completed').map((a: any) => ({
            championId: a.championId,
            championName: a.championName
          }))
        })));
        logConfirmationModal(`🎯 [getTeamPicks] Usando estrutura hierárquica - ${teamData.players.length} jogadores`);

        teamData.players.forEach((player: any, playerIndex: number) => {
          const playerName = player.summonerName || player.name || `Player ${playerIndex}`;
          console.log(`🟢 [CONFIRMATION-MODAL] Player [${playerIndex}]:`, playerName, 'Actions count:', player.actions?.length || 0);

          if (player.actions && Array.isArray(player.actions)) {
            player.actions.forEach((action: any, actionIndex: number) => {
              console.log(`🟢 [CONFIRMATION-MODAL] Action [${actionIndex}]:`, {
                type: action.type,
                championId: action.championId,
                status: action.status,
                championName: action.championName
              });

              if (action.type === 'pick' && action.championId && action.status === 'completed') {
                console.log(`✅ [CONFIRMATION-MODAL] Pick encontrado! Player: ${playerName}, ChampionId: ${action.championId}`);

                // Buscar campeão no cache
                const champion = this.getChampionFromCache(parseInt(action.championId, 10));
                console.log(`🟢 [CONFIRMATION-MODAL] Champion do cache para ID ${action.championId}:`, champion ? champion.name : 'NÃO ENCONTRADO');

                if (champion) {
                  // ✅ CORREÇÃO: Retornar com image como string URL (igual aos bans)
                  const pickData = {
                    id: champion.key,
                    name: champion.name,
                    image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`,
                    championId: action.championId
                  };
                  console.log(`✅ [CONFIRMATION-MODAL] Adicionando pick: ${pickData.name} (ID: ${pickData.championId})`);
                  teamPicks.push(pickData);
                  logConfirmationModal(`✅ [getTeamPicks] Pick encontrado:`, {
                    player: playerName,
                    champion: champion.name,
                    championId: action.championId
                  });
                } else {
                  console.warn(`⚠️ [CONFIRMATION-MODAL] Champion ID ${action.championId} NÃO encontrado no cache!`);
                }
              } else {
                console.log(`⏭️ [CONFIRMATION-MODAL] Action ignorada:`, {
                  reason: action.type !== 'pick' ? 'tipo não é pick' :
                    !action.championId ? 'sem championId' :
                      action.status !== 'completed' ? 'status não é completed' : 'desconhecido',
                  action: action
                });
              }
            });
          } else {
            console.warn(`⚠️ [CONFIRMATION-MODAL] Player ${playerName} não tem actions ou actions não é array!`, {
              hasActions: !!player.actions,
              actionsType: typeof player.actions
            });
          }
        });
      }
    }
    // FALLBACK 1: Estrutura antiga (actions)
    else if (this.session.actions && this.session.actions.length > 0) {
      const teamIndex = team === 'blue' ? 1 : 2;
      logConfirmationModal(`🎯 [getTeamPicks] Usando actions (fallback) - teamIndex: ${teamIndex}`);

      teamPicks = this.session.actions
        .filter((action: any) => {
          return action.teamIndex === teamIndex &&
            action.action === 'pick' &&
            action.champion &&
            action.locked;
        })
        .map((action: any) => action.champion);
    }
    // FALLBACK 2: Estrutura muito antiga (phases)
    else if (this.session.phases) {
      logConfirmationModal(`🎯 [getTeamPicks] Usando phases (fallback antigo)`);
      teamPicks = this.session.phases
        .filter(phase => phase.team === team && phase.action === 'pick' && phase.champion)
        .map(phase => phase.champion!);
    }

    logConfirmationModal(`🎯 [getTeamPicks] Picks finais do time ${team}: ${teamPicks.length} picks`, teamPicks.map(pick => pick.name));

    // ✅ CRÍTICO: Sempre retornar NOVO array para garantir detecção de mudança (OnPush)
    // Isso é essencial quando o modal está visível ou quando há mudança na session
    const newTeamPicks = [...teamPicks];

    if (team === 'blue') {
      this._cachedBlueTeamPicks = newTeamPicks;
    } else {
      this._cachedRedTeamPicks = newTeamPicks;
    }
    this._lastCacheUpdate = Date.now();

    return newTeamPicks;
  }

  getTeamBans(team: 'blue' | 'red'): any[] {
    if (!this.session) return [];

    logConfirmationModal(`🎯 [getTeamBans] Obtendo bans do time ${team}`);

    // ✅ Usar allBans da estrutura hierárquica (mais simples e direto)
    if (this.session.teams?.[team]?.allBans) {
      const bans = this.session.teams[team].allBans;
      logConfirmationModal(`✅ [getTeamBans] Encontrados ${bans.length} IDs de bans`, bans);

      const bansArray = bans.map((championId: string) => {
        const champion = this.getChampionFromCache(parseInt(championId, 10));
        if (champion) {
          logConfirmationModal(`✅ [getTeamBans] Campeão ${champion.name} (ID: ${championId}) encontrado no cache`);
          // ✅ CORREÇÃO: Retornar com image como string URL (igual draft-pick-ban.ts)
          return {
            id: champion.key,
            name: champion.name,
            image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`
          };
        }
        // Fallback: retornar objeto básico se não encontrar no cache
        logConfirmationModal(`⚠️ [getTeamBans] Campeão ID ${championId} NÃO encontrado no cache`);
        return {
          id: championId,
          name: `Champion ${championId}`,
          image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/Unknown.png`
        };
      });

      // ✅ CRÍTICO: Sempre retornar NOVO array para garantir detecção de mudança (OnPush)
      return [...bansArray];
    }

    // Fallback para actions (estrutura antiga)
    if (this.session.actions && this.session.actions.length > 0) {
      const teamIndex = team === 'blue' ? 1 : 2;
      const bansArray = this.session.actions
        .filter((action: any) => {
          return action.teamIndex === teamIndex &&
            action.action === 'ban' &&
            action.champion &&
            action.locked;
        })
        .map((action: any) => action.champion);
      // ✅ CRÍTICO: Sempre retornar NOVO array para garantir detecção de mudança (OnPush)
      return [...bansArray];
    }

    // Fallback para phases (estrutura muito antiga)
    if (this.session.phases) {
      const bansArray = this.session.phases
        .filter(phase => phase.team === team && phase.action === 'ban' && phase.champion)
        .map(phase => phase.champion!);
      // ✅ CRÍTICO: Sempre retornar NOVO array para garantir detecção de mudança (OnPush)
      return [...bansArray];
    }

    return [];
  }

  // MÉTODOS PARA ORGANIZAR TIMES POR LANE
  getSortedTeamByLane(team: 'blue' | 'red'): any[] {
    if (!this.session) return [];

    logConfirmationModal(`🎯 [getSortedTeamByLane] === OBTENDO TIME ${team.toUpperCase()} ORDENADO ===`);

    const teamPlayers = team === 'blue' ? this.session.blueTeam : this.session.redTeam;

    logConfirmationModal(`🎯 [getSortedTeamByLane] Time ${team} antes da ordenação:`, teamPlayers.map(p => ({
      name: p.summonerName || p.name,
      teamIndex: p.teamIndex,
      assignedLane: p.assignedLane,
      lane: p.lane
    })));

    const sortedTeam = this.sortPlayersByLane(teamPlayers);

    logConfirmationModal(`🎯 [getSortedTeamByLane] Time ${team} após ordenação:`, sortedTeam.map(p => ({
      name: p.summonerName || p.name,
      teamIndex: p.teamIndex,
      assignedLane: p.assignedLane,
      lane: p.lane
    })));

    return sortedTeam;
  }

  private sortPlayersByLane(players: any[]): any[] {
    logConfirmationModal('🎯 [sortPlayersByLane] === INICIANDO ORDENAÇÃO ===');
    logConfirmationModal('🎯 [sortPlayersByLane] Players recebidos:', players.map(p => ({
      name: p.summonerName || p.name,
      teamIndex: p.teamIndex,
      assignedLane: p.assignedLane,
      lane: p.lane
    })));

    // ✅ CORREÇÃO CRÍTICA: Usar teamIndex quando disponível para manter ordem do draft
    const sortedPlayers = players.sort((a, b) => {
      // Se ambos têm teamIndex, usar isso (fonte de verdade do draft)
      if (a.teamIndex !== undefined && b.teamIndex !== undefined) {
        logConfirmationModal('🎯 [sortPlayersByLane] Ordenando por teamIndex:', {
          playerA: { name: a.summonerName || a.name, teamIndex: a.teamIndex },
          playerB: { name: b.summonerName || b.name, teamIndex: b.teamIndex }
        });
        return a.teamIndex - b.teamIndex;
      }

      // Fallback para ordenação por lane se teamIndex não disponível
      const laneOrder = ['top', 'jungle', 'mid', 'bot', 'support']; // ✅ CORREÇÃO: 'bot' ao invés de 'adc'

      // ✅ CORREÇÃO: Usar assignedLane em vez de lane
      const laneA = a.assignedLane || a.lane || 'unknown';
      const laneB = b.assignedLane || b.lane || 'unknown';

      // ✅ LOG DETALHADO: Verificar propriedades dos jogadores
      logConfirmationModal('🎯 [sortPlayersByLane] Verificando lanes (fallback):', {
        playerA: {
          name: a.summonerName || a.name,
          assignedLane: a.assignedLane,
          lane: a.lane,
          finalLane: laneA
        },
        playerB: {
          name: b.summonerName || b.name,
          assignedLane: b.assignedLane,
          lane: b.lane,
          finalLane: laneB
        }
      });

      const indexA = laneOrder.indexOf(laneA);
      const indexB = laneOrder.indexOf(laneB);

      if (indexA === -1 && indexB === -1) return 0;
      if (indexA === -1) return 1;
      if (indexB === -1) return -1;

      return indexA - indexB;
    });

    logConfirmationModal('🎯 [sortPlayersByLane] Resultado final da ordenação:', sortedPlayers.map(p => ({
      name: p.summonerName || p.name,
      teamIndex: p.teamIndex,
      assignedLane: p.assignedLane || p.lane
    })));

    return sortedPlayers;
  }

  // MÉTODOS PARA ORGANIZAR TIMES COM PICKS
  getTeamByLane(team: 'blue' | 'red'): TeamSlot[] {
    // ✅ CRÍTICO: Se o modal está visível, sempre recalcular para garantir dados atualizados
    if (this.isVisible) {
      // Modal visível - sempre recalcular (não usar cache)
      console.log('🟢 [CONFIRMATION-MODAL] getTeamByLane - Modal visível, recalculando para:', team);
    } else if (team === 'blue' && this.isCacheValid() && this._cachedBlueTeamByLane) {
      // ✅ CRÍTICO: Mesmo retornando do cache, criar nova referência para garantir detecção (OnPush)
      return this._cachedBlueTeamByLane.map(slot => ({ ...slot }));
    } else if (team === 'red' && this.isCacheValid() && this._cachedRedTeamByLane) {
      // ✅ CRÍTICO: Mesmo retornando do cache, criar nova referência para garantir detecção (OnPush)
      return this._cachedRedTeamByLane.map(slot => ({ ...slot }));
    }

    if (!this.session) return [];

    logConfirmationModal(`🎯 [getTeamByLane] === ORGANIZANDO TIME ${team.toUpperCase()} ===`);
    const teamPlayers = this.getSortedTeamByLane(team);
    const teamPicks = this.getTeamPicks(team);

    logConfirmationModal(`🎯 [getTeamByLane] Time ${team}:`, {
      playersCount: teamPlayers.length,
      picksCount: teamPicks.length,
      players: teamPlayers.map(p => ({
        name: p.summonerName || p.name,
        teamIndex: p.teamIndex,
        assignedLane: p.assignedLane,
        lane: p.lane
      })),
      picks: teamPicks.map(c => c.name)
    });

    const organizedTeam = this.organizeTeamByLanes(teamPlayers, teamPicks);

    logConfirmationModal(`🎯 [getTeamByLane] Time ${team} organizado:`, organizedTeam.map(slot => ({
      playerName: slot.player.summonerName || slot.player.name,
      playerTeamIndex: slot.player.teamIndex,
      playerLane: slot.player.assignedLane || slot.player.lane,
      championName: slot.champion?.name || 'Sem pick',
      phaseIndex: slot.phaseIndex
    })));

    // ✅ CRÍTICO: Sempre retornar NOVO array para garantir detecção de mudança (OnPush)
    // Isso é essencial quando o modal está visível ou quando há mudança na session
    const newOrganizedTeam = organizedTeam.map(slot => ({ ...slot })); // Shallow copy dos slots também

    if (team === 'blue') {
      this._cachedBlueTeamByLane = newOrganizedTeam;
    } else {
      this._cachedRedTeamByLane = newOrganizedTeam;
    }
    this._lastCacheUpdate = Date.now();

    return newOrganizedTeam;
  }

  private organizeTeamByLanes(teamPlayers: any[], teamPicks: any[]): TeamSlot[] {
    logConfirmationModal('🎯 [organizeTeamByLanes] === CRIANDO SLOTS ===');
    logConfirmationModal('🎯 [organizeTeamByLanes] Entrada:', {
      teamPlayersCount: teamPlayers.length,
      teamPicksCount: teamPicks.length,
      teamPlayers: teamPlayers.map(p => ({
        name: p.summonerName || p.name,
        teamIndex: p.teamIndex,
        assignedLane: p.assignedLane,
        hasActions: !!p.actions,
        actionsCount: p.actions?.length || 0
      })),
      teamPicks: teamPicks.map(p => ({
        name: p.name || 'Unknown',
        id: p.id,
        image: p.image
      }))
    });

    // ✅ LOG DETALHADO: Mostrar estrutura completa dos players
    teamPlayers.forEach((p, idx) => {
      logConfirmationModal(`📋 [organizeTeamByLanes] Player ${idx}:`, JSON.stringify(p, null, 2));
    });

    // ✅ CORREÇÃO CRÍTICA: Associar picks por jogador específico usando estrutura hierárquica
    const slots = teamPlayers.map((player, index) => {
      const phaseIndex = this.getPhaseIndexForPlayer(player) || 0;

      // ✅ PRIORIDADE 1: Encontrar pick nas actions do próprio player (estrutura hierárquica)
      let playerChampion = undefined;

      if (player.actions && Array.isArray(player.actions)) {
        const pickAction = player.actions.find((action: any) =>
          action.type === 'pick' && action.championId && action.status === 'completed'
        );

        if (pickAction) {
          const champion = this.getChampionFromCache(parseInt(pickAction.championId, 10));
          if (champion) {
            // ✅ Retornar com image como URL string
            playerChampion = {
              id: champion.key,
              name: champion.name,
              image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`
            };
            logConfirmationModal('🎯 [organizeTeamByLanes] Pick encontrado nas actions do player:', {
              playerName: player.summonerName || player.name,
              championName: champion.name,
              championId: pickAction.championId
            });
          }
        }
      }

      // ✅ FALLBACK 1: Buscar na estrutura antiga (actions)
      if (!playerChampion && this.session?.actions) {
        const playerAction = this.session.actions.find((action: any) => {
          return action.action === 'pick' &&
            action.locked &&
            action.champion &&
            this.comparePlayerWithId(player, action.playerId || '');
        });

        if (playerAction) {
          playerChampion = playerAction.champion;
          logConfirmationModal('🎯 [organizeTeamByLanes] Pick encontrado via actions (fallback) para:', {
            playerName: player.summonerName || player.name,
            championName: playerAction.champion.name,
            actionPlayerId: playerAction.playerId
          });
        }
      }

      // ✅ FALLBACK 2: Se não encontrou via actions, usar índice do array teamPicks
      if (!playerChampion && teamPicks[index]) {
        playerChampion = teamPicks[index];
        logConfirmationModal('🎯 [organizeTeamByLanes] Pick encontrado via índice (fallback 2) para:', {
          playerName: player.summonerName || player.name,
          championName: teamPicks[index].name,
          index: index
        });
      }

      logConfirmationModal('🎯 [organizeTeamByLanes] Criando slot para jogador:', {
        playerIndex: index,
        playerName: player.summonerName || player.name,
        playerTeamIndex: player.teamIndex,
        playerLane: player.assignedLane || player.lane,
        phaseIndex: phaseIndex,
        hasChampion: !!playerChampion,
        championName: playerChampion?.name || 'Sem champion'
      });

      return {
        player,
        champion: playerChampion,
        phaseIndex: phaseIndex
      };
    });

    logConfirmationModal('🎯 [organizeTeamByLanes] Slots criados:', slots.map(slot => ({
      playerName: slot.player.summonerName || slot.player.name,
      championName: slot.champion?.name || 'Sem pick',
      phaseIndex: slot.phaseIndex
    })));

    return slots;
  }

  private getPhaseIndexForPlayer(player: any): number {
    if (!this.session) return 0;

    console.log('🔍 [getPhaseIndexForPlayer] Procurando fase para jogador:', {
      id: player.id,
      summonerName: player.summonerName,
      name: player.name,
      lane: player.lane
    });

    // Encontrar a fase onde este jogador fez pick
    for (let i = 0; i < this.session.phases.length; i++) {
      const phase = this.session.phases[i];
      if (phase.action === 'pick' && phase.champion && phase.playerId) {
        const isMatch = this.comparePlayerWithId(player, phase.playerId);
        console.log(`🔍 [getPhaseIndexForPlayer] Fase ${i}:`, {
          phasePlayerId: phase.playerId,
          phasePlayerName: phase.playerName,
          champion: phase.champion?.name,
          isMatch: isMatch
        });

        if (isMatch) {
          console.log(`✅ [getPhaseIndexForPlayer] Encontrada fase ${i} para jogador ${player.summonerName || player.name}`);
          return i;
        }
      }
    }

    console.log(`❌ [getPhaseIndexForPlayer] Nenhuma fase encontrada para jogador ${player.summonerName || player.name}, retornando 0`);
    return 0;
  }

  // MÉTODOS PARA SLOTS VAZIOS
  getEmptyBanSlots(banCount: number): number[] {
    const totalBans = 5;
    const emptySlots = totalBans - banCount;
    return Array.from({ length: Math.max(0, emptySlots) }, (_, i) => i);
  }

  // MÉTODOS PARA VERIFICAR JOGADOR ATUAL
  isCurrentPlayer(player: any): boolean {
    logConfirmationModal('🔍 [isCurrentPlayer] === VERIFICANDO JOGADOR ATUAL ===');
    logConfirmationModal('🔍 [isCurrentPlayer] Verificando:', {
      hasCurrentPlayer: !!this.currentPlayer,
      currentPlayer: {
        id: this.currentPlayer?.id,
        summonerName: this.currentPlayer?.summonerName,
        name: this.currentPlayer?.name,
        gameName: this.currentPlayer?.gameName,
        tagLine: this.currentPlayer?.tagLine
      },
      player: {
        id: player?.id,
        summonerName: player?.summonerName,
        name: player?.name,
        gameName: player?.gameName,
        tagLine: player?.tagLine
      },
      playerName: player?.summonerName || player?.name
    });

    if (!this.currentPlayer || !player) {
      logConfirmationModal('❌ [isCurrentPlayer] currentPlayer ou player é null');
      return false;
    }

    const result = this.comparePlayers(this.currentPlayer, player);
    logConfirmationModal('🔍 [isCurrentPlayer] Resultado final:', result);
    return result;
  }

  // MÉTODOS PARA VERIFICAR SE É BOT
  isPlayerBot(player: any): boolean {
    if (!player) return false;

    const name = player.summonerName || player.name || '';
    const id = player.id;

    console.log('🤖 [isPlayerBot] Verificando bot:', {
      playerName: name,
      playerId: id,
      isNegativeId: id < 0
    });

    if (id < 0) {
      console.log('✅ [isPlayerBot] É bot (ID negativo)');
      return true;
    }

    if (typeof id === 'string') {
      const numericId = parseInt(id);
      if (!isNaN(numericId) && numericId < 0) {
        return true;
      }

      if (id.toLowerCase().includes('bot') || id.startsWith('-')) {
        return true;
      }
    }

    // ✅ ATUALIZADO: Padrões de bot (incluindo novo padrão sequencial)
    const botPatterns = [
      /^bot\d+$/i,           // ✅ NOVO: Bot1, Bot2, etc (padrão sequencial)
      /^bot\s*\d+$/i,
      /^ai\s*bot$/i,
      /^computer\s*\d*$/i,
      /^bot\s*player$/i,
      /^ai\s*player$/i,
      /^bot$/i,
      /^ai$/i,
      /^popcornseller$/i,
      /^bot\s*[a-z]*$/i,
      /^ai\s*[a-z]*$/i,
      /^bot\s*\d+\s*[a-z]*$/i,
      /^ai\s*\d+\s*[a-z]*$/i,
      /^bot\d+[a-z]*$/i,
      /^ai\d+[a-z]*$/i
    ];

    for (const pattern of botPatterns) {
      if (pattern.test(name)) {
        return true;
      }
    }

    if (name.toLowerCase().includes('bot')) {
      return true;
    }

    if (name.toLowerCase().includes('ai')) {
      return true;
    }

    if (/\d/.test(name) && (name.toLowerCase().includes('bot') || name.toLowerCase().includes('ai'))) {
      return true;
    }

    return false;
  }

  // MÉTODOS PARA LANE DISPLAY
  getPlayerLaneDisplayForPlayer(player: any): string {
    // ✅ CORREÇÃO: Usar assignedLane em vez de lane
    const lane = player.assignedLane || player.lane || 'unknown';
    console.log('🎯 [getPlayerLaneDisplayForPlayer] Player lane info:', {
      playerName: player.summonerName || player.name,
      assignedLane: player.assignedLane,
      lane: player.lane,
      finalLane: lane
    });
    return this.getLaneDisplayName(lane);
  }

  getLaneDisplayName(lane: string): string {
    const laneNames: { [key: string]: string } = {
      'top': '🛡️ Top',
      'jungle': '🌲 Jungle',
      'mid': '⚡ Mid',
      'bot': '🏹 ADC',    // ✅ CORREÇÃO: 'bot' mapeia para 'ADC'
      'adc': '🏹 ADC',    // ✅ MANTIDO: Para compatibilidade
      'support': '💎 Support',
      'unknown': '❓ Unknown'
    };
    return laneNames[lane] || laneNames['unknown'];
  }

  // MÉTODOS PARA CONTROLE DO MODAL
  closeModal(): void {
    logConfirmationModal('🚪 [closeModal] Fechando modal de confirmação');
    this.onClose.emit();
  }

  async confirmFinalDraft(): Promise<void> {
    console.log('🟢 [CONFIRM-FINAL-DRAFT] === CONFIRMANDO DRAFT FINAL ===');
    console.log('🟢 [CONFIRM-FINAL-DRAFT] Session:', this.session);
    console.log('🟢 [CONFIRM-FINAL-DRAFT] CurrentPlayer:', this.currentPlayer);

    logConfirmationModal('✅ [confirmFinalDraft] === CONFIRMANDO DRAFT FINAL ===');

    // ✅ Validar dados necessários
    if (!this.session?.id) {
      console.error('❌ [confirmFinalDraft] Session ID não disponível');
      this.confirmationMessage = 'Erro: Session não disponível';
      return;
    }

    if (!this.currentPlayer?.summonerName) {
      console.error('❌ [confirmFinalDraft] Player não disponível');
      this.confirmationMessage = 'Erro: Jogador não identificado';
      return;
    }

    // ✅ Mostrar feedback de carregamento
    this.isConfirming = true;
    this.confirmationMessage = 'Confirmando sua seleção...';

    try {
      const url = `${this.baseUrl}/match/${this.session.id}/confirm-final-draft`;
      const body = {
        playerId: this.currentPlayer.summonerName
      };

      console.log('� [confirmFinalDraft] Enviando confirmação:', { url, body });
      logConfirmationModal('📤 [confirmFinalDraft] Enviando HTTP POST:', url);

      const response: any = await firstValueFrom(
        this.http.post(url, body, {
          headers: this.apiService.getAuthenticatedHeaders()
        })
      );

      console.log('✅ [confirmFinalDraft] Resposta recebida:', response);
      logConfirmationModal('✅ [confirmFinalDraft] Confirmação registrada:', response);

      // ✅ Atualizar UI com resposta
      if (response.success) {
        const { allConfirmed, confirmedCount, totalPlayers, message } = response;

        this.confirmationMessage = message ||
          (allConfirmed
            ? 'Todos confirmaram! Iniciando partida...'
            : `Confirmado! Aguardando ${totalPlayers - confirmedCount} jogadores...`);

        console.log(`📊 [confirmFinalDraft] Confirmações: ${confirmedCount}/${totalPlayers}`);

        if (allConfirmed) {
          console.log('🎮 [confirmFinalDraft] TODOS CONFIRMARAM! Aguardando game_started...');
          // ✅ Modal será fechado quando receber evento game_started via WebSocket
        } else {
          this.isConfirming = false;
        }
      } else {
        throw new Error(response.message || 'Falha ao confirmar');
      }

    } catch (error: any) {
      console.error('❌ [confirmFinalDraft] Erro ao confirmar:', error);
      logConfirmationModal('❌ [confirmFinalDraft] Erro:', error);

      this.isConfirming = false;
      this.confirmationMessage = error?.error?.error || error?.message || 'Erro ao confirmar. Tente novamente.';
    }
  }

  cancelFinalDraft(): void {
    logConfirmationModal('❌ [cancelFinalDraft] === CANCELANDO DRAFT ===');
    this.onCancel.emit();
  }

  // ✅ NOVO: Método para atualizar estado da confirmação
  updateConfirmationState(confirmed: boolean, allConfirmed: boolean): void {
    if (confirmed) {
      this.confirmationMessage = allConfirmed
        ? 'Todos confirmaram! Iniciando partida...'
        : 'Sua confirmação foi registrada! Aguardando outros jogadores...';

      if (allConfirmed) {
        // Todos confirmaram, modal será fechado automaticamente
        setTimeout(() => {
          this.isConfirming = false;
          this.confirmationMessage = '';
        }, 2000);
      } else {
        // Resetar estado de carregamento, mas manter mensagem
        this.isConfirming = false;
      }
    } else {
      this.isConfirming = false;
      this.confirmationMessage = 'Erro ao confirmar. Tente novamente.';
    }
  }


  // ✅ NOVO: Método para atualizar estado do draft
  refreshDraftState(): void {
    logConfirmationModal('🔄 [refreshDraftState] Solicitando atualização do estado do draft');
    this.forceRefresh(); // Limpar cache local
    this.onRefreshDraft.emit(); // Notificar componente pai para buscar dados atualizados
  }

  // MÉTODO PARA VERIFICAR SE BOTÃO DEVE APARECER
  shouldShowEditButton(slot: any): boolean {
    if (!slot?.player) {
      logConfirmationModal('🔍 [shouldShowEditButton] Slot ou player inválido');
      return false;
    }

    logConfirmationModal('🔍 [shouldShowEditButton] === VERIFICANDO BOTÃO ===');
    logConfirmationModal('🔍 [shouldShowEditButton] currentPlayer:', this.currentPlayer);
    logConfirmationModal('🔍 [shouldShowEditButton] slot.player:', {
      id: slot.player.id,
      summonerName: slot.player.summonerName,
      name: slot.player.name,
      gameName: slot.player.gameName,
      tagLine: slot.player.tagLine,
      teamIndex: slot.player.teamIndex,
      assignedLane: slot.player.assignedLane
    });

    const isCurrentPlayerResult = this.isCurrentPlayer(slot.player);
    const isBotResult = this.isPlayerBot(slot.player);

    // ✅ NOVO: Não mostrar botão individual para o jogador atual (usará botão principal)
    // Mostrar apenas para outros jogadores humanos ou bots
    const shouldShow = !isCurrentPlayerResult && (!isBotResult || isBotResult);

    logConfirmationModal('🔍 [shouldShowEditButton] Resultado final:', {
      playerName: slot.player.summonerName || slot.player.name,
      slotPlayerGameName: slot.player.gameName,
      slotPlayerTagLine: slot.player.tagLine,
      currentPlayerGameName: this.currentPlayer?.gameName,
      currentPlayerTagLine: this.currentPlayer?.tagLine,
      currentPlayerSummonerName: this.currentPlayer?.summonerName,
      isCurrentPlayer: isCurrentPlayerResult,
      isBot: isBotResult,
      shouldShow: shouldShow,
      hasCurrentPlayer: !!this.currentPlayer,
      reasoning: isCurrentPlayerResult ? 'Jogador atual - usar botão principal' : 'Mostrar botão individual'
    });

    return shouldShow;
  }

  // ❌ MÉTODO OBSOLETO - Removido conforme requisito (botões individuais foram removidos)
  // O único botão de edição é o "Editar Minha Seleção" no rodapé, que usa startEditingCurrentPlayer()

  // MÉTODOS PARA EDIÇÃO
  startEditingPick(playerId: string, phaseIndex: number): void {
    console.log('🟡 [START-EDITING-PICK] === INICIANDO EDIÇÃO ===');
    console.log('🟡 [START-EDITING-PICK] playerId:', playerId);
    console.log('🟡 [START-EDITING-PICK] phaseIndex:', phaseIndex);

    logConfirmationModal('🎯 [startEditingPick] === INICIANDO EDIÇÃO ===');
    logConfirmationModal('🎯 [startEditingPick] playerId:', playerId);
    logConfirmationModal('🎯 [startEditingPick] phaseIndex:', phaseIndex);
    logConfirmationModal('🎯 [startEditingPick] currentPlayer:', this.currentPlayer);
    logConfirmationModal('🎯 [startEditingPick] session presente:', !!this.session);

    console.log('🟡 [START-EDITING-PICK] Emitindo evento onEditPick...');
    this.onEditPick.emit({ playerId, phaseIndex });
    console.log('🟡 [START-EDITING-PICK] Evento onEditPick emitido!');
    logConfirmationModal('🎯 [startEditingPick] Evento onEditPick emitido com sucesso');
  }

  // ✅ NOVO: Método para editar o pick do jogador atual via botão principal
  async startEditingCurrentPlayer(): Promise<void> {
    console.log('🎯 [EDITAR MEU PICK] === INICIANDO ===');
    logConfirmationModal('🎯 [startEditingCurrentPlayer] === INICIANDO EDIÇÃO DO JOGADOR LOGADO ===');

    if (!this.session) {
      console.error('❌ [EDITAR MEU PICK] Session não disponível');
      logConfirmationModal('❌ [startEditingCurrentPlayer] Session não disponível');
      return;
    }

    // ✅ PASSO 1: Buscar dados do jogador LOGADO via LCU (Electron Gateway)
    const electronAPI = (window as any).electronAPI;
    if (!electronAPI || !electronAPI.lcu || !electronAPI.lcu.getCurrentSummoner) {
      console.error('❌ [EDITAR MEU PICK] Electron API não disponível');
      logConfirmationModal('❌ [startEditingCurrentPlayer] Electron API não disponível');
      return;
    }

    try {
      console.log('🔍 [EDITAR MEU PICK] Buscando jogador atual do LCU...');
      const lcuSummoner = await electronAPI.lcu.getCurrentSummoner();

      if (!lcuSummoner) {
        console.error('❌ [EDITAR MEU PICK] Não foi possível obter dados do LCU');
        logConfirmationModal('❌ [startEditingCurrentPlayer] LCU summoner não encontrado');
        return;
      }

      console.log('✅ [EDITAR MEU PICK] Dados do LCU:', {
        gameName: lcuSummoner.gameName,
        tagLine: lcuSummoner.tagLine,
        displayName: lcuSummoner.displayName
      });

      // ✅ PASSO 2: Construir identificador gameName#tagLine
      const gameName = lcuSummoner.gameName || lcuSummoner.displayName;
      const tagLine = lcuSummoner.tagLine || '';
      const playerIdentifier = tagLine ? `${gameName}#${tagLine}` : gameName;

      console.log('🎯 [EDITAR MEU PICK] Identificador do jogador:', playerIdentifier);
      logConfirmationModal('🎯 [startEditingCurrentPlayer] playerIdentifier:', playerIdentifier);

      // ✅ DEBUG: Mostrar TODAS as phases disponíveis
      console.log('📋 [EDITAR MEU PICK] TODAS AS PHASES:', JSON.stringify(this.session.phases, null, 2));
      console.log('📋 [EDITAR MEU PICK] Total de phases:', this.session.phases?.length || 0);

      // ✅ PASSO 3: Buscar o pick deste jogador nas phases
      let phaseIndex = -1;
      let currentChampionId: string | null = null;

      if (this.session.phases) {
        console.log('🔍 [EDITAR MEU PICK] Procurando pick para:', playerIdentifier);

        for (let i = 0; i < this.session.phases.length; i++) {
          const phase = this.session.phases[i];

          // ✅ CORREÇÃO: O campo correto é 'byPlayer', não 'playerName' ou 'playerId'
          const phasePlayer = (phase as any).byPlayer || phase.playerName || phase.playerId;

          console.log(`🔍 [EDITAR MEU PICK] Phase ${i}:`, {
            type: (phase as any).type || phase.action,
            byPlayer: phasePlayer,
            championId: (phase as any).championId || phase.champion?.id || 'SEM CAMPEÃO',
            championName: (phase as any).championName
          });

          // ✅ Verificar se é um PICK (type='pick' no backend)
          const isPick = (phase as any).type === 'pick' || phase.action === 'pick';
          const hasChampion = (phase as any).championId || phase.champion?.id;

          if (isPick && hasChampion) {
            console.log(`  ➡️ É um PICK! Comparando:`);
            console.log(`     playerIdentifier: "${playerIdentifier}"`);
            console.log(`     phasePlayer (byPlayer): "${phasePlayer}"`);
            console.log(`     Igualdade: ${phasePlayer === playerIdentifier}`);

            // ✅ Comparar byPlayer com o identificador construído
            if (phasePlayer === playerIdentifier) {
              phaseIndex = i;
              currentChampionId = (phase as any).championId || phase.champion?.id || null;
              console.log('✅✅✅ [EDITAR MEU PICK] Pick ENCONTRADO!', {
                phaseIndex,
                currentChampionId,
                playerName: phase.playerName
              });
              break;
            }
          }
        }
      }

      if (phaseIndex === -1) {
        console.error('❌ [EDITAR MEU PICK] Pick NÃO encontrado para:', playerIdentifier);
        console.error('❌ [EDITAR MEU PICK] Verifique os logs acima para ver todas as phases');
        logConfirmationModal('❌ [startEditingCurrentPlayer] Pick não encontrado');
        alert('❌ Não foi possível encontrar seu pick no draft. Verifique se você participou deste draft.');
        return;
      }

      // ✅ PASSO 4: Iniciar edição com o identificador correto
      console.log('🚀 [EDITAR MEU PICK] Iniciando edição:', {
        playerIdentifier,
        phaseIndex,
        currentChampionId
      });

      logConfirmationModal('🎯 [startEditingCurrentPlayer] Iniciando edição:', {
        playerId: playerIdentifier,
        phaseIndex: phaseIndex
      });

      this.startEditingPick(playerIdentifier, phaseIndex);

    } catch (error) {
      console.error('❌ [EDITAR MEU PICK] Erro ao buscar dados do LCU:', error);
      logConfirmationModal('❌ [startEditingCurrentPlayer] Erro:', error);
      alert('❌ Erro ao buscar seus dados do League of Legends. Verifique se o cliente está aberto.');
    }
  }

  confirmBotPick(playerId: string, phaseIndex: number): void {
    console.log('🤖 [CONFIRMAR BOT] Confirmando pick de bot:', playerId);
    // Para bots, apenas confirmar automaticamente (não precisa editar)
    // Pode ser implementado no futuro se necessário
    logConfirmationModal('🤖 [confirmBotPick] Bot confirmado automaticamente:', playerId);
  }

  // MÉTODOS PARA CACHE
  private invalidateCache(): void {
    this._cachedBannedanys = null;
    this._cachedBlueTeamPicks = null;
    this._cachedRedTeamPicks = null;
    this._cachedBlueTeamByLane = null;
    this._cachedRedTeamByLane = null;
    this._lastCacheUpdate = 0; // ✅ CORREÇÃO: Forçar cache inválido imediatamente
  }

  private isCacheValid(): boolean {
    return Date.now() - this._lastCacheUpdate < this.CACHE_DURATION;
  }

  // MÉTODOS AUXILIARES
  onImageError(event: any, champion: any): void {
    event.target.src = 'assets/images/champion-placeholder.svg';
  }

  // MÉTODO PARA QUANDO O MODAL SE TORNA VISÍVEL
  onModalShow(): void {
    if (this.isVisible) {
      this.invalidateCache();
    }
  }

  // ✅ NOVO: Método para forçar atualização completa (público para ser chamado do componente pai)
  forceRefresh(): void {
    console.log('🔄 [CONFIRMATION-MODAL] forceRefresh() CHAMADO - forçando atualização completa');
    console.log('🔄 [CONFIRMATION-MODAL] Estado atual:', {
      isVisible: this.isVisible,
      hasSession: !!this.session,
      sessionId: this.session?.id || this.session?.matchId,
      hasTeams: !!this.session?.teams,
      blueTeamPicks: this.session?.teams?.blue?.allPicks?.length || 0,
      redTeamPicks: this.session?.teams?.red?.allPicks?.length || 0,
      blueTeamPicksArray: this.session?.teams?.blue?.allPicks,
      redTeamPicksArray: this.session?.teams?.red?.allPicks,
      bluePlayersCount: this.session?.teams?.blue?.players?.length || 0,
      redPlayersCount: this.session?.teams?.red?.players?.length || 0
    });

    // ✅ CRÍTICO: Log detalhado dos players e actions
    if (this.session?.teams?.blue?.players) {
      console.log('🔵 [CONFIRMATION-MODAL] Blue team players:', this.session.teams.blue.players.map((p: any) => ({
        name: p.summonerName || p.name,
        actions: p.actions?.map((a: any) => ({
          type: a.type,
          championId: a.championId,
          status: a.status
        }))
      })));
    }
    if (this.session?.teams?.red?.players) {
      console.log('🔴 [CONFIRMATION-MODAL] Red team players:', this.session.teams.red.players.map((p: any) => ({
        name: p.summonerName || p.name,
        actions: p.actions?.map((a: any) => ({
          type: a.type,
          championId: a.championId,
          status: a.status
        }))
      })));
    }

    logConfirmationModal('🔄 [forceRefresh] Forçando atualização do modal de confirmação');

    // ✅ CRÍTICO: Invalidar TODOS os caches
    this.invalidateCache();
    this._cachedBannedanys = null;
    this._cachedBlueTeamPicks = null;
    this._cachedRedTeamPicks = null;
    this._cachedBlueTeamByLane = null;
    this._cachedRedTeamByLane = null;
    this._lastCacheUpdate = 0; // Forçar recache

    // ✅ CRÍTICO: Forçar recálculo COMPLETO de todos os dados
    if (this.session) {
      console.log('🔄 [CONFIRMATION-MODAL] Recalculando dados...');
      const bluePicks = this.getTeamPicks('blue');
      const redPicks = this.getTeamPicks('red');
      const blueBans = this.getTeamBans('blue');
      const redBans = this.getTeamBans('red');
      const blueByLane = this.getTeamByLane('blue');
      const redByLane = this.getTeamByLane('red');

      console.log('✅ [CONFIRMATION-MODAL] Dados recalculados:', {
        bluePicks: bluePicks.length,
        redPicks: redPicks.length,
        blueBans: blueBans.length,
        redBans: redBans.length,
        blueByLane: blueByLane.length,
        redByLane: redByLane.length
      });
    } else {
      console.warn('⚠️ [CONFIRMATION-MODAL] Session não existe - não é possível recalcular');
    }

    // ✅ CRÍTICO: Forçar detecção de mudanças (OnPush requer)
    this.cdr.markForCheck();
    this.cdr.detectChanges();

    console.log('✅ [CONFIRMATION-MODAL] forceRefresh() concluído - tudo recalculado e detecção de mudanças forçada');
    logConfirmationModal('🔄 [forceRefresh] Cache invalidado e dados recalculados com sucesso');
  }

  // ✅ NOVO: Métodos para status e blur dos jogadores

  /**
   * Identifica o usuário atual via LCU
   */
  private identifyCurrentUser(): void {
    // Tentar obter dados do usuário atual do window.appComponent
    const appComponent = (window as any).appComponent;
    if (appComponent?.currentPlayer) {
      const currentUser = appComponent.currentPlayer;
      logConfirmationModal('🔍 [ConfirmationModal] Usuário atual identificado via LCU:', {
        displayName: currentUser.displayName,
        summonerName: currentUser.summonerName,
        gameName: currentUser.gameName,
        tagLine: currentUser.tagLine
      });

      this.markCurrentUserInPlayers(currentUser);
    } else {
      logConfirmationModal('⚠️ [ConfirmationModal] Usuário atual não disponível via LCU');
    }
  }

  /**
   * Marca o jogador atual nos dados da partida
   */
  private markCurrentUserInPlayers(currentUser: any): void {
    if (!this.session) return;

    const allPlayers = [
      ...(this.session.blueTeam || []),
      ...(this.session.redTeam || [])
    ];

    allPlayers.forEach(player => {
      if (this.isPlayerCurrentUser(player, currentUser)) {
        player.isCurrentUser = true;
        logConfirmationModal('✅ [ConfirmationModal] Jogador marcado como usuário atual:', player.summonerName);
      }
    });
  }

  /**
   * Verifica se um jogador é o usuário atual baseado nos dados do LCU
   */
  private isPlayerCurrentUser(player: any, currentUser: any): boolean {
    if (!player || !currentUser) return false;

    // Comparar por displayName (formato completo com #)
    if (currentUser.displayName && player.summonerName === currentUser.displayName) {
      return true;
    }

    // Comparar por summonerName
    if (currentUser.summonerName && player.summonerName === currentUser.summonerName) {
      return true;
    }

    // Comparar por gameName#tagLine
    if (currentUser.gameName && currentUser.tagLine) {
      const fullName = `${currentUser.gameName}#${currentUser.tagLine}`;
      if (player.summonerName === fullName) {
        return true;
      }
    }

    // Comparar por gameName (sem tag)
    if (currentUser.gameName && player.summonerName === currentUser.gameName) {
      return true;
    }

    return false;
  }

  /**
   * Verifica se um jogador é o usuário atual (para uso no template)
   */
  isCurrentUser(player: any): boolean {
    return player?.isCurrentUser === true;
  }

  /**
   * Obtém o status de confirmação de um jogador
   * ✅ CORREÇÃO: Usar confirmationData.confirmations (array de strings) para verificar status
   */
  getPlayerConfirmationStatus(summonerName: string): 'pending' | 'confirmed' | 'declined' | 'timeout' {
    // ✅ PRIORIDADE 1: Verificar em confirmationData.confirmations (fonte mais atualizada)
    if (this.confirmationData?.confirmations && Array.isArray(this.confirmationData.confirmations)) {
      const normalizedSummonerName = summonerName?.toLowerCase().trim();
      const isConfirmed = this.confirmationData.confirmations.some((confirmedPlayer: string) =>
        confirmedPlayer?.toLowerCase().trim() === normalizedSummonerName
      );

      if (isConfirmed) {
        return 'confirmed';
      }
    }

    // ✅ FALLBACK: Tentar buscar no session (menos confiável)
    const allPlayers = [
      ...(this.session?.blueTeam || []),
      ...(this.session?.redTeam || [])
    ];

    const player = allPlayers.find(p => {
      const pName = p.summonerName || p.gameName || p.name || '';
      return pName.toLowerCase().trim() === summonerName.toLowerCase().trim();
    });

    if (player?.acceptanceStatus) {
      return player.acceptanceStatus;
    }

    return 'pending';
  }


  /**
   * Obtém o ícone de status de confirmação
   */
  getConfirmationStatusIcon(player: any): string {
    const status = this.getPlayerConfirmationStatus(player.summonerName);
    console.log(`🎯 [ConfirmationModal] Ícone para ${player.summonerName}: ${status}`);
    switch (status) {
      case 'confirmed': return '✅';
      case 'declined': return '❌';
      case 'timeout': return '⏰';
      default: return '⏳';
    }
  }

  /**
   * Obtém a classe CSS para o status de confirmação
   */
  getConfirmationStatusClass(player: any): string {
    const status = this.getPlayerConfirmationStatus(player.summonerName);
    console.log(`🎨 [ConfirmationModal] Classe para ${player.summonerName}: confirmation-status-${status}`);
    return `confirmation-status-${status}`;
  }

  /**
   * Obtém contagem de confirmações
   * ✅ CORREÇÃO: Usar confirmationData se disponível (fonte mais atualizada)
   */
  getConfirmationCount(): { confirmed: number; total: number } {
    // ✅ PRIORIDADE 1: Usar confirmationData (vem do WebSocket em tempo real)
    if (this.confirmationData) {
      return {
        confirmed: this.confirmationData.confirmedCount || this.confirmationData.confirmations?.length || 0,
        total: this.confirmationData.totalPlayers || 10
      };
    }

    // ✅ FALLBACK: Usar valores internos atualizados
    if (this.confirmedCount > 0 || this.totalPlayers > 0) {
      return {
        confirmed: this.confirmedCount,
        total: this.totalPlayers
      };
    }

    // ✅ FALLBACK 2: Tentar contar do session (menos confiável)
    let confirmed = 0;
    const allPlayers = [
      ...(this.session?.blueTeam || []),
      ...(this.session?.redTeam || [])
    ];

    allPlayers.forEach(player => {
      if (player.acceptanceStatus === 'confirmed') {
        confirmed++;
      }
    });

    return {
      confirmed,
      total: allPlayers.length || 10
    };
  }

  /**
   * Verifica se todos os jogadores confirmaram
   */
  haveAllPlayersConfirmed(): boolean {
    const count = this.getConfirmationCount();
    return count.confirmed === count.total;
  }

  /**
   * Obtém progresso de confirmação em porcentagem
   * ✅ CORREÇÃO: Usar confirmationData se disponível
   */
  getConfirmationProgress(): number {
    const count = this.getConfirmationCount();
    if (count.total === 0) return 0;
    const progress = Math.round((count.confirmed / count.total) * 100);

    // ✅ LOG para debug
    console.log('📊 [getConfirmationProgress]', {
      confirmed: count.confirmed,
      total: count.total,
      progress: progress + '%',
      hasConfirmationData: !!this.confirmationData
    });

    return progress;
  }

  // ✅ NOVO: Métodos WebSocket para atualizações em tempo real

  /**
   * ✅ NOVO: Configura observables do ElectronEventsService (mesma técnica do draft)
   */
  private setupObservableListeners(): void {
    console.log('🎯 [CONFIRMATION-MODAL] setupObservableListeners CHAMADO - registrando listener draft_updated$');

    // ✅ SIMPLIFICADO: Listener para draft_updated APENAS para invalidar cache
    // O modal agora usa APENAS a session do pai (@Input) como fonte única de verdade
    // Não mais atualiza a session diretamente - deixa o pai fazer isso
    this.subscriptions.push(
      this.electronEvents.draftUpdated$.subscribe((data: any) => {
        console.log('🎯 [CONFIRMATION-MODAL] draft_updated$ recebido');

        const dataMatchId = String(data?.matchId || data?.id);

        // Se o evento é para este match E o modal está visível, apenas invalidar cache
        // O pai já atualizou a session, então apenas forçar recálculo
        if (data && dataMatchId && this.activeMatchId && dataMatchId === this.activeMatchId && this.isVisible) {
          console.log('✅ [CONFIRMATION-MODAL] draft_updated para este match - invalidando cache E recalculando dados');

          // ✅ Invalidar cache e forçar recálculo com a session do pai (que já foi atualizada)
          this.invalidateCache();

          // ✅ CRÍTICO: Recalcular TODOS os dados para atualizar a UI após changePick
          // Apenas invalidar cache não é suficiente - precisamos forçar recálculo IMEDIATO
          this.getTeamPicks('blue');
          this.getTeamPicks('red');
          this.getTeamBans('blue');
          this.getTeamBans('red');
          this.getTeamByLane('blue');
          this.getTeamByLane('red');

          // ✅ Forçar detecção de mudanças (OnPush requer)
          this.cdr.markForCheck();
          this.cdr.detectChanges();

          console.log('✅ [CONFIRMATION-MODAL] Cache invalidado E dados recalculados - UI atualizada');
          logConfirmationModal('✅ [draft_updated$] Cache invalidado e dados recalculados, usando session do pai');
        }
      })
    );

    // ✅ Listener para draft_confirmation_update (progresso de confirmação)
    this.subscriptions.push(
      this.electronEvents.draftConfirmationUpdate$.subscribe((data: any) => {
        // ✅ CORREÇÃO: Verificar matchId de múltiplas formas
        const sessionMatchId = this.session?.id || this.session?.matchId;
        const dataMatchId = data?.matchId || data?.id;

        if (data && dataMatchId && sessionMatchId && String(dataMatchId) === String(sessionMatchId)) {
          console.log('📊 [CONFIRMATION-MODAL] draft_confirmation_update recebido via Observable!', data);
          this.handleConfirmationProgress(data);
          this.cdr.markForCheck();
          this.cdr.detectChanges();
        }
      })
    );

    console.log('✅ [CONFIRMATION-MODAL] Observables configurados (mesma técnica do draft)');
  }

  /**
   * Configura os listeners WebSocket para atualizações em tempo real (LEGADO - manter para compatibilidade)
   */
  private setupWebSocketListeners(): void {
    if (this.wsSubscription) {
      this.wsSubscription.unsubscribe();
    }

    // ✅ NOVO: Escutar evento customizado do app.ts
    document.addEventListener('draftConfirmationUpdate', this.handleCustomEvent);

    this.wsSubscription = this.apiService.onWebSocketMessage().subscribe({
      next: (message: any) => {
        const { type, data } = message;

        if (type === 'draft_updated' && data?.type === 'draft_confirmation_update') {
          this.handleConfirmationProgress(data);
        } else if (type === 'draft_confirmation_update') {
          this.handleConfirmationUpdate(data);
        }
      },
      error: (error: any) => {
        console.error('❌ [ConfirmationModal] Erro no WebSocket:', error);
      }
    });

    console.log('🔌 [ConfirmationModal] WebSocket listeners configurados');
  }

  /**
   * Limpa os listeners WebSocket
   */
  private cleanupWebSocketListeners(): void {
    if (this.wsSubscription) {
      this.wsSubscription.unsubscribe();
      this.wsSubscription = undefined;
      console.log('🔌 [ConfirmationModal] WebSocket listeners limpos');
    }

    // ✅ NOVO: Remover listener customizado
    document.removeEventListener('draftConfirmationUpdate', this.handleCustomEvent);
  }

  // ✅ NOVO: Método para lidar com evento customizado
  private readonly handleCustomEvent = (event: any): void => {
    console.log('📊 [ConfirmationModal] Evento customizado recebido:', event.detail);
    this.handleConfirmationProgress(event.detail);
  };

  /**
   * Manipula atualizações de progresso de confirmação
   */
  private handleConfirmationProgress(data: any): void {
    console.log('📊 [ConfirmationModal] Progresso de confirmação recebido:', data);

    if (data.confirmations && Array.isArray(data.confirmations)) {
      // ✅ CORREÇÃO: Usar mesma técnica do match_found
      const allPlayers = [
        ...(this.session?.blueTeam || []),
        ...(this.session?.redTeam || [])
      ];

      console.log(`📊 [ConfirmationModal] Processando ${data.confirmations.length} confirmações para ${allPlayers.length} jogadores`);

      // Atualizar status de todos os jogadores para 'pending' primeiro
      allPlayers.forEach(player => {
        player.acceptanceStatus = 'pending';
        console.log(`📊 [ConfirmationModal] Resetando ${player.summonerName} para pending`);
      });

      // Marcar jogadores confirmados como 'confirmed'
      data.confirmations.forEach((confirmedPlayerName: string) => {
        const player = allPlayers.find(p =>
          p.summonerName?.toLowerCase() === confirmedPlayerName?.toLowerCase() ||
          (p.riotIdGameName && p.riotIdTagline && `${p.riotIdGameName}#${p.riotIdTagline}`.toLowerCase() === confirmedPlayerName?.toLowerCase())
        );

        if (player) {
          player.acceptanceStatus = 'confirmed';
          player.acceptedAt = new Date().toISOString();
          console.log('✅ [ConfirmationModal] Jogador confirmado:', player.summonerName);
        } else {
          console.log('⚠️ [ConfirmationModal] Jogador não encontrado:', confirmedPlayerName);
        }
      });

      // Atualizar contadores
      this.confirmedCount = data.confirmedCount || 0;
      this.totalPlayers = data.totalPlayers || 10;

      console.log(`📊 [ConfirmationModal] Atualizado: ${this.confirmedCount}/${this.totalPlayers} confirmados`);
      console.log(`📊 [ConfirmationModal] Progresso: ${this.getConfirmationProgress()}%`);

      // ✅ NOVO: Forçar detecção de mudanças
      this.cdr.markForCheck();
      this.cdr.detectChanges(); // ✅ CRÍTICO: Forçar detecção imediata (OnPush requer)

      setTimeout(() => {
        console.log(`🔄 [ConfirmationModal] Re-check progresso: ${this.getConfirmationProgress()}%`);
        console.log(`🔄 [ConfirmationModal] Re-check contagem: ${this.getConfirmationCount().confirmed}/${this.getConfirmationCount().total}`);
      }, 100);
    } else {
      console.log('⚠️ [ConfirmationModal] Dados de confirmação inválidos:', data);
    }
  }

  /**
   * Manipula atualizações individuais de confirmação
   */
  private handleConfirmationUpdate(data: any): void {
    console.log('🔄 [ConfirmationModal] Atualização de confirmação recebida:', data);

    if (data.playerName && data.status) {
      const allPlayers = [
        ...(this.session?.blueTeam || []),
        ...(this.session?.redTeam || [])
      ];

      const player = allPlayers.find(p => p.summonerName === data.playerName);
      if (player) {
        player.acceptanceStatus = data.status;
        if (data.status === 'confirmed') {
          player.acceptedAt = new Date().toISOString();
        }
        console.log(`🔄 [ConfirmationModal] Status atualizado para ${data.playerName}: ${data.status}`);
        // ✅ NOVO: Forçar detecção de mudanças
        this.cdr.markForCheck();
      }
    }
  }


  /**
   * Inicializa os status de confirmação de todos os jogadores
   */
  private initializeConfirmationStatuses(): void {
    if (!this.session) {
      console.log('⚠️ [ConfirmationModal] Session não disponível para inicialização');
      return;
    }

    const allPlayers = [
      ...(this.session.blueTeam || []),
      ...(this.session.redTeam || [])
    ];

    console.log(`🔄 [ConfirmationModal] Inicializando ${allPlayers.length} jogadores`);

    allPlayers.forEach(player => {
      // ✅ CORREÇÃO: Usar mesma técnica do match_found - inicializar como pending
      player.acceptanceStatus = 'pending';
      player.isCurrentUser = player.isCurrentUser || false;
      console.log(`🔄 [ConfirmationModal] Jogador ${player.summonerName} inicializado como pending`);
    });

    this.confirmedCount = 0;
    this.totalPlayers = allPlayers.length;

    console.log(`🔄 [ConfirmationModal] Status inicializados: ${this.confirmedCount}/${this.totalPlayers} jogadores`);
    console.log(`🔄 [ConfirmationModal] Progresso inicial: ${this.getConfirmationProgress()}%`);
  }
}
