import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ChampionService, Champion } from '../../services/champion.service';

function logConfirmationModal(...args: any[]) {
  const fs = (window as any).electronAPI?.fs;
  const path = (window as any).electronAPI?.path;
  const process = (window as any).electronAPI?.process;
  const logPath = path && process ? path.join(process.cwd(), 'confirmation-modal.log') : '';
  const logLine = `[${new Date().toISOString()}] [ConfirmationModal] ` + args.map(arg => (typeof arg === 'object' ? JSON.stringify(arg) : arg)).join(' ') + '\n';
  if (fs && logPath) {
    fs.appendFile(logPath, logLine, (err: any) => {
      if (err) console.error('Erro ao escrever log:', err);
    });
  }
  console.log('[ConfirmationModal]', ...args);
}

interface PickBanPhase {
  team: 'blue' | 'red';
  action: 'ban' | 'pick';
  champion?: Champion;
  playerId?: string;
  playerName?: string;
  locked: boolean;
  timeRemaining: number;
}

interface CustomPickBanSession {
  id: string;
  phase: 'bans' | 'picks' | 'completed';
  currentAction: number;
  extendedTime: number;
  phases: PickBanPhase[];
  blueTeam: any[];
  redTeam: any[];
  currentPlayerIndex: number;
  actions?: any[]; // ✅ NOVO: Ações do draft com dados completos
}

interface TeamSlot {
  player: any;
  champion?: Champion;
  phaseIndex: number;
}

@Component({
  selector: 'app-draft-confirmation-modal',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './draft-confirmation-modal.html',
  styleUrl: './draft-confirmation-modal.scss'
})
export class DraftConfirmationModalComponent implements OnChanges {
  @Input() session: CustomPickBanSession | null = null;
  @Input() currentPlayer: any = null;
  @Input() isVisible: boolean = false;
  @Input() confirmationData: any = null; // ✅ NOVO: Dados de confirmação dos jogadores
  @Output() onClose = new EventEmitter<void>();
  @Output() onConfirm = new EventEmitter<void>();
  @Output() onCancel = new EventEmitter<void>();
  @Output() onEditPick = new EventEmitter<{ playerId: string, phaseIndex: number }>();
  @Output() onRefreshDraft = new EventEmitter<void>(); // ✅ NOVO: Para atualizar estado do draft

  // ✅ NOVO: Estado da confirmação
  isConfirming: boolean = false;
  confirmationMessage: string = '';

  // PROPRIEDADES PARA CACHE
  private _cachedBannedChampions: Champion[] | null = null;
  private _cachedBlueTeamPicks: Champion[] | null = null;
  private _cachedRedTeamPicks: Champion[] | null = null;
  private _cachedBlueTeamByLane: TeamSlot[] | null = null;
  private _cachedRedTeamByLane: TeamSlot[] | null = null;
  private _lastCacheUpdate: number = 0;
  private readonly CACHE_DURATION = 100;

  constructor(private readonly championService: ChampionService) { }

  ngOnChanges(changes: SimpleChanges): void {
    // ✅ NOVO: Invalidar cache quando session ou isVisible mudam
    if (changes['session'] || changes['isVisible']) {
      logConfirmationModal('🔄 [ngOnChanges] Detectada mudança na session ou visibilidade');
      logConfirmationModal('🔄 [ngOnChanges] Changes:', {
        sessionChanged: !!changes['session'],
        visibilityChanged: !!changes['isVisible'],
        newSession: changes['session']?.currentValue ? 'presente' : 'ausente',
        newVisibility: changes['isVisible']?.currentValue
      });
      this.forceRefresh();
    }
  }

  // ✅ NOVO: Verificar se jogador confirmou
  isPlayerConfirmed(player: any): boolean {
    if (!this.confirmationData?.confirmations) {
      return false;
    }

    const playerId = this.getPlayerIdentifier(player);
    return this.confirmationData.confirmations[playerId]?.confirmed === true;
  }

  // ✅ NOVO: Verificar se jogador é bot e foi auto-confirmado
  isPlayerAutoConfirmed(player: any): boolean {
    if (!this.confirmationData?.confirmations) {
      return false;
    }

    const playerId = this.getPlayerIdentifier(player);
    const confirmation = this.confirmationData.confirmations[playerId];
    return confirmation?.confirmed === true && confirmation?.autoConfirmed === true;
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
  getBannedChampions(): Champion[] {
    if (this.isCacheValid() && this._cachedBannedChampions) {
      return this._cachedBannedChampions;
    }

    if (!this.session) return [];

    const bannedChampions = this.session.phases
      .filter(phase => phase.action === 'ban' && phase.champion)
      .map(phase => phase.champion!)
      .filter((champion, index, self) =>
        index === self.findIndex(c => c.id === champion.id)
      );

    this._cachedBannedChampions = bannedChampions;
    this._lastCacheUpdate = Date.now();

    return bannedChampions;
  }

  getTeamPicks(team: 'blue' | 'red'): Champion[] {
    if (team === 'blue' && this.isCacheValid() && this._cachedBlueTeamPicks) {
      return this._cachedBlueTeamPicks;
    }
    if (team === 'red' && this.isCacheValid() && this._cachedRedTeamPicks) {
      return this._cachedRedTeamPicks;
    }

    if (!this.session) return [];

    logConfirmationModal(`🎯 [getTeamPicks] === OBTENDO PICKS DO TIME ${team.toUpperCase()} ===`);

    // ✅ CORREÇÃO: Usar actions em vez de phases para obter dados reais
    let teamPicks: Champion[] = [];

    if (this.session.actions && this.session.actions.length > 0) {
      // Usar dados das actions (fonte de verdade)
      const teamIndex = team === 'blue' ? 1 : 2;

      logConfirmationModal(`🎯 [getTeamPicks] Usando actions - teamIndex procurado: ${teamIndex}`);
      logConfirmationModal(`🎯 [getTeamPicks] Actions disponíveis:`, this.session.actions.map((action: any, index: number) => ({
        index,
        teamIndex: action.teamIndex,
        action: action.action,
        champion: action.champion?.name || 'Sem champion',
        locked: action.locked,
        playerId: action.playerId,
        playerName: action.playerName
      })));

      teamPicks = this.session.actions
        .filter((action: any) => {
          const match = action.teamIndex === teamIndex &&
            action.action === 'pick' &&
            action.champion &&
            action.locked;

          if (match) {
            logConfirmationModal(`🎯 [getTeamPicks] Pick encontrado:`, {
              teamIndex: action.teamIndex,
              champion: action.champion.name,
              playerId: action.playerId,
              playerName: action.playerName
            });
          }

          return match;
        })
        .map((action: any) => action.champion);
    } else {
      // Fallback para phases (pode estar vazio)
      logConfirmationModal(`🎯 [getTeamPicks] Usando phases (fallback)`);
      teamPicks = this.session.phases
        .filter(phase => phase.team === team && phase.action === 'pick' && phase.champion)
        .map(phase => phase.champion!);
    }

    logConfirmationModal(`🎯 [getTeamPicks] Picks finais do time ${team}:`, teamPicks.map(pick => pick.name));

    if (team === 'blue') {
      this._cachedBlueTeamPicks = teamPicks;
    } else {
      this._cachedRedTeamPicks = teamPicks;
    }
    this._lastCacheUpdate = Date.now();

    return teamPicks;
  }

  getTeamBans(team: 'blue' | 'red'): Champion[] {
    if (!this.session) return [];

    // ✅ CORREÇÃO: Usar actions em vez de phases para obter dados reais
    let teamBans: Champion[] = [];

    if (this.session.actions && this.session.actions.length > 0) {
      // Usar dados das actions (fonte de verdade)
      const teamIndex = team === 'blue' ? 1 : 2;
      teamBans = this.session.actions
        .filter((action: any) => {
          return action.teamIndex === teamIndex &&
            action.action === 'ban' &&
            action.champion &&
            action.locked;
        })
        .map((action: any) => action.champion);
    } else {
      // Fallback para phases (pode estar vazio)
      teamBans = this.session.phases
        .filter(phase => phase.team === team && phase.action === 'ban' && phase.champion)
        .map(phase => phase.champion!);
    }

    return teamBans;
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
    if (team === 'blue' && this.isCacheValid() && this._cachedBlueTeamByLane) {
      return this._cachedBlueTeamByLane;
    }
    if (team === 'red' && this.isCacheValid() && this._cachedRedTeamByLane) {
      return this._cachedRedTeamByLane;
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

    if (team === 'blue') {
      this._cachedBlueTeamByLane = organizedTeam;
    } else {
      this._cachedRedTeamByLane = organizedTeam;
    }
    this._lastCacheUpdate = Date.now();

    return organizedTeam;
  }

  private organizeTeamByLanes(teamPlayers: any[], teamPicks: any[]): TeamSlot[] {
    logConfirmationModal('🎯 [organizeTeamByLanes] === CRIANDO SLOTS ===');
    logConfirmationModal('🎯 [organizeTeamByLanes] Entrada:', {
      teamPlayersCount: teamPlayers.length,
      teamPicksCount: teamPicks.length,
      teamPlayers: teamPlayers.map(p => ({
        name: p.summonerName || p.name,
        teamIndex: p.teamIndex,
        assignedLane: p.assignedLane
      })),
      teamPicks: teamPicks.map(p => p.name || 'Unknown pick')
    });

    // ✅ CORREÇÃO CRÍTICA: Associar picks por jogador específico, não por índice
    const slots = teamPlayers.map((player, index) => {
      const phaseIndex = this.getPhaseIndexForPlayer(player) || 0;

      // ✅ NOVO: Encontrar o pick específico deste jogador usando actions
      let playerChampion = undefined;

      if (this.session?.actions) {
        const playerAction = this.session.actions.find((action: any) => {
          return action.action === 'pick' &&
            action.locked &&
            action.champion &&
            this.comparePlayerWithId(player, action.playerId || '');
        });

        if (playerAction) {
          playerChampion = playerAction.champion;
          logConfirmationModal('🎯 [organizeTeamByLanes] Pick encontrado via actions para:', {
            playerName: player.summonerName || player.name,
            championName: playerAction.champion.name,
            actionPlayerId: playerAction.playerId
          });
        }
      }

      // ✅ FALLBACK: Se não encontrou via actions, usar índice (comportamento antigo)
      if (!playerChampion && teamPicks[index]) {
        playerChampion = teamPicks[index];
        logConfirmationModal('🎯 [organizeTeamByLanes] Pick encontrado via índice (fallback) para:', {
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

  confirmFinalDraft(): void {
    logConfirmationModal('✅ [confirmFinalDraft] === CONFIRMANDO DRAFT FINAL ===');

    // ✅ NOVO: Mostrar feedback de carregamento
    this.isConfirming = true;
    this.confirmationMessage = 'Confirmando sua seleção...';

    this.onConfirm.emit();
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

  // ✅ NOVO: Método para obter contagem de confirmações
  getConfirmationCount(): { confirmed: number, total: number } {
    if (!this.confirmationData?.confirmations) {
      return { confirmed: 0, total: 10 };
    }

    const confirmations = Object.values(this.confirmationData.confirmations);
    const confirmed = confirmations.filter((c: any) => c?.confirmed === true).length;
    const total = confirmations.length;

    return { confirmed, total };
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

  // MÉTODO PARA DEBUG DE CLIQUE
  onButtonClick(slot: any): void {
    logConfirmationModal('🎯 [onButtonClick] === BOTÃO CLICADO ===');
    logConfirmationModal('🎯 [onButtonClick] slot completo:', slot);
    logConfirmationModal('🎯 [onButtonClick] player:', {
      id: slot.player?.id,
      summonerName: slot.player?.summonerName,
      name: slot.player?.name,
      gameName: slot.player?.gameName,
      tagLine: slot.player?.tagLine,
      teamIndex: slot.player?.teamIndex
    });
    logConfirmationModal('🎯 [onButtonClick] phaseIndex:', slot.phaseIndex);
    logConfirmationModal('🎯 [onButtonClick] isBot:', this.isPlayerBot(slot.player));
    logConfirmationModal('🎯 [onButtonClick] currentPlayer:', {
      id: this.currentPlayer?.id,
      summonerName: this.currentPlayer?.summonerName,
      gameName: this.currentPlayer?.gameName,
      tagLine: this.currentPlayer?.tagLine
    });

    if (this.isPlayerBot(slot.player)) {
      logConfirmationModal('🎯 [onButtonClick] Confirmando pick de bot');
      this.confirmBotPick(slot.player.id || slot.player.summonerName, slot.phaseIndex);
    } else {
      logConfirmationModal('🎯 [onButtonClick] Iniciando edição de pick humano');
      this.startEditingPick(slot.player.id || slot.player.summonerName, slot.phaseIndex);
    }
  }

  // MÉTODOS PARA EDIÇÃO
  startEditingPick(playerId: string, phaseIndex: number): void {
    logConfirmationModal('🎯 [startEditingPick] === INICIANDO EDIÇÃO ===');
    logConfirmationModal('🎯 [startEditingPick] playerId:', playerId);
    logConfirmationModal('🎯 [startEditingPick] phaseIndex:', phaseIndex);
    logConfirmationModal('🎯 [startEditingPick] currentPlayer:', this.currentPlayer);
    logConfirmationModal('🎯 [startEditingPick] session presente:', !!this.session);

    this.onEditPick.emit({ playerId, phaseIndex });
    logConfirmationModal('🎯 [startEditingPick] Evento onEditPick emitido com sucesso');
  }

  // ✅ NOVO: Método para editar o pick do jogador atual via botão principal
  startEditingCurrentPlayer(): void {
    logConfirmationModal('🎯 [startEditingCurrentPlayer] === INICIANDO EDIÇÃO DO JOGADOR ATUAL ===');

    if (!this.currentPlayer || !this.session) {
      logConfirmationModal('❌ [startEditingCurrentPlayer] currentPlayer ou session não disponível');
      return;
    }

    const currentPlayerFormatted = this.currentPlayer.gameName && this.currentPlayer.tagLine
      ? `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`
      : this.currentPlayer.summonerName || this.currentPlayer.name;

    logConfirmationModal('🔍 [startEditingCurrentPlayer] Buscando pick do jogador:', currentPlayerFormatted);

    // Buscar nas actions em vez de phases
    let playerActionIndex = -1;
    let matchedPlayerId = '';

    // Verificar se há actions na session
    if (this.session.actions && Array.isArray(this.session.actions)) {
      for (let i = 0; i < this.session.actions.length; i++) {
        const action = this.session.actions[i];
        if (action.action === 'pick' && action.champion) {
          logConfirmationModal('🔍 [startEditingCurrentPlayer] Verificando action:', {
            index: i,
            action: action.action,
            playerId: action.playerId,
            playerName: action.playerName,
            champion: action.champion
          });

          if (action.playerId === currentPlayerFormatted ||
            action.playerName === currentPlayerFormatted ||
            this.comparePlayerWithId(this.currentPlayer, action.playerId || '')) {
            playerActionIndex = i;
            matchedPlayerId = action.playerId || action.playerName || currentPlayerFormatted;
            logConfirmationModal('✅ [startEditingCurrentPlayer] Pick encontrado no index:', playerActionIndex);
            break;
          }
        }
      }
    }

    // Se não encontrou nas actions, tentar nas phases (fallback)
    if (playerActionIndex === -1 && this.session.phases) {
      logConfirmationModal('🔍 [startEditingCurrentPlayer] Buscando nas phases como fallback');
      for (let i = 0; i < this.session.phases.length; i++) {
        const phase = this.session.phases[i];
        if (phase.action === 'pick' && phase.champion) {
          if (this.comparePlayerWithId(this.currentPlayer, phase.playerId || '') ||
            phase.playerId === currentPlayerFormatted ||
            phase.playerName === currentPlayerFormatted) {
            playerActionIndex = i;
            matchedPlayerId = phase.playerId || phase.playerName || currentPlayerFormatted;
            break;
          }
        }
      }
    }

    // Se ainda não encontrou, usar fallback final
    if (playerActionIndex === -1) {
      logConfirmationModal('❌ [startEditingCurrentPlayer] Usando fallback final - pick não encontrado');
      matchedPlayerId = this.currentPlayer.id?.toString() || currentPlayerFormatted;
      playerActionIndex = 0;
    }

    logConfirmationModal('🎯 [startEditingCurrentPlayer] Iniciando edição:', {
      playerId: matchedPlayerId,
      phaseIndex: playerActionIndex
    });

    this.startEditingPick(matchedPlayerId, playerActionIndex);
  }

  confirmBotPick(playerId: string, phaseIndex: number): void {
    // Para bots, apenas confirmar (não editar)
    // Pode ser implementado conforme necessário
  }

  // MÉTODOS PARA CACHE
  private invalidateCache(): void {
    this._cachedBannedChampions = null;
    this._cachedBlueTeamPicks = null;
    this._cachedRedTeamPicks = null;
    this._cachedBlueTeamByLane = null;
    this._cachedRedTeamByLane = null;
    this._lastCacheUpdate = Date.now();
  }

  private isCacheValid(): boolean {
    return Date.now() - this._lastCacheUpdate < this.CACHE_DURATION;
  }

  // MÉTODOS AUXILIARES
  onImageError(event: any, champion: Champion): void {
    event.target.src = 'assets/images/champion-placeholder.svg';
  }

  // MÉTODO PARA QUANDO O MODAL SE TORNA VISÍVEL
  onModalShow(): void {
    if (this.isVisible) {
      this.invalidateCache();
    }
  }

  // ✅ NOVO: Método para forçar atualização completa
  forceRefresh(): void {
    logConfirmationModal('🔄 [forceRefresh] Forçando atualização do modal de confirmação');
    this.invalidateCache();
    // Forçar recálculo de todos os dados
    this._cachedBannedChampions = null;
    this._cachedBlueTeamPicks = null;
    this._cachedRedTeamPicks = null;
    this._cachedBlueTeamByLane = null;
    this._cachedRedTeamByLane = null;
    this._lastCacheUpdate = 0; // Forçar recache
    logConfirmationModal('🔄 [forceRefresh] Cache invalidado com sucesso');
  }
}
