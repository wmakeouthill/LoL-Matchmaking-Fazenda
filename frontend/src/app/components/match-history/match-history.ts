import { Component, Input, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService } from '../../services/api';
import { Player, Match } from '../../interfaces';
import { ChampionService } from '../../services/champion.service';
import { interval, Subject, Subscription, takeUntil } from 'rxjs';

@Component({
  selector: 'app-match-history',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './match-history.html',
  styleUrl: './match-history.scss'
})
export class MatchHistoryComponent implements OnInit, OnDestroy {
  @Input() player: Player | null = null;

  // Tab system
  activeTab: string = 'riot'; // Aceita apenas 'riot' ou 'custom' para evitar erro de lint

  // Match arrays
  matches: Match[] = []; // Legacy - será mantido para compatibilidade
  riotMatches: Match[] = []; // Partidas da Riot API
  customMatches: Match[] = []; // Partidas customizadas

  // Expanded matches tracking
  expandedMatches: Set<string> = new Set();

  loading = false;
  error: string | null = null;
  currentPage = 0;
  totalMatches = 0;
  matchesPerPage = 10;

  currentGame: any = null;
  isInGame = false;
  gamePhase = '';

  private refreshInterval: Subscription | null = null;
  private destroy$ = new Subject<void>();

  // Strategy objects for different data sources
  private dataStrategies = {
    riot: {
      loadMethod: () => this.loadRiotMatches(),
      getMatches: () => this.riotMatches,
      getStats: () => this.getRiotStats(),
      getWinStreak: () => this.getRiotWinStreakInfo(),
      getAverageGain: () => this.getAverageGain(),
      getMostPlayedChampion: () => this.getMostPlayedChampion(),
      getAverageKDA: () => this.getAverageKDA(),
      emptyMessage: 'Nenhuma partida ranqueada encontrada',
      emptyDescription: 'Você ainda não jogou nenhuma partida ranqueada.'
    },
    custom: {
      loadMethod: () => this.loadCustomMatches(),
      getMatches: () => this.customMatches,
      getStats: () => this.getCustomStats(),
      getWinStreak: () => this.getCustomWinStreakInfo(),
      getAverageGain: () => this.getAverageGain(),
      getMostPlayedChampion: () => this.getMostPlayedChampion(),
      getAverageKDA: () => this.getAverageKDA(),
      emptyMessage: 'Nenhuma partida customizada encontrada',
      emptyDescription: 'Ainda não há partidas customizadas registradas.'
    }
  };

  constructor(private apiService: ApiService, private cdr: ChangeDetectorRef) { }  // ========== UNIFIED LOADING METHODS ==========

  /**
   * Loads matches for the current active tab using Strategy Pattern
   */  loadCurrentTabMatches(): void {
    const strategy = this.getCurrentStrategy();
    if (strategy) {
      strategy.loadMethod();
    } else {
      console.error('❌ Strategy não encontrada para tab:', this.activeTab);
    }
  }

  /**
   * Gets the current data strategy based on active tab
   */
  private getCurrentStrategy() {
    return this.dataStrategies[this.activeTab as keyof typeof this.dataStrategies];
  }

  /**
   * Gets current matches using strategy pattern
   */
  getCurrentMatches(): Match[] {
    const strategy = this.getCurrentStrategy();
    const result = strategy ? strategy.getMatches() : [];

    return result;
  }

  /**
   * Gets win rate for current tab
   */
  getWinRate(): string {
    const matches = this.getCurrentMatches();
    if (matches.length === 0) return '0.0';
    const wins = matches.filter(m => m.playerStats?.isWin).length;
    return ((wins / matches.length) * 100).toFixed(1);
  }

  loadRiotMatches(): void {
    console.log('🔍 [MATCH-HISTORY] loadRiotMatches called');
    if (!this.player) {
      console.log('🔍 [MATCH-HISTORY] No player available');
      // console.warn('⚠️ Nenhum player disponível para carregar partidas');
      return;
    }
    console.log('🔍 [MATCH-HISTORY] Player available:', this.player.summonerName);
    this.loading = true;
    this.error = null;
    console.log('🔍 [MATCH-HISTORY] Calling getLCUMatchHistoryAll');
    this.apiService.getLCUMatchHistoryAll(0, 20, false).subscribe({
      next: (lcuResponse: any) => {
        if (lcuResponse && lcuResponse.success && lcuResponse.matches && lcuResponse.matches.length > 0) {
          this.processLCUMatches(lcuResponse);
        } else {
          this.riotMatches = [];
          this.error = 'Nenhuma partida encontrada no histórico do League of Legends. Certifique-se de que o LoL está aberto e você jogou partidas recentemente.';
        }
        this.loading = false;
      }, error: (error: any) => {
        this.riotMatches = [];

        // Mensagem mais específica baseada no tipo de erro
        if (error.message && error.message.includes('404')) {
          this.error = 'League of Legends não está aberto ou não há partidas ativas. Abra o cliente do LoL para ver o histórico.';
        } else if (error.message && error.message.includes('current-match-details')) {
          this.error = 'Nenhuma partida ativa encontrada. Para ver o histórico, certifique-se de que o LoL está aberto.';
        } else {
          this.error = 'Não foi possível conectar ao League of Legends. Verifique se o cliente está aberto e funcionando.';
        }

        this.loading = false;
      }
    });
  }

  loadCustomMatches(): void {
    if (!this.player) {
      return;
    }

    this.loading = true;
    this.error = null;
    
    try {
      // Usar gameName#tagLine como identificador principal, com fallbacks
      const playerIdentifier = this.player.gameName && this.player.tagLine
        ? `${this.player.gameName}#${this.player.tagLine}`
        : this.player.summonerName || this.player.id.toString();

      this.apiService.getCustomMatches(playerIdentifier, this.currentPage * this.matchesPerPage, this.matchesPerPage).subscribe({
        next: (response) => {
          if (response && response.success && response.matches && response.matches.length > 0) {

            this.customMatches = this.mapApiMatchesToModel(response.matches);
            this.totalMatches = response.pagination.total;
            // Forçar detecção de mudanças
            this.cdr.detectChanges();
          } else {
            this.customMatches = [];
            this.totalMatches = 0;
            this.error = 'Você ainda não jogou nenhuma partida customizada. Complete uma partida personalizada para vê-la aparecer aqui.';
          }
          this.loading = false;
        },
        error: (error) => {
          console.error('❌ Erro na requisição:', error);

          // FALLBACK TEMPORÁRIO: Usar dados mock para testar o frontend
          this.customMatches = this.generateMockCustomMatches();
          this.totalMatches = this.customMatches.length;
          this.loading = false;
          this.error = null; // Remover erro para mostrar os dados mock

          // Forçar detecção de mudanças para mock data também
          this.cdr.detectChanges();
        }
      });
    } catch (error: any) {
      console.error('❌ Erro no try/catch:', error);

      // FALLBACK TEMPORÁRIO: Usar dados mock para testar o frontend
      this.customMatches = this.generateMockCustomMatches();
      this.totalMatches = this.customMatches.length;
      this.loading = false;
      this.error = null; // Remover erro para mostrar os dados mock

      // Forçar detecção de mudanças para mock data também
      this.cdr.detectChanges();
    }
  }  // ========== TAB SYSTEM ==========
  setActiveTab(tab: string): void {
    this.activeTab = tab;
    this.currentPage = 0;

    this.loadCurrentTabMatches();
  }

  // Helper function para evitar erro de lint no template
  isCustomTab(): boolean {
    const result = this.activeTab === 'custom';
    return result;
  }
  isRiotTab(): boolean {
    const result = this.activeTab === 'riot';
    return result;
  }

  // Método para identificar se um participante é o jogador atual (para partidas customizadas)
  private isCurrentPlayer(participantName: string): boolean {
    if (!this.player) return false;

    const currentPlayerName = this.player.summonerName?.toLowerCase().trim() || '';
    const currentPlayerTagLine = this.player.tagLine || '';
    const fullPlayerName = currentPlayerTagLine ? `${currentPlayerName}#${currentPlayerTagLine}` : currentPlayerName;

    const participantNameLower = participantName?.toLowerCase().trim() || '';

    // Comparar por nome completo (gameName#tagLine) ou apenas gameName
    return participantNameLower === fullPlayerName.toLowerCase() ||
      participantNameLower === currentPlayerName;
  }

  ngOnInit() {
    // NO MORE MOCK DATA BY DEFAULT - only load real data
    // Only show mock data if explicitly needed for demo purposes

    // If there's a player, load real data
    if (this.player) {
      this.loadCurrentTabMatches();
    } else {
      // No player, clear arrays
      this.riotMatches = [];
      this.customMatches = [];
    }

    this.startCurrentGameMonitoring();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (this.refreshInterval) {
      this.refreshInterval.unsubscribe();
    }
  }

  private startCurrentGameMonitoring(): void {
    // Check current game every 30 seconds
    this.refreshInterval = interval(30000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        this.checkCurrentGame();
      });

    // Initial check
    this.checkCurrentGame();
  } private checkCurrentGame(): void {
    if (!this.player) return;

    // Use LCU status check instead of current-match-details to avoid 404s
    this.apiService.getLCUStatus().subscribe({
      next: (response: any) => {
        if (response && response.isConnected) {
          // LCU is connected, but we don't need to check for active games here
          // This was causing the 404 errors. We'll just update the connection status.
          this.isInGame = false;
          this.currentGame = null;
          this.gamePhase = '';
        } else {
          this.isInGame = false;
          this.currentGame = null;
          this.gamePhase = '';
        }
      }, error: (err: any) => {
        // console.error('Failed to get LCU status:', err);
        this.isInGame = false;
        this.currentGame = null;
        this.gamePhase = '';
      }
    });
  }// Legacy method for compatibility
  async loadMatches() {
    return this.loadCurrentTabMatches();
  }

  private fallbackToLocalMatches(): void {
    // Try local database match history
    this.apiService.getMatchHistory(this.player!.id.toString(), this.currentPage * this.matchesPerPage, this.matchesPerPage).subscribe({
      next: (response) => {
        if (response && response.success && response.matches) {
          // Map the API response to our Match interface
          this.matches = this.mapApiMatchesToModel(response.matches);
          this.totalMatches = response.pagination.total;
        } else {
          // Final fallback to mock data if no real data available
          this.matches = this.generateMockMatches();
          this.totalMatches = this.matches.length;
        }
        this.loading = false;
      }, error: (error) => {
        this.error = error.message || 'Erro ao carregar histórico de partidas';
        this.loading = false;
        // console.error('Error loading match history:', error);

        // Fallback to mock data in case of error
        setTimeout(() => {
          this.error = null;
          this.matches = this.generateMockMatches();
          this.totalMatches = this.matches.length;
          this.loading = false;
        }, 1000);
      }
    });
  }

  // MÉTODO REMOVIDO: processRiotMatches
  // Agora usamos apenas LCU via processLCUMatches
  // A aba "Riot API" usa exclusivamente dados do League of Legends Client (LCU)

  private mapRiotMatchesToModel(riotMatches: any[]): Match[] {
    return riotMatches.map(matchData => {
      // Validar estrutura dos dados
      if (!matchData?.info?.participants || !Array.isArray(matchData.info.participants)) {
        // console.warn('⚠️ Dados de partida inválidos:', matchData);
        return this.createDefaultMatch();
      }

      const playerData = matchData.info.participants.find(
        (p: any) => p.puuid === this.player?.puuid
      ); if (!playerData) {
        // console.warn('⚠️ Jogador não encontrado na partida:', matchData.metadata?.matchId);
        return this.createDefaultMatch();
      }      // Mapear participantes com dados completos para exibição
      const enhancedParticipants = matchData.info.participants.map((p: any) => ({
        ...p,
        // Adicionar summonerName se não existir - usar riotIdGameName + riotIdTagline
        summonerName: p.summonerName || (p.riotIdGameName ? `${p.riotIdGameName}#${p.riotIdTagline}` : 'Unknown'),
        // Adicionar firstBloodKill
        firstBloodKill: p.firstBloodKill || false
      }));// Separar participantes em times com dados completos
      const team1 = enhancedParticipants.filter((p: any) => p.teamId === 100);
      const team2 = enhancedParticipants.filter((p: any) => p.teamId === 200);

      // Determinar vencedor baseado nos dados dos times
      const team1Won = matchData.info.teams?.find((t: any) => t.teamId === 100)?.win || false;
      const team2Won = matchData.info.teams?.find((t: any) => t.teamId === 200)?.win || false;
      const matchWinner = team1Won ? 1 : (team2Won ? 2 : 0);

      return {
        id: matchData.metadata.matchId,
        createdAt: new Date(matchData.info.gameCreation),
        duration: matchData.info.gameDuration,
        gameMode: matchData.info.gameMode,
        gameVersion: matchData.info.gameVersion,
        mapId: matchData.info.mapId,
        participants: enhancedParticipants,
        teams: matchData.info.teams,
        team1: team1,
        team2: team2,
        winner: matchWinner, playerStats: {
          champion: playerData.championName,
          kills: playerData.kills,
          deaths: playerData.deaths,
          assists: playerData.assists,
          mmrChange: playerData.win ? 15 : -12, // Mock MMR - API não fornece
          isWin: playerData.win,
          championLevel: playerData.champLevel,
          firstBloodKill: playerData.firstBloodKill || false,
          doubleKills: playerData.doubleKills || 0,
          tripleKills: playerData.tripleKills || 0,
          quadraKills: playerData.quadraKills || 0,
          pentaKills: playerData.pentaKills || 0,
          items: [
            playerData.item0 || 0, playerData.item1 || 0, playerData.item2 || 0,
            playerData.item3 || 0, playerData.item4 || 0, playerData.item5 || 0
          ],
          lpChange: playerData.win ? Math.floor(Math.random() * 25) + 10 : -(Math.floor(Math.random() * 20) + 10), // Mock LP
          // Dados expandidos
          goldEarned: playerData.goldEarned || 0,
          totalDamageDealt: playerData.totalDamageDealt || 0,
          totalDamageDealtToChampions: playerData.totalDamageDealtToChampions || 0,
          totalDamageTaken: playerData.totalDamageTaken || 0,
          totalMinionsKilled: playerData.totalMinionsKilled || 0,
          neutralMinionsKilled: playerData.neutralMinionsKilled || 0,
          wardsPlaced: playerData.wardsPlaced || 0,
          wardsKilled: playerData.wardsKilled || 0,
          visionScore: playerData.visionScore || 0,
          summoner1Id: playerData.summoner1Id || 0,
          summoner2Id: playerData.summoner2Id || 0,
          perks: playerData.perks
        }
      };
    });
  }

  private createDefaultMatch(): Match {
    return {
      id: Date.now(),
      createdAt: new Date(),
      duration: 1200,
      team1: [],
      team2: [],
      winner: 1,
      averageMMR1: 1200,
      averageMMR2: 1200,
      playerStats: {
        champion: 'Unknown',
        kills: 0,
        deaths: 0,
        assists: 0,
        mmrChange: 0,
        isWin: false
      }
    };
  }

  private mapApiMatchesToModel(apiMatches: any[]): Match[] {
    return apiMatches.map((match, index) => {
      // Parse JSON fields safely
      let team1Players: any[] = [];
      let team2Players: any[] = [];
      let pickBanData: any = null;

      try {
        team1Players = typeof match.team1_players === 'string' ? JSON.parse(match.team1_players) : (match.team1_players || []);
        team2Players = typeof match.team2_players === 'string' ? JSON.parse(match.team2_players) : (match.team2_players || []);

        if (match.pick_ban_data) {
          pickBanData = typeof match.pick_ban_data === 'string' ? JSON.parse(match.pick_ban_data) : match.pick_ban_data;
        }

        // Parse participants_data se existir
        if (match.participants_data) {
          match.participants_data = typeof match.participants_data === 'string' ?
            JSON.parse(match.participants_data) : match.participants_data;
        }
      } catch (e) {
        team1Players = [];
        team2Players = [];
        pickBanData = null;
      }
      
      // Verificar se temos dados reais dos participantes
      const hasRealData = match.participants_data && Array.isArray(match.participants_data) && match.participants_data.length > 0;

      // Debug: Log dos dados para verificar se estão chegando corretamente
      console.log('🔍 [mapApiMatchesToModel] Debug dados da partida:', {
        matchId: match.id,
        hasRealData,
        participantsDataLength: match.participants_data?.length || 0,
        participantsDataSample: match.participants_data?.[0] || null,
        team1Players,
        team2Players,
        pickBanData: !!pickBanData
      });

      // Determinar o campeão do jogador a partir dos dados de pick/ban
      let playerChampion = 'Unknown';
      let currentPlayerIndex = -1; // Índice do jogador atual nos times
      let currentPlayerTeam = -1;  // Time do jogador atual (1 ou 2)

      try {
        if (pickBanData && pickBanData.team1Picks) {
          const currentPlayerName = this.player?.summonerName?.toLowerCase() || '';
          const currentPlayerTagLine = this.player?.tagLine || '';
          const fullPlayerName = currentPlayerTagLine ? `${currentPlayerName}#${currentPlayerTagLine}` : currentPlayerName;

          // Buscar o jogador atual nos dados reais primeiro
          if (hasRealData) {
            const realPlayerData = match.participants_data.find((p: any) => {
              const pName = p.summonerName?.toLowerCase() || '';
              const pRiotId = p.riotIdGameName && p.riotIdTagline ?
                `${p.riotIdGameName}#${p.riotIdTagline}`.toLowerCase() : '';

              return pName === currentPlayerName || pRiotId === fullPlayerName.toLowerCase();
            });

            if (realPlayerData) {
              currentPlayerTeam = realPlayerData.teamId === 100 ? 1 : 2;
              playerChampion = ChampionService.getChampionNameById(realPlayerData.championId) || 'Unknown';
            }
          }

          // Fallback para dados de pick/ban se não encontrou nos dados reais
          if (currentPlayerTeam === -1) {
            // Tentar encontrar em team1
            let playerPick = pickBanData.team1Picks?.find((pick: any, index: number) => {
              if (pick.player && pick.player.toString().toLowerCase().includes(currentPlayerName)) {
                currentPlayerIndex = index;
                currentPlayerTeam = 1;
                return true;
              }
              return false;
            });

            // Se não encontrou em team1, tentar team2
            if (!playerPick && pickBanData.team2Picks) {
              playerPick = pickBanData.team2Picks.find((pick: any, index: number) => {
                if (pick.player && pick.player.toString().toLowerCase().includes(currentPlayerName)) {
                  currentPlayerIndex = index;
                  currentPlayerTeam = 2;
                  return true;
                }
                return false;
              });
            }

            if (playerPick && playerPick.champion) {
              playerChampion = playerPick.champion;
            }
          }
        }
      } catch (e) {
        // Silently handle error
      }      // Verificar se temos dados reais do jogador atual
      let playerRealData = null;
      if (hasRealData && this.player) {
        const currentPlayerName = this.player.summonerName?.toLowerCase().trim() || '';
        const currentPlayerTagLine = this.player.tagLine || '';
        const fullPlayerName = currentPlayerTagLine ? `${currentPlayerName}#${currentPlayerTagLine}` : currentPlayerName;

        // Buscar o jogador atual nos dados reais
        playerRealData = match.participants_data.find((p: any) => {
          const pName = p.summonerName?.toLowerCase().trim() || '';
          const pRiotId = p.riotIdGameName && p.riotIdTagline ?
            `${p.riotIdGameName}#${p.riotIdTagline}`.toLowerCase() : '';

          // Correspondência exata
          return pName === currentPlayerName || pRiotId === fullPlayerName.toLowerCase();
        });

        // Se temos dados reais, usar o campeão real
        if (playerRealData && playerRealData.championId) {
          // Usar championName processado pelo backend se disponível, senão converter ID
          playerChampion = playerRealData.championName || ChampionService.getChampionNameById(playerRealData.championId) || playerChampion;
          currentPlayerTeam = playerRealData.teamId === 100 ? 1 : 2;
        }
      }

      // Usar dados reais quando disponíveis, senão gerar aleatórios para o jogador atual
      const isWin = match.player_won || false;
      const playerKills = playerRealData?.kills ?? Math.floor(Math.random() * 12) + 3;
      const playerDeaths = playerRealData?.deaths ?? Math.floor(Math.random() * 8) + 1;
      const playerAssists = playerRealData?.assists ?? Math.floor(Math.random() * 15) + 5;

      // Itens reais ou simulados para o jogador atual
      let playerItems = [];
      if (playerRealData) {
        playerItems = [
          playerRealData.item0 || 0,
          playerRealData.item1 || 0,
          playerRealData.item2 || 0,
          playerRealData.item3 || 0,
          playerRealData.item4 || 0,
          playerRealData.item5 || 0
        ];
      } else {
        playerItems = this.generateRandomItems();
      }

      // Função para obter dados reais de um participante por nome
      const getRealDataForPlayer = (playerName: string, teamId: number) => {
        if (!hasRealData) return null;

        // Normalizar o nome do jogador para busca
        const normalizedPlayerName = playerName?.toString().toLowerCase().trim() || '';

        // Buscar por correspondência exata primeiro
        let foundPlayer = match.participants_data.find((p: any) => {
          const pName = p.summonerName?.toLowerCase().trim() || '';
          const teamMatches = (teamId === 100 && p.teamId === 100) || (teamId === 200 && p.teamId === 200);

          // Correspondência exata por summonerName
          if (teamMatches && pName === normalizedPlayerName) {
            return true;
          }

          // Correspondência por Riot ID (gameName#tagLine)
          if (teamMatches && p.riotIdGameName && p.riotIdTagline) {
            const riotId = `${p.riotIdGameName}#${p.riotIdTagline}`.toLowerCase();
            if (riotId === normalizedPlayerName) {
              return true;
            }
          }

          // Correspondência por Riot ID completo passado como parâmetro
          if (teamMatches && normalizedPlayerName.includes('#')) {
            const pRiotId = p.riotIdGameName && p.riotIdTagline ?
              `${p.riotIdGameName}#${p.riotIdTagline}`.toLowerCase() : '';
            if (pRiotId === normalizedPlayerName) {
              return true;
            }
          }

          return false;
        });

        // Se não encontrou por correspondência exata, tentar busca parcial mais restritiva
        if (!foundPlayer) {
          foundPlayer = match.participants_data.find((p: any) => {
            const pName = p.summonerName?.toLowerCase().trim() || '';
            const teamMatches = (teamId === 100 && p.teamId === 100) || (teamId === 200 && p.teamId === 200);

            // Busca parcial mais restritiva - só se um nome contém o outro completamente
            return teamMatches && (
              (pName.includes(normalizedPlayerName) && normalizedPlayerName.length > 2) ||
              (normalizedPlayerName.includes(pName) && pName.length > 2)
            );
          });
        }

        return foundPlayer;
      };

      // Convert database match to our frontend Match interface
      const mappedMatch = {
        id: match.id || match.match_id,
        createdAt: new Date(match.created_at),
        duration: match.duration ? (match.duration * 60) : 1500, // Converter minutos para segundos
        gameMode: match.game_mode || 'CUSTOM', team1: team1Players.map((playerId: any, index: number) => {
          let championName = 'Unknown';
          let playerName = 'Unknown Player';

          // Obter dados reais para este jogador primeiro (prioridade)
          const realData = getRealDataForPlayer(playerId.toString(), 100);
          if (realData) {
            // Se temos dados reais, usar eles como fonte principal
            championName = realData.championName || ChampionService.getChampionNameById(realData.championId) || 'Unknown';
            playerName = realData.summonerName || 'Unknown Player';
          } else {
            // Fallback para dados de pick/ban se não temos dados reais
            try {
              if (pickBanData && pickBanData.team1Picks && pickBanData.team1Picks[index]) {
                championName = pickBanData.team1Picks[index].champion || 'Unknown';
                if (pickBanData.team1Picks[index].player) {
                  playerName = pickBanData.team1Picks[index].player.toString();
                }
              }
            } catch (e) {
              // Silently handle error
            }

            // Se é o jogador atual, usar seus dados reais
            if (currentPlayerTeam === 1 && currentPlayerIndex === index && this.player?.summonerName) {
              // Usar Riot ID completo se disponível
              const currentPlayerName = this.player.summonerName;
              const currentPlayerTagLine = this.player.tagLine;
              playerName = currentPlayerTagLine ? `${currentPlayerName}#${currentPlayerTagLine}` : currentPlayerName;
            }
          }

          const teamItems = realData ? [
            realData.item0 || 0,
            realData.item1 || 0,
            realData.item2 || 0,
            realData.item3 || 0,
            realData.item4 || 0,
            realData.item5 || 0
          ] : this.generateRandomItems();

          return {
            name: playerName, // Usar o nome real ao invés do playerId
            champion: championName,
            championName: championName, // Para compatibilidade com o template
            summonerName: playerName, // Nome real do jogador
            puuid: this.isCurrentPlayer(playerName) ? this.player?.puuid || `custom_player_${playerId}_${index}` : `custom_player_${playerId}_${index}`,
            teamId: 100,
            kills: realData?.kills ?? Math.floor(Math.random() * 10) + 2,
            deaths: realData?.deaths ?? Math.floor(Math.random() * 8) + 1,
            assists: realData?.assists ?? Math.floor(Math.random() * 12) + 3,
            champLevel: realData?.champLevel ?? Math.floor(Math.random() * 6) + 13,
            goldEarned: realData?.goldEarned ?? Math.floor(Math.random() * 8000) + 12000,
            totalMinionsKilled: realData?.totalMinionsKilled ?? Math.floor(Math.random() * 150) + 100,
            neutralMinionsKilled: realData?.neutralMinionsKilled ?? Math.floor(Math.random() * 50) + 10,
            totalDamageDealtToChampions: realData?.totalDamageDealtToChampions ?? Math.floor(Math.random() * 20000) + 15000,
            visionScore: realData?.visionScore ?? Math.floor(Math.random() * 40) + 20,
            firstBloodKill: realData?.firstBloodKill ?? Math.random() > 0.9, // 10% chance de first blood
            item0: teamItems[0],
            item1: teamItems[1],
            item2: teamItems[2],
            item3: teamItems[3],
            item4: teamItems[4],
            item5: teamItems[5]
          };
        }), team2: team2Players.map((playerId: any, index: number) => {
          let championName = 'Unknown';
          let playerName = 'Unknown Player';

          // Obter dados reais para este jogador primeiro (prioridade)
          const realData = getRealDataForPlayer(playerId.toString(), 200);
          if (realData) {
            // Se temos dados reais, usar eles como fonte principal
            championName = realData.championName || ChampionService.getChampionNameById(realData.championId) || 'Unknown';
            playerName = realData.summonerName || 'Unknown Player';
          } else {
            // Fallback para dados de pick/ban se não temos dados reais
            try {
              if (pickBanData && pickBanData.team2Picks && pickBanData.team2Picks[index]) {
                championName = pickBanData.team2Picks[index].champion || 'Unknown';
                // Tentar usar o nome real do jogador dos dados de pick/ban
                if (pickBanData.team2Picks[index].player) {
                  playerName = pickBanData.team2Picks[index].player.toString();
                }
              }
            } catch (e) {
              // Silently handle error
            }

            // Se é o jogador atual, usar seus dados reais
            if (currentPlayerTeam === 2 && currentPlayerIndex === index && this.player?.summonerName) {
              // Usar Riot ID completo se disponível
              const currentPlayerName = this.player.summonerName;
              const currentPlayerTagLine = this.player.tagLine;
              playerName = currentPlayerTagLine ? `${currentPlayerName}#${currentPlayerTagLine}` : currentPlayerName;
            }
          }
          const teamItems = realData ? [
            realData.item0 || 0,
            realData.item1 || 0,
            realData.item2 || 0,
            realData.item3 || 0,
            realData.item4 || 0,
            realData.item5 || 0
          ] : this.generateRandomItems(); return {
            name: playerName, // Usar o nome real ao invés do playerId
            champion: championName,
            championName: championName, // Para compatibilidade com o template
            summonerName: playerName, // Nome real do jogador
            puuid: this.isCurrentPlayer(playerName) ? this.player?.puuid || `custom_player_${playerId}_${index + 5}` : `custom_player_${playerId}_${index + 5}`,
            teamId: 200,
            kills: realData?.kills ?? Math.floor(Math.random() * 10) + 2,
            deaths: realData?.deaths ?? Math.floor(Math.random() * 8) + 1,
            assists: realData?.assists ?? Math.floor(Math.random() * 12) + 3,
            champLevel: realData?.champLevel ?? Math.floor(Math.random() * 6) + 13,
            goldEarned: realData?.goldEarned ?? Math.floor(Math.random() * 8000) + 12000,
            totalMinionsKilled: realData?.totalMinionsKilled ?? Math.floor(Math.random() * 150) + 100,
            neutralMinionsKilled: realData?.neutralMinionsKilled ?? Math.floor(Math.random() * 50) + 10,
            totalDamageDealtToChampions: realData?.totalDamageDealtToChampions ?? Math.floor(Math.random() * 20000) + 15000,
            visionScore: realData?.visionScore ?? Math.floor(Math.random() * 40) + 20,
            firstBloodKill: realData?.firstBloodKill ?? Math.random() > 0.9, // 10% chance de first blood
            item0: teamItems[0],
            item1: teamItems[1],
            item2: teamItems[2],
            item3: teamItems[3],
            item4: teamItems[4],
            item5: teamItems[5]
          };
        }),
        winner: match.winner_team || 0,
        averageMMR1: 1200, // Mock value for custom matches
        averageMMR2: 1200, // Mock value for custom matches
        participants: [...team1Players, ...team2Players], // Will be populated with team1 + team2
        teams: [
          { teamId: 100, win: (match.winner_team === 1) },
          { teamId: 200, win: (match.winner_team === 2) }
        ],        // Determine if current player won
        playerStats: {
          champion: playerChampion,
          kills: playerKills,
          deaths: playerDeaths,
          assists: playerAssists,
          mmrChange: match.player_won ? (Math.floor(Math.random() * 20) + 10) : -(Math.floor(Math.random() * 15) + 5),
          isWin: isWin,
          championLevel: playerRealData?.champLevel ?? Math.floor(Math.random() * 6) + 13,
          firstBloodKill: playerRealData?.firstBloodKill ?? Math.random() > 0.85, // 15% chance de first blood para o jogador
          doubleKills: playerRealData?.doubleKills ?? (Math.random() > 0.7 ? Math.floor(Math.random() * 2) + 1 : 0),
          tripleKills: playerRealData?.tripleKills ?? (Math.random() > 0.9 ? 1 : 0),
          quadraKills: playerRealData?.quadraKills ?? (Math.random() > 0.98 ? 1 : 0),
          pentaKills: playerRealData?.pentaKills ?? (Math.random() > 0.995 ? 1 : 0),
          items: playerItems,
          goldEarned: playerRealData?.goldEarned ?? Math.floor(Math.random() * 10000) + 15000,
          totalDamageDealt: playerRealData?.totalDamageDealt ?? Math.floor(Math.random() * 50000) + 80000,
          totalDamageDealtToChampions: playerRealData?.totalDamageDealtToChampions ?? Math.floor(Math.random() * 25000) + 20000,
          totalDamageTaken: playerRealData?.totalDamageTaken ?? Math.floor(Math.random() * 20000) + 15000,
          totalMinionsKilled: playerRealData?.totalMinionsKilled ?? Math.floor(Math.random() * 180) + 120,
          neutralMinionsKilled: playerRealData?.neutralMinionsKilled ?? Math.floor(Math.random() * 60) + 20,
          wardsPlaced: playerRealData?.wardsPlaced ?? Math.floor(Math.random() * 15) + 8,
          wardsKilled: playerRealData?.wardsKilled ?? Math.floor(Math.random() * 10) + 3,
          visionScore: playerRealData?.visionScore ?? Math.floor(Math.random() * 50) + 25,
          lpChange: match.player_lp_change || 0 // Usar LP change real das partidas customizadas
        },

        // Campos específicos para partidas customizadas
        player_lp_change: match.player_lp_change || 0,
        player_mmr_change: match.player_mmr_change || 0,
        player_team: match.player_team || null,
        player_won: match.player_won || false,
        lp_changes: match.lp_changes || {}
      };

      return mappedMatch;
    });
  }

  private generateMockMatches(): Match[] {
    // Generate some mock match data for demonstration
    const mockMatches: Match[] = [];
    const champions = ['Jinx', 'Ashe', 'Caitlyn', 'Vayne', 'Ezreal', 'Kai\'Sa', 'Sivir', 'Tristana'];

    for (let i = 0; i < 15; i++) {
      const isWin = Math.random() > 0.5;
      const kills = Math.floor(Math.random() * 15) + 1;
      const deaths = Math.floor(Math.random() * 8) + 1;
      const assists = Math.floor(Math.random() * 20) + 2;
      const mmrChange = isWin ? Math.floor(Math.random() * 25) + 10 : -(Math.floor(Math.random() * 20) + 5);

      mockMatches.push({
        id: i,
        createdAt: new Date(Date.now() - (i * 24 * 60 * 60 * 1000)),
        duration: Math.floor(Math.random() * 1800) + 1200, // 20-50 minutes
        team1: [],
        team2: [],
        winner: isWin ? 1 : 2,
        averageMMR1: 1200 + Math.floor(Math.random() * 400),
        averageMMR2: 1200 + Math.floor(Math.random() * 400),
        playerStats: {
          champion: champions[Math.floor(Math.random() * champions.length)],
          kills,
          deaths,
          assists,
          mmrChange,
          isWin
        }
      });
    }

    return mockMatches;
  }

  // ========== MOCK DATA GENERATORS ==========
  private generateMockRiotMatches(): Match[] {
    const mockMatches: Match[] = [];
    const champions = ['Jinx', 'Ashe', 'Caitlyn', 'Vayne', 'Ezreal', 'Kai\'Sa', 'Sivir', 'Tristana', 'Jhin', 'Lucian'];
    const gameModes = ['RANKED_SOLO_5x5', 'RANKED_FLEX_SR', 'ARAM', 'NORMAL'];

    for (let i = 0; i < 15; i++) {
      const isWin = Math.random() > 0.4; // 60% win rate for demo
      const kills = Math.floor(Math.random() * 18) + 2;
      const deaths = Math.floor(Math.random() * 8) + 1;
      const assists = Math.floor(Math.random() * 25) + 3;
      const lpChange = isWin ? Math.floor(Math.random() * 25) + 15 : -(Math.floor(Math.random() * 20) + 10);

      mockMatches.push({
        id: i + 1000,
        createdAt: new Date(Date.now() - (i * 3 * 60 * 60 * 1000)), // 3 hours between matches
        duration: Math.floor(Math.random() * 1200) + 1200, // 20-40 minutes
        gameMode: gameModes[Math.floor(Math.random() * gameModes.length)],
        team1: [],
        team2: [],
        winner: isWin ? 1 : 2,
        playerStats: {
          champion: champions[Math.floor(Math.random() * champions.length)],
          kills,
          deaths,
          assists,
          mmrChange: lpChange, // Will be used as LP change for Riot matches
          isWin,
          championLevel: Math.floor(Math.random() * 8) + 13,
          doubleKills: Math.random() > 0.7 ? Math.floor(Math.random() * 3) + 1 : 0,
          tripleKills: Math.random() > 0.9 ? 1 : 0,
          quadraKills: Math.random() > 0.98 ? 1 : 0,
          pentaKills: Math.random() > 0.995 ? 1 : 0,
          items: this.generateRandomItems(),
          lpChange
        }
      });
    }

    return mockMatches;
  }
  private generateMockCustomMatches(): Match[] {
    const mockMatches: Match[] = [];
    const champions = ['Azir', 'Orianna', 'Syndra', 'Yasuo', 'Zed', 'LeBlanc', 'Ahri', 'Viktor'];

    for (let i = 0; i < 8; i++) {
      const isWin = Math.random() > 0.5;
      const kills = Math.floor(Math.random() * 15) + 1;
      const deaths = Math.floor(Math.random() * 8) + 1;
      const assists = Math.floor(Math.random() * 20) + 2;
      const mmrChange = isWin ? Math.floor(Math.random() * 25) + 10 : -(Math.floor(Math.random() * 20) + 5);

      // Gerar times mock para as partidas customizadas
      const team1 = [];
      const team2 = [];

      for (let j = 0; j < 5; j++) {
        const randomChampion = champions[Math.floor(Math.random() * champions.length)];
        team1.push({
          name: `Player${j + 1}`,
          champion: randomChampion,
          teamId: 100,
          kills: Math.floor(Math.random() * 10) + 2,
          deaths: Math.floor(Math.random() * 8) + 1,
          assists: Math.floor(Math.random() * 12) + 3,
          champLevel: Math.floor(Math.random() * 6) + 13,
          goldEarned: Math.floor(Math.random() * 8000) + 12000,
          totalMinionsKilled: Math.floor(Math.random() * 150) + 100,
          neutralMinionsKilled: Math.floor(Math.random() * 50) + 10,
          totalDamageDealtToChampions: Math.floor(Math.random() * 20000) + 15000,
          visionScore: Math.floor(Math.random() * 40) + 20,
          item0: this.generateRandomItems()[0],
          item1: this.generateRandomItems()[1],
          item2: this.generateRandomItems()[2],
          item3: this.generateRandomItems()[3],
          item4: this.generateRandomItems()[4],
          item5: this.generateRandomItems()[5]
        });

        team2.push({
          name: `Player${j + 6}`,
          champion: champions[Math.floor(Math.random() * champions.length)],
          teamId: 200,
          kills: Math.floor(Math.random() * 10) + 2,
          deaths: Math.floor(Math.random() * 8) + 1,
          assists: Math.floor(Math.random() * 12) + 3,
          champLevel: Math.floor(Math.random() * 6) + 13,
          goldEarned: Math.floor(Math.random() * 8000) + 12000,
          totalMinionsKilled: Math.floor(Math.random() * 150) + 100,
          neutralMinionsKilled: Math.floor(Math.random() * 50) + 10,
          totalDamageDealtToChampions: Math.floor(Math.random() * 20000) + 15000,
          visionScore: Math.floor(Math.random() * 40) + 20,
          item0: this.generateRandomItems()[0],
          item1: this.generateRandomItems()[1],
          item2: this.generateRandomItems()[2],
          item3: this.generateRandomItems()[3],
          item4: this.generateRandomItems()[4],
          item5: this.generateRandomItems()[5]
        });
      }

      mockMatches.push({
        id: i + 2000,
        createdAt: new Date(Date.now() - (i * 24 * 60 * 60 * 1000)),
        duration: Math.floor(Math.random() * 1800) + 1200, // 20-50 minutes
        gameMode: 'CUSTOM',
        team1: team1,
        team2: team2,
        participants: [...team1, ...team2],
        teams: [
          { teamId: 100, win: isWin },
          { teamId: 200, win: !isWin }
        ],
        winner: isWin ? 1 : 2,
        averageMMR1: 1200 + Math.floor(Math.random() * 400),
        averageMMR2: 1200 + Math.floor(Math.random() * 400),
        playerStats: {
          champion: champions[Math.floor(Math.random() * champions.length)],
          kills,
          deaths,
          assists,
          mmrChange,
          isWin,
          championLevel: Math.floor(Math.random() * 6) + 13,
          doubleKills: Math.random() > 0.7 ? Math.floor(Math.random() * 2) + 1 : 0,
          tripleKills: Math.random() > 0.9 ? 1 : 0,
          quadraKills: Math.random() > 0.98 ? 1 : 0,
          pentaKills: Math.random() > 0.995 ? 1 : 0,
          items: this.generateRandomItems(),
          goldEarned: Math.floor(Math.random() * 10000) + 15000,
          totalDamageDealt: Math.floor(Math.random() * 50000) + 80000,
          totalDamageDealtToChampions: Math.floor(Math.random() * 25000) + 20000,
          totalDamageTaken: Math.floor(Math.random() * 20000) + 15000,
          totalMinionsKilled: Math.floor(Math.random() * 180) + 120,
          neutralMinionsKilled: Math.floor(Math.random() * 60) + 20,
          wardsPlaced: Math.floor(Math.random() * 15) + 8,
          wardsKilled: Math.floor(Math.random() * 10) + 3,
          visionScore: Math.floor(Math.random() * 50) + 25
        }
      });
    }

    return mockMatches;
  }

  private generateRandomItems(): number[] {
    const items = [3031, 3006, 3046, 3153, 3072, 3026, 3094]; // Sample item IDs
    const playerItems = [];
    for (let i = 0; i < 6; i++) {
      if (Math.random() > 0.2) { // 80% chance of having an item in each slot
        playerItems.push(items[Math.floor(Math.random() * items.length)]);
      } else {
        playerItems.push(0); // Empty slot
      }
    }
    return playerItems;
  }

  // ========== STATS METHODS ==========
  getRiotStats() {
    const stats = {
      totalKills: 0,
      totalDeaths: 0,
      totalAssists: 0,
      totalWins: 0,
      totalMMRGained: 0
    };

    this.riotMatches.forEach(match => {
      if (match.playerStats) {
        stats.totalKills += match.playerStats.kills;
        stats.totalDeaths += match.playerStats.deaths;
        stats.totalAssists += match.playerStats.assists;
        if (match.playerStats.isWin) stats.totalWins++;
        stats.totalMMRGained += match.playerStats.lpChange || match.playerStats.mmrChange;
      }
    });

    return stats;
  } getCustomStats() {
    const stats = {
      totalKills: 0,
      totalDeaths: 0,
      totalAssists: 0,
      totalWins: 0,
      totalMMRGained: 0
    };

    this.customMatches.forEach(match => {
      if (match.playerStats) {
        stats.totalKills += match.playerStats.kills;
        stats.totalDeaths += match.playerStats.deaths;
        stats.totalAssists += match.playerStats.assists;
        if (match.playerStats.isWin) stats.totalWins++;
        // Para partidas customizadas, usar lpChange em vez de mmrChange
        stats.totalMMRGained += match.playerStats.lpChange || match.player_mmr_change || 0;
      }
    });

    return stats;
  }

  getRiotWinStreakInfo(): { current: number; longest: number } {
    return this.calculateWinStreak(this.riotMatches);
  }

  getCustomWinStreakInfo(): { current: number; longest: number } {
    return this.calculateWinStreak(this.customMatches);
  }

  private calculateWinStreak(matches: Match[]): { current: number; longest: number } {
    let current = 0;
    let longest = 0;
    let temp = 0;

    for (let i = 0; i < matches.length; i++) {
      const match = matches[i];
      if (match.playerStats?.isWin) {
        temp++;
        if (i === 0) current = temp;
      } else {
        longest = Math.max(longest, temp);
        temp = 0;
        if (i === 0) current = 0;
      }
    }

    longest = Math.max(longest, temp);
    return { current, longest };
  }

  // ========== UNIFIED STATS METHODS USING STRATEGY PATTERN ==========

  /**
   * Gets statistics for current tab using strategy pattern
   */
  getTabStats() {
    const strategy = this.getCurrentStrategy();
    return strategy ? strategy.getStats() : { totalWins: 0, totalMMRGained: 0 };
  }

  /**
   * Gets win streak info for current tab
   */
  getTabWinStreakInfo() {
    const strategy = this.getCurrentStrategy();
    return strategy ? strategy.getWinStreak() : { current: 0, longest: 0 };
  }

  /**
   * Gets average gain for current tab
   */
  getTabAverageGain() {
    const strategy = this.getCurrentStrategy();
    return strategy ? strategy.getAverageGain() : 0;
  }

  /**
   * Gets most played champion for current tab
   */
  getTabMostPlayedChampion() {
    const strategy = this.getCurrentStrategy();
    return strategy ? strategy.getMostPlayedChampion() : 'N/A';
  }

  /**
   * Gets average KDA for current tab
   */
  getTabAverageKDA() {
    const strategy = this.getCurrentStrategy();
    return strategy ? strategy.getAverageKDA() : '0.0:1';
  }

  /**
   * Gets empty state message for current tab
   */
  getTabEmptyMessage() {
    const strategy = this.getCurrentStrategy();
    return strategy ? strategy.emptyMessage : 'Nenhuma partida encontrada';
  }

  /**
   * Gets empty state description for current tab
   */
  getTabEmptyDescription() {
    const strategy = this.getCurrentStrategy();
    return strategy ? strategy.emptyDescription : 'Nenhuma partida encontrada';
  }

  // ========== LEGACY STATS METHODS (mantidos para compatibilidade) ==========
  getLegacyStats() {
    return {
      totalKills: this.matches.reduce((sum, m) => sum + (m.playerStats?.kills || 0), 0),
      totalDeaths: this.matches.reduce((sum, m) => sum + (m.playerStats?.deaths || 0), 0),
      totalAssists: this.matches.reduce((sum, m) => sum + (m.playerStats?.assists || 0), 0),
      totalWins: this.matches.filter(m => m.playerStats?.isWin).length,
      totalMMRGained: this.matches.reduce((sum, m) => sum + (m.playerStats?.mmrChange || 0), 0)
    };
  }

  // ========== UI HELPER METHODS ==========
  getGameModeDisplay(gameMode?: string): string {
    const modes: { [key: string]: string } = {
      'RANKED_SOLO_5x5': 'Solo/Duo',
      'RANKED_FLEX_SR': 'Flex',
      'ARAM': 'ARAM',
      'NORMAL': 'Normal',
      'CLASSIC': 'Clássica'
    };
    return modes[gameMode || ''] || 'Desconhecido';
  } getChampionImageUrl(championName?: string): string {
    if (!championName) return '';

    // Usar a versão mais recente do Data Dragon (igual ao backend)
    const version = '15.14.1';

    // O nome do campeão já vem normalizado do backend, então usar diretamente
    return `https://ddragon.leagueoflegends.com/cdn/15.14.1/img/champion/${championName}.png`;
  }

  getItemImageUrl(itemId: number): string {
    if (!itemId || itemId === 0) return '';
    // Data Dragon item image URL
    return `https://ddragon.leagueoflegends.com/cdn/15.14.1/img/item/${itemId}.png`;
  }

  getPlayerItems(match: Match): number[] {
    return match.playerStats?.items || [0, 0, 0, 0, 0, 0];
  }

  getAverageKDA(): string {
    const matches = this.getCurrentMatches();
    if (matches.length === 0) return '0.0 / 0.0 / 0.0';

    const totalKills = matches.reduce((sum, m) => sum + (m.playerStats?.kills || 0), 0);
    const totalDeaths = matches.reduce((sum, m) => sum + (m.playerStats?.deaths || 0), 0);
    const totalAssists = matches.reduce((sum, m) => sum + (m.playerStats?.assists || 0), 0);

    const avgKills = (totalKills / matches.length).toFixed(1);
    const avgDeaths = (totalDeaths / matches.length).toFixed(1);
    const avgAssists = (totalAssists / matches.length).toFixed(1);

    return `${avgKills} / ${avgDeaths} / ${avgAssists}`;
  }

  getAverageGain(): number {
    const matches = this.getCurrentMatches();
    if (matches.length === 0) return 0;

    const totalGain = matches.reduce((sum, m) => {
      if (this.activeTab === 'riot') {
        return sum + (m.playerStats?.lpChange || m.playerStats?.mmrChange || 0);
      } else {
        return sum + (m.playerStats?.mmrChange || 0);
      }
    }, 0);

    return totalGain / matches.length;
  }

  // ========== UTILITY METHODS (from original component) ==========
  getMatchDuration(match: Match): string {
    const minutes = Math.floor(match.duration / 60);
    const seconds = match.duration % 60;
    return `${minutes}m ${seconds}s`;
  }

  getKDA(match: Match): string {
    if (!match.playerStats) return '0/0/0';
    return `${match.playerStats.kills}/${match.playerStats.deaths}/${match.playerStats.assists}`;
  }

  getKDARatio(match: Match): number {
    if (!match.playerStats || match.playerStats.deaths === 0) return 0;
    return ((match.playerStats.kills + match.playerStats.assists) / match.playerStats.deaths);
  }

  getTimeAgo(date: Date): string {
    const now = new Date();
    const diffMs = now.getTime() - new Date(date).getTime();
    const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));
    const diffHours = Math.floor(diffMs / (1000 * 60 * 60));
    const diffMinutes = Math.floor(diffMs / (1000 * 60));

    if (diffDays > 0) return `${diffDays} dia${diffDays > 1 ? 's' : ''} atrás`;
    if (diffHours > 0) return `${diffHours} hora${diffHours > 1 ? 's' : ''} atrás`;
    if (diffMinutes > 0) return `${diffMinutes} minuto${diffMinutes > 1 ? 's' : ''} atrás`;
    return 'Agora mesmo';
  }
  trackMatch(index: number, match: Match): string {
    const trackId = match.id.toString();
    return trackId;
  }
  toggleMatchDetails(matchId: string | number): void {
    const matchIdString = matchId.toString();
    if (this.expandedMatches.has(matchIdString)) {
      this.expandedMatches.delete(matchIdString);
    } else {
      this.expandedMatches.add(matchIdString);
    }
  }

  isMatchExpanded(matchId: string | number): boolean {
    return this.expandedMatches.has(matchId.toString());
  }

  hasMoreMatches(): boolean {
    const currentMatches = this.getCurrentMatches();
    return (this.currentPage + 1) * this.matchesPerPage < this.totalMatches;
  }

  async loadMoreMatches() {
    this.currentPage++;
    await this.loadCurrentTabMatches();
  }

  getMostPlayedChampion(): string {
    const matches = this.getCurrentMatches();
    if (matches.length === 0) return 'N/A';

    const championCounts: { [key: string]: number } = {};

    matches.forEach(match => {
      if (match.playerStats?.champion) {
        championCounts[match.playerStats.champion] = (championCounts[match.playerStats.champion] || 0) + 1;
      }
    });

    let mostPlayed = '';
    let maxCount = 0;

    for (const [champion, count] of Object.entries(championCounts)) {
      if (count > maxCount) {
        maxCount = count;
        mostPlayed = champion;
      }
    }

    return mostPlayed || 'N/A';
  }

  // ========== EXPANDED MATCH METHODS ==========

  getParticipantKDARatio(participant: any): number {
    if (!participant || participant.deaths === 0) {
      return (participant?.kills || 0) + (participant?.assists || 0);
    }
    return ((participant.kills || 0) + (participant.assists || 0)) / participant.deaths;
  }
  getParticipantItems(participant: any): number[] {
    if (!participant) return [0, 0, 0, 0, 0, 0];
    return [
      participant.item0 || 0,
      participant.item1 || 0,
      participant.item2 || 0,
      participant.item3 || 0,
      participant.item4 || 0,
      participant.item5 || 0
    ];
  }
  // ========== LANE DETECTION AND ORGANIZATION ==========
  private getParticipantLane(participant: any): string {
    // Para ARAM, não há lanes específicas - organizar por papel/champion
    if (participant.lane === 'BOTTOM' && participant.gameMode === 'ARAM') {
      return this.detectARAMRole(participant);
    }

    // Determinar lane baseado em teamPosition ou lane
    const lane = participant.teamPosition || participant.lane || 'UNKNOWN';
    const role = participant.role || 'UNKNOWN';

    // Mapear para lanes do LoL
    switch (lane) {
      case 'TOP':
        return 'TOP';
      case 'JUNGLE':
        return 'JUNGLE';
      case 'MIDDLE':
      case 'MID':
        return 'MIDDLE';
      case 'BOTTOM':
        if (role === 'DUO_CARRY' || participant.individualPosition === 'Bottom') {
          return 'ADC';
        } else if (role === 'DUO_SUPPORT' || participant.individualPosition === 'Utility') {
          return 'SUPPORT';
        }
        // Fallback: determinar por champion ou items
        return this.detectBotLaneRole(participant);
      case 'UTILITY':
        return 'SUPPORT';
      default:        // Fallback para detecção baseada em posição individual
        return this.detectLaneByPosition(participant);
    }
  }

  private detectARAMRole(participant: any): string {
    // Para ARAM, organizar por tipo de champion
    const championName = participant.championName;

    // Tanks/Supports
    const tanks = ['Leona', 'Braum', 'Alistar', 'Thresh', 'Blitzcrank', 'Nautilus', 'Malphite', 'Rammus', 'Shen'];
    const supports = ['Nami', 'Soraka', 'Janna', 'Lulu', 'Sona', 'Karma', 'Yuumi', 'Seraphine'];

    // ADC/Marksmen
    const adcs = ['Jinx', 'Caitlyn', 'Ashe', 'Vayne', 'Ezreal', 'Kai\'Sa', 'Jhin', 'Xayah', 'Tristana', 'Sivir'];

    // AP Carries/Mages
    const mages = ['Lux', 'Orianna', 'Syndra', 'Azir', 'Veigar', 'Brand', 'Vel\'Koz', 'Xerath', 'Ziggs'];

    // Assassins/AD
    const assassins = ['Zed', 'Yasuo', 'Katarina', 'Akali', 'Talon', 'Qiyana'];

    if (tanks.includes(championName)) return 'SUPPORT';
    if (supports.includes(championName)) return 'SUPPORT';
    if (adcs.includes(championName)) return 'ADC';
    if (mages.includes(championName)) return 'MIDDLE';
    if (assassins.includes(championName)) return 'JUNGLE';

    // Fallback baseado em items
    return this.detectBotLaneRole(participant);
  }

  private detectBotLaneRole(participant: any): string {    // Lista de campeões tradicionalmente ADC
    const adcChampions = [
      'Jinx', 'Caitlyn', 'Ashe', 'Vayne', 'Ezreal', 'Kai\'Sa', 'Jhin', 'Xayah',
      'Tristana', 'Sivir', 'Miss Fortune', 'Lucian', 'Draven', 'Twitch', 'Kog\'Maw',
      'Varus', 'Kalista', 'Aphelios', 'Samira', 'Zeri', 'Nilah'
    ];

    // Lista de campeões tradicionalmente Support
    const supportChampions = [
      'Thresh', 'Blitzcrank', 'Leona', 'Braum', 'Alistar', 'Nautilus', 'Pyke',
      'Lulu', 'Janna', 'Soraka', 'Nami', 'Sona', 'Yuumi', 'Seraphine', 'Karma',
      'Morgana', 'Zyra', 'Brand', 'Vel\'Koz', 'Xerath', 'Swain', 'Pantheon',
      'Rakan', 'Taric', 'Bard', 'Zilean', 'Senna', 'Milio', 'Renata Glasc'
    ];

    const championName = participant.championName;

    if (adcChampions.includes(championName)) {
      return 'ADC';
    } else if (supportChampions.includes(championName)) {
      return 'SUPPORT';
    }

    // Verificar itens de suporte
    const items = [
      participant.item0, participant.item1, participant.item2,
      participant.item3, participant.item4, participant.item5
    ];

    // IDs de itens de suporte comuns
    const supportItems = [3850, 3851, 3853, 3854, 3855, 3857, 3858, 3859, 3860, 3862, 3863, 3864];
    const hasSupportItem = items.some(item => supportItems.includes(item));

    if (hasSupportItem) {
      return 'SUPPORT';
    }

    // Se não conseguiu determinar, usar farm como critério
    const totalFarm = (participant.totalMinionsKilled || 0) + (participant.neutralMinionsKilled || 0);
    return totalFarm > 100 ? 'ADC' : 'SUPPORT';
  }
  private detectLaneByPosition(participant: any): string {
    const position = participant.individualPosition || participant.teamPosition || 'UNKNOWN';

    switch (position) {
      case 'Top':
        return 'TOP';
      case 'Jungle':
        return 'JUNGLE';
      case 'Middle':
        return 'MIDDLE';
      case 'Bottom':
        return 'ADC';
      case 'Utility':
        return 'SUPPORT';
      default:
        // Fallback: detect by participant order and champion role
        return this.detectLaneByChampionAndOrder(participant);
    }
  }

  private detectLaneByChampionAndOrder(participant: any): string {
    const championName = participant.championName;
    const participantId = participant.participantId;

    // Common role detection based on champion types
    const junglers = ['Graves', 'Nidalee', 'Kindred', 'KhaZix', 'Rengar', 'Ekko', 'Diana', 'Kayn', 'Viego', 'Belveth'];
    const supports = ['Thresh', 'Leona', 'Braum', 'Nautilus', 'Alistar', 'Lulu', 'Nami', 'Soraka', 'Janna', 'Sona', 'Yuumi'];
    const adcs = ['Jinx', 'Caitlyn', 'Ashe', 'Vayne', 'Ezreal', 'Kaisa', 'Jhin', 'Xayah', 'Tristana', 'Sivir', 'Draven'];

    if (junglers.includes(championName)) return 'JUNGLE';
    if (supports.includes(championName)) return 'SUPPORT';
    if (adcs.includes(championName)) return 'ADC';

    // Fallback by participant order (common in LCU data)
    // Usually: 1-TOP, 2-JUNGLE, 3-MID, 4-ADC, 5-SUPPORT per team
    const order = (participantId - 1) % 5;
    switch (order) {
      case 0: return 'TOP';
      case 1: return 'JUNGLE';
      case 2: return 'MIDDLE';
      case 3: return 'ADC';
      case 4: return 'SUPPORT';
      default: return 'UNKNOWN';
    }
  } organizeTeamByLanes(team: any[] | undefined): { [lane: string]: any } {
    const organizedTeam: { [lane: string]: any } = {
      'TOP': null,
      'JUNGLE': null,
      'MIDDLE': null,
      'ADC': null,
      'SUPPORT': null
    };

    if (!team || !Array.isArray(team)) {
      return organizedTeam;
    }    // First pass: assign players to their detected lanes
    team.forEach((participant, index) => {
      const lane = this.getParticipantLane(participant);

      if (organizedTeam[lane] === null) {
        organizedTeam[lane] = participant;
      }
    });

    // Second pass: fill empty lanes with remaining players
    const unassignedPlayers = team.filter(participant => {
      const lane = this.getParticipantLane(participant);
      return organizedTeam[lane] !== participant;
    }); const emptyLanes = Object.keys(organizedTeam).filter(lane => organizedTeam[lane] === null);

    unassignedPlayers.forEach((participant, index) => {
      if (index < emptyLanes.length) {
        organizedTeam[emptyLanes[index]] = participant;
      }
    });    // Final pass: ensure all players are assigned (force assignment if needed)
    const lanes = ['TOP', 'JUNGLE', 'MIDDLE', 'ADC', 'SUPPORT'];
    lanes.forEach((lane, index) => {
      if (organizedTeam[lane] === null && team.length > index) {
        organizedTeam[lane] = team[index];
      }
    });

    return organizedTeam;
  }

  // Method specifically for custom matches team organization
  organizeCustomTeamByLanes(team: any[] | undefined): { [lane: string]: any } {
    console.log('🚀 [organizeCustomTeamByLanes] Função chamada com:', {
      teamLength: team?.length || 0,
      team: team?.map(p => ({ name: p.summonerName || p.name, champion: p.championName || p.champion }))
    });

    const organizedTeam: { [lane: string]: any } = {
      'TOP': null,
      'JUNGLE': null,
      'MIDDLE': null,
      'ADC': null,
      'SUPPORT': null
    };

    if (!team || !Array.isArray(team)) {
      return organizedTeam;
    }

    // Função para detectar lane baseada em dados reais
    const detectLane = (participant: any): string => {
      const championName = participant.championName || participant.champion;

      console.log('🔍 [detectLane] Análise do campeão:', {
        championName
      });

      // Sistema de pontuação para cada lane
      const laneScores: { [key: string]: number } = {
        'TOP': 0,
        'JUNGLE': 0,
        'MIDDLE': 0,
        'ADC': 0,
        'SUPPORT': 0
      };

      // 1. Verificar SMITE (Jungle) - Pontuação máxima
      const hasSmite = participant.summoner1Id === 11 || participant.summoner2Id === 11;
      if (hasSmite) {
        laneScores['JUNGLE'] += 100; // Pontuação máxima
        console.log('✅ [detectLane] +100 pontos para JUNGLE por SMITE:', championName);
      }

      // 2. Verificar itens de suporte
      const items = [
        participant.item0, participant.item1, participant.item2,
        participant.item3, participant.item4, participant.item5
      ];

      const supportItems = [
        3850, 3851, 3853, 3854, 3855, 3857, 3858, 3859, 3860, 3862, 3863, 3864
      ];
      const hasSupportItem = items.some(item => supportItems.includes(item));

      if (hasSupportItem) {
        laneScores['SUPPORT'] += 100; // Pontuação máxima
        console.log('✅ [detectLane] +100 pontos para SUPPORT por item:', championName);
      }

      // 3. Usar lane detectada pelo backend (Data Dragon)
      if (participant.detectedLane && participant.detectedLane !== 'UNKNOWN') {
        laneScores[participant.detectedLane] += 50; // Pontuação alta para lane detectada pelo Data Dragon
        console.log('✅ [detectLane] +50 pontos para', participant.detectedLane, 'por Data Dragon:', championName);
      }

      // Encontrar a lane com maior pontuação
      const bestLane = Object.entries(laneScores).reduce((a, b) =>
        laneScores[a[0]] > laneScores[b[0]] ? a : b
      )[0];

      // Se a melhor pontuação for 0, usar fallback
      if (laneScores[bestLane] === 0) {
        console.log('❌ [detectLane] Fallback para UNKNOWN:', championName);
        return 'UNKNOWN';
      }

      console.log('✅ [detectLane] Lane detectada:', {
        championName,
        lane: bestLane,
        score: laneScores[bestLane]
      });

      return bestLane;
    };

    // Detectar lanes para cada participante
    const participantsWithLanes = team.map(participant => ({
      ...participant,
      championName: participant.championName || participant.champion,
      summonerName: participant.summonerName || participant.name,
      kills: participant.kills || 0,
      deaths: participant.deaths || 0,
      assists: participant.assists || 0,
      champLevel: participant.champLevel || 18,
      goldEarned: participant.goldEarned || 0,
      totalMinionsKilled: participant.totalMinionsKilled || 0,
      neutralMinionsKilled: participant.neutralMinionsKilled || 0,
      totalDamageDealtToChampions: participant.totalDamageDealtToChampions || 0,
      visionScore: participant.visionScore || 0,
      item0: participant.item0 || 0,
      item1: participant.item1 || 0,
      item2: participant.item2 || 0,
      item3: participant.item3 || 0,
      item4: participant.item4 || 0,
      item5: participant.item5 || 0,
      summoner1Id: participant.summoner1Id || 0,
      summoner2Id: participant.summoner2Id || 0,
      detectedLane: detectLane(participant)
    }));

    // Organizar por lanes detectadas
    participantsWithLanes.forEach(participant => {
      const lane = participant.detectedLane;

      if (lane !== 'UNKNOWN' && !organizedTeam[lane]) {
        organizedTeam[lane] = participant;
      }
    });

    // Para participantes não classificados, usar ordem sequencial
    const remainingParticipants = participantsWithLanes.filter(p => p.detectedLane === 'UNKNOWN');
    const availableLanes = ['TOP', 'JUNGLE', 'MIDDLE', 'ADC', 'SUPPORT'].filter(lane => !organizedTeam[lane]);

    remainingParticipants.forEach((participant, index) => {
      if (index < availableLanes.length) {
        organizedTeam[availableLanes[index]] = participant;
      }
    });

    // Se ainda há lanes vazias, redistribuir jogadores que podem ter múltiplas lanes
    const emptyLanes = ['TOP', 'JUNGLE', 'MIDDLE', 'ADC', 'SUPPORT'].filter(lane => !organizedTeam[lane]);
    if (emptyLanes.length > 0) {
      console.log('⚠️ [organizeCustomTeamByLanes] Lanes vazias encontradas:', emptyLanes);

      // Buscar jogadores que podem ser redistribuídos
      const allParticipants = participantsWithLanes;
      const usedParticipants = Object.values(organizedTeam).filter(p => p !== null);
      const unusedParticipants = allParticipants.filter(p => !usedParticipants.includes(p));

      console.log('🔍 [organizeCustomTeamByLanes] Jogadores não utilizados:', unusedParticipants.map(p => p.summonerName || p.name));

      // Distribuir jogadores não utilizados nas lanes vazias
      unusedParticipants.forEach((participant, index) => {
        if (index < emptyLanes.length) {
          organizedTeam[emptyLanes[index]] = participant;
          console.log('✅ [organizeCustomTeamByLanes] Redistribuído:', {
            player: participant.summonerName || participant.name,
            champion: participant.championName || participant.champion,
            lane: emptyLanes[index]
          });
        }
      });
    }

    console.log('🎯 [organizeCustomTeamByLanes] Resultado final:', {
      team: team.map(p => p.summonerName || p.name),
      organizedLanes: Object.keys(organizedTeam).map(lane => ({
        lane,
        player: organizedTeam[lane]?.summonerName || organizedTeam[lane]?.name,
        champion: organizedTeam[lane]?.championName || organizedTeam[lane]?.champion,
        detectedLane: organizedTeam[lane]?.detectedLane
      }))
    });

    return organizedTeam;
  }

  getLaneIcon(lane: string): string {
    switch (lane) {
      case 'TOP': return '⚔️';
      case 'JUNGLE': return '🌲';
      case 'MIDDLE': return '🏰';
      case 'ADC': return '🏹';
      case 'SUPPORT': return '🛡️';
      default: return '❓';
    }
  }

  getLaneName(lane: string): string {
    switch (lane) {
      case 'TOP': return 'Top';
      case 'JUNGLE': return 'Jungle';
      case 'MIDDLE': return 'Mid';
      case 'ADC': return 'ADC';
      case 'SUPPORT': return 'Support';
      default: return 'Unknown';
    }
  }

  // Método para obter as tags de um campeão usando o ChampionService
  private getChampionTags(championName: string): string[] {
    if (!championName) return [];

    // Fallback simples - retornar tags básicas baseadas no nome do campeão
    // Em uma implementação completa, isso seria resolvido pelo backend
    const championNameLower = championName.toLowerCase();

    // Mapeamento básico de alguns campeões conhecidos
    const championTags: { [key: string]: string[] } = {
      'jinx': ['Marksman'],
      'ashe': ['Marksman'],
      'caitlyn': ['Marksman'],
      'vayne': ['Marksman'],
      'ezreal': ['Marksman'],
      'kaisa': ['Marksman'],
      'sivir': ['Marksman'],
      'tristana': ['Marksman'],
      'jhin': ['Marksman'],
      'lucian': ['Marksman'],
      'azir': ['Mage'],
      'orianna': ['Mage'],
      'syndra': ['Mage'],
      'yasuo': ['Fighter'],
      'zed': ['Assassin'],
      'leblanc': ['Mage', 'Assassin'],
      'ahri': ['Mage', 'Assassin'],
      'viktor': ['Mage']
    };

    return championTags[championNameLower] || ['Unknown'];
  }

  // New method to process LCU match data
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
      const mappedMatches = games.map((match: any) => {
        return this.mapLCUMatchToModel(match);
      }).filter((match: any) => match !== null) as Match[];

      this.riotMatches = mappedMatches;
      this.totalMatches = this.riotMatches.length;
    } else {
      this.riotMatches = [];
      this.totalMatches = 0;
    }
  }
  // New method to map LCU match data to our Match model
  private mapLCUMatchToModel(lcuMatch: any): Match | null {
    try {
      // Try to find player data
      let playerData = null;
      const currentPlayerPuuid = this.player?.puuid;
      const currentPlayerName = this.player?.summonerName?.toLowerCase();

      // Strategy 1: Match by PUUID in participants directly
      if (currentPlayerPuuid) {
        playerData = lcuMatch.participants?.find((p: any) => p.puuid === currentPlayerPuuid);
      }

      // Strategy 2: Match by participantIdentities
      if (!playerData && lcuMatch.participantIdentities) {
        if (currentPlayerPuuid) {
          const identity = lcuMatch.participantIdentities.find((id: any) =>
            id.player?.puuid === currentPlayerPuuid
          );
          if (identity) {
            playerData = lcuMatch.participants?.find((p: any) =>
              p.participantId === identity.participantId
            );
          }
        }

        if (!playerData && currentPlayerName) {
          const identity = lcuMatch.participantIdentities.find((id: any) => {
            const playerSummonerName = id.player?.summonerName?.toLowerCase();
            const playerGameName = id.player?.gameName?.toLowerCase();
            return playerSummonerName === currentPlayerName || playerGameName === currentPlayerName;
          });
          if (identity) {
            playerData = lcuMatch.participants?.find((p: any) =>
              p.participantId === identity.participantId
            );
          }
        }
      }

      if (!playerData) {
        return null;
      }

      // Map participants with complete information
      // O backend já processa os dados dos campeões, então usamos championName diretamente
      const enhancedParticipants = lcuMatch.participants?.map((p: any) => {
        const identity = lcuMatch.participantIdentities?.find((id: any) =>
          id.participantId === p.participantId
        );

        const isCurrentPlayerByPuuid = identity?.player?.puuid === currentPlayerPuuid;
        const isCurrentPlayerByName = currentPlayerName &&
          (identity?.player?.summonerName?.toLowerCase() === currentPlayerName ||
            identity?.player?.gameName?.toLowerCase() === currentPlayerName);
        const isCurrentPlayer = isCurrentPlayerByPuuid || isCurrentPlayerByName;

        // Create a proper display name combining gameName and tagLine
        let displayName = `Player ${p.participantId}`;
        if (identity?.player) {
          const player = identity.player;
          if (player.gameName && player.tagLine) {
            displayName = `${player.gameName}#${player.tagLine}`;
          } else if (player.summonerName) {
            displayName = player.summonerName;
          } else if (player.gameName) {
            displayName = player.gameName;
          }
        }

        return {
          ...p,
          // Usar championName já processado pelo backend, com fallback para o ID
          championName: p.championName || `Champion${p.championId}`,
          summonerName: displayName,
          puuid: identity?.player?.puuid || '',
          kills: p.stats?.kills || 0,
          deaths: p.stats?.deaths || 0,
          assists: p.stats?.assists || 0,
          champLevel: p.stats?.champLevel || 1,
          firstBloodKill: p.stats?.firstBloodKill || false,
          goldEarned: p.stats?.goldEarned || 0,
          totalDamageDealt: p.stats?.totalDamageDealt || 0,
          totalDamageDealtToChampions: p.stats?.totalDamageDealtToChampions || 0,
          totalDamageTaken: p.stats?.totalDamageTaken || 0,
          totalMinionsKilled: p.stats?.totalMinionsKilled || 0,
          neutralMinionsKilled: p.stats?.neutralMinionsKilled || 0,
          wardsPlaced: p.stats?.wardsPlaced || 0,
          wardsKilled: p.stats?.wardsKilled || 0,
          visionScore: p.stats?.visionScore || 0,
          item0: p.stats?.item0 || 0,
          item1: p.stats?.item1 || 0,
          item2: p.stats?.item2 || 0,
          item3: p.stats?.item3 || 0,
          item4: p.stats?.item4 || 0,
          item5: p.stats?.item5 || 0,
          isCurrentPlayer: isCurrentPlayer
        };
      }) || [];

      // Separate into teams
      const team1 = enhancedParticipants.filter((p: any) => p.teamId === 100);
      const team2 = enhancedParticipants.filter((p: any) => p.teamId === 200);

      // Find current player data
      const currentPlayerData = enhancedParticipants.find((p: any) => p.isCurrentPlayer) || playerData;

      return {
        id: lcuMatch.gameId || lcuMatch.gameCreation,
        createdAt: new Date(lcuMatch.gameCreation || lcuMatch.gameCreationDate),
        duration: lcuMatch.gameDuration || (lcuMatch.gameLength / 1000),
        gameMode: lcuMatch.gameMode,
        gameVersion: lcuMatch.gameVersion,
        mapId: lcuMatch.mapId,
        participants: enhancedParticipants,
        teams: lcuMatch.teams,
        team1: team1,
        team2: team2,
        winner: lcuMatch.teams?.find((t: any) => t.win)?.teamId === 100 ? 1 : 2,
        playerStats: {
          // Usar championName já processado pelo backend
          champion: currentPlayerData.championName || `Champion${currentPlayerData.championId}`,
          kills: currentPlayerData.stats?.kills || currentPlayerData.kills || 0,
          deaths: currentPlayerData.stats?.deaths || currentPlayerData.deaths || 0,
          assists: currentPlayerData.stats?.assists || currentPlayerData.assists || 0,
          mmrChange: 0,
          isWin: currentPlayerData.stats?.win || false,
          championLevel: currentPlayerData.stats?.champLevel || currentPlayerData.champLevel || 1,
          firstBloodKill: currentPlayerData.stats?.firstBloodKill || currentPlayerData.firstBloodKill || false,
          doubleKills: currentPlayerData.stats?.doubleKills || currentPlayerData.doubleKills || 0,
          tripleKills: currentPlayerData.stats?.tripleKills || currentPlayerData.tripleKills || 0,
          quadraKills: currentPlayerData.stats?.quadraKills || currentPlayerData.quadraKills || 0,
          pentaKills: currentPlayerData.stats?.pentaKills || currentPlayerData.pentaKills || 0,
          items: [
            currentPlayerData.stats?.item0 || currentPlayerData.item0 || 0,
            currentPlayerData.stats?.item1 || currentPlayerData.item1 || 0,
            currentPlayerData.stats?.item2 || currentPlayerData.item2 || 0,
            currentPlayerData.stats?.item3 || currentPlayerData.item3 || 0,
            currentPlayerData.stats?.item4 || currentPlayerData.item4 || 0,
            currentPlayerData.stats?.item5 || currentPlayerData.item5 || 0
          ],
          lpChange: 0,
          goldEarned: currentPlayerData.stats?.goldEarned || currentPlayerData.goldEarned || 0,
          totalDamageDealt: currentPlayerData.stats?.totalDamageDealt || currentPlayerData.totalDamageDealt || 0,
          totalDamageDealtToChampions: currentPlayerData.stats?.totalDamageDealtToChampions || currentPlayerData.totalDamageDealtToChampions || 0,
          totalDamageTaken: currentPlayerData.stats?.totalDamageTaken || currentPlayerData.totalDamageTaken || 0,
          totalMinionsKilled: currentPlayerData.stats?.totalMinionsKilled || currentPlayerData.totalMinionsKilled || 0,
          neutralMinionsKilled: currentPlayerData.stats?.neutralMinionsKilled || currentPlayerData.neutralMinionsKilled || 0,
          wardsPlaced: currentPlayerData.stats?.wardsPlaced || currentPlayerData.wardsPlaced || 0,
          wardsKilled: currentPlayerData.stats?.wardsKilled || currentPlayerData.wardsKilled || 0,
          visionScore: currentPlayerData.stats?.visionScore || currentPlayerData.visionScore || 0,
          summoner1Id: currentPlayerData.spell1Id || 0,
          summoner2Id: currentPlayerData.spell2Id || 0
        }
      };
    } catch (error: any) {
      console.error('❌ Erro ao mapear partida LCU:', error);
      return null;
    }
  }
  // Debug method para template
  debugCustomMatches(): void {
    // Método de debug removido - não é mais necessário
  }

  // Método super simples para verificar se os dados estão corretos
  getSimpleMatchCount(): number {
    const count = this.activeTab === 'custom' ? this.customMatches.length : this.riotMatches.length;
    return count;
  }
}
