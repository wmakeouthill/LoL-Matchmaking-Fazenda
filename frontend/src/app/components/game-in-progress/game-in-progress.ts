import { Component, input, output, OnInit, OnDestroy, OnChanges, SimpleChanges, ChangeDetectionStrategy, ChangeDetectorRef, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api';
import { ChampionService } from '../../services/champion.service';
import { interval, Subscription, Observable, of, firstValueFrom } from 'rxjs';
import { shareReplay } from 'rxjs/operators';
import { ProfileIconService } from '../../services/profile-icon.service';
import { BotService } from '../../services/bot.service';
import { LcuMatchConfirmationModalComponent } from '../lcu-match-confirmation-modal/lcu-match-confirmation-modal';
import { WinnerConfirmationModalComponent } from '../winner-confirmation-modal/winner-confirmation-modal.component';
import { SpectatorsModalComponent } from '../spectators-modal/spectators-modal.component';
import { ElectronEventsService } from '../../services/electron-events.service';

type TeamColor = 'blue' | 'red';

interface GameData {
  sessionId: string;
  gameId: string;
  team1: any[];
  team2: any[];
  startTime: Date;
  pickBanData: any;
  isCustomGame: boolean;
  id?: number; // ✅ ID da partida (pode vir como 'id')
  matchId?: number; // ✅ ID da partida (pode vir como 'matchId')
  originalMatchId?: any;
  originalMatchData?: any;
  riotId?: string | null;
}

interface GameResult {
  sessionId: string;
  gameId: string;
  winner: TeamColor | null;
  duration: number;
  endTime: Date;
  team1: any[];
  team2: any[];
  pickBanData: any;
  detectedByLCU: boolean;
  isCustomGame: boolean;
  originalMatchId?: any;
  originalMatchData?: any;
  riotId?: string | null;
}

// ✅ NOVO: Interface para status de votação dos jogadores
interface PlayerVoteStatus {
  summonerName: string;
  voteStatus: 'pending' | 'voted'; // ✅ CORREÇÃO: Apenas pending ou voted
  votedAt?: string; // ISO timestamp quando votou
  votedFor?: 'blue' | 'red'; // Qual time votou
  isCurrentUser?: boolean; // Se é o usuário logado via LCU
}

// ✅ DESABILITADO: Salvamento de logs em arquivo (por solicitação do usuário)
function logGameInProgress(...args: any[]) {
  // Apenas console.log para debug no DevTools
  console.log('[GameInProgress]', ...args);
}

@Component({
  selector: 'app-game-in-progress',
  standalone: true,
  imports: [CommonModule, WinnerConfirmationModalComponent, SpectatorsModalComponent],
  templateUrl: './game-in-progress.html',
  styleUrl: './game-in-progress.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GameInProgressComponent implements OnInit, OnDestroy, OnChanges {
  // ✅ SIGNALS: Converter @Input → input<T>()
  gameData = input<GameData | null>(null);
  currentPlayer = input<any>(null);

  // ✅ SIGNALS: Converter @Output → output<T>()
  onGameComplete = output<GameResult>();
  onGameCancel = output<void>();

  // ✅ SIGNALS: Game state convertido para signal()
  gameStatus = signal<'waiting' | 'in-progress' | 'ended'>('waiting');
  gameStartTime = signal<Date | null>(null);
  gameDuration = signal<number>(0);

  // LCU detection - DISABLED (detecção automática removida)
  lcuGameDetected = signal<boolean>(false);
  lcuDetectionEnabled = signal<boolean>(false); // ✅ DESABILITADO

  // Manual result declaration
  selectedWinner = signal<TeamColor | null>(null);

  // ✅ SIGNALS: Modal de confirmação de vencedor manual
  showWinnerConfirmationModal = signal<boolean>(false);
  customMatchesForConfirmation = signal<any[]>([]);
  isAutoDetecting = signal<boolean>(false);

  // ✅ SIGNALS: Controle do modal de espectadores
  showSpectatorsModal = signal<boolean>(false);

  // ✅ SIGNALS: Status de votação dos jogadores
  playerVoteStatuses = signal<Map<string, PlayerVoteStatus>>(new Map());
  votedCount = signal<number>(0);
  totalPlayers = signal<number>(10);

  // ✅ NOVO: WebSocket subscription para atualizações em tempo real
  private voteWsSubscription?: Subscription;

  // ✅ NOVO: Array para gerenciar subscrições de observables (mesma técnica do draft confirmation)
  private subscriptions: Subscription[] = [];

  // ✅ SIGNALS: Getter para obter o matchId com fallback robusto
  get matchId(): number | undefined {
    // ✅ SIGNALS: Extrair valor do input signal
    const data = this.gameData();
    // Tentar todas as propriedades possíveis onde o backend pode enviar o ID
    return data?.matchId ||
      data?.id ||
      data?.originalMatchId;
  }

  // ✅ SIGNALS: Getter para obter summoner name do currentPlayer
  get summonerName(): string {
    // ✅ SIGNALS: Extrair valor do input signal
    const player = this.currentPlayer();
    if (!player) return '';

    // Prioridade 1: displayName
    if (player.displayName) {
      return player.displayName;
    }

    // Prioridade 2: gameName#tagLine
    if (player.gameName && player.tagLine) {
      return `${player.gameName}#${player.tagLine}`;
    }

    // Prioridade 3: summonerName
    if (player.summonerName) {
      return player.summonerName;
    }

    // Prioridade 4: name
    if (player.name) {
      return player.name;
    }

    return '';
  }

  // Timers
  private gameTimer: Subscription | null = null;
  private lcuDetectionTimer: Subscription | null = null;

  // Game tracking
  private currentGameSession: any = null;

  // ✅ CACHE: Para evitar recalcular a cada ciclo de detecção de mudanças
  private cachedTeamPlayers: Map<TeamColor, any[]> = new Map();
  private cachedTeamBans: Map<TeamColor, any[]> = new Map();
  private cachedTeamPicks: Map<TeamColor, any[]> = new Map();
  private cachedIsBot: Map<string, boolean> = new Map(); // Cache para isBot
  private cachedProfileIconUrls: Map<string, Observable<string | null>> = new Map(); // Cache para URLs de ícones
  private cacheVersion: number = 0;

  constructor(
    private readonly apiService: ApiService,
    private readonly championService: ChampionService,
    private readonly profileIconService: ProfileIconService,
    public botService: BotService,
    private readonly cdr: ChangeDetectorRef,
    private electronEvents: ElectronEventsService
  ) { } ngOnInit() {
    logGameInProgress('🚀 [GameInProgress] Inicializando componente...');

    // ✅ SIGNALS: Extrair valor do input signal
    const data = this.gameData();

    logGameInProgress('📊 [GameInProgress] gameData recebido:', {
      hasGameData: !!data,
      originalMatchId: data?.originalMatchId,
      gameDataKeys: data ? Object.keys(data) : [],
      hasTeam1: !!(data?.team1),
      hasTeam2: !!(data?.team2),
      team1Length: data?.team1?.length || 0,
      team2Length: data?.team2?.length || 0,
      fullGameData: data
    });
    logGameInProgress('👤 [GameInProgress] currentPlayer:', this.currentPlayer());

    // ✅ NOVO: Identificar usuário atual
    this.identifyCurrentUser();

    // ✅ NOVO: Configurar listeners WebSocket para votação (migrando para observables)
    this.setupVoteWebSocketListeners();
    this.setupVoteObservables(); // ✅ NOVO: Usar observables do ElectronEventsService (mesma técnica do draft)

    // ✅ SIGNALS: Verificar se temos gameData usando o valor extraído
    if (data) {
      this.initializeGame();

      // ✅ NOVO: Inicializar status de votação
      this.resetAllVoteStatuses();

      // ✅ NOVO: Notificar backend que este jogador JÁ ESTÁ vendo o game
      // Isso permite que o backend PARE de enviar retry desnecessário
      this.sendGameAcknowledgment();
    } else {
      logGameInProgress('⏳ [GameInProgress] Aguardando gameData...');
    }
  }

  ngOnChanges(changes: SimpleChanges) {
    logGameInProgress('🔄 [GameInProgress] ngOnChanges detectado:', changes);

    // ✅ CORREÇÃO: Detectar quando gameData é recebido
    if (changes['gameData']?.currentValue && !changes['gameData']?.previousValue) {
      logGameInProgress('🎮 [GameInProgress] gameData recebido via ngOnChanges, inicializando jogo...');
      this.invalidateCache(); // Limpar cache quando dados mudam
      this.initializeGame();

      // ✅ NOVO: Inicializar status de votação
      this.resetAllVoteStatuses();
    }
  }

  /**
   * ✅ NOVO: Invalidar cache quando dados mudam
   */
  private invalidateCache(): void {
    this.cacheVersion++;
    this.cachedTeamPlayers.clear();
    this.cachedTeamBans.clear();
    this.cachedTeamPicks.clear();
    this.cachedIsBot.clear();
    this.cachedProfileIconUrls.clear();
  }

  /**
   * ✅ NOVO: Wrapper com cache para botService.isBot()
   * Evita chamadas recursivas no template
   */
  isPlayerBot(player: any): boolean {
    if (!player) return false;

    // Criar chave única para o jogador
    const key = player.id || player.summonerName || player.name || JSON.stringify(player);

    // Verificar cache
    if (this.cachedIsBot.has(key)) {
      return this.cachedIsBot.get(key)!;
    }

    // Calcular e armazenar no cache
    const isBot = this.botService.isBot(player);
    this.cachedIsBot.set(key, isBot);
    return isBot;
  }

  ngOnDestroy() {
    this.stopTimers();
    this.cleanupVoteWebSocketListeners(); // ✅ NOVO: Limpar listeners WebSocket

    // ✅ CRÍTICO: Limpar todas as subscrições de observables
    this.subscriptions.forEach(sub => sub.unsubscribe());
    this.subscriptions = [];
    logGameInProgress('[GameInProgress] ✅ Subscrições de observables limpas');
  } private initializeGame() {
    logGameInProgress('🎮 [GameInProgress] Inicializando jogo...');
    this.logGameDataSnapshot();

    if (!this.ensureGameData()) {
      return;
    }

    this.setupInitialState();
    this.normalizePickBanDataSafe();

    // ✅ CORREÇÃO: Garantir que campeões sejam carregados antes de hidratar
    this.loadChampionsAndHydrate();

    // Start game timer only
    this.startGameTimer();
  }

  /**
   * ✅ NOVO: Carrega campeões do Data Dragon e depois hidrata os jogadores
   */
  private loadChampionsAndHydrate(): void {
    // Tentar carregar qualquer campeão para forçar o carregamento do cache
    this.championService.getChampionById(1).subscribe({
      next: () => {
        logGameInProgress('✅ Cache de campeões carregado, hidratando jogadores...');
        // Aguardar um pouco para garantir que o cache foi populado
        setTimeout(() => {
          this.hydratePlayersFromPickBanData();
        }, 100);
      },
      error: (err: any) => {
        logGameInProgress('⚠️ Erro ao carregar cache de campeões:', err);
        // Tentar hidratar mesmo assim
        this.hydratePlayersFromPickBanData();
      }
    });
  }

  /**
   * ✅ NOVO: Resolve nomes de campeões no pickBanData quando vêm como "Champion X"
   */
  private resolveChampionNamesInPickBanData(pickBanData: any): void {
    if (!pickBanData) return;

    const resolveChampionInAction = (action: any) => {
      if (!action) return;

      // Se já tem nome válido, não precisa resolver
      if (action.championName && !action.championName.startsWith('Champion ')) return;

      // Extrair championId
      const championId = action.championId || action.champion_id || action.champion?.id;
      if (!championId) return;

      const idNum = typeof championId === 'string' ? parseInt(championId, 10) : championId;
      const realName = this.getChampionNameById(idNum);

      if (realName && realName !== 'Minion') {
        action.championName = realName;
        if (action.champion) {
          action.champion.name = realName;
        } else {
          action.champion = { id: idNum, name: realName };
        }
      }
    };

    // Resolver em team1 e team2 (estrutura do banco)
    if (pickBanData.team1 && Array.isArray(pickBanData.team1)) {
      pickBanData.team1.forEach((player: any) => {
        if (player.actions && Array.isArray(player.actions)) {
          player.actions.forEach(resolveChampionInAction);
        }
      });
    }

    if (pickBanData.team2 && Array.isArray(pickBanData.team2)) {
      pickBanData.team2.forEach((player: any) => {
        if (player.actions && Array.isArray(player.actions)) {
          player.actions.forEach(resolveChampionInAction);
        }
      });
    }

    // Resolver em teams.blue e teams.red (estrutura alternativa)
    if (pickBanData.teams) {
      if (pickBanData.teams.blue?.players) {
        pickBanData.teams.blue.players.forEach((player: any) => {
          if (player.actions && Array.isArray(player.actions)) {
            player.actions.forEach(resolveChampionInAction);
          }
        });
      }
      if (pickBanData.teams.red?.players) {
        pickBanData.teams.red.players.forEach((player: any) => {
          if (player.actions && Array.isArray(player.actions)) {
            player.actions.forEach(resolveChampionInAction);
          }
        });
      }
    }

    // Resolver em actions diretas
    if (pickBanData.actions && Array.isArray(pickBanData.actions)) {
      pickBanData.actions.forEach(resolveChampionInAction);
    }

    logGameInProgress('✅ [GameInProgress] Nomes de campeões resolvidos no pickBanData');
  }

  // ✅ NOVO: Hidratar campeões dos jogadores usando pick_ban_data (suporta resume/polling)
  private hydratePlayersFromPickBanData(): void {
    try {
      if (!this.gameData) return;

      // ✅ RESOLUÇÃO DIRETA: Resolver campeões diretamente nos jogadores de team1/team2
      const resolvePlayerChampion = (player: any) => {
        if (!player) return;

        // Extrair championId das actions do jogador
        let championId: number | null = null;

        if (player.actions && Array.isArray(player.actions)) {
          const pickAction = player.actions.find((a: any) =>
            a.type === 'pick' || a.phase?.includes('pick')
          );

          if (pickAction) {
            const id = pickAction.championId || pickAction.champion_id || pickAction.champion?.id;
            championId = typeof id === 'string' ? parseInt(id, 10) : id;
            logGameInProgress(`🔍 Extraído championId das actions: ${championId} para ${player.summonerName}`);
          }
        }

        // Se não encontrou nas actions, tentar no próprio player
        if (!championId) {
          const id = player.championId || player.champion_id || player.champion?.id;
          championId = typeof id === 'string' ? parseInt(id, 10) : id;
          if (championId) {
            logGameInProgress(`🔍 Extraído championId do player: ${championId} para ${player.summonerName}`);
          }
        }

        // Resolver nome do campeão
        if (championId) {
          const championName = this.getChampionNameById(championId);
          logGameInProgress(`🔍 Tentando resolver championId ${championId}: resultado = ${championName}`);

          if (championName && championName !== 'Minion') {
            player.championId = championId;
            player.championName = championName;
            player.champion = {
              id: championId,
              name: championName
            };

            logGameInProgress(`✅ Resolvido campeão para ${player.summonerName}: ${championName} (ID: ${championId})`);
          }
        }
      };

      // Hidratar times
      const data = this.gameData();
      if (data && Array.isArray(data.team1)) {
        logGameInProgress('🔍 Resolvendo campeões do Team 1...');
        data.team1.forEach(resolvePlayerChampion);
      }
      if (data && Array.isArray(data.team2)) {
        logGameInProgress('🔍 Resolvendo campeões do Team 2...');
        data.team2.forEach(resolvePlayerChampion);
      }

      logGameInProgress('✅ [GameInProgress] Jogadores hidratados com nomes de campeões');
    } catch (e) {
      logGameInProgress('⚠️ [GameInProgress] Falha ao hidratar jogadores via pick_ban_data:', e);
    }
  }

  private logGameDataSnapshot(): void {
    const data = this.gameData();
    logGameInProgress('📊 [GameInProgress] gameData atual:', {
      gameData: data,
      hasGameData: !!data,
      sessionId: data?.sessionId,
      team1Length: data?.team1?.length || 0,
      team2Length: data?.team2?.length || 0,
      team1Sample: data?.team1?.[0],
      team2Sample: data?.team2?.[0]
    });
  }

  private ensureGameData(): boolean {
    // ✅ SIGNALS: Extrair valor do input signal
    const data = this.gameData();

    if (!data) {
      logGameInProgress('❌ [GameInProgress] gameData não está disponível');
      return false;
    }

    // ✅ CORREÇÃO: Verificar se temos os dados mínimos necessários
    if (!data.team1 || !data.team2) {
      logGameInProgress('❌ [GameInProgress] Dados dos times não estão disponíveis:', {
        hasTeam1: !!data.team1,
        hasTeam2: !!data.team2,
        gameDataKeys: Object.keys(data)
      });
      return false;
    }

    if (data.team1.length === 0 || data.team2.length === 0) {
      logGameInProgress('❌ [GameInProgress] Times estão vazios:', {
        team1Length: data.team1.length,
        team2Length: data.team2.length
      });
      return false;
    }

    return true;
  }

  private setupInitialState(): void {
    // ✅ SIGNALS: Usar .set() para atualizar signals
    this.gameStartTime.set(new Date());
    this.gameStatus.set('waiting');

    // ✅ SIGNALS: Extrair valor do input signal para log
    const data = this.gameData();
    logGameInProgress('✅ [GameInProgress] Partida inicializada com sucesso:', {
      sessionId: data?.sessionId,
      team1: data?.team1?.length || 0,
      team2: data?.team2?.length || 0,
      isCustom: data?.isCustomGame
    });
  }

  private normalizePickBanDataSafe(): void {
    try {
      // ✅ SIGNALS: Extrair valor do input signal
      const data = this.gameData();
      if (!data) return;

      const maybePickBan = (this as any)._normalizedPickBanData || null;
      if (maybePickBan) return;

      let parsedPickBan: any = null;
      const pb = (this.gameData as any).pickBanData;
      const dr = (this.gameData as any).draftResults;

      if (pb) {
        parsedPickBan = typeof pb === 'string' ? JSON.parse(pb) : pb;
      } else if (dr) {
        parsedPickBan = typeof dr === 'string' ? JSON.parse(dr) : dr;
      }

      // ✅ Normalização das phases: garantir team 'blue'/'red', champion {id,name}, e locked boolean
      if (parsedPickBan && Array.isArray(parsedPickBan.phases)) {
        const normalizeTeam = (team: any, teamId?: any): 'blue' | 'red' | undefined => {
          // Suporta 1/2, '1'/'2', 100/200, 'blue'/'red'
          if (team === 'blue' || team === 'red') return team;
          const t = typeof team === 'string' ? parseInt(team, 10) : team;
          const tid = typeof teamId === 'string' ? parseInt(teamId, 10) : teamId;
          let value: number | undefined = undefined;
          if (Number.isFinite(t)) {
            value = t as number;
          } else if (Number.isFinite(tid)) {
            value = tid as number;
          }
          if (value === 1 || value === 100) return 'blue';
          if (value === 2 || value === 200) return 'red';
          return undefined;
        };

        const normalizedPhases = parsedPickBan.phases.map((phase: any) => {
          const teamNormalized = normalizeTeam(phase.team, phase.teamId);
          let action = phase.action || phase.type;
          if (!action) {
            if (phase.phase === 'bans') action = 'ban';
            else if (phase.phase === 'picks') action = 'pick';
          }

          // Normalizar champion
          let championObj = phase.champion;
          const championId = phase.championId || phase.champion_id || phase.champion?.id;
          if (!championObj && (championId || championId === 0)) {
            const idNum = typeof championId === 'string' ? parseInt(championId, 10) : championId;
            const name = this.getChampionNameById(idNum || 0) || undefined;
            championObj = { id: idNum, name };
          } else if (championObj && typeof championObj === 'object') {
            if (championObj.id != null && championObj.name == null) {
              const name = this.getChampionNameById(typeof championObj.id === 'string' ? parseInt(championObj.id, 10) : championObj.id);
              if (name && name !== 'Minion') championObj = { ...championObj, name };
            }
          }

          // locked padrão: true se tem champion; respeita flags alternativas
          const locked = phase.locked === true || phase.completed === true || phase.done === true || !!championObj;

          return {
            ...phase,
            team: teamNormalized || phase.team,
            action,
            champion: championObj,
            locked
          };
        });

        (this as any)._normalizedPickBanData = { ...parsedPickBan, phases: normalizedPhases };
      } else {
        // Sem phases ou estrutura desconhecida: ainda assim armazenar o bruto para fallbacks
        (this as any)._normalizedPickBanData = parsedPickBan || null;
      }

      const currentData = this.gameData();
      if ((this as any)._normalizedPickBanData && !currentData?.pickBanData && dr) {
        logGameInProgress('✅ [GameInProgress] pickBanData normalizado a partir de draftResults');
      }
    } catch (e) {
      logGameInProgress('⚠️ [GameInProgress] Falha ao normalizar pickBanData:', e);
    }
  }

  private startGameTimer() {
    this.gameTimer = interval(1000).subscribe(() => {
      const startTime = this.gameStartTime();
      if (startTime) {
        this.gameDuration.set(Math.floor((Date.now() - startTime.getTime()) / 1000));
      }
    });
  }

  // ❌ REMOVIDO: Sistema de detecção automática durante o jogo
  // TODO sistema de "live match linking" foi removido - agora usa apenas votação manual
  /*
  private startLiveMatchLinking() {
    if (!this.matchLinkingEnabled) return;

    // ✅ CORREÇÃO: Não iniciar detecção automática para jogos customizados/simulados
    if (this.gameData?.isCustomGame) {
      logGameInProgress('🎭 [GameInProgress] Jogo customizado/simulado detectado - desabilitando detecção automática LCU');
      return;
    }

    // Try to link immediately
    this.tryLinkToLiveMatch();

    // Then try every 2 minutes
    this.lcuDetectionTimer = interval(120000).subscribe(() => { // 2 minutes
      this.tryLinkToLiveMatch();
    });
  }

  private async tryLinkToLiveMatch(): Promise<void> {
    if (this.shouldStopLinking()) {
      return;
    }

    this.lastLinkingAttempt = Date.now();
    this.linkingAttempts++;

    try {
      const gameState = await firstValueFrom(this.apiService.getCurrentGame());

      if (!gameState?.success || !gameState.data) {
        return;
      }

      const currentGame = gameState.data;
      await this.processLiveGameCandidate(currentGame);

    } catch (error) {
      logGameInProgress('❌ Erro ao tentar vincular partida ao vivo:', error);
    }
  }

  private shouldStopLinking(): boolean {
    const now = Date.now();

    // Check if we've exceeded the maximum number of attempts
    if (this.linkingAttempts >= this.maxLinkingAttempts) {
      this.stopLcuDetectionTimer();
      return true;
    }

    // Check if we've exceeded the time limit (10 minutes)
    const timeLimitMs = 10 * 60 * 1000;
    if (now - this.linkingStartTime > timeLimitMs) {
      this.stopLcuDetectionTimer();
      return true;
    }

    // Avoid too frequent attempts
    return (now - this.lastLinkingAttempt < 30000);
  }

  private stopLcuDetectionTimer(): void {
    if (this.lcuDetectionTimer) {
      this.lcuDetectionTimer.unsubscribe();
      this.lcuDetectionTimer = null;
    }
  }

  private async processLiveGameCandidate(currentGame: any): Promise<void> {
    // Check if this is a valid game to link
    if (currentGame.gameMode && currentGame.gameId) {
      // Check if we're already linked to this match
      if (this.currentLiveMatchId === parseInt(currentGame.gameId)) {
        return;
      }

      // Check if this match seems to correspond to our draft
      const linkingScore = this.calculateLiveLinkingScore(currentGame);

      if (linkingScore.shouldLink) {
        // NEW: Instead of auto-linking, show confirmation modal
        this.showLcuMatchConfirmationModal(currentGame, linkingScore);
      }
    }
  }

  // Calculate if current live match should be linked to our draft
  private calculateLiveLinkingScore(liveGame: any): { shouldLink: boolean, score: number, reason: string } {
    if (!this.gameData?.pickBanData) {
      return { shouldLink: false, score: 0, reason: 'Dados de draft não disponíveis' };
    }

    let score = 0;
    const reasons: string[] = [];

    // Check game timing (should be recent)
    if (liveGame.gameCreation) {
      const gameTime = new Date(liveGame.gameCreation);
      const draftTime = this.gameData.startTime ? new Date(this.gameData.startTime) : new Date();
      const timeDiff = Math.abs(gameTime.getTime() - draftTime.getTime()) / (1000 * 60); // minutes

      if (timeDiff <= 15) { // Within 15 minutes of draft
        score += 30;
        reasons.push(`Horário compatível (${Math.round(timeDiff)} min)`);
      } else {
        return { shouldLink: false, score: 0, reason: `Muito tempo entre draft e partida (${Math.round(timeDiff)} min)` };
      }
    }

    // Check if it's a custom game (preferred for our use case)
    if (liveGame.gameMode === 'CLASSIC' && liveGame.gameType === 'CUSTOM_GAME') {
      score += 40;
      reasons.push('Partida customizada');
    } else if (liveGame.gameMode === 'CLASSIC') {
      score += 20;
      reasons.push('Partida clássica');
    }

    // Check player participation (if current player is in the game)
    if (this.currentPlayer && liveGame.participants) {
      const currentPlayerInGame = liveGame.participants.some((p: any) =>
        p.summonerName === this.currentPlayer?.summonerName ||
        p.gameName === this.currentPlayer?.summonerName
      );

      if (currentPlayerInGame) {
        score += 30;
        reasons.push('Jogador atual está na partida');
      } else {
        // Not necessarily a deal-breaker, but reduces confidence
        score -= 10;
        reasons.push('Jogador atual não encontrado na partida');
      }
    }

    const shouldLink = score >= 60; // Need at least 60% confidence
    const reason = reasons.join(', ');

    return { shouldLink, score, reason };
  }
  */

  // ❌ REMOVIDO: Detecção automática de fim de jogo via LCU
  // Causava finalização automática sem confirmação do usuário
  /*
  private startLCUDetection() {
    if (!this.lcuDetectionEnabled()) return;  // ✅ CORRIGIDO: Adicionar ()
    this.lcuDetectionTimer = interval(5000).subscribe(() => {
      this.checkLCUStatus();
    });
  }

  private async checkLCUStatus() {
    // ... código de detecção automática removido
  }

  private onLCUGameDetected(gameData: any) {
    // ... código removido
  }

  private onLCUGameEnded(endGameData: any) {
    // ... código removido
  }
  */

  private autoCompleteGame(winner: TeamColor, detectedByLCU: boolean) {
    const data = this.gameData();
    if (!data) return;

    const result: GameResult = {
      sessionId: data.sessionId,
      gameId: this.generateGameId(),
      winner: winner,
      duration: this.gameDuration(),
      endTime: new Date(),
      team1: data.team1,
      team2: data.team2,
      pickBanData: data.pickBanData,
      detectedByLCU: detectedByLCU,
      isCustomGame: true,
      originalMatchId: data.originalMatchId,
      originalMatchData: data.originalMatchData,
      riotId: data.riotId
    };

    this.onGameComplete.emit(result);
  }
  // Novo método para completar jogo com dados reais do LCU
  private autoCompleteGameWithRealData(winner: TeamColor | null, detectedByLCU: boolean, lcuMatchData: any) {
    const data = this.gameData();
    if (!data) return;

    const result: GameResult = {
      sessionId: data.sessionId,
      gameId: this.generateGameId(),
      winner: winner,
      duration: this.gameDuration(),
      endTime: new Date(),
      team1: data.team1,
      team2: data.team2,
      pickBanData: data.pickBanData,
      detectedByLCU: detectedByLCU,
      isCustomGame: true,
      originalMatchId: data.originalMatchId || lcuMatchData.gameId,
      originalMatchData: lcuMatchData, // Incluir dados completos da partida do LCU
      riotId: data.riotId || (lcuMatchData.platformId ? `${lcuMatchData.platformId}_${lcuMatchData.gameId}` : `BR1_${lcuMatchData.gameId}`)
    };

    logGameInProgress('✅ Partida concluída automaticamente com dados reais do LCU:', result);
    this.onGameComplete.emit(result);
  }

  // Manual winner declaration
  declareWinner(winner: TeamColor) {
    this.selectedWinner.set(winner);
  }
  confirmWinner() {
    const winner = this.selectedWinner();
    const data = this.gameData();
    if (!winner || !data) return;

    // ❌ REMOVIDO: detecção automática via LCU
    // Agora sempre usa conclusão manual
    logGameInProgress('✅ Confirmando vencedor (manual)');
    const result: GameResult = {
      sessionId: data.sessionId,
      gameId: this.generateGameId(),
      winner: winner,
      duration: this.gameDuration(),
      endTime: new Date(),
      team1: data.team1,
      team2: data.team2,
      pickBanData: data.pickBanData,
      detectedByLCU: false,
      isCustomGame: true,
      originalMatchId: data.originalMatchId,
      originalMatchData: data.originalMatchData,
      riotId: data.riotId
    };

    this.onGameComplete.emit(result);
  }  // Cancel game
  async cancelGame() {
    const data = this.gameData();
    logGameInProgress('❌ [GameInProgress] Cancelando partida...');
    logGameInProgress('📊 [GameInProgress] gameData atual:', {
      hasGameData: !!data,
      gameDataKeys: data ? Object.keys(data) : [],
      originalMatchId: data?.originalMatchId,
      matchId: data?.matchId,
      fullGameData: data
    });

    // ✅ CORREÇÃO: Usar getter com fallback robusto
    const matchIdValue = this.matchId;

    if (!matchIdValue) {
      logGameInProgress('⚠️ [GameInProgress] Nenhum ID de partida encontrado (id, matchId e originalMatchId ausentes)');
      alert('Erro: ID da partida não encontrado');
      return;
    }

    // Confirmar cancelamento
    const confirmed = confirm('Tem certeza que deseja cancelar esta partida? Todos os jogadores serão redirecionados para a tela inicial.');
    if (!confirmed) {
      logGameInProgress('🚫 [GameInProgress] Cancelamento abortado pelo usuário');
      return;
    }

    try {
      logGameInProgress('📡 [GameInProgress] Enviando requisição de cancelamento para matchId:', matchIdValue);

      // Chamar endpoint DELETE /api/match/{matchId}/cancel
      const response: any = await firstValueFrom(
        this.apiService.cancelMatchInProgress(matchIdValue)
      );

      if (response?.success) {
        logGameInProgress('✅ [GameInProgress] Partida cancelada com sucesso');
        // O WebSocket vai receber o evento match_cancelled e redirecionar
      } else {
        logGameInProgress('⚠️ [GameInProgress] Resposta de cancelamento sem sucesso:', response);
        alert('Erro ao cancelar partida: ' + (response?.error || 'Erro desconhecido'));
      }
    } catch (error) {
      logGameInProgress('❌ [GameInProgress] Erro ao cancelar partida:', error);
      alert('Erro ao cancelar partida. Verifique os logs.');
    }
  }

  // ✅ NOVO: Callbacks do modal de confirmação de vencedor
  onWinnerConfirmed(data: { match: any, winner: 'blue' | 'red' }) {
    logGameInProgress('✅ Vencedor confirmado pelo usuário:', data.winner);
    logGameInProgress('📊 Partida selecionada:', data.match);

    // Fechar modal
    this.showWinnerConfirmationModal.set(false);

    // Completar jogo com dados da partida do LCU
    this.autoCompleteGameWithRealData(data.winner, true, data.match);
  }

  onWinnerConfirmationCancelled() {
    logGameInProgress('🚫 Confirmação de vencedor cancelada pelo usuário');
    this.showWinnerConfirmationModal.set(false);
    this.customMatchesForConfirmation.set([]);
    this.isAutoDetecting.set(false);
    this.cdr.markForCheck();
  }

  // ✅ NOVO: Retorna todos os jogadores (team1 + team2)
  getAllPlayers(): any[] {
    const data = this.gameData();
    if (!data) return [];
    const team1 = data.team1 || [];
    const team2 = data.team2 || [];
    return [...team1, ...team2];
  }

  // ❌ REMOVIDO: Detecção automática de vencedor desabilitada
  /*
  private async tryAutoResolveWinner() {
    // Este método não é mais usado - foi substituído pelo sistema manual de votação
    const lcuWinner = await this.tryGetWinnerFromLCU();
    if (lcuWinner) {
      this.autoCompleteGame(lcuWinner, true);
      return;
    }
    const historyWinner = await this.tryGetWinnerFromHistory();
    if (historyWinner) {
      this.autoCompleteGame(historyWinner, false);
    }
  }
  */

  // ✅ NOVO: Método para identificar vencedor manualmente via modal
  async retryAutoDetection() {
    logGameInProgress('🔍️ Abrindo modal de confirmação de vencedor manual...');

    // Set loading state
    this.isAutoDetecting.set(true);
    this.cdr.markForCheck();

    try {
      // ✅ Buscar últimas partidas PERSONALIZADAS COM DETALHES COMPLETOS (todos os 10 jogadores)
      // IMPORTANTE: customOnly=true filtra apenas custom games (queueId=0 ou gameType=CUSTOM_GAME)
      // Isso garante que apenas partidas personalizadas apareçam no modal de seleção
      logGameInProgress('📥 Buscando histórico de partidas PERSONALIZADAS do LCU com detalhes completos...');
      logGameInProgress('📥 Parâmetros da busca: offset=0, limit=20, customOnly=true');

      const historyResponse = await firstValueFrom(
        this.apiService.getLCUCustomGamesWithDetails(0, 20, true)
      );

      logGameInProgress('📥 Resposta da API recebida:', historyResponse);

      if (!historyResponse?.success || !historyResponse?.matches?.length) {
        logGameInProgress('⚠️ Nenhuma partida encontrada no histórico do LCU');
        alert('Nenhuma partida encontrada no histórico do LCU. Certifique-se de que o League of Legends está aberto e que você jogou partidas recentemente.');
        this.isAutoDetecting.set(false);
        this.cdr.markForCheck();
        return;
      }

      // ✅ Pegar apenas as últimas 3 partidas (já vem com detalhes completos)
      const last3Matches = historyResponse.matches.slice(0, 3);

      logGameInProgress('🔍 Últimas 3 partidas encontradas:', last3Matches.length);
      logGameInProgress('🔍 Detalhes das partidas:', last3Matches.map((m: any) => ({
        gameId: m.gameId,
        gameCreation: m.gameCreation,
        queueId: m.queueId,
        gameType: m.gameType,
        participants: m.participants?.length || 0,
        teams: m.teams?.length || 0
      })));

      // 🐛 DEBUG: Ver estrutura completa da primeira partida
      if (last3Matches.length > 0) {
        logGameInProgress('🔍 ESTRUTURA COMPLETA DA PRIMEIRA PARTIDA:');
        logGameInProgress('  - gameId:', last3Matches[0].gameId);
        logGameInProgress('  - participants:', last3Matches[0].participants?.length || 0);
        logGameInProgress('  - teams:', last3Matches[0].teams?.length || 0);
        logGameInProgress('  - participantIdentities:', last3Matches[0].participantIdentities?.length || 0);

        // Ver as chaves disponíveis
        logGameInProgress('  - Chaves do objeto:', Object.keys(last3Matches[0]));

        // Ver se tem participants
        if (last3Matches[0].participants && last3Matches[0].participants.length > 0) {
          logGameInProgress('  - Primeiro participant:', JSON.stringify(last3Matches[0].participants[0], null, 2));
        }
      }

      // ✅ Validar que as partidas têm dados completos (relaxar validação - aceitar 8-10 jogadores)
      const validMatches = last3Matches.filter((m: any) => {
        const hasParticipants = m.participants && Array.isArray(m.participants);
        const participantsCount = hasParticipants ? m.participants.length : 0;
        const hasEnoughPlayers = participantsCount >= 8; // Aceitar 8, 9 ou 10 jogadores

        logGameInProgress(`🔍 Validando partida ${m.gameId}:`, {
          hasParticipants,
          participantsCount,
          hasEnoughPlayers
        });

        return hasEnoughPlayers;
      });

      if (validMatches.length === 0) {
        logGameInProgress('⚠️ Partidas encontradas não possuem dados completos');
        logGameInProgress('⚠️ Total de partidas recebidas:', last3Matches.length);
        logGameInProgress('⚠️ Partidas válidas após filtro:', validMatches.length);
        alert('As partidas encontradas não possuem dados completos. Tente novamente em alguns segundos.');
        this.isAutoDetecting.set(false);
        this.cdr.markForCheck();
        return;
      }

      logGameInProgress(`✅ ${validMatches.length} partidas válidas com todos os 10 jogadores`);

      // ✅ Abrir modal de confirmação
      this.customMatchesForConfirmation.set(validMatches);
      this.showWinnerConfirmationModal.set(true);
      this.isAutoDetecting.set(false);
      this.cdr.markForCheck();

    } catch (error) {
      logGameInProgress('❌ Erro ao buscar histórico do LCU:', error);
      alert('Erro ao acessar o histórico do LCU. Certifique-se de que o League of Legends está aberto.');
      this.isAutoDetecting.set(false);
      this.cdr.markForCheck();
    }
  }

  // ❌ REMOVIDO: Sistema de busca e comparação de partidas LCU
  // Agora usa apenas seleção manual das últimas 3 custom matches
  /*
  private findMatchingLCUGame(lcuMatches: any[]): { match: any, confidence: number, reason: string } {
    if (!this.gameData) {
      return { match: null, confidence: 0, reason: 'Nenhum dado de jogo disponível' };
    }

    logGameInProgress('[DEBUG-SIMULATE] 🔍 Procurando partida correspondente entre', lcuMatches.length, 'partidas do LCU');
    logGameInProgress('[DEBUG-SIMULATE] 🔍 GameData atual:', {
      sessionId: this.gameData.sessionId,
      gameId: this.gameData.gameId,
      originalMatchId: this.gameData.originalMatchId,
      team1Count: this.gameData.team1?.length,
      team2Count: this.gameData.team2?.length
    });

    // HIGHEST PRIORITY: Check if we have a live-linked match
    if (this.currentLiveMatchId) {
      const linkedMatch = lcuMatches.find((match: any) => match.gameId.toString() === this.currentLiveMatchId);
      if (linkedMatch) {
        logGameInProgress('🎯 Partida encontrada por vinculação automática:', linkedMatch.gameId);
        return {
          match: linkedMatch,
          confidence: 100,
          reason: `Partida vinculada automaticamente durante o jogo (ID: ${linkedMatch.gameId})`
        };
      }
    }

    // SECOND PRIORITY: Try to match by original match ID if this is a simulation
    if (this.gameData.originalMatchId) {
      const exactMatch = lcuMatches.find((match: any) => match.gameId === this.gameData?.originalMatchId);
      if (exactMatch) {
        logGameInProgress('✅ Partida encontrada por ID exato:', exactMatch.gameId);
        return {
          match: exactMatch,
          confidence: 100,
          reason: `Partida encontrada por ID exato (${exactMatch.gameId})`
        };
      }
    }

    // THIRD PRIORITY: Compare by similarity (champions, timing, etc.)
    let bestMatch: any = null;
    let bestScore = 0;
    let bestReason = '';

    // ✅ NOVO: Log das primeiras partidas para debug
    logGameInProgress('[DEBUG-SIMULATE] 🔍 Primeiras 3 partidas do LCU para debug:');
    lcuMatches.slice(0, 3).forEach((match, index) => {
      logGameInProgress(`[DEBUG-SIMULATE] 🔍 LCU Match ${index + 1}:`, {
        gameId: match.gameId,
        queueId: match.queueId,
        gameCreation: match.gameCreation,
        participantsCount: match.participants?.length || 0,
        hasTeams: !!match.teams,
        teams: match.teams?.map((t: any) => ({ teamId: t.teamId, win: t.win }))
      });
    });

    for (const lcuMatch of lcuMatches) {
      const similarity = this.calculateMatchSimilarity(lcuMatch);

      // ✅ NOVO: Log do score de cada partida
      if (similarity.confidence > 20) { // Só logar partidas com score > 20
        logGameInProgress(`[DEBUG-SIMULATE] 🔍 LCU Match ${lcuMatch.gameId}: score=${similarity.confidence}, reason="${similarity.reason}"`);
      }

      if (similarity.confidence > bestScore) {
        bestMatch = lcuMatch;
        bestScore = similarity.confidence;
        bestReason = similarity.reason;
      }
    }

    // Only accept matches with reasonable confidence
    if (bestScore >= 70) {
      logGameInProgress('[DEBUG-SIMULATE] ✅ Partida correspondente encontrada por similaridade:', { match: bestMatch.gameId, score: bestScore });
      return {
        match: bestMatch,
        confidence: bestScore,
        reason: bestReason
      };
    }

    // No good match found
    logGameInProgress('[DEBUG-SIMULATE] ⚠️ Nenhuma partida correspondente encontrada');
    return {
      match: null,
      confidence: 0,
      reason: 'Nenhuma partida correspondente encontrada no histórico'
    };
  }

  // Calculate similarity between current game and LCU match
  private calculateMatchSimilarity(lcuMatch: any): { confidence: number, reason: string } {
    if (!this.gameData?.pickBanData) {
      return { confidence: 0, reason: 'Dados de pick/ban não disponíveis' };
    }

    let totalScore = 0;
    let maxScore = 0;
    const reasons: string[] = [];

    // Team composition comparison
    const compositionResult = this.compareTeamCompositions(lcuMatch);
    totalScore += compositionResult.score;
    maxScore += compositionResult.maxScore;
    if (compositionResult.reason) reasons.push(compositionResult.reason);

    // Timing comparison
    const timingResult = this.compareGameTiming(lcuMatch);
    totalScore += timingResult.score;
    maxScore += timingResult.maxScore;
    if (timingResult.reason) reasons.push(timingResult.reason);

    // Match completion check
    const completionResult = this.checkMatchCompletion(lcuMatch);
    totalScore += completionResult.score;
    maxScore += completionResult.maxScore;
    if (completionResult.reason) reasons.push(completionResult.reason);

    const confidence = maxScore > 0 ? Math.round((totalScore / maxScore) * 100) : 0;
    const reason = reasons.length > 0 ? reasons.join(', ') : 'Pouca similaridade encontrada';

    return { confidence, reason };
  }

  private compareTeamCompositions(lcuMatch: any): { score: number, maxScore: number, reason: string | null } {
    if (!this.gameData) return { score: 0, maxScore: 50, reason: null };

    const currentTeam1Champions = this.extractChampionsFromTeam(this.gameData.team1);
    const currentTeam2Champions = this.extractChampionsFromTeam(this.gameData.team2);
    const lcuChampions = this.extractChampionsFromLCUMatch(lcuMatch);

    if (currentTeam1Champions.length === 0 || currentTeam2Champions.length === 0) {
      return { score: 0, maxScore: 50, reason: null };
    }

    const team1MatchScore = this.compareChampionLists(currentTeam1Champions, lcuChampions.team1) +
      this.compareChampionLists(currentTeam2Champions, lcuChampions.team2);

    const team2MatchScore = this.compareChampionLists(currentTeam1Champions, lcuChampions.team2) +
      this.compareChampionLists(currentTeam2Champions, lcuChampions.team1);

    const bestScore = Math.max(team1MatchScore, team2MatchScore);
    const reason = bestScore > 30 ? `Composição de times similar (${Math.round(bestScore)}%)` : null;

    return { score: bestScore, maxScore: 50, reason };
  }

  private compareGameTiming(lcuMatch: any): { score: number, maxScore: number, reason: string | null } {
    if (!lcuMatch.gameCreation) {
      return { score: 0, maxScore: 20, reason: null };
    }

    const matchTime = new Date(lcuMatch.gameCreation);
    const gameStartTime = this.gameData?.startTime ? new Date(this.gameData.startTime) : new Date();
    const timeDifference = Math.abs(matchTime.getTime() - gameStartTime.getTime()) / (1000 * 60); // minutes

    if (timeDifference > 30) {
      return { score: 0, maxScore: 20, reason: null };
    }

    const timeScore = Math.max(0, 20 - (timeDifference / 30) * 20);
    const reason = timeScore > 10 ? `Horário compatível (${Math.round(timeDifference)} min de diferença)` : null;

    return { score: timeScore, maxScore: 20, reason };
  }

  private checkMatchCompletion(lcuMatch: any): { score: number, maxScore: number, reason: string | null } {
    if (!lcuMatch.teams || lcuMatch.teams.length !== 2) {
      return { score: 0, maxScore: 30, reason: null };
    }

    const hasWinner = lcuMatch.teams.some((team: any) => team.win === "Win" || team.win === true);
    const score = hasWinner ? 30 : 0;
    const reason = hasWinner ? 'Partida finalizada com vencedor definido' : null;

    return { score, maxScore: 30, reason };
  }

  // Extract champion names from team
  private extractChampionsFromTeam(team: any[]): string[] {
    return team.map(player => {
      // ✅ CORREÇÃO: Suportar diferentes formatos de dados de campeão
      let championName = null;

      if (player.champion) {
        if (typeof player.champion === 'string') {
          // Formato: player.champion = "Trundle"
          championName = player.champion;
        } else if (player.champion.name) {
          // Formato: player.champion = { name: "Trundle" }
          championName = player.champion.name;
        }
      }

      return championName ? championName.toLowerCase() : null;
    }).filter(name => name !== null);
  }

  // Extract champions from LCU match
  private extractChampionsFromLCUMatch(lcuMatch: any): { team1: string[], team2: string[] } {
    const team1: string[] = [];
    const team2: string[] = [];

    logGameInProgress('[DEBUG-SIMULATE] 🔍 Extraindo campeões do LCU Match:', lcuMatch.gameId);
    logGameInProgress('[DEBUG-SIMULATE] 🔍 Participants:', lcuMatch.participants?.length || 0);
    logGameInProgress('[DEBUG-SIMULATE] 🔍 ParticipantIdentities:', lcuMatch.participantIdentities?.length || 0);

    if (lcuMatch.participants && lcuMatch.participantIdentities) {
      lcuMatch.participants.forEach((participant: any, index: number) => {
        const championName = this.getChampionNameById(participant.championId);
        logGameInProgress(`[DEBUG-SIMULATE] 🔍 Participant ${index}: championId=${participant.championId}, championName="${championName}", teamId=${participant.teamId}`);

        if (championName) {
          if (participant.teamId === 100) {
            team1.push(championName.toLowerCase());
          } else if (participant.teamId === 200) {
            team2.push(championName.toLowerCase());
          }
        }
      });
    }

    logGameInProgress('[DEBUG-SIMULATE] 🔍 Resultado final:');
    logGameInProgress('[DEBUG-SIMULATE] 🔍 Team1 champions:', team1);
    logGameInProgress('[DEBUG-SIMULATE] 🔍 Team2 champions:', team2);

    return { team1, team2 };
  }

  // Compare two champion lists and return similarity percentage
  private compareChampionLists(list1: string[], list2: string[]): number {
    if (list1.length === 0 || list2.length === 0) return 0;

    let matches = 0;
    for (const champion of list1) {
      // ✅ NOVO: Comparação mais flexível
      const found = list2.find(c => {
        // Comparação exata
        if (c === champion) return true;
        // Comparação ignorando espaços e caracteres especiais
        const normalized1 = champion.replace(/[^a-zA-Z]/g, '').toLowerCase();
        const normalized2 = c.replace(/[^a-zA-Z]/g, '').toLowerCase();
        if (normalized1 === normalized2) return true;
        // Comparação parcial (um contém o outro)
        if (normalized1.includes(normalized2) || normalized2.includes(normalized1)) return true;
        return false;
      });

      if (found) {
        matches++;
        logGameInProgress(`[DEBUG-SIMULATE] ✅ Match encontrado: "${champion}" = "${found}"`);
      } else {
        logGameInProgress(`[DEBUG-SIMULATE] ❌ No match: "${champion}" não encontrado em [${list2.join(', ')}]`);
      }
    }

    const similarity = (matches / Math.max(list1.length, list2.length)) * 50; // Max 50 points per team
    logGameInProgress(`[DEBUG-SIMULATE] 📊 Similaridade: ${matches}/${Math.max(list1.length, list2.length)} = ${similarity} pontos`);

    return similarity;
  }
  */

  // ❌ REMOVIDO: Modals de confirmação de partida detectada automaticamente
  /*
  confirmDetectedMatch(): void {
    if (!this.detectedLCUMatch || !this.matchComparisonResult) return;

    const lcuMatch = this.detectedLCUMatch;    // Extract winner from LCU match
    let winner: TeamColor | null = null;
    if (lcuMatch.teams && lcuMatch.teams.length === 2) {
      // LCU teams use string values: "Win" or "Fail"
      const winningTeam = lcuMatch.teams.find((team: any) => team.win === "Win" || team.win === true);
      if (winningTeam) {
        winner = winningTeam.teamId === 100 ? 'blue' : 'red';
      }
    }

    logGameInProgress('🔍 Detecção de vencedor:', {
      teams: lcuMatch.teams?.map((t: any) => ({ teamId: t.teamId, win: t.win })),
      detectedWinner: winner
    });

    logGameInProgress('✅ Partida confirmada automaticamente');
    logGameInProgress('🎮 Dados da partida LCU:', lcuMatch.gameId);

    // Fechar modal imediatamente
    this.showMatchConfirmation = false;

    // Atualizar gameData com informações da partida real
    if (this.gameData) {
      this.gameData.originalMatchId = lcuMatch.gameId;
      this.gameData.riotId = lcuMatch.platformId ? `${lcuMatch.platformId}_${lcuMatch.gameId}` : `BR1_${lcuMatch.gameId}`;
      this.gameData.originalMatchData = lcuMatch;
    }

    if (winner) {
      logGameInProgress('🏆 Vencedor detectado automaticamente via LCU:', winner);
      this.selectedWinner.set(winner);  // ✅ CORRIGIDO: Usar .set()

      // Completar jogo automaticamente com dados reais - APENAS uma vez via evento onGameComplete
      this.autoCompleteGameWithRealData(winner, true, lcuMatch);
    } else {
      logGameInProgress('⚠️ Partida confirmada mas sem vencedor detectado - completando partida como inconclusiva');

      // Mesmo sem vencedor detectado, completar a partida automaticamente
      // Marca como null (inconclusivo) mas salva no histórico
      this.autoCompleteGameWithRealData(null, true, lcuMatch);

      // Mostrar notificação de que a partida foi salva mas sem vencedor definido
      this.showSuccessNotification(
        'Partida salva!',
        'A partida foi detectada e salva no histórico, mas o vencedor não pôde ser determinado automaticamente.'
      );
    }
  }

  rejectDetectedMatch(): void {
    logGameInProgress('❌ Partida detectada rejeitada pelo usuário');
    this.closeMatchConfirmation();
  }

  closeMatchConfirmation(): void {
    this.showMatchConfirmation = false;
    this.detectedLCUMatch = null;
    this.matchComparisonResult = null;
  }
  */

  // Get champion name by ID (helper method)
  getChampionNameById(championId: number): string | null {
    if (!championId) return null;

    try {
      // ✅ CORREÇÃO: Usar o ChampionService que tem cache de todos os campeões
      const championName = this.championService.getChampionName(championId);

      // Se retornou "Champion X", significa que não está no cache
      if (championName && !championName.startsWith('Champion ')) {
        return championName;
      }

      // Se retornou no formato "Champion X", retornar null para indicar que não encontrou
      logGameInProgress(`⚠️ Campeão ${championId} não encontrado no cache do ChampionService`);
      return null;
    } catch (error) {
      logGameInProgress('⚠️ [GameInProgress] Erro ao usar ChampionService:', error);
      return null;
    }
  }

  // Helper methods for modal template
  formatLCUMatchDate(gameCreation: number): string {
    if (!gameCreation) return 'Data não disponível';
    return new Date(gameCreation).toLocaleString('pt-BR');
  }

  formatGameDuration(gameDuration: number): string {
    if (!gameDuration) return 'Duração não disponível';
    const minutes = Math.floor(gameDuration / 60);
    const seconds = gameDuration % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }
  getLCUMatchWinner(lcuMatch: any): TeamColor | null {
    if (!lcuMatch?.teams || lcuMatch.teams.length !== 2) return null;

    // LCU teams use string values: "Win" or "Fail"
    const winningTeam = lcuMatch.teams.find((team: any) => team.win === "Win" || team.win === true);
    if (!winningTeam) return null;

    return winningTeam.teamId === 100 ? 'blue' : 'red';
  }

  getLCUTeamParticipants(lcuMatch: any, teamId: number): any[] {
    if (!lcuMatch?.participants) return [];

    return lcuMatch.participants.filter((participant: any) => participant.teamId === teamId);
  }
  // Missing utility methods
  private stopTimers(): void {
    if (this.gameTimer) {
      this.gameTimer.unsubscribe();
      this.gameTimer = null;
    }
    if (this.lcuDetectionTimer) {
      this.lcuDetectionTimer.unsubscribe();
      this.lcuDetectionTimer = null;
    }

    // ❌ REMOVIDO: linking state
  }

  private generateGameId(): string {
    return 'game_' + Date.now() + '_' + Math.random().toString(36).substring(2, 11);
  }

  // ❌ REMOVIDO: Métodos de detecção automática não são mais usados
  /*
  private async tryGetWinnerFromLCU(): Promise<TeamColor | null> {
    try {
      const gameState = await firstValueFrom(this.apiService.getCurrentGame());
      if (!gameState?.success || !gameState.data) return null;
      const currentGame = gameState.data;
      if (currentGame.gamePhase === 'EndOfGame' || currentGame.gamePhase === 'PostGame') {
        if (currentGame.teams) {
          const winningTeam = currentGame.teams.find((team: any) => team.win === "Win" || team.win === true);
          if (winningTeam) return winningTeam.teamId === 100 ? 'blue' : 'red';
        }
      }
      return null;
    } catch (error) {
      return null;
    }
  }
  */

  // ❌ REMOVIDO: Método de comparação com histórico não é mais usado
  /*
  private async tryGetWinnerFromHistory(): Promise<TeamColor | null> {
    try {
      if (!this.currentPlayer?.id) {
        logGameInProgress('❌ ID do jogador atual não encontrado');
        return null;
      }

      logGameInProgress('🔍 Buscando histórico para player ID:', this.currentPlayer.id);

      // Para o sistema buscar corretamente, vamos usar múltiplos identificadores
      let playerIdentifiers = [this.currentPlayer.id.toString()];

      if (this.currentPlayer?.summonerName) {
        playerIdentifiers.push(this.currentPlayer.summonerName);
      }

      // Para usuário especial, adicionar IDs conhecidos
      if (this.currentPlayer?.summonerName === 'popcorn seller' && this.currentPlayer?.tagLine === 'coup') {
        playerIdentifiers.push('1'); // ID numérico
        playerIdentifiers.push('popcorn seller'); // Nome do summoner
        logGameInProgress('🎯 Usando múltiplos identificadores para busca:', playerIdentifiers);
      }

      // Tentar buscar com cada identificador até encontrar partidas
      let history: any = null;
      for (const identifier of playerIdentifiers) {
        logGameInProgress(`🔍 Tentando buscar com identificador: ${identifier}`);
        history = await firstValueFrom(this.apiService.getCustomMatches(identifier, 0, 30));

        if (history?.success && history.matches?.length > 0) {
          logGameInProgress(`✅ Encontrado histórico com identificador: ${identifier}`);
          break;
        }
      }

      logGameInProgress('📋 Resposta do histórico de partidas:', history);

      if (!history?.success || !history?.matches?.length) {
        logGameInProgress('📝 Nenhum histórico de partidas customizadas encontrado');
        return null;
      }

      // Compare with the last custom match to find potential winner
      const lastMatch = history.matches[0];
      logGameInProgress('🔍 Última partida customizada:', lastMatch);

      // This is a simplified comparison - you might want to enhance this
      if (this.compareGameWithMatch(lastMatch)) {
        // Try to extract winner from match data
        const winner = this.extractWinnerFromMatch(lastMatch);
        if (winner) {
          logGameInProgress('🏆 Vencedor encontrado via histórico:', winner);
          return winner;
        }
      }

      return null;
    } catch (error) {
      logGameInProgress('❌ Erro ao buscar histórico:', error);
      return null;
    }
  }
  */

  // ❌ REMOVIDO: Métodos auxiliares de comparação não são mais usados
  /*
  private compareGameWithMatch(match: any): boolean {
    return true;
  }

  private extractWinnerFromMatch(match: any): TeamColor | null {
    if (!match.winner_team) return null;
    return match.winner_team === 1 ? 'blue' : 'red';
  }
  */

  // ❌ REMOVIDO: Toggle LCU detection
  // Detecção automática foi desabilitada
  toggleLCUDetection(): void {
    logGameInProgress('⚠️ LCU Detection automática foi desabilitada. Use votação manual.');
  }

  private stopLCUDetection(): void {
    if (this.lcuDetectionTimer) {
      this.lcuDetectionTimer.unsubscribe();
      this.lcuDetectionTimer = null;
    }
    this.lcuGameDetected.set(false);
  }

  // Template helper methods
  getGameStatusIcon(): string {
    const status = this.gameStatus();
    switch (status) {
      case 'waiting': return '⏳';
      case 'in-progress': return '🎮';
      case 'ended': return '🏁';
      default: return '❓';
    }
  }

  getGameStatusText(): string {
    const status = this.gameStatus();
    switch (status) {
      case 'waiting': return 'Aguardando início';
      case 'in-progress': return 'Jogo em andamento';
      case 'ended': return 'Jogo finalizado';
      default: return 'Status desconhecido';
    }
  }

  getGameDurationFormatted(): string {
    const duration = this.gameDuration();
    const minutes = Math.floor(duration / 60);
    const seconds = duration % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  getTeamColor(team: TeamColor): string {
    return team === 'blue' ? '#4FC3F7' : '#F44336';
  }

  getTeamName(team: TeamColor): string {
    return team === 'blue' ? 'Time Azul' : 'Time Vermelho';
  }

  getTeamPlayers(team: TeamColor): any[] {
    // ✅ CACHE: Retornar do cache se disponível
    if (this.cachedTeamPlayers.has(team)) {
      return this.cachedTeamPlayers.get(team)!;
    }

    const data = this.gameData();
    if (!data) {
      return [];
    }

    const players = team === 'blue' ? data.team1 : data.team2;
    const result = players || [];

    // ✅ CACHE: Armazenar resultado
    this.cachedTeamPlayers.set(team, result);

    return result;
  }

  getMyTeam(): TeamColor | null {
    const data = this.gameData();
    const player = this.currentPlayer();
    if (!data || !player) return null;

    const isInTeam1 = data.team1.some((p: any) =>
      p.id === player?.id ||
      p.summonerName === player?.summonerName
    );

    if (isInTeam1) return 'blue';

    const isInTeam2 = data.team2.some((p: any) =>
      p.id === player?.id ||
      p.summonerName === player?.summonerName
    );

    if (isInTeam2) return 'red';

    return null;
  }
  isMyTeamWinner(): boolean {
    const myTeam = this.getMyTeam();
    const winner = this.selectedWinner();
    return myTeam === winner;
  }

  /**
   * Retorna o Observable da URL do ícone de perfil se for humano, ou null se for bot
   * ✅ COM CACHE para evitar chamadas recursivas ao LCU
   */
  getProfileIconUrlIfHuman(player: any): Observable<string | null> {
    // ✅ CORREÇÃO: Usar método com cache
    if (this.isPlayerBot(player)) {
      return of(null);
    }
    const identifier = player.summonerName || player.name || player.displayName || player.gameName;
    if (!identifier) return of(null);

    // ✅ CACHE: Verificar se já temos o Observable em cache
    if (this.cachedProfileIconUrls.has(identifier)) {
      return this.cachedProfileIconUrls.get(identifier)!;
    }

    // ✅ CACHE: Criar Observable e armazenar em cache
    const iconUrl$: Observable<string | null> = this.profileIconService.getProfileIconUrl(identifier).pipe(
      shareReplay(1) // Compartilhar o resultado entre múltiplos subscribers
    ) as Observable<string | null>;
    this.cachedProfileIconUrls.set(identifier, iconUrl$);
    return iconUrl$;
  }

  // Métodos de notificação simplificados (podem ser integrados com um serviço de notificações mais complexo)
  private showSuccessNotification(title: string, message: string): void {
    // Por enquanto usar alert, mas pode ser substituído por um toast/notification service
    alert(`✅ ${title}\n${message}`);
  }

  private showErrorNotification(title: string, message: string): void {
    // Por enquanto usar alert, mas pode ser substituído por um toast/notification service
    alert(`❌ ${title}\n${message}`);
  }

  // ✅ NOVO: Métodos para obter bans dos times
  getTeamBans(team: TeamColor): any[] {
    logGameInProgress(`🔍 [GameInProgress] getTeamBans chamado para team: ${team}`);

    // ✅ TEMPORÁRIO: Limpar cache para debug
    this.cachedTeamBans.clear();

    // ✅ CACHE: Retornar do cache se disponível
    if (this.cachedTeamBans.has(team)) {
      logGameInProgress(`✅ [GameInProgress] Retornando bans do cache para team: ${team}`);
      return this.cachedTeamBans.get(team)!;
    }

    // Extrair valor do signal gameData
    const gameDataValue = this.gameData();

    // ✅ PRIORIDADE 1: Fallback direto no gameData (estrutura que o backend está enviando)
    if (team === 'blue' && Array.isArray((gameDataValue as any)?.blueBans)) {
      const bans = (gameDataValue as any).blueBans.map((championId: any) => ({
        champion: { id: championId, name: this.getChampionNameById(championId) },
        championName: this.getChampionNameById(championId),
        championId: championId
      }));
      if (bans.length > 0) {
        logGameInProgress(`✅ [GameInProgress] Bans diretos do gameData encontrados para time ${team}:`, bans);
        this.cachedTeamBans.set(team, bans);
        return bans;
      }
    } else if (team === 'red' && Array.isArray((gameDataValue as any)?.redBans)) {
      const bans = (gameDataValue as any).redBans.map((championId: any) => ({
        champion: { id: championId, name: this.getChampionNameById(championId) },
        championName: this.getChampionNameById(championId),
        championId: championId
      }));
      if (bans.length > 0) {
        logGameInProgress(`✅ [GameInProgress] Bans diretos do gameData encontrados para time ${team}:`, bans);
        this.cachedTeamBans.set(team, bans);
        return bans;
      }
    }

    // Usar pickBanData normalizado se existir
    const normalizedPickBan = (this as any)._normalizedPickBanData;
    const data = this.gameData();
    const hasPickBan = !!(normalizedPickBan || data?.pickBanData);
    if (!hasPickBan) {
      this.cachedTeamBans.set(team, []);
      return [];
    }

    try {
      const pickBanData = normalizedPickBan || data?.pickBanData;

      logGameInProgress(`🔍 [GameInProgress] pickBanData para team ${team}:`, pickBanData);
      logGameInProgress(`🔍 [GameInProgress] blueBans:`, pickBanData?.blueBans);
      logGameInProgress(`🔍 [GameInProgress] redBans:`, pickBanData?.redBans);

      // ✅ NOVO: Debug da estrutura completa do gameData
      logGameInProgress(`🔍 [GameInProgress] gameData completo:`, gameDataValue);
      logGameInProgress(`🔍 [GameInProgress] gameData.blueBans:`, (gameDataValue as any)?.blueBans);
      logGameInProgress(`🔍 [GameInProgress] gameData.redBans:`, (gameDataValue as any)?.redBans);

      // ✅ CORREÇÃO: Extrair bans das phases (estrutura correta)
      if (Array.isArray(pickBanData?.phases)) {
        const teamBans = pickBanData.phases
          .filter((phase: any) =>
            phase.action === 'ban' &&
            phase.team === team &&
            phase.champion &&
            phase.locked
          )
          .map((phase: any) => ({
            champion: phase.champion,
            championName: phase.champion?.name,
            championId: phase.champion?.id
          }));

        if (teamBans.length > 0) {
          this.cachedTeamBans.set(team, teamBans);
          return teamBans;
        }
        // Se não encontramos nas phases (campos não preenchidos), continuar para fallbacks abaixo
      }

      // ✅ FALLBACK: Verificar estrutura alternativa para dados de partida real
      if (team === 'blue' && pickBanData?.team1Bans) {
        const bans = (pickBanData.team1Bans || []).map((b: any) => ({
          champion: b.champion || (b.championId != null ? { id: b.championId, name: this.getChampionNameById(b.championId) } : undefined),
          championName: b.champion?.name || b.championName || (b.championId != null ? this.getChampionNameById(b.championId) : undefined),
          championId: b.champion?.id ?? b.championId
        }));
        if (bans.length > 0) {
          this.cachedTeamBans.set(team, bans);
          return bans;
        }
      } else if (team === 'red' && pickBanData?.team2Bans) {
        const bans = (pickBanData.team2Bans || []).map((b: any) => ({
          champion: b.champion || (b.championId != null ? { id: b.championId, name: this.getChampionNameById(b.championId) } : undefined),
          championName: b.champion?.name || b.championName || (b.championId != null ? this.getChampionNameById(b.championId) : undefined),
          championId: b.champion?.id ?? b.championId
        }));
        if (bans.length > 0) {
          this.cachedTeamBans.set(team, bans);
          return bans;
        }
      }

      // ✅ NOVO: Fallback via actions (se existir)
      if (Array.isArray(pickBanData?.actions)) {
        const normalizeTeam = (t: any): TeamColor | null => {
          if (t === 'blue' || t === 'red') return t;
          const n = typeof t === 'string' ? parseInt(t, 10) : t;
          if (n === 1 || n === 100) return 'blue';
          if (n === 2 || n === 200) return 'red';
          return null;
        };
        const bans = pickBanData.actions
          .filter((a: any) => (a.action === 'ban' || a.type === 'ban') && normalizeTeam(a.team) === team && (a.locked || a.completed || a.done || a.championId != null || a.champion))
          .map((a: any) => {
            const id = a.champion?.id ?? a.championId;
            const name = a.champion?.name ?? (id != null ? this.getChampionNameById(id) : undefined);
            return {
              champion: a.champion || (id != null ? { id, name } : undefined),
              championName: name,
              championId: id
            };
          });
        if (bans.length > 0) {
          this.cachedTeamBans.set(team, bans);
          return bans;
        }
      }

      // ✅ NOVO: Verificar estrutura de dados da partida real (Riot API)
      if (Array.isArray(pickBanData?.bans)) {
        const teamId = team === 'blue' ? 100 : 200;
        const teamBans = pickBanData.bans
          .filter((ban: any) => ban.teamId === teamId)
          .map((ban: any) => ({
            champion: { id: ban.championId, name: this.getChampionNameById(ban.championId) },
            championName: this.getChampionNameById(ban.championId),
            championId: ban.championId
          }));

        logGameInProgress(`✅ [GameInProgress] Bans da partida real encontrados para time ${team}:`, teamBans);
        // ✅ CACHE: Armazenar resultado
        this.cachedTeamBans.set(team, teamBans);
        return teamBans;
      }

      // ✅ NOVO: Fallback para blueBans/redBans (estrutura enviada pelo backend)
      if (team === 'blue' && Array.isArray(pickBanData?.blueBans)) {
        const bans = pickBanData.blueBans.map((championId: any) => ({
          champion: { id: championId, name: this.getChampionNameById(championId) },
          championName: this.getChampionNameById(championId),
          championId: championId
        }));
        if (bans.length > 0) {
          logGameInProgress(`✅ [GameInProgress] Bans do backend encontrados para time ${team}:`, bans);
          this.cachedTeamBans.set(team, bans);
          return bans;
        }
      } else if (team === 'red' && Array.isArray(pickBanData?.redBans)) {
        const bans = pickBanData.redBans.map((championId: any) => ({
          champion: { id: championId, name: this.getChampionNameById(championId) },
          championName: this.getChampionNameById(championId),
          championId: championId
        }));
        if (bans.length > 0) {
          logGameInProgress(`✅ [GameInProgress] Bans do backend encontrados para time ${team}:`, bans);
          this.cachedTeamBans.set(team, bans);
          return bans;
        }
      }


      logGameInProgress(`⚠️ [GameInProgress] Nenhum ban encontrado para time ${team}`);
      const emptyResult: any[] = [];
      // ✅ CACHE: Armazenar resultado vazio
      this.cachedTeamBans.set(team, emptyResult);
      return emptyResult;
    } catch (error) {
      logGameInProgress('❌ [GameInProgress] Erro ao obter bans do time:', error);
      const emptyResult: any[] = [];
      this.cachedTeamBans.set(team, emptyResult);
      return emptyResult;
    }
  }

  // ✅ NOVO: Método para obter picks dos times
  getTeamPicks(team: TeamColor): any[] {
    // ✅ CACHE: Retornar do cache se disponível
    if (this.cachedTeamPicks.has(team)) {
      return this.cachedTeamPicks.get(team)!;
    }

    const normalizedPickBan2 = (this as any)._normalizedPickBanData;
    const data2 = this.gameData();
    const hasPickBan2 = !!(normalizedPickBan2 || data2?.pickBanData);
    if (!hasPickBan2) {
      const emptyResult: any[] = [];
      this.cachedTeamPicks.set(team, emptyResult);
      return emptyResult;
    }

    try {
      const pickBanData = normalizedPickBan2 || data2?.pickBanData;

      // ✅ CORREÇÃO: Extrair picks das phases (estrutura correta)
      if (Array.isArray(pickBanData?.phases)) {
        const teamPicks = pickBanData.phases
          .filter((phase: any) =>
            phase.action === 'pick' &&
            phase.team === team &&
            phase.champion &&
            phase.locked
          )
          .map((phase: any) => ({
            champion: phase.champion,
            championName: phase.champion?.name,
            championId: phase.champion?.id,
            player: phase.playerName || phase.player
          }));

        if (teamPicks.length > 0) {
          this.cachedTeamPicks.set(team, teamPicks);
          return teamPicks;
        }
        // Se fases não possuem campeões preenchidos, seguir com fallbacks
      }

      // ✅ FALLBACK: Verificar estrutura alternativa para dados de partida real
      if (team === 'blue' && pickBanData?.team1Picks) {
        const picks = (pickBanData.team1Picks || []).map((p: any) => ({
          champion: p.champion || (p.championId != null ? { id: p.championId, name: this.getChampionNameById(p.championId) } : undefined),
          championName: p.champion?.name || p.championName || (p.championId != null ? this.getChampionNameById(p.championId) : undefined),
          championId: p.champion?.id ?? p.championId,
          player: p.playerName || p.player
        }));
        if (picks.length > 0) {
          this.cachedTeamPicks.set(team, picks);
          return picks;
        }
      } else if (team === 'red' && pickBanData?.team2Picks) {
        const picks = (pickBanData.team2Picks || []).map((p: any) => ({
          champion: p.champion || (p.championId != null ? { id: p.championId, name: this.getChampionNameById(p.championId) } : undefined),
          championName: p.champion?.name || p.championName || (p.championId != null ? this.getChampionNameById(p.championId) : undefined),
          championId: p.champion?.id ?? p.championId,
          player: p.playerName || p.player
        }));
        if (picks.length > 0) {
          this.cachedTeamPicks.set(team, picks);
          return picks;
        }
      }

      // ✅ NOVO: Fallback via actions (se existir)
      if (Array.isArray(pickBanData?.actions)) {
        const normalizeTeam = (t: any): TeamColor | null => {
          if (t === 'blue' || t === 'red') return t;
          const n = typeof t === 'string' ? parseInt(t, 10) : t;
          if (n === 1 || n === 100) return 'blue';
          if (n === 2 || n === 200) return 'red';
          return null;
        };
        const picks = pickBanData.actions
          .filter((a: any) => (a.action === 'pick' || a.type === 'pick') && normalizeTeam(a.team) === team && (a.locked || a.completed || a.done || a.championId != null || a.champion))
          .map((a: any) => {
            const id = a.champion?.id ?? a.championId;
            const name = a.champion?.name ?? (id != null ? this.getChampionNameById(id) : undefined);
            return {
              champion: a.champion || (id != null ? { id, name } : undefined),
              championName: name,
              championId: id,
              player: a.playerName || a.player
            };
          });
        if (picks.length > 0) {
          this.cachedTeamPicks.set(team, picks);
          return picks;
        }
      }

      // ✅ NOVO: Verificar estrutura de dados da partida real (Riot API)
      if (Array.isArray(pickBanData?.picks)) {
        const teamId = team === 'blue' ? 100 : 200;
        const teamPicks = pickBanData.picks
          .filter((pick: any) => pick.teamId === teamId)
          .map((pick: any) => ({
            champion: { id: pick.championId, name: this.getChampionNameById(pick.championId) },
            championName: this.getChampionNameById(pick.championId),
            championId: pick.championId,
            player: pick.playerName || pick.summonerName
          }));

        this.cachedTeamPicks.set(team, teamPicks);
        return teamPicks;
      }

      const emptyResult: any[] = [];
      this.cachedTeamPicks.set(team, emptyResult);
      return emptyResult;
    } catch (error) {
      logGameInProgress('❌ [GameInProgress] Erro ao obter picks do time:', error);
      const emptyResult: any[] = [];
      this.cachedTeamPicks.set(team, emptyResult);
      return emptyResult;
    }
  }

  // ✅ NOVO: Método para obter ícone da lane
  getLaneIcon(lane: string): string {
    const laneIcons: { [key: string]: string } = {
      'top': '⚔️',
      'jungle': '🌲',
      'mid': '🔮',
      'adc': '🏹',
      'bot': '🏹',      // ✅ CORRIGIDO: Adicionar "bot" como alias de "adc"
      'bottom': '🏹',   // ✅ CORRIGIDO: Adicionar "bottom" como alias de "adc"
      'support': '🛡️',
      'fill': '❓'
    };

    return laneIcons[lane?.toLowerCase()] || '❓';
  }

  // ✅ NOVO: Método para formatar nome da lane
  getLaneName(lane: string): string {
    const laneNames: { [key: string]: string } = {
      'top': 'Top',
      'jungle': 'Jungle',
      'mid': 'Mid',
      'adc': 'ADC',
      'bot': 'ADC',      // ✅ CORRIGIDO: Adicionar "bot" como alias de "ADC"
      'bottom': 'ADC',   // ✅ CORRIGIDO: Adicionar "bottom" como alias de "ADC"
      'support': 'Support',
      'fill': 'Fill'
    };

    return laneNames[lane?.toLowerCase()] || lane || 'Fill';
  }

  // ❌ REMOVIDO: Old LCU Match Confirmation Modal Handlers
  // Sistema de detecção automática durante jogo foi removido
  /*
  onLcuMatchConfirmed(comparisonData: any): void {
    console.log('🎯 LCU match confirmed by leader:', comparisonData);

    // Extract winner from LCU match data
    let winner: TeamColor | null = null;
    if (comparisonData?.lcuMatch?.teams && comparisonData.lcuMatch.teams.length === 2) {
      const winningTeam = comparisonData.lcuMatch.teams.find((team: any) => team.win === "Win" || team.win === true);
      if (winningTeam) {
        winner = winningTeam.teamId === 100 ? 'blue' : 'red';
      }
    }

    // Apply the confirmed result to the game
    if (winner) {
      this.selectedWinner.set(winner);  // ✅ CORRIGIDO: Usar .set()
      console.log('🏆 Winner applied from LCU confirmation:', winner);

      // Complete the game with LCU data
      if (comparisonData.lcuMatch) {
        console.log('📊 Applying LCU match data:', comparisonData.lcuMatch);
        this.autoCompleteGameWithRealData(winner, true, comparisonData.lcuMatch);
      } else {
        // Fallback to normal completion
        this.autoCompleteGame(winner, true);
      }
    } else {
      console.log('⚠️ No winner detected in LCU match, requiring manual declaration');
      // If no winner detected, just close modal and let user declare manually
    }

    this.closeLcuConfirmationModal();
  } onLcuMatchRejected(): void {
    console.log('❌ LCU match rejected by leader');
    this.closeLcuConfirmationModal();
    // Continue trying to detect other matches
    this.tryLinkToLiveMatch();
  }

  onLcuModalClosed(): void {
    console.log('🚫 LCU confirmation modal closed');
    this.closeLcuConfirmationModal();
  }

  private showLcuMatchConfirmationModal(lcuMatchData: any, linkingScore: any): void {
    console.log('🎯 Showing LCU match confirmation modal');

    // Extract pick/ban data from our game for comparison
    const ourPickBanData = this.extractPickBanDataForComparison();

    // Transform our game data into the structure expected by the modal
    const customMatchData = this.transformGameDataForModal(ourPickBanData);

    // Calculate similarity details
    const similarityData = this.calculateSimilarityForModal(customMatchData, lcuMatchData, linkingScore);

    // Create comparison data structure in the format expected by the modal
    this.lcuConfirmationData = {
      customMatch: customMatchData,
      lcuMatch: lcuMatchData,
      similarity: similarityData
    };

    console.log('🎯 LCU confirmation data prepared:', this.lcuConfirmationData);

    // Determine if user is the match leader
    this.isMatchLeader = this.determineIfUserIsLeader();

    // Show the modal
    this.showLcuConfirmationModal = true;
  }

  private closeLcuConfirmationModal(): void {
    this.showLcuConfirmationModal = false;
    this.lcuConfirmationData = null;
  }

  private determineIfUserIsLeader(): boolean {
    // For now, always return true since we want the leader to be able to confirm
    // In a real implementation, you would check if the current user is the match creator
    // or has leader privileges in the current match
    console.log('🎯 Determining if user is leader - currently always true');
    return true;
  }

  // Private method to extract data from pick_ban_data for LCU comparison
  private extractPickBanDataForComparison(): any {
    if (!this.gameData?.pickBanData) {
      return null;
    }

    try {
      const pickBanData = typeof this.gameData.pickBanData === 'string'
        ? JSON.parse(this.gameData.pickBanData)
        : this.gameData.pickBanData;

      return {
        team1: {
          picks: pickBanData.team1Picks || [],
          bans: pickBanData.team1Bans || []
        },
        team2: {
          picks: pickBanData.team2Picks || [],
          bans: pickBanData.team2Bans || []
        }
      };
    } catch (error) {
      console.error('❌ Error parsing pick_ban_data:', error);
      return null;
    }
  }

  // Transform game data into the structure expected by the modal
  private transformGameDataForModal(ourPickBanData: any): any {
    if (!this.gameData || !ourPickBanData) {
      return {
        id: 0,
        players: [],
        picks: [],
        bans: [],
        pickBanData: {
          team1Picks: [],
          team2Picks: [],
          team1Bans: [],
          team2Bans: []
        }
      };
    }

    // Extract player names from both teams
    const allPlayers = [
      ...(this.gameData.team1?.map(p => p.summonerName || p.name) || []),
      ...(this.gameData.team2?.map(p => p.summonerName || p.name) || [])
    ];

    // Transform picks and bans into modal format
    const transformPicks = (picks: any[]): any[] => {
      return picks.map(pick => ({
        playerName: pick.player || pick.summonerName || pick.playerName || 'Unknown',
        champion: {
          id: pick.championId?.toString() || pick.champion?.id || 'unknown',
          name: pick.championName || pick.champion?.name || pick.champion || 'Unknown Champion',
          image: pick.champion?.image || undefined
        },
        playerLane: pick.lane || pick.assignedLane || pick.playerLane || 'unknown'
      }));
    };

    const transformBans = (bans: any[]): any[] => {
      return bans.map(ban => ({
        playerName: ban.playerName || ban.player || 'Unknown',
        champion: {
          id: ban.championId?.toString() || ban.champion?.id || 'unknown',
          name: ban.championName || ban.champion?.name || ban.champion || 'Unknown Champion',
          image: ban.champion?.image || undefined
        }
      }));
    };

    const team1Picks = transformPicks(ourPickBanData.team1?.picks || []);
    const team2Picks = transformPicks(ourPickBanData.team2?.picks || []);
    const team1Bans = transformBans(ourPickBanData.team1?.bans || []);
    const team2Bans = transformBans(ourPickBanData.team2?.bans || []);

    // Extract all champions for picks/bans arrays
    const allPicks = [...team1Picks, ...team2Picks].map(p => p.champion);
    const allBans = [...team1Bans, ...team2Bans].map(b => b.champion);

    return {
      id: parseInt(this.gameData.gameId) || 0,
      players: allPlayers,
      picks: allPicks,
      bans: allBans,
      pickBanData: {
        team1Picks,
        team2Picks,
        team1Bans,
        team2Bans
      }
    };
  }

  // Calculate similarity data for the modal
  private calculateSimilarityForModal(customMatchData: any, lcuMatchData: any, linkingScore: any): any {
    const details: string[] = [];

    // Add linking score reason
    if (linkingScore.reason) {
      details.push(linkingScore.reason);
    }

    // Calculate player match percentage
    const customPlayers = customMatchData.players || [];
    const lcuPlayers = lcuMatchData.participants?.map((p: any) =>
      p.summonerName || p.gameName || `Summoner ${p.participantId}`
    ) || [];

    let playerMatches = 0;
    customPlayers.forEach((player: string) => {
      if (lcuPlayers.some((lcuPlayer: string) =>
        lcuPlayer.toLowerCase().includes(player.toLowerCase()) ||
        player.toLowerCase().includes(lcuPlayer.toLowerCase())
      )) {
        playerMatches++;
      }
    });

    const playerMatchPercentage = customPlayers.length > 0
      ? Math.round((playerMatches / customPlayers.length) * 100)
      : 0;

    if (playerMatches > 0) {
      details.push(`${playerMatches}/${customPlayers.length} jogadores encontrados`);
    }

    // Calculate champion match percentage
    const customChampions = customMatchData.picks?.map((p: any) => p.name?.toLowerCase()) || [];
    const lcuChampions = lcuMatchData.participants?.map((p: any) =>
      this.getChampionNameById(p.championId)?.toLowerCase()
    ) || [];

    let championMatches = 0;
    customChampions.forEach((champion: string) => {
      if (lcuChampions.includes(champion)) {
        championMatches++;
      }
    });

    const championMatchPercentage = customChampions.length > 0
      ? Math.round((championMatches / customChampions.length) * 100)
      : 0;

    if (championMatches > 0) {
      details.push(`${championMatches}/${customChampions.length} campeões correspondentes`);
    }

    // Use the linking score as overall confidence, but adjust based on our calculations
    const overallScore = Math.max(linkingScore.score || 0,
      Math.round((playerMatchPercentage + championMatchPercentage) / 2));

    return {
      playerMatch: playerMatchPercentage,
      championMatch: championMatchPercentage,
      overall: overallScore,
      details: details
    };
  }
  */

  /**
   * ✅ NOVO: Retorna o texto do badge de lane
   */
  getLaneBadgeText(laneBadge: string): string {
    switch (laneBadge) {
      case 'primary': return '1ª Lane';
      case 'secondary': return '2ª Lane';
      case 'autofill': return 'Auto-fill';
      default: return '';
    }
  }

  /**
   * ✅ NOVO: Abre o modal de espectadores
   */
  openSpectatorsModal(): void {
    console.log('[GameInProgress] Abrindo modal de espectadores');
    this.showSpectatorsModal.set(true);
  }

  /**
   * ✅ NOVO: Fecha o modal de espectadores
   */
  closeSpectatorsModal(): void {
    console.log('[GameInProgress] Fechando modal de espectadores');
    this.showSpectatorsModal.set(false);
  }

  /**
   * ✅ NOVO: Envia acknowledgment ao backend que o jogador JÁ ESTÁ vendo o game
   * Permite que o backend pare de enviar retry para este jogador
   */
  private sendGameAcknowledgment(): void {
    try {
      const data = this.gameData();
      if (!data?.matchId && !data?.id) {
        logGameInProgress('⚠️ Sem matchId para enviar acknowledgment');
        return;
      }

      const matchId = data.matchId || data.id;
      const playerName = this.summonerName;

      if (!playerName) {
        logGameInProgress('⚠️ Sem playerName para enviar acknowledgment');
        return;
      }

      const ackData = {
        type: 'game_acknowledged',
        matchId: matchId,
        playerName: playerName
      };

      logGameInProgress('📤 [GameInProgress] Enviando acknowledgment:', ackData);
      this.apiService.sendWebSocketMessage(ackData);

    } catch (error) {
      logGameInProgress('❌ Erro ao enviar game acknowledgment:', error);
    }
  }

  // ✅ NOVO: Métodos para status e identificação dos jogadores

  /**
   * Identifica o usuário atual via LCU
   */
  private identifyCurrentUser(): void {
    const player = this.currentPlayer();
    if (player) {
      console.log('🔍 [GameInProgress] Usuário atual identificado via LCU:', {
        displayName: player.displayName,
        summonerName: player.summonerName,
        gameName: player.gameName,
        tagLine: player.tagLine
      });

      this.markCurrentUserInPlayers(player);
    } else {
      console.log('⚠️ [GameInProgress] Usuário atual não disponível via LCU');
    }
  }

  /**
   * Marca o jogador atual nos dados da partida
   */
  private markCurrentUserInPlayers(currentUser: any): void {
    const data = this.gameData();
    if (!data) return;

    const allPlayers = [
      ...(data.team1 || []),
      ...(data.team2 || [])
    ];

    allPlayers.forEach(player => {
      if (this.isPlayerCurrentUser(player, currentUser)) {
        player.isCurrentUser = true;
        console.log('✅ [GameInProgress] Jogador marcado como usuário atual:', player.summonerName);
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
   * Obtém o status de votação de um jogador
   */
  getPlayerVoteStatus(summonerName: string): 'pending' | 'voted' {
    // ✅ CORREÇÃO: Apenas pending ou voted (sem declined/timeout)
    const data = this.gameData();
    const allPlayers = [
      ...(data?.team1 || []),
      ...(data?.team2 || [])
    ];

    const player = allPlayers.find(p => p.summonerName === summonerName);
    return player?.voteStatus || 'pending';
  }

  /**
   * Obtém o ícone de status de votação
   */
  getVoteStatusIcon(player: any): string {
    const status = this.getPlayerVoteStatus(player.summonerName);
    switch (status) {
      case 'voted': return '✅';
      case 'pending': return '⏳';
      default: return '⏳';
    }
  }

  /**
   * Obtém a classe CSS para o status de votação
   */
  getVoteStatusClass(player: any): string {
    const status = this.getPlayerVoteStatus(player.summonerName);
    return `vote-status-${status}`;
  }

  /**
   * Obtém contagem de votos
   */
  getVoteCount(): { voted: number; total: number } {
    let voted = 0;
    const data = this.gameData();
    if (!data) return { voted: 0, total: 0 };

    const allPlayers = [
      ...(data.team1 || []),
      ...(data.team2 || [])
    ];

    allPlayers.forEach(player => {
      if (player.voteStatus === 'voted') {
        voted++;
      }
    });

    return {
      voted,
      total: allPlayers.length
    };
  }

  /**
   * Verifica se todos os jogadores votaram
   */
  haveAllPlayersVoted(): boolean {
    const count = this.getVoteCount();
    return count.voted === count.total;
  }

  /**
   * Obtém progresso de votação em porcentagem
   */
  getVoteProgress(): number {
    const count = this.getVoteCount();
    if (count.total === 0) return 0;
    return Math.round((count.voted / count.total) * 100);
  }

  // ✅ NOVO: Métodos WebSocket para atualizações de votação em tempo real

  /**
   * ✅ NOVO: Configura observables do ElectronEventsService (mesma técnica do draft confirmation)
   */
  private setupVoteObservables(): void {
    // ✅ Listener para progresso de votação via Observable
    this.subscriptions.push(
      this.electronEvents.matchVoteProgress$.subscribe((data: any) => {
        if (data && data.matchId === this.matchId) {
          console.log('📊📊📊 [matchVoteProgress$] PROGRESSO RECEBIDO via ElectronEventsService!', {
            matchId: data.matchId,
            votedCount: data.votedCount,
            totalPlayers: data.totalPlayers
          });
          this.handleVoteProgress(data);
          this.cdr.markForCheck();
          this.cdr.detectChanges();
        }
      })
    );

    // ✅ Listener para atualizações individuais de votação via Observable
    this.subscriptions.push(
      this.electronEvents.matchVoteUpdate$.subscribe((data: any) => {
        if (data && data.matchId === this.matchId) {
          console.log('📊📊📊 [matchVoteUpdate$] ATUALIZAÇÃO RECEBIDA via ElectronEventsService!', data);
          this.handleVoteUpdate(data);
          this.cdr.markForCheck();
          this.cdr.detectChanges();
        }
      })
    );

    logGameInProgress('✅ [GameInProgress] Observables de votação configurados (mesma técnica do draft)');
  }

  /**
   * Configura os listeners WebSocket para atualizações de votação (LEGADO - manter para compatibilidade)
   */
  private setupVoteWebSocketListeners(): void {
    // ✅ MANTIDO: Para compatibilidade, mas observables são a fonte principal agora
    if (this.voteWsSubscription) {
      this.voteWsSubscription.unsubscribe();
    }

    // ✅ NOVO: Escutar eventos customizados do app.ts (fallback)
    document.addEventListener('matchVoteProgress', this.handleVoteProgressEvent);
    document.addEventListener('matchVoteUpdate', this.handleVoteUpdateEvent);

    this.voteWsSubscription = this.apiService.onWebSocketMessage().subscribe({
      next: (message: any) => {
        const { type, data } = message;

        if (type === 'match_vote_progress') {
          this.handleVoteProgress(data);
        } else if (type === 'match_vote_update') {
          this.handleVoteUpdate(data);
        }
      },
      error: (error: any) => {
        console.error('❌ [GameInProgress] Erro no WebSocket de votação:', error);
      }
    });

    console.log('🔌 [GameInProgress] WebSocket listeners de votação configurados (legado)');
  }

  /**
   * Limpa os listeners WebSocket de votação
   */
  private cleanupVoteWebSocketListeners(): void {
    if (this.voteWsSubscription) {
      this.voteWsSubscription.unsubscribe();
      this.voteWsSubscription = undefined;
      console.log('🔌 [GameInProgress] WebSocket listeners de votação limpos');
    }

    // ✅ NOVO: Remover listeners customizados
    document.removeEventListener('matchVoteProgress', this.handleVoteProgressEvent);
    document.removeEventListener('matchVoteUpdate', this.handleVoteUpdateEvent);
  }

  // ✅ NOVO: Métodos para lidar com eventos customizados
  private readonly handleVoteProgressEvent = (event: any): void => {
    console.log('📊 [GameInProgress] Evento customizado de progresso recebido:', event.detail);
    this.handleVoteProgress(event.detail);
  };

  private readonly handleVoteUpdateEvent = (event: any): void => {
    console.log('📊 [GameInProgress] Evento customizado de atualização recebido:', event.detail);
    this.handleVoteUpdate(event.detail);
  };

  /**
   * Manipula atualizações de progresso de votação
   */
  private handleVoteProgress(data: any): void {
    console.log('🗳️ [GameInProgress] Progresso de votação recebido:', data);

    if (data.votedPlayers && Array.isArray(data.votedPlayers)) {
      // ✅ CORREÇÃO: Usar mesma técnica do draft - buscar nos dados dos jogadores
      const gameData = this.gameData();
      const allPlayers = [
        ...(gameData?.team1 || []),
        ...(gameData?.team2 || [])
      ];

      console.log(`🗳️ [GameInProgress] Processando ${data.votedPlayers.length} votos para ${allPlayers.length} jogadores`);
      console.log(`🗳️ [GameInProgress] Jogadores disponíveis:`, allPlayers.map(p => p.summonerName));
      console.log(`🗳️ [GameInProgress] Jogadores que votaram:`, data.votedPlayers);

      // Atualizar status de todos os jogadores para 'pending' primeiro
      allPlayers.forEach(player => {
        player.voteStatus = 'pending';
        console.log(`🗳️ [GameInProgress] Resetando ${player.summonerName} para pending`);
      });

      // Marcar jogadores que votaram como 'voted'
      data.votedPlayers.forEach((votedPlayerName: string) => {
        const player = allPlayers.find(p =>
          p.summonerName?.toLowerCase() === votedPlayerName?.toLowerCase() ||
          (p.riotIdGameName && p.riotIdTagline && `${p.riotIdGameName}#${p.riotIdTagline}`.toLowerCase() === votedPlayerName?.toLowerCase())
        );

        if (player) {
          player.voteStatus = 'voted';
          player.votedAt = new Date().toISOString();
          player.votedFor = data.winnerTeam || 'blue';
          console.log('✅ [GameInProgress] Jogador votou:', player.summonerName, 'Status:', player.voteStatus);
        } else {
          console.log('⚠️ [GameInProgress] Jogador não encontrado:', votedPlayerName);
        }
      });

      // Atualizar contadores usando signals
      this.votedCount.set(data.votedCount || 0);
      this.totalPlayers.set(data.totalPlayers || 10);

      console.log(`🗳️ [GameInProgress] Atualizado: ${this.votedCount()}/${this.totalPlayers()} votaram`);
      console.log(`🗳️ [GameInProgress] Progresso: ${this.getVoteProgress()}%`);
      console.log(`🗳️ [GameInProgress] Status final dos jogadores:`, allPlayers.map(p => ({ name: p.summonerName, status: p.voteStatus })));

      // ✅ NOVO: Forçar detecção de mudanças
      this.cdr.markForCheck();
      setTimeout(() => {
        console.log(`🔄 [GameInProgress] Re-check progresso: ${this.getVoteProgress()}%`);
        console.log(`🔄 [GameInProgress] Re-check contagem: ${this.getVoteCount().voted}/${this.getVoteCount().total}`);
      }, 100);
    } else {
      console.log('⚠️ [GameInProgress] Dados de votação inválidos:', data);
    }
  }

  /**
   * Manipula atualizações individuais de votação
   */
  private handleVoteUpdate(data: any): void {
    console.log('🔄 [GameInProgress] Atualização de votação recebida:', data);

    if (data.playerName && data.status) {
      const gameData = this.gameData();
      const allPlayers = [
        ...(gameData?.team1 || []),
        ...(gameData?.team2 || [])
      ];

      const player = allPlayers.find(p => p.summonerName === data.playerName);
      if (player) {
        // ✅ CORREÇÃO: Apenas 'pending' ou 'voted'
        player.voteStatus = data.status === 'voted' ? 'voted' : 'pending';
        if (data.status === 'voted') {
          player.votedAt = new Date().toISOString();
          player.votedFor = data.votedFor;
        }
        console.log(`🔄 [GameInProgress] Status atualizado para ${data.playerName}: ${player.voteStatus}`);
        // ✅ NOVO: Forçar detecção de mudanças
        this.cdr.markForCheck();
      }
    }
  }

  /**
   * Reseta o status de votação de todos os jogadores para 'pending'
   */
  private resetAllVoteStatuses(): void {
    const data = this.gameData();
    if (!data) return;

    const allPlayers = [
      ...(data.team1 || []),
      ...(data.team2 || [])
    ];

    allPlayers.forEach(player => {
      // ✅ CORREÇÃO: Usar mesma técnica do draft - definir no player.voteStatus
      player.voteStatus = 'pending';
      console.log(`🗳️ [GameInProgress] Resetando ${player.summonerName} para pending`);
    });
  }
}

