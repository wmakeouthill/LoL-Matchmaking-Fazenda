import { Component, EventEmitter, Input, OnInit, OnDestroy, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, firstValueFrom } from 'rxjs';
import { ApiService } from '../../services/api';
import { getChampionKeyById } from '../../utils/champion-data';

interface CustomMatch {
  gameId: number;
  gameCreation: number;
  gameDuration: number;
  participants: any[];
  participantIdentities?: any[];
  teams: any[];
  gameMode: string;
  queueId: number;
}

interface MatchOption {
  match: CustomMatch;
  matchIndex: number;
  winningTeam: 'blue' | 'red' | null;
  formattedDate: string;
  formattedDuration: string;
  ourPlayers: {
    blue: any[];
    red: any[];
  };
  voteCount?: number; // Contador de votos para esta partida
}

// ✅ NOVO: Interface para status de votação dos jogadores
interface PlayerVoteStatus {
  summonerName: string;
  voteStatus: 'pending' | 'voted' | 'declined' | 'timeout';
  votedAt?: string; // ISO timestamp quando votou
  votedFor?: 'blue' | 'red'; // Qual time votou
  isCurrentUser?: boolean; // Se é o usuário logado via LCU
}

@Component({
  selector: 'app-winner-confirmation-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './winner-confirmation-modal.component.html',
  styleUrls: ['./winner-confirmation-modal.component.scss']
})
export class WinnerConfirmationModalComponent implements OnInit, OnDestroy {
  @Input() customMatches: CustomMatch[] = [];
  @Input() currentPlayers: any[] = []; // Jogadores da partida atual
  @Input() currentPlayer: any = null; // ✅ NOVO: Jogador logado via LCU
  @Input() matchId: number | null = null; // ID da partida customizada para votação
  @Output() onConfirm = new EventEmitter<{ match: CustomMatch, winner: 'blue' | 'red' }>();
  @Output() onCancel = new EventEmitter<void>();

  // ✅ NOVO: Propriedades para special user
  isSpecialUser: boolean = false;
  selectedVoteWeight: number = 1;
  currentSummonerName: string = '';

  matchOptions: MatchOption[] = [];
  selectedMatchIndex: number | null = null;
  voteCounts: Map<number, number> = new Map(); // lcuGameId -> voteCount
  myVotedGameId: number | null = null; // LCU game que o usuário votou
  private wsSubscription?: Subscription;

  // ✅ NOVO: Status de votação dos jogadores
  playerVoteStatuses: Map<string, PlayerVoteStatus> = new Map();
  votedCount: number = 0;
  totalPlayers: number = 10;

  constructor(private apiService: ApiService) { }

  ngOnInit() {
    this.processMatches();
    this.setupWebSocketListeners();
    this.loadInitialVotes();
    this.identifyCurrentUser(); // ✅ NOVO: Identificar usuário atual
    this.checkSpecialUserStatus(); // ✅ NOVO: Verificar se é special user
  }

  ngOnDestroy() {
    if (this.wsSubscription) {
      this.wsSubscription.unsubscribe();
    }
  }

  /**
   * ✅ NOVO: Verificar se o usuário atual é special user
   */
  private async checkSpecialUserStatus(): Promise<void> {
    try {
      // Obter nome do summoner atual via LCU
      if (window.electronAPI?.lcu?.getCurrentSummoner) {
        const currentSummoner = await window.electronAPI.lcu.getCurrentSummoner();
        if (currentSummoner) {
          this.currentSummonerName = currentSummoner.displayName || currentSummoner.summonerName || '';

          // Verificar se é special user via API
          const isSpecial = await firstValueFrom(
            this.apiService.checkSpecialUserStatus(this.currentSummonerName)
          );

          this.isSpecialUser = isSpecial || false;

          if (this.isSpecialUser) {
            console.log('🌟 [WinnerModal] Special user detectado:', this.currentSummonerName);
            // Carregar configuração atual do special user
            await this.loadSpecialUserConfig();
          }
        }
      }
    } catch (error) {
      console.error('❌ [WinnerModal] Erro ao verificar special user status:', error);
      this.isSpecialUser = false;
    }
  }

  /**
   * ✅ NOVO: Carregar configuração atual do special user
   */
  private async loadSpecialUserConfig(): Promise<void> {
    try {
      const config = await firstValueFrom(
        this.apiService.getSpecialUserConfig(this.currentSummonerName)
      );

      if (config && config.voteWeight) {
        this.selectedVoteWeight = config.voteWeight;
        console.log('🌟 [WinnerModal] Configuração carregada - peso:', this.selectedVoteWeight);
      }
    } catch (error) {
      console.error('❌ [WinnerModal] Erro ao carregar configuração:', error);
    }
  }

  /**
   * ✅ NOVO: Salvar configuração do special user
   */
  private async saveSpecialUserConfig(): Promise<void> {
    if (!this.isSpecialUser || !this.currentSummonerName) return;

    try {
      await firstValueFrom(
        this.apiService.updateSpecialUserConfig(this.currentSummonerName, {
          voteWeight: this.selectedVoteWeight,
          allowMultipleVotes: false, // Por enquanto, não permitir múltiplos votos
          maxVotes: 1
        })
      );

      console.log('✅ [WinnerModal] Configuração salva - peso:', this.selectedVoteWeight);
    } catch (error) {
      console.error('❌ [WinnerModal] Erro ao salvar configuração:', error);
    }
  }

  /**
   * ✅ NOVO: Handler para mudança de peso do voto
   */
  onVoteWeightChange(): void {
    console.log('🌟 [WinnerModal] Peso do voto alterado para:', this.selectedVoteWeight);
    this.saveSpecialUserConfig();
  }

  private setupWebSocketListeners() {
    this.wsSubscription = this.apiService.onWebSocketMessage().subscribe({
      next: (message) => {
        const { type, data } = message;

        if (type === 'match_vote_update') {
          this.handleVoteUpdate(data);
        } else if (type === 'match_linked') {
          this.handleMatchLinked(data);
        }
      },
      error: (error) => {
        console.error('❌ [WinnerModal] Erro no WebSocket:', error);
      }
    });
  }

  private async loadInitialVotes() {
    if (!this.matchId) return;

    try {
      // Carregar votos atuais do backend
      const votes = await firstValueFrom(this.apiService.getMatchVotes(this.matchId));
      if (votes) {
        this.voteCounts = new Map(Object.entries(votes).map(([k, v]) => [Number(k), Number(v)]));
        this.updateVoteCountsInOptions();
      }
    } catch (error) {
      console.error('❌ [WinnerModal] Erro ao carregar votos:', error);
    }
  }

  private handleVoteUpdate(data: any) {
    console.log('🗳️ [WinnerModal] Atualização de votos recebida:', data);

    if (data.matchId !== this.matchId) return;

    // Atualizar contadores de votos
    if (data.voteCounts) {
      this.voteCounts = new Map(Object.entries(data.voteCounts).map(([k, v]) => [Number(k), Number(v)]));
      this.updateVoteCountsInOptions();
    }
  }

  private handleMatchLinked(data: any) {
    console.log('🔗 [WinnerModal] Partida vinculada:', data);

    if (data.matchId !== this.matchId) return;

    // Fechar modal automaticamente após vinculação
    setTimeout(() => {
      alert(`✅ Partida vinculada automaticamente! Vencedor: Time ${data.winner === 'blue' ? 'Azul' : 'Vermelho'}`);
      this.cancel();
    }, 1000);
  }

  private updateVoteCountsInOptions() {
    this.matchOptions.forEach(option => {
      const voteCount = this.voteCounts.get(option.match.gameId) || 0;
      option.voteCount = voteCount;
    });
  }

  selectMatch(index: number) {
    // ✅ CORREÇÃO: Apenas marcar a seleção, não votar ainda
    this.selectedMatchIndex = index;
    console.log('🎯 [WinnerModal] Partida selecionada:', index, this.matchOptions[index].match.gameId);
  }

  private processMatches() {
    console.log(`🔍 [WinnerModal] processMatches: ${this.customMatches.length} partidas recebidas`);

    this.matchOptions = this.customMatches.map((match, index) => {
      console.log(`🔍 [WinnerModal] Processando partida ${index + 1}:`, {
        gameId: match.gameId,
        participants: match.participants?.length || 0,
        teams: match.teams?.length || 0
      });

      // ✅ NORMALIZAR participants: extrair stats para o nível superior
      const normalizedMatch = this.normalizeMatchData(match);

      const winningTeam = this.getWinningTeam(normalizedMatch);
      const ourPlayers = this.identifyOurPlayers(normalizedMatch);

      return {
        match: normalizedMatch,
        matchIndex: index,
        winningTeam,
        formattedDate: this.formatDate(normalizedMatch.gameCreation),
        formattedDuration: this.formatDuration(normalizedMatch.gameDuration),
        ourPlayers
      };
    });

    console.log(`✅ [WinnerModal] ${this.matchOptions.length} opções de partida criadas`);
  }

  /**
   * Normaliza dados do LCU: extrai stats de participant.stats.* para participant.*
   * Igual ao que match-history faz em mapLCUMatchToModel()
   */
  private normalizeMatchData(match: CustomMatch): CustomMatch {
    if (!match.participants) return match;

    console.log('🔄 [WinnerModal] normalizeMatchData: Normalizando', match.participants.length, 'participants');

    const normalizedParticipants = match.participants.map(p => {
      // Buscar identidade do jogador
      const identity = match.participantIdentities?.find((id: any) =>
        id.participantId === p.participantId
      );

      console.log(`🔍 [WinnerModal] Normalizando participant ${p.participantId}:`, {
        hasStats: !!p.stats,
        statsItem0: p.stats?.item0,
        statsKills: p.stats?.kills,
        lane: p.timeline?.lane || p.lane,
        championId: p.championId
      });

      // Criar display name
      let displayName = `Player ${p.participantId}`;
      if (identity?.player) {
        const player = identity.player;
        if (player.gameName && player.tagLine) {
          displayName = `${player.gameName}#${player.tagLine}`;
        } else if (player.summonerName) {
          displayName = player.summonerName;
        }
      }

      // ✅ EXTRAIR stats do objeto .stats para o nível superior
      return {
        ...p,
        summonerName: displayName,
        puuid: identity?.player?.puuid || '',
        // Stats principais
        kills: p.stats?.kills || 0,
        deaths: p.stats?.deaths || 0,
        assists: p.stats?.assists || 0,
        champLevel: p.stats?.champLevel || 1,
        // Itens
        item0: p.stats?.item0 || 0,
        item1: p.stats?.item1 || 0,
        item2: p.stats?.item2 || 0,
        item3: p.stats?.item3 || 0,
        item4: p.stats?.item4 || 0,
        item5: p.stats?.item5 || 0,
        item6: p.stats?.item6 || 0,
        // Stats expandidas
        goldEarned: p.stats?.goldEarned || 0,
        totalDamageDealtToChampions: p.stats?.totalDamageDealtToChampions || 0,
        totalMinionsKilled: p.stats?.totalMinionsKilled || 0,
        neutralMinionsKilled: p.stats?.neutralMinionsKilled || 0,
        visionScore: p.stats?.visionScore || 0,
        // Lane detection (já vem no participant)
        teamPosition: p.timeline?.lane || p.lane || 'UNKNOWN',
        role: p.timeline?.role || p.role || 'UNKNOWN',
        individualPosition: p.individualPosition || '',
        // Summoner spells
        summoner1Id: p.spell1Id || 0,
        summoner2Id: p.spell2Id || 0
      };
    });

    return {
      ...match,
      participants: normalizedParticipants
    };
  }

  private getWinningTeam(match: CustomMatch): 'blue' | 'red' | null {
    if (!match.teams || match.teams.length < 2) return null;

    const blueTeam = match.teams.find(t => t.teamId === 100);
    const redTeam = match.teams.find(t => t.teamId === 200);

    if (blueTeam?.win === 'Win' || blueTeam?.win === true) return 'blue';
    if (redTeam?.win === 'Win' || redTeam?.win === true) return 'red';

    return null;
  }

  private identifyOurPlayers(match: CustomMatch) {
    const blue: any[] = [];
    const red: any[] = [];

    if (!match.participants) return { blue, red };

    // ✅ CORREÇÃO: Para o modal de votação, queremos TODOS os 10 jogadores da partida LCU
    // Não filtrar por currentPlayers - mostrar composição completa do time azul vs vermelho
    match.participants.forEach(participant => {
      if (participant.teamId === 100) {
        blue.push(participant);
      } else if (participant.teamId === 200) {
        red.push(participant);
      }
    });

    console.log(`🔍 [WinnerModal] Time Azul: ${blue.length} jogadores, Time Vermelho: ${red.length} jogadores`);

    return { blue, red };
  }

  private formatDate(timestamp: number): string {
    const date = new Date(timestamp);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);

    if (diffMins < 60) {
      return `${diffMins} minutos atrás`;
    } else if (diffMins < 1440) { // 24 hours
      const hours = Math.floor(diffMins / 60);
      return `${hours} hora${hours > 1 ? 's' : ''} atrás`;
    } else {
      return date.toLocaleDateString('pt-BR', {
        day: '2-digit',
        month: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      });
    }
  }

  private formatDuration(seconds: number): string {
    const minutes = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${minutes}:${secs.toString().padStart(2, '0')}`;
  }

  async confirmSelection() {
    console.log('🔍 [DEBUG 1/10] ===== confirmSelection INICIADO =====');
    console.log('🔍 [DEBUG 2/10] selectedMatchIndex:', this.selectedMatchIndex);
    console.log('🔍 [DEBUG 3/10] matchOptions.length:', this.matchOptions?.length);
    console.log('🔍 [DEBUG 4/10] matchId:', this.matchId);

    if (this.selectedMatchIndex === null) {
      console.log('❌ [DEBUG] FALHOU: selectedMatchIndex é null');
      alert('Por favor, selecione uma partida primeiro.');
      return;
    }

    console.log('✅ [DEBUG 5/10] selectedMatchIndex válido:', this.selectedMatchIndex);
    const selectedOption = this.matchOptions[this.selectedMatchIndex];
    console.log('🔍 [DEBUG 6/10] selectedOption:', selectedOption);

    if (!selectedOption.winningTeam) {
      console.log('❌ [DEBUG] FALHOU: sem winningTeam');
      alert('A partida selecionada não possui um vencedor identificado.');
      return;
    }

    console.log('✅ [DEBUG 7/10] winningTeam válido:', selectedOption.winningTeam);

    // ✅ VERIFICAÇÃO: Apenas verificar se currentPlayer está disponível (sem confirmação crítica)
    if (!this.currentPlayer) {
      console.error('❌ [WinnerModal] currentPlayer não disponível');
      alert('Erro: Jogador não identificado. Não é possível votar.');
      return;
    }

    console.log('✅ [WinnerModal] currentPlayer disponível, prosseguindo com votação...');

    // ✅ NOVO: Garantir que a configuração do special user esteja salva antes de votar
    if (this.isSpecialUser) {
      console.log('🌟 [WinnerModal] Special user detectado, salvando configuração antes de votar...');
      await this.saveSpecialUserConfig();
    }

    // ✅ VOTAÇÃO: Enviar voto ao backend
    if (!this.matchId) {
      console.warn('⚠️ [DEBUG 8/10] FALHOU: Match ID não fornecido, pulando votação');
      // Apenas emitir confirmação sem votar
      this.onConfirm.emit({
        match: selectedOption.match,
        winner: selectedOption.winningTeam
      });
      return;
    }

    console.log('✅ [DEBUG 9/10] matchId válido:', this.matchId);
    const lcuGameId = selectedOption.match.gameId;
    console.log('🔍 [DEBUG 10/10] lcuGameId:', lcuGameId);

    try {
      console.log('🗳️ [WinnerModal] >>> CHAMANDO voteForMatch() <<<');
      console.log('🗳️ [WinnerModal] Parametros:', {
        matchId: this.matchId,
        lcuGameId: lcuGameId
      });

      // Enviar voto ao backend
      const voteResponse = await firstValueFrom(this.apiService.voteForMatch(this.matchId, lcuGameId));
      console.log('✅ [WinnerModal] <<< voteForMatch() RETORNOU <<<');
      this.myVotedGameId = lcuGameId;

      console.log('✅ [WinnerModal] Voto registrado para LCU game:', lcuGameId);
      console.log('📊 [WinnerModal] Resposta do voto:', voteResponse);

      // ✅ Verificar se deve finalizar a partida (shouldLink = true)
      if (voteResponse?.shouldLink === true) {
        if (voteResponse?.specialUserVote === true) {
          console.log('🌟 [WinnerModal] SPECIAL USER finalizou a partida!');

          // Fechar modal automaticamente
          setTimeout(() => {
            alert(`🌟 ${voteResponse.voterName || 'Você'} é um SPECIAL USER!\nA partida foi finalizada automaticamente.`);
            this.onConfirm.emit({
              match: selectedOption.match,
              winner: (selectedOption.winningTeam || 'blue') as 'blue' | 'red'
            });
          }, 500);
        } else {
          console.log('🎯 [WinnerModal] Votos suficientes atingidos! Finalizando automaticamente...');

          // Fechar modal automaticamente
          setTimeout(() => {
            alert('🎯 Votos suficientes atingidos! A partida foi finalizada automaticamente.');
            this.onConfirm.emit({
              match: selectedOption.match,
              winner: (selectedOption.winningTeam || 'blue') as 'blue' | 'red'
            });
          }, 500);
        }
      } else {
        // Voto registrado, mas aguardando mais votos
        if (voteResponse?.specialUserVote === true) {
          console.log('⏳ [WinnerModal] Special user votou, mas peso insuficiente. Aguardando mais votos...');
          const remainingVotes = 6 - (voteResponse.voteCount || 1);
          alert(`🌟 ${voteResponse.voterName || 'Você'} é um SPECIAL USER!\nSeu voto valeu ${voteResponse.voteWeight || 1} voto(s).\nAguardando mais ${remainingVotes} votos para finalizar automaticamente.`);
        } else {
          console.log('⏳ [WinnerModal] Voto registrado, aguardando mais votos...');
          const remainingVotes = 6 - (voteResponse.voteCount || 1);
          alert(`✅ Seu voto foi registrado!\nAguardando mais ${remainingVotes} votos para finalizar automaticamente.`);
        }

        // Não fechar o modal, deixar aberto para outros votarem
      }
    } catch (error: any) {
      console.error('❌ [WinnerModal] Erro ao votar:', error);
      console.error('❌ [WinnerModal] Erro detalhado:', {
        message: error?.message,
        status: error?.status,
        statusText: error?.statusText,
        error: error?.error,
        stack: error?.stack
      });
      alert('Erro ao registrar voto: ' + (error?.message || error?.statusText || 'Erro desconhecido'));
    }
  }

  cancel() {
    this.onCancel.emit();
  }


  getChampionName(championId: number): string {
    const key = getChampionKeyById(championId);

    // Convert key back to readable name (e.g., "MissFortune" -> "Miss Fortune")
    return key
      .replace(/([A-Z])/g, ' $1') // Add space before capitals
      .trim() // Remove leading space
      .replace(/\s+/g, ' '); // Normalize spaces
  }

  getPlayersByTeam(match: CustomMatch, teamId: number) {
    if (!match.participants) return [];
    const players = match.participants.filter(p => p.teamId === teamId);
    console.log(`🔍 [WinnerModal] getPlayersByTeam(${teamId}): ${players.length} jogadores`, players.map(p => p.summonerName || p.championId));
    return players;
  }

  getChampionIconUrl(championId: number): string {
    const key = getChampionKeyById(championId);
    return `https://ddragon.leagueoflegends.com/cdn/14.1.1/img/champion/${key}.png`;
  }

  getChampionKeyById(championId: number): string {
    return getChampionKeyById(championId);
  }

  getItemIconUrl(itemId: number): string {
    return `https://ddragon.leagueoflegends.com/cdn/14.1.1/img/item/${itemId}.png`;
  }

  getPlayerItems(player: any): number[] {
    return [
      player.item0 || 0,
      player.item1 || 0,
      player.item2 || 0,
      player.item3 || 0,
      player.item4 || 0,
      player.item5 || 0,
      player.item6 || 0 // Trinket
    ];
  }

  getKDAFormattedRatio(player: any): string {
    if (!player.deaths || player.deaths === 0) {
      return 'Perfect';
    }
    const kda = (player.kills + player.assists) / player.deaths;
    return kda.toFixed(2);
  }

  formatNumber(num: number): string {
    if (!num) return '0';
    if (num >= 1000) {
      return (num / 1000).toFixed(1) + 'k';
    }
    return num.toString();
  }

  getPlayerTooltip(player: any): string {
    const items = this.getPlayerItems(player)
      .filter(id => id > 0)
      .map(id => `Item ${id}`)
      .join(', ');

    return `${player.summonerName || player.riotIdGameName}
Champion: ${this.getChampionName(player.championId)} (Nv. ${player.champLevel})
KDA: ${player.kills}/${player.deaths}/${player.assists} (${this.getKDAFormattedRatio(player)})
Dano: ${this.formatNumber(player.totalDamageDealtToChampions)}
Gold: ${this.formatNumber(player.goldEarned)}
CS: ${player.totalMinionsKilled + player.neutralMinionsKilled}
Items: ${items || 'Nenhum'}`;
  }

  // ========== LANE ORGANIZATION METHODS (From match-history) ==========

  organizeTeamByLanes(team: any[] | undefined): { [lane: string]: any } {
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

    // First pass: assign players to their detected lanes
    const assignedPlayers = new Set();
    team.forEach((participant) => {
      const lane = this.getParticipantLane(participant);

      if (organizedTeam[lane] === null && !assignedPlayers.has(participant.puuid)) {
        organizedTeam[lane] = participant;
        assignedPlayers.add(participant.puuid);
      }
    });

    // Second pass: fill empty lanes with remaining players
    const unassignedPlayers = team.filter(participant => !assignedPlayers.has(participant.puuid));
    const emptyLanes = Object.keys(organizedTeam).filter(lane => organizedTeam[lane] === null);

    unassignedPlayers.forEach((participant, index) => {
      if (index < emptyLanes.length) {
        organizedTeam[emptyLanes[index]] = participant;
        assignedPlayers.add(participant.puuid);
      }
    });

    // Final pass: ensure all players are assigned
    const lanes = ['TOP', 'JUNGLE', 'MIDDLE', 'ADC', 'SUPPORT'];
    lanes.forEach((lane, index) => {
      if (organizedTeam[lane] === null && team.length > index) {
        const playerToAssign = team[index];
        if (!assignedPlayers.has(playerToAssign.puuid)) {
          organizedTeam[lane] = playerToAssign;
          assignedPlayers.add(playerToAssign.puuid);
        }
      }
    });

    return organizedTeam;
  }

  private getParticipantLane(participant: any): string {
    const lane = participant.teamPosition || participant.lane || 'UNKNOWN';
    const role = participant.role || 'UNKNOWN';

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
        return 'ADC';
      case 'UTILITY':
        return 'SUPPORT';
      default:
        // Fallback: detect by summoner spells (Smite = Jungle)
        if (participant.summoner1Id === 11 || participant.summoner2Id === 11) {
          return 'JUNGLE';
        }
        return 'MIDDLE'; // Default fallback
    }
  }

  getLaneIcon(lane: string): string {
    const icons: { [key: string]: string } = {
      'TOP': '🛡️',
      'JUNGLE': '🌳',
      'MIDDLE': '⚡',
      'ADC': '🏹',
      'SUPPORT': '💚'
    };
    return icons[lane] || '❓';
  }

  getLaneName(lane: string): string {
    const names: { [key: string]: string } = {
      'TOP': 'Topo',
      'JUNGLE': 'Selva',
      'MIDDLE': 'Meio',
      'ADC': 'Atirador',
      'SUPPORT': 'Suporte'
    };
    return names[lane] || lane;
  }

  getParticipantKDARatio(participant: any): number {
    if (!participant || participant.deaths === 0) {
      return (participant?.kills || 0) + (participant?.assists || 0);
    }
    return ((participant.kills || 0) + (participant.assists || 0)) / participant.deaths;
  }

  getParticipantItems(participant: any): number[] {
    if (!participant) return [0, 0, 0, 0, 0, 0];

    const items = [
      participant.item0 || 0,
      participant.item1 || 0,
      participant.item2 || 0,
      participant.item3 || 0,
      participant.item4 || 0,
      participant.item5 || 0
    ];

    // Debug apenas se items estiver vazio
    if (items.every(i => i === 0)) {
      console.log('🔍 [WinnerModal] getParticipantItems: SEM ITENS!', {
        participant,
        hasStats: !!participant.stats,
        statsItem0: participant.stats?.item0
      });
    }

    return items;
  }

  getChampionImageUrl(championId: number): string {
    const key = getChampionKeyById(championId);
    return `https://ddragon.leagueoflegends.com/cdn/14.1.1/img/champion/${key}.png`;
  }

  getItemImageUrl(itemId: number): string {
    return `https://ddragon.leagueoflegends.com/cdn/14.1.1/img/item/${itemId}.png`;
  }

  // ✅ CORRIGIDO: Verificar se o participante é o jogador logado via LCU
  isPlayerInOurMatch(participant: any): boolean {
    if (!this.currentPlayer) {
      return false;
    }

    // Construir nome completo do jogador logado
    const currentPlayerName = (this.currentPlayer.summonerName || this.currentPlayer.gameName || '').toLowerCase().trim();
    const currentPlayerTagLine = this.currentPlayer.tagLine || '';
    const fullPlayerName = currentPlayerTagLine
      ? `${currentPlayerName}#${currentPlayerTagLine}`.toLowerCase()
      : currentPlayerName;

    // Construir nome completo do participante
    const participantName = (participant.summonerName || participant.riotIdGameName || '').toLowerCase().trim();

    // Comparar por nome completo (gameName#tagLine) ou apenas gameName
    return participantName === fullPlayerName || participantName === currentPlayerName;
  }

  // ✅ NOVO: Métodos para status e identificação dos jogadores

  /**
   * Identifica o usuário atual via LCU
   */
  private identifyCurrentUser(): void {
    if (this.currentPlayer) {
      console.log('🔍 [WinnerModal] Usuário atual identificado via LCU:', {
        displayName: this.currentPlayer.displayName,
        summonerName: this.currentPlayer.summonerName,
        gameName: this.currentPlayer.gameName,
        tagLine: this.currentPlayer.tagLine
      });

      this.markCurrentUserInPlayers(this.currentPlayer);
    } else {
      console.log('⚠️ [WinnerModal] Usuário atual não disponível via LCU');
    }
  }

  /**
   * Marca o jogador atual nos dados da partida
   */
  private markCurrentUserInPlayers(currentUser: any): void {
    this.currentPlayers.forEach(player => {
      if (this.isPlayerCurrentUser(player, currentUser)) {
        player.isCurrentUser = true;
        console.log('✅ [WinnerModal] Jogador marcado como usuário atual:', player.summonerName);
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
  getPlayerVoteStatus(summonerName: string): 'pending' | 'voted' | 'declined' | 'timeout' {
    const status = this.playerVoteStatuses.get(summonerName);
    return status?.voteStatus || 'pending';
  }

  /**
   * Obtém o ícone de status de votação
   */
  getVoteStatusIcon(player: any): string {
    const status = this.getPlayerVoteStatus(player.summonerName);
    switch (status) {
      case 'voted': return '✅';
      case 'declined': return '❌';
      case 'timeout': return '⏰';
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
    this.currentPlayers.forEach(player => {
      if (this.getPlayerVoteStatus(player.summonerName) === 'voted') {
        voted++;
      }
    });

    return {
      voted,
      total: this.currentPlayers.length
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
}
