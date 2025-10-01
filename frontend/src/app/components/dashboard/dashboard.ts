import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, OnChanges, ChangeDetectorRef, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Player, QueueStatus, Match } from '../../interfaces';
import { ApiService } from '../../services/api';
import { ChampionService } from '../../services/champion.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DashboardComponent implements OnInit, OnDestroy, OnChanges {
  @Input() player: Player | null = null;
  @Input() queueStatus: QueueStatus = {
    playersInQueue: 0,
    averageWaitTime: 0,
    estimatedMatchTime: 0,
    isActive: true
  };

  @Output() joinQueue = new EventEmitter<void>();
  @Output() viewHistory = new EventEmitter<void>();
  @Output() openSettings = new EventEmitter<void>();

  leaderboardPosition: number = 0;
  private subscriptions: Subscription[] = [];
  private dataLoaded = false; // Flag para controlar se os dados já foram carregados
  private lastPlayerIdentifier = ''; // ✅ NOVO: Controlar última identificação do player
  private lastPlayerObject: Player | null = null; // ✅ NOVO: Referência do último player processado
  private lcuFallbackAttempted = false; // ✅ NOVO: Controlar se já tentou LCU para evitar loop
  private lastLoadTime = 0; // ✅ NOVO: Throttle para loadAllData
  private loadThrottleMs = 5000; // ✅ CORREÇÃO: 5 segundos entre tentativas para evitar loops
  private customMatchesAttempted = false; // ✅ NOVO: Flag para custom matches
  private fallbackCompleted = false; // ✅ NOVO: Flag para fallback completo
  private processingPlayer = false; // ✅ NOVO: Flag para evitar processamento simultâneo
  private lcuCacheKey = 'dashboard_lcu_cache'; // ✅ NOVO: Chave para cache do LCU
  private cacheExpireMs = 30 * 60 * 1000; // ✅ NOVO: Cache expira em 30 minutos

  // Dados de partidas - agora preenchidos com dados reais
  recentMatches: Match[] = [];
  isLoadingMatches: boolean = false;
  matchHistoryError: string | null = null;

  // Contagem de partidas customizadas
  customMatchesCount: number = 0;
  isLoadingCustomCount: boolean = false;

  constructor(private apiService: ApiService, private cdr: ChangeDetectorRef, private championService: ChampionService) { }

  private ensureChampionsLoaded(next: () => void): void {
    if (this.championService.isLoaded()) {
      next();
      return;
    }
    this.championService.preloadChampions().subscribe({ next: () => next(), error: () => next() });
  }
  // Detectar mudanças no player - APENAS quando o player muda pela primeira vez
  ngOnChanges(): void {
    // ✅ CORREÇÃO CRÍTICA: Evitar processamento se já está processando
    if (this.processingPlayer) {
      console.log('🔒 [DASHBOARD] Already processing player, blocking ngOnChanges');
      return;
    }

    // ✅ CORREÇÃO: Se não há player, apenas reset se necessário
    if (!this.player) {
      if (this.lastPlayerObject) {
        console.log('🚪 [DASHBOARD] Player removed, resetting state');
        this.resetDashboardState();
        this.lastPlayerObject = null;
      }
      return;
    }

    // ✅ CORREÇÃO: Verificar se é realmente o mesmo objeto player
    if (this.player === this.lastPlayerObject) {
      console.log('🔄 [DASHBOARD] Same player object reference, skipping...');
      return;
    }

    const currentPlayerIdentifier = this.getPlayerIdentifier(this.player);
    const now = Date.now();

    // ✅ CORREÇÃO: Se é o mesmo player identifier E já carregamos dados, skip
    if (this.lastPlayerIdentifier === currentPlayerIdentifier && this.dataLoaded && this.fallbackCompleted) {
      console.log('✅ [DASHBOARD] Same player with completed data, skipping:', currentPlayerIdentifier);
      this.lastPlayerObject = this.player; // Atualizar referência
      return;
    }

    // ✅ THROTTLE: Evitar chamadas muito frequentes
    if (this.lastLoadTime && (now - this.lastLoadTime) < this.loadThrottleMs) {
      console.log('⏳ [DASHBOARD] Throttling: Too frequent attempts, skipping:', currentPlayerIdentifier);
      return;
    }

    // ✅ NOVO PLAYER: Processar apenas se mudou ou ainda não carregou
    console.log('🎮 [DASHBOARD] Processing player:', {
      from: this.lastPlayerIdentifier || 'none',
      to: currentPlayerIdentifier,
      dataLoaded: this.dataLoaded,
      fallbackCompleted: this.fallbackCompleted
    });

    // ✅ LOCK: Marcar como processando
    this.processingPlayer = true;
    this.lastLoadTime = now;

    // Se é um player diferente, reset completo
    if (currentPlayerIdentifier !== this.lastPlayerIdentifier) {
      this.resetDashboardState();
      this.lastPlayerIdentifier = currentPlayerIdentifier;
    }

    // Atualizar referência do objeto
    this.lastPlayerObject = this.player;

    // ✅ CARREGAMENTO: Apenas se ainda não carregamos dados
    if (!this.dataLoaded || !this.fallbackCompleted) {
      console.log('📊 [DASHBOARD] Loading data for player:', currentPlayerIdentifier);

      // Garantir champions carregados antes da primeira renderização
      this.ensureChampionsLoaded(() => {
        setTimeout(() => {
          this.loadAllData();
          this.dataLoaded = true;
          this.processingPlayer = false; // ✅ UNLOCK
        }, 100);
      });
    } else {
      this.processingPlayer = false; // ✅ UNLOCK
    }
  }

  // ✅ NOVO: Método para reset completo do estado
  private resetDashboardState(): void {
    this.dataLoaded = false;
    this.isLoadingMatches = false;
    this.isLoadingCustomCount = false;
    this.recentMatches = [];
    this.customMatchesCount = 0;
    this.leaderboardPosition = 0;
    this.matchHistoryError = null;
    this.lcuFallbackAttempted = false;
    this.customMatchesAttempted = false;
    this.fallbackCompleted = false;
    this.processingPlayer = false; // ✅ NOVO: Reset lock
    this.lastLoadTime = 0;

    // ✅ NOVO: Limpar cache apenas quando player muda (não quando reseta estado)
    // O cache será mantido para o mesmo player

    console.log('🔄 [DASHBOARD] Dashboard state reset');
  }

  // ✅ NOVO: Método para criar identificador único do player
  private getPlayerIdentifier(player: Player): string {
    if (player.displayName && player.displayName.trim() !== '') {
      return player.displayName;
    }
    if (player.summonerName) {
      return player.summonerName;
    }
    if (player.gameName && player.tagLine) {
      return `${player.gameName}#${player.tagLine}`;
    }
    return player.id?.toString() || 'unknown';
  }

  // Tips array
  private tips = [
    {
      title: "Mantenha a calma",
      description: "Jogadores com mentalidade positiva ganham 23% mais partidas. Foque no próximo play, não no último erro!"
    },
    {
      title: "Ward é vida",
      description: "Colocar wards nos momentos certos pode decidir uma partida. Visão é controle de mapa!"
    },
    {
      title: "CS é fundamental",
      description: "10 CS equivalem a 1 kill em gold. Foque em farm consistente para dominar o mid game."
    },
    {
      title: "Jogue com seu time",
      description: "League é um jogo de equipe. Coordenação vale mais que individual skill na maioria das situações."
    }
  ];
  getWinRate(): number {
    // Try to get win rate from ranked data first
    if (this.player?.rankedData?.soloQueue) {
      const soloQueue = this.player.rankedData.soloQueue;
      const totalGames = soloQueue.wins + soloQueue.losses;
      if (totalGames > 0) {
        return Math.round((soloQueue.wins / totalGames) * 100);
      }
    }

    // Fallback to basic wins/losses
    if (!this.player || !this.player.wins || !this.player.losses || (this.player.wins + this.player.losses) === 0) return 0;
    return Math.round((this.player.wins / (this.player.wins + this.player.losses)) * 100);
  }

  getTotalGames(): number {
    // Try to get total games from ranked data first
    if (this.player?.rankedData?.soloQueue) {
      const soloQueue = this.player.rankedData.soloQueue;
      return soloQueue.wins + soloQueue.losses;
    }

    // Fallback to basic wins/losses
    if (!this.player || !this.player.wins || !this.player.losses) return 0;
    return this.player.wins + this.player.losses;
  }

  getRankDisplay(): string {
    if (this.player?.rankedData?.soloQueue) {
      const soloQueue = this.player.rankedData.soloQueue;
      return `${soloQueue.tier} ${soloQueue.rank}`;
    }

    if (this.player?.rank) {
      return this.player.rank.display;
    }

    return 'Unranked';
  }

  getRankLP(): number {
    if (this.player?.rankedData?.soloQueue) {
      return this.player.rankedData.soloQueue.leaguePoints || 0;
    }

    if (this.player?.rank?.lp) {
      return this.player.rank.lp;
    }

    return 0;
  }

  formatWaitTime(seconds: number): string {
    if (seconds === 0) return 'N/A';

    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;

    if (minutes > 0) {
      return `${minutes}m ${remainingSeconds}s`;
    }
    return `${remainingSeconds}s`;
  }

  formatMatchDuration(seconds: number): string {
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  formatRelativeTime(timestamp: number): string {
    const now = Date.now();
    const diff = now - timestamp;
    const minutes = Math.floor(diff / (1000 * 60));
    const hours = Math.floor(diff / (1000 * 60 * 60));
    const days = Math.floor(diff / (1000 * 60 * 60 * 24));

    if (days > 0) {
      return `${days} dia${days > 1 ? 's' : ''} atrás`;
    } else if (hours > 0) {
      return `${hours} hora${hours > 1 ? 's' : ''} atrás`;
    } else if (minutes > 0) {
      return `${minutes} minuto${minutes > 1 ? 's' : ''} atrás`;
    } else {
      return 'Agora mesmo';
    }
  }

  getCurrentStreak(): number {
    if (!this.recentMatches || this.recentMatches.length === 0) return 0;

    let streak = 0;
    const lastResult = this.recentMatches[0].isVictory;

    for (const match of this.recentMatches) {
      if (match.isVictory === lastResult) {
        streak++;
      } else {
        break;
      }
    }

    return streak;
  }

  getStreakType(): string {
    if (!this.recentMatches || this.recentMatches.length === 0) return 'sem dados';

    const isWinStreak = this.recentMatches[0].isVictory;
    const streak = this.getCurrentStreak();

    if (streak === 0) return 'sem sequência';

    return isWinStreak ?
      `vitória${streak > 1 ? 's' : ''} seguida${streak > 1 ? 's' : ''}` :
      `derrota${streak > 1 ? 's' : ''} seguida${streak > 1 ? 's' : ''}`;
  }

  getHighestMMR(): number {
    return this.player?.currentMMR ? this.player.currentMMR + 50 : 1200; // Mock highest MMR
  }  // Método para buscar dados reais de partidas
  loadRecentMatches(): void {
    // ✅ CORREÇÃO CRÍTICA: Verificar se já está carregando ou já tem dados
    if (this.isLoadingMatches) {
      console.log('⏳ [DASHBOARD] Already loading matches, skipping...');
      return;
    }

    // ✅ CORREÇÃO: Se já tem dados e não precisa recarregar, skip
    if (this.recentMatches && this.recentMatches.length > 0 && this.dataLoaded) {
      console.log('✅ [DASHBOARD] Already have recent matches, skipping reload...');
      return;
    }

    console.log('📊 [DASHBOARD] Starting match loading process...');

    // ✅ NOVA ESTRATÉGIA: Tentar custom matches primeiro, depois LCU como fallback
    this.loadCustomMatchesFromDatabase();
  }

  private loadFromLCU(): void {
    // ✅ CORREÇÃO TEMPORÁRIA: Desabilitar completamente para evitar loop
    console.log('🚫 [DASHBOARD] LCU loading temporariamente desabilitado para evitar loop');
    this.recentMatches = [];
    this.isLoadingMatches = false;
    this.matchHistoryError = 'Carregamento do LCU desabilitado temporariamente.';
    return;
  }

  private loadFromLCUOriginal(): void {
    // ✅ CORREÇÃO TEMPORÁRIA: Desabilitar completamente para evitar loop
    console.log('� [DASHBOARD] LCU Original loading temporariamente desabilitado para evitar loop');
    this.recentMatches = [];
    this.isLoadingMatches = false;
    this.matchHistoryError = 'Carregamento alternativo do LCU desabilitado temporariamente.';
    return;
  }

  private loadFromRiotAPI(): void {
    // Only attempt Riot API if absolutely necessary and show minimal error feedback
    console.log('📡 Attempting to load match history from Riot API as fallback...');

    const riotHistorySub = this.apiService.getPlayerMatchHistoryFromRiot(this.player!.puuid!, this.player!.region || 'br1', 5)
      .subscribe({
        next: (response) => {
          this.processRiotApiMatches(response);
          this.isLoadingMatches = false;
          console.log('✅ Match history loaded from Riot API successfully');
        },
        error: (error) => {
          // Suppress verbose error logging for Riot API failures when service is down
          if (error.message?.includes('Riot API') || error.message?.includes('503') || error.message?.includes('API')) {
            console.log('🚫 Riot API unavailable for match history - using fallback data');
          } else {
            console.warn('⚠️ Failed to load match history from Riot API:', error.message);
          }

          // Set a more user-friendly error message instead of technical details
          this.matchHistoryError = 'Histórico indisponível - dados do LCU serão usados quando possível';
          this.generateMockData(); // Fallback para dados mock
          this.isLoadingMatches = false;
        }
      });

    this.subscriptions.push(riotHistorySub);
  }

  private processRiotApiMatches(response: any): void {
    if (response && response.matches && Array.isArray(response.matches)) {
      // A API retorna apenas IDs, então vamos usar dados básicos mockados por enquanto
      // Em uma implementação completa, faria chamadas para cada matchId para obter detalhes
      this.recentMatches = response.matches.slice(0, 5).map((matchId: number, index: number) => {
        return {
          id: index + 1,
          timestamp: Date.now() - (index * 60 * 60 * 1000), // Simular timestamps
          isVictory: Math.random() > 0.5, // Temporário até obter dados reais
          duration: 1500 + Math.floor(Math.random() * 1200), // 25-45 min
          mmrChange: this.calculateMMRChange(Math.random() > 0.5),
          gameMode: 'Ranked Solo',
          champion: 'Unknown', // Temporário
          kda: '0/0/0' // Temporário
        };
      });
    } else {
      // Fallback para LCU se não conseguir dados da Riot API
      this.loadFromLCU();
    }
  }

  private processLCUMatches(response: any): void {
    // A estrutura do LCU agora retorna { success: true, matches: [...] }
    let games = null;

    if (response && response.success && Array.isArray(response.matches)) {
      games = response.matches;
    } else if (response && Array.isArray(response.games)) {
      games = response.games;
    } else if (response && Array.isArray(response)) {
      games = response;
    }

    if (games && games.length > 0) {
      // Pegar só as 3 primeiras para o dashboard
      const selectedGames = games.slice(0, 3);

      this.recentMatches = selectedGames.map((match: any, index: number) => {
        // Encontrar o jogador atual para determinar vitória
        const currentPlayerPuuid = this.player?.puuid;
        let isVictory = false;
        let playerStats = null;
        let currentPlayerIdentity = null;
        let currentParticipant = null;

        if (currentPlayerPuuid && match.participants) {
          // Encontrar o participante atual
          currentParticipant = match.participants.find((p: any) =>
            match.participantIdentities?.find((pi: any) =>
              pi.participantId === p.participantId &&
              pi.player?.puuid === currentPlayerPuuid
            )
          );

          // Encontrar a identidade do jogador atual
          currentPlayerIdentity = match.participantIdentities?.find((pi: any) =>
            pi.player?.puuid === currentPlayerPuuid
          );

          if (currentParticipant) {
            isVictory = currentParticipant.stats?.win || false;
            playerStats = currentParticipant.stats;
          }
        }

        // Determinar o modo de jogo: preferir queueId quando disponível
        let gameMode = match.gameMode || 'CLASSIC';
        if (match.gameType === 'CUSTOM_GAME') {
          gameMode = 'CUSTOM';
        }
        let gameModeLabel = '';
        const queueId = match.queueId || match.queue || match.queueType;
        if (typeof queueId === 'number') {
          // Mapeamento por queueId (LCU/Riot)
          switch (queueId) {
            case 420: gameModeLabel = 'Ranked Solo'; break;
            case 440: gameModeLabel = 'Ranked Flex'; break;
            case 400: gameModeLabel = 'Normal Draft'; break;
            case 430: gameModeLabel = 'Normal Blind'; break;
            case 450: gameModeLabel = 'ARAM'; break;
            case 700: gameModeLabel = 'Clash'; break;
            case 1900: gameModeLabel = 'URF'; break;
            default: gameModeLabel = this.formatGameMode(gameMode); break;
          }
        } else {
          gameModeLabel = this.formatGameMode(gameMode);
        }

        // Tentar pegar o championId do participante principal
        const championId = currentParticipant?.championId || playerStats?.championId;
        const championName = championId ? (this.championService.getChampionName(championId) || `Champion${championId}`) : 'Unknown';

        // Buscar o nome do jogador
        const playerName = currentPlayerIdentity?.player?.gameName ||
          this.player?.summonerName ||
          'Unknown';

        const processedMatch = {
          id: index + 1,
          timestamp: match.gameCreation || match.gameStartTime || Date.now(),
          isVictory: isVictory,
          duration: match.gameDuration || match.gameLength || 1800,
          mmrChange: null, // LCU não provê LP/PDL; ocultar no dashboard
          gameMode: gameModeLabel,
          champion: championName || 'Unknown',
          playerName: playerName,
          kda: `${playerStats?.kills || 0}/${playerStats?.deaths || 0}/${playerStats?.assists || 0}`,
          isCustomMatch: false
        };

        return processedMatch;
      });

      // Força a detecção de mudanças
      this.cdr.detectChanges();
    } else {
      this.generateMockData();
    }
  }

  private calculateMMRChange(isWin: boolean): number {
    const baseChange = Math.floor(Math.random() * 10) + 10; // 10-20 pontos
    return isWin ? baseChange : -baseChange;
  }

  private formatGameMode(gameMode: string): string {
    const gameModes: { [key: string]: string } = {
      'CLASSIC': 'Ranked Solo',
      'RANKED_SOLO_5x5': 'Ranked Solo',
      'RANKED_FLEX_SR': 'Ranked Flex',
      'ARAM': 'ARAM',
      'NORMAL': 'Normal',
      'DRAFT': 'Draft Pick',
      'CUSTOM': 'Personalizada',
      'PRACTICETOOL': 'Ferramenta de Treino',
      'TUTORIAL': 'Tutorial',
      'ONEFORALL': 'Um para Todos',
      'ASCENSION': 'Ascensão',
      'FIRSTBLOOD': 'Snowdown Showdown',
      'KINGPORO': 'Rei Poro',
      'SIEGE': 'Nexus Siege',
      'ASSASSINATE': 'Blood Hunt Assassin',
      'ARSR': 'All Random Summoner\'s Rift',
      'DARKSTAR': 'Dark Star: Singularity',
      'STARGUARDIAN': 'Star Guardian',
      'PROJECT': 'PROJECT: Hunters',
      'GAMEMODEX': 'Nexus Blitz',
      'ODYSSEY': 'Odyssey: Extraction',
      'NEXUSBLITZ': 'Nexus Blitz',
      'ULTBOOK': 'Ultimate Spellbook',
      'CHERRY': 'Arena',
      'URF': 'URF',
      'ARURF': 'ARURF'
    };

    return gameModes[gameMode] || gameMode || 'Personalizada';
  }

  getTodayWins(): number {
    if (!this.recentMatches) return 0;

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const todayTimestamp = today.getTime();

    return this.recentMatches.filter(match =>
      match.timestamp && match.timestamp >= todayTimestamp && match.isVictory
    ).length;
  }

  loadLeaderboardPosition(): void {
    if (!this.player) return;

    const leaderboardSub = this.apiService.getLeaderboard(50).subscribe({
      next: (response) => {
        if (response.success) {
          const rank = response.data.findIndex((p: any) => this.getPlayerIdentifier(p) === this.getPlayerIdentifier(this.player!)) + 1;
          this.leaderboardPosition = rank > 0 ? rank : 0;
          if (rank === 0) {
            this.searchFullLeaderboard();
          }
        }
        this.cdr.markForCheck();
      },
      error: (error) => console.error('Error loading leaderboard', error)
    });
    this.subscriptions.push(leaderboardSub);
  }

  private searchFullLeaderboard(): void {
    if (!this.player) return;
    const fullLeaderboardSub = this.apiService.getLeaderboard(200).subscribe({
      next: (response) => {
        if (response.success) {
          const rank = response.data.findIndex((p: any) => this.getPlayerIdentifier(p) === this.getPlayerIdentifier(this.player!)) + 1;
          this.leaderboardPosition = rank > 0 ? rank : 0;
        }
        this.cdr.markForCheck();
      },
      error: (error) => console.error('Error loading full leaderboard', error)
    });
    this.subscriptions.push(fullLeaderboardSub);
  }

  getCurrentTip() {
    const index = new Date().getDate() % this.tips.length;
    return this.tips[index];
  }

  getRankColor(): string {
    if (!this.player?.rank) return '#8a8a8a';

    const tier = this.player.rank.tier.toLowerCase();
    const colors: { [key: string]: string } = {
      'iron': '#4a4a4a',
      'bronze': '#cd7f32',
      'silver': '#c0c0c0',
      'gold': '#ffd700',
      'platinum': '#00ff9f',
      'diamond': '#b9f2ff',
      'master': '#9933ff',
      'grandmaster': '#ff3333',
      'challenger': '#f0e68c'
    };

    return colors[tier] || '#8a8a8a';
  }

  getWaitTimeText(): string {
    if (!this.queueStatus.averageWaitTime || this.queueStatus.averageWaitTime === 0) {
      return 'Calculando...';
    }
    return this.formatWaitTime(this.queueStatus.averageWaitTime);
  }

  // ========== MÉTODOS DE RANK MELHORADOS ==========

  hasSoloQueueRank(): boolean {
    return !!(this.player?.rankedData?.soloQueue?.tier || this.player?.rank?.tier);
  }

  hasFlexRank(): boolean {
    return !!(this.player?.rankedData?.flexQueue?.tier);
  }

  getSoloQueueRank(): string {
    if (this.player?.rankedData?.soloQueue) {
      const soloQueue = this.player.rankedData.soloQueue;
      return `${soloQueue.tier} ${soloQueue.rank || soloQueue.division || 'IV'}`;
    }

    if (this.player?.rank) {
      return this.player.rank.display || `${this.player.rank.tier} ${this.player.rank.rank}`;
    }

    return 'Unranked';
  }

  getSoloQueueLP(): number {
    if (this.player?.rankedData?.soloQueue) {
      return this.player.rankedData.soloQueue.leaguePoints || 0;
    }

    if (this.player?.rank?.lp) {
      return this.player.rank.lp;
    }

    return 0;
  }

  getFlexRank(): string {
    if (this.player?.rankedData?.flexQueue) {
      const flexQueue = this.player.rankedData.flexQueue;
      return `${flexQueue.tier} ${flexQueue.rank || flexQueue.division || 'IV'}`;
    }

    return 'Unranked';
  }

  getFlexLP(): number {
    if (this.player?.rankedData?.flexQueue) {
      return this.player.rankedData.flexQueue.leaguePoints || 0;
    }

    return 0;
  }

  getRankStatus(): string {
    // Check if we have partial data (LCU only)
    const isPartialData = (this.player as any)?._isPartialData;
    const dataSource = (this.player as any)?._dataSource;

    if (isPartialData) {
      return 'Dados incompletos';
    }

    if (!this.hasSoloQueueRank() && !this.hasFlexRank()) {
      return 'Unranked';
    }

    return 'Sem dados';
  }

  // ========== FIM DOS MÉTODOS DE RANK MELHORADOS ==========

  // Método para gerar dados de exemplo quando não há dados reais
  generateMockData(): void {
    this.recentMatches = [
      {
        id: 1,
        timestamp: Date.now() - 1000 * 60 * 30, // 30 minutes ago
        isVictory: true,
        duration: 1800, // 30 minutes
        mmrChange: 15,
        gameMode: 'Personalizada',
        champion: 'Jinx',
        kda: '12/3/8'
      },
      {
        id: 2,
        timestamp: Date.now() - 1000 * 60 * 60 * 2, // 2 hours ago
        isVictory: false,
        duration: 1500, // 25 minutes
        mmrChange: -12,
        gameMode: 'ARAM',
        champion: 'Yasuo',
        kda: '8/7/5'
      },
      {
        id: 3,
        timestamp: Date.now() - 1000 * 60 * 60 * 5, // 5 hours ago
        isVictory: true,
        duration: 2100, // 35 minutes
        mmrChange: 18,
        gameMode: 'Personalizada',
        champion: 'Thresh',
        kda: '2/2/18'
      }
    ];

    // Remover o erro se houver dados mockados
    this.matchHistoryError = null;
  }
  ngOnInit(): void {
    // Se já temos dados do player, carregar imediatamente após champions
    if (this.player && !this.dataLoaded) {
      this.ensureChampionsLoaded(() => {
        this.loadAllData();
        this.dataLoaded = true;
      });
    }
  }

  ngOnDestroy(): void {
    // Limpar todas as subscriptions
    this.subscriptions.forEach(sub => sub.unsubscribe());

    // ✅ CORREÇÃO: Reset completo no destroy
    this.processingPlayer = false;
    this.isLoadingMatches = false;
    this.customMatchesAttempted = false;
    this.lcuFallbackAttempted = false;
    this.fallbackCompleted = false;
    this.dataLoaded = false;

    // ✅ NOVO: Manter cache do LCU mesmo após destroy do componente
    // O cache só será limpo quando o app for recarregado ou manualmente

    console.log('🧹 [DASHBOARD] Component destroyed and cleaned up (cache preserved)');
  }

  // Método centralizado para carregar todos os dados
  private loadAllData(): void {
    // ✅ CORREÇÃO: Verificar se já carregou dados para evitar loops
    if (!this.player) {
      console.log('❌ [DASHBOARD] No player available for loadAllData');
      return;
    }

    console.log('📊 [DASHBOARD] Loading all data for player:', this.getPlayerIdentifier(this.player));

    // Carregar apenas se necessário e não duplicar carregamentos
    this.loadRecentMatches();
    this.loadCustomMatchesCount();
    this.loadLeaderboardPosition();
  }

  // Public method to refresh all dashboard data (para uso manual)
  public refreshAllData(): void {
    console.log('🔄 Atualizando todos os dados do dashboard');

    // ✅ NOVO: Limpar cache do LCU para forçar nova busca
    this.clearLCUCache();

    this.dataLoaded = false; // Reset flag para permitir recarregamento
    this.lcuFallbackAttempted = false; // ✅ CORREÇÃO: Reset flag LCU para permitir nova tentativa
    this.customMatchesAttempted = false; // ✅ NOVO: Reset flag custom matches
    this.fallbackCompleted = false; // ✅ NOVO: Reset flag fallback

    this.loadAllData();
    this.dataLoaded = true;
  }

  // ✅ NOVO: Método público para limpar cache do LCU
  public clearLCUCacheManually(): void {
    console.log('🗑️ [DASHBOARD] Manually clearing LCU cache...');
    this.clearLCUCache();
  }

  // Método para carregar contagem de partidas customizadas
  public loadCustomMatchesCount(): void {
    if (!this.player) return;

    // Usar o formato correto do Riot ID (gameName#tagLine) se disponível
    let playerIdentifier = this.player.summonerName || this.player.id.toString();

    // Se temos gameName e tagLine, usar o formato Riot ID
    if (this.player.gameName && this.player.tagLine) {
      playerIdentifier = `${this.player.gameName}#${this.player.tagLine}`;
    } else if (this.player.summonerName && this.player.tagLine) {
      playerIdentifier = `${this.player.summonerName}#${this.player.tagLine}`;
    }

    console.log('🔢 Carregando contagem de partidas customizadas para:', playerIdentifier);

    const countSub = this.apiService.getCustomMatchesCount(playerIdentifier)
      .subscribe({
        next: (response) => {
          if (response && response.success) {
            this.customMatchesCount = response.count || 0;
            console.log('✅ Contagem de partidas customizadas carregada:', this.customMatchesCount);
          } else {
            console.warn('⚠️ Resposta inválida ao carregar contagem:', response);
            this.customMatchesCount = 0;
          }
          this.isLoadingCustomCount = false;
        },
        error: (error) => {
          console.warn('⚠️ Erro ao carregar contagem de partidas customizadas:', error);
          this.customMatchesCount = 0;
          this.isLoadingCustomCount = false;
        }
      });

    this.subscriptions.push(countSub);
  }

  // Event handlers
  onJoinQueue(): void {
    this.joinQueue.emit();
  }

  onViewHistory(): void {
    this.viewHistory.emit();
  }

  onOpenSettings(): void {
    this.openSettings.emit();
  }

  onProfileIconError(event: Event): void {
    // Fallback para ícone padrão se falhar o carregamento
    const iconId = this.player?.profileIconId || 29;

    const fallbackUrls = [
      `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/profileicon/${iconId}.png`,
      'https://ddragon.leagueoflegends.com/cdn/15.19.1/img/profileicon/29.png', // Default icon
      'https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/profile-icons/29.jpg'
    ];

    // Se já tentou todos os fallbacks, usar um ícone SVG genérico
    const target = event.target as HTMLImageElement;
    if (!target) return;

    const currentAttempt = parseInt(target.dataset['fallbackAttempt'] || '0');
    if (currentAttempt >= fallbackUrls.length - 1) {
      return;
    }

    const attemptIndex = currentAttempt + 1;
    target.dataset['fallbackAttempt'] = attemptIndex.toString();
    target.src = fallbackUrls[attemptIndex];
  }

  getPlayerTag(): string {
    if (this.player?.tagLine) {
      return ` #${this.player.tagLine}`;
    }
    return '';
  }

  getProfileIconUrl(): string {
    const iconId = this.player?.profileIconId || 29;
    // Usar Community Dragon como primeira opção (mais confiável)
    return `https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/profile-icons/${iconId}.jpg`;
  }

  // Método para mostrar feedback visual quando botões são clicados
  showButtonFeedback(event: Event): void {
    const button = event.currentTarget as HTMLElement;
    button.style.transform = 'scale(0.95)';
    setTimeout(() => {
      button.style.transform = '';
    }, 150);
  }
  public loadCustomMatchesFromDatabase(): void {
    if (!this.player) return;

    // ✅ CORREÇÃO CRÍTICA: Múltiplas verificações para evitar execução
    if (this.customMatchesAttempted) {
      console.log('⏭️ [DASHBOARD] Custom matches already attempted for this player, BLOCKING...');
      return;
    }

    if (this.isLoadingMatches) {
      console.log('⏳ [DASHBOARD] Already loading matches, BLOCKING custom matches...');
      return;
    }

    if (this.fallbackCompleted) {
      console.log('✅ [DASHBOARD] Fallback already completed, BLOCKING custom matches...');
      return;
    }

    // ✅ VERIFICAÇÃO EXTRA: Throttle por player
    const now = Date.now();
    if (this.lastLoadTime && (now - this.lastLoadTime) < 3000) { // 3 segundos mínimo
      console.log('⏱️ [DASHBOARD] Too soon since last attempt, BLOCKING...');
      return;
    }

    console.log('💾 [DASHBOARD] Attempting to load custom matches from database...');

    // ✅ MARCAR IMEDIATAMENTE para evitar chamadas simultâneas
    this.customMatchesAttempted = true;
    this.isLoadingMatches = true;
    this.lastLoadTime = now;

    const playerIdentifier = this.getPlayerIdentifier(this.player);
    console.log('🎯 [DASHBOARD] Using player identifier for search:', playerIdentifier);

    const customMatchesSub = this.apiService.getCustomMatches(playerIdentifier, 0, 3)
      .subscribe({
        next: (response) => {
          if (response && response.success && response.matches && response.matches.length > 0) {
            console.log('✅ [DASHBOARD] Custom matches loaded from database:', response.matches.length);

            // Convert custom matches to dashboard format
            const customMatchesForDashboard = this.convertCustomMatchesToDashboard(response.matches);
            // Marcar matches customizadas para exibir MMR/LP
            this.recentMatches = customMatchesForDashboard.slice(0, 3).map(m => ({ ...m, isCustomMatch: true }));

            console.log('🎯 [DASHBOARD] Recent matches from database:', this.recentMatches.length);
            this.isLoadingMatches = false;
            this.matchHistoryError = null;
            this.fallbackCompleted = true; // ✅ MARCAR COMO CONCLUÍDO
            this.cdr.detectChanges(); // Forçar detecção de mudanças
          } else {
            console.log('📝 [DASHBOARD] No custom matches found in database');
            this.handleNoCustomMatches();
          }
        },
        error: (error: any) => {
          console.warn('⚠️ [DASHBOARD] Error loading custom matches from database:', error);
          this.handleNoCustomMatches();
        }
      });

    this.subscriptions.push(customMatchesSub);
  }

  // ✅ NOVO: Método para lidar com ausência de partidas customizadas
  private handleNoCustomMatches(): void {
    const playerIdentifier = this.getPlayerIdentifier(this.player!);

    // ✅ NOVO: Verificar cache do LCU primeiro
    const cachedData = this.getCachedLCUData(playerIdentifier);
    if (cachedData) {
      console.log('🎯 [DASHBOARD] Using cached LCU data instead of new request');
      this.processLCUMatches(cachedData);
      this.isLoadingMatches = false;
      this.fallbackCompleted = true;
      this.cdr.detectChanges();
      return;
    }

    // ✅ CORREÇÃO CRÍTICA: Fazer fallback para LCU apenas UMA VEZ
    if (!this.lcuFallbackAttempted && !this.fallbackCompleted) {
      console.log('🔄 [DASHBOARD] No custom matches found, attempting LCU fallback...');
      this.lcuFallbackAttempted = true;
      this.loadFromLCUSafe();
    } else {
      console.log('📝 [DASHBOARD] Using mock data (fallback already attempted)');
      this.generateMockData();
      this.isLoadingMatches = false;
      this.matchHistoryError = 'Nenhuma partida customizada encontrada. Jogue partidas customizadas para ver o histórico.';
      this.fallbackCompleted = true; // ✅ MARCAR COMO CONCLUÍDO
      this.cdr.detectChanges(); // Forçar detecção de mudanças
    }
  }

  // ✅ NOVO: Método seguro para carregar do LCU
  private loadFromLCUSafe(): void {
    console.log('🎮 [DASHBOARD] Attempting safe LCU load (one-time fallback)...');

    const playerIdentifier = this.getPlayerIdentifier(this.player!);
    const lcuSub = this.apiService.getLCUMatchHistoryAll(3) // Buscar últimas 3 partidas
      .subscribe({
        next: (response) => {
          console.log('✅ [DASHBOARD] LCU match history loaded:', response);

          // ✅ NOVO: Salvar no cache antes de processar
          this.setCachedLCUData(playerIdentifier, response);

          this.processLCUMatches(response);
          this.isLoadingMatches = false;
          this.fallbackCompleted = true; // ✅ MARCAR COMO CONCLUÍDO
          this.cdr.detectChanges(); // Forçar detecção de mudanças
        },
        error: (error) => {
          console.warn('⚠️ [DASHBOARD] LCU fallback failed:', error);
          this.generateMockData();
          this.isLoadingMatches = false;
          this.matchHistoryError = 'Histórico indisponível - usando dados de exemplo';
          this.fallbackCompleted = true; // ✅ MARCAR COMO CONCLUÍDO
          this.cdr.detectChanges(); // Forçar detecção de mudanças
        }
      });

    this.subscriptions.push(lcuSub);
  }

  private convertCustomMatchesToDashboard(customMatches: any[]): any[] {
    console.log('🔄 [Dashboard] convertCustomMatchesToDashboard called with', customMatches.length, 'matches');
    return customMatches.map((match: any, index: number) => {
      console.log(`🔍 [Dashboard] Processing match ${index + 1}:`, {
        id: match.id,
        rawMatchData: match, // ✅ LOG COMPLETO DA PARTIDA
        hasParticipantsData: !!match.participants_data,
        participantsDataType: typeof match.participants_data,
        participantsDataLength: Array.isArray(match.participants_data) ? match.participants_data.length : 'not array'
      });

      // O backend já processa os dados com DataDragonService, então podemos usar diretamente
      let playerChampion = 'Unknown';
      let playerKDA = '0/0/0';
      let playerStats = null;

      // Identificadores do jogador atual
      const currentPlayerName = this.player?.summonerName?.toLowerCase() || '';
      const currentGameName = this.player?.gameName?.toLowerCase() || '';
      const currentDisplayName = this.player?.displayName?.toLowerCase() || '';

      console.log(`🎯 [Dashboard] Looking for player:`, {
        summonerName: currentPlayerName,
        gameName: currentGameName,
        displayName: currentDisplayName
      });

      // Usar os dados já processados do backend
      if (match.participants_data && Array.isArray(match.participants_data)) {
        console.log(`📊 [Dashboard] Participants data found:`, match.participants_data.length, 'participants');

        // Log dos primeiros participantes para debug
        match.participants_data.slice(0, 2).forEach((p: any, i: number) => {
          console.log(`👤 [Dashboard] Participant ${i + 1}:`, {
            riotIdGameName: p.riotIdGameName,
            gameName: p.gameName,
            summonerName: p.summonerName,
            championName: p.championName,
            championId: p.championId,
            kills: p.kills,
            deaths: p.deaths,
            assists: p.assists
          });
        });

        // Buscar o participante atual nos dados já processados
        playerStats = match.participants_data.find((participant: any) => {
          const participantRiotId = (participant.riotIdGameName || '').toLowerCase();
          const participantGameName = (participant.gameName || '').toLowerCase();
          const participantSummonerName = (participant.summonerName || '').toLowerCase();

          const isMatch = participantRiotId === currentGameName ||
            participantGameName === currentGameName ||
            participantSummonerName === currentPlayerName ||
            participantRiotId === currentDisplayName ||
            participantGameName === currentDisplayName;

          if (isMatch) {
            console.log(`✅ [Dashboard] Found player match:`, {
              participant: {
                riotIdGameName: participantRiotId,
                gameName: participantGameName,
                summonerName: participantSummonerName
              },
              current: {
                summonerName: currentPlayerName,
                gameName: currentGameName,
                displayName: currentDisplayName
              }
            });
          }

          return isMatch;
        });

        if (playerStats) {
          // Usar o championName já processado pelo backend
          playerChampion = playerStats.championName || 'Unknown';
          playerKDA = `${playerStats.kills || 0}/${playerStats.deaths || 0}/${playerStats.assists || 0}`;

          console.log(`🏆 [Dashboard] Player stats found:`, {
            championName: playerChampion,
            kda: playerKDA,
            rawStats: {
              kills: playerStats.kills,
              deaths: playerStats.deaths,
              assists: playerStats.assists
            }
          });
        } else {
          console.warn(`⚠️ [Dashboard] Player not found in participants data for match ${match.id}`);
        }
      } else {
        console.warn(`⚠️ [Dashboard] No participants_data found for match ${match.id}`);
      }

      // Fallback: Se não encontramos nos participants_data, tentar pick_ban_data
      if (playerChampion === 'Unknown' && match.pick_ban_data) {
        try {
          const pickBanData = typeof match.pick_ban_data === 'string' ?
            JSON.parse(match.pick_ban_data) : match.pick_ban_data;

          if (pickBanData && pickBanData.team1Picks) {
            const playerTeam = match.player_team || (match.player_won && match.winner_team === 1 ? 1 : 2);
            const picks = playerTeam === 1 ? pickBanData.team1Picks : pickBanData.team2Picks;

            if (picks && picks.length > 0) {
              const currentPlayerName = this.player?.summonerName?.toLowerCase() || '';
              let playerPick = picks.find((pick: any) =>
                pick.player && pick.player.toString().toLowerCase().includes(currentPlayerName)
              );

              if (!playerPick && picks.length > 0) {
                playerPick = picks[0]; // Fallback to first pick
              }

              if (playerPick && playerPick.champion) {
                playerChampion = playerPick.champion;
              }
            }
          }
        } catch (e) {
          console.warn('⚠️ Error extracting player champion from pick_ban_data:', e);
        }
      }

      // Usar o MMR change já calculado pelo backend
      const mmrChange = match.player_mmr_change || match.player_lp_change || (match.player_won ? 15 : -10);

      return {
        id: match.id || match.match_id,
        timestamp: new Date(match.created_at).getTime(),
        isVictory: match.player_won || false,
        duration: (match.duration || 25) * 60, // Convert minutes to seconds
        mmrChange: mmrChange,
        gameMode: 'Personalizada',
        champion: playerChampion,
        kda: playerKDA,
        isCustomMatch: true // Flag to identificar custom matches
      };
    });
  }

  // ✅ NOVO: Métodos para gerenciar cache do LCU
  private getCachedLCUData(playerIdentifier: string): any | null {
    try {
      const cacheData = sessionStorage.getItem(this.lcuCacheKey);
      if (!cacheData) return null;

      const parsed = JSON.parse(cacheData);

      // Verificar se o cache é para o mesmo player
      if (parsed.playerIdentifier !== playerIdentifier) {
        console.log('🗑️ [DASHBOARD] Cache is for different player, clearing...');
        this.clearLCUCache();
        return null;
      }

      // Verificar se o cache não expirou
      const now = Date.now();
      if (now - parsed.timestamp > this.cacheExpireMs) {
        console.log('⏰ [DASHBOARD] Cache expired, clearing...');
        this.clearLCUCache();
        return null;
      }

      console.log('✅ [DASHBOARD] Found valid LCU cache for player:', playerIdentifier);
      return parsed.data;
    } catch (error) {
      console.warn('⚠️ [DASHBOARD] Error reading LCU cache:', error);
      this.clearLCUCache();
      return null;
    }
  }

  private setCachedLCUData(playerIdentifier: string, data: any): void {
    try {
      const cacheData = {
        playerIdentifier: playerIdentifier,
        timestamp: Date.now(),
        data: data
      };

      sessionStorage.setItem(this.lcuCacheKey, JSON.stringify(cacheData));
      console.log('💾 [DASHBOARD] LCU data cached for player:', playerIdentifier);
    } catch (error) {
      console.warn('⚠️ [DASHBOARD] Error saving to LCU cache:', error);
    }
  }

  private clearLCUCache(): void {
    try {
      sessionStorage.removeItem(this.lcuCacheKey);
      console.log('🗑️ [DASHBOARD] LCU cache cleared');
    } catch (error) {
      console.warn('⚠️ [DASHBOARD] Error clearing LCU cache:', error);
    }
  }
}
