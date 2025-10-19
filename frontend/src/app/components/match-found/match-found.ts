import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ProfileIconService } from '../../services/profile-icon.service';
import { Observable, of } from 'rxjs';
import { BotService } from '../../services/bot.service';

export interface MatchFoundData {
  matchId: number;
  playerSide: 'blue' | 'red';
  teammates: PlayerInfo[];
  enemies: PlayerInfo[];
  averageMMR: {
    yourTeam: number;
    enemyTeam: number;
  };
  estimatedGameDuration: number;
  phase: 'accept' | 'draft' | 'in_game';
  acceptTimeout?: number; // ✅ CORREÇÃO: Compatibilidade com dados antigos
  acceptanceTimer?: number; // ✅ NOVO: Timer em segundos do backend
  acceptanceDeadline?: string; // ✅ NOVO: Deadline ISO string
  teamStats?: any; // ✅ NOVO: Estatísticas dos times
  balancingInfo?: any; // ✅ NOVO: Informações de balanceamento
  acceptedCount?: number; // ✅ NOVO: Contador de jogadores que aceitaram
  totalPlayers?: number; // ✅ NOVO: Total de jogadores na partida
}

export interface PlayerInfo {
  id: number;
  summonerName: string;
  mmr: number;
  primaryLane: string;
  secondaryLane: string;
  assignedLane: string;
  teamIndex?: number; // ✅ NOVO: Índice para o draft (0-4)
  isAutofill: boolean;
  riotIdGameName?: string;
  riotIdTagline?: string;
  profileIconId?: number;
  // ✅ NOVO: Status de aceitação do jogador
  acceptanceStatus?: 'pending' | 'accepted' | 'declined' | 'timeout';
  acceptedAt?: string; // ISO timestamp quando aceitou
  isCurrentUser?: boolean; // Se é o usuário logado via LCU
}

// ✅ DESABILITADO: Salvamento de logs em arquivo (por solicitação do usuário)
function logMatchFound(...args: any[]) {
  // Apenas console.log para debug no DevTools
  console.log('[MatchFound]', ...args);
}

@Component({
  selector: 'app-match-found',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './match-found.html',
  styleUrl: './match-found.scss'
})
export class MatchFoundComponent implements OnInit, OnDestroy, OnChanges {
  @Input() matchData: MatchFoundData | null = null;
  @Input() isVisible = false;
  @Output() acceptMatch = new EventEmitter<number>();
  @Output() declineMatch = new EventEmitter<number>();

  acceptTimeLeft = 30;
  sortedBlueTeam: PlayerInfo[] = [];
  sortedRedTeam: PlayerInfo[] = [];
  private countdownTimer?: number;
  isTimerUrgent = false;

  private readonly playerIconMap = new Map<string, number>();

  constructor(private readonly profileIconService: ProfileIconService, public botService: BotService) { }

  ngOnInit() {
    if (this.matchData && this.matchData.phase === 'accept') {
      this.startAcceptCountdown();
    }
    this.updateSortedTeams();
    // ✅ NOVO: Escutar atualizações de timer do backend
    this.setupTimerListener();
    // ✅ NOVO: Identificar usuário atual via LCU
    this.identifyCurrentUser();
  }

  ngOnChanges(changes: SimpleChanges) {
    // ✅ CORREÇÃO CRÍTICA: Só reiniciar timer se for uma nova partida REAL
    if (changes['matchData']?.currentValue) {
      const previousMatchData = changes['matchData'].previousValue;
      const currentMatchData = changes['matchData'].currentValue;

      const previousMatchId = previousMatchData?.matchId;
      const currentMatchId = currentMatchData?.matchId;

      logMatchFound('🎮 [MatchFound] === ngOnChanges CHAMADO ===');
      logMatchFound('🎮 [MatchFound] MatchId anterior:', previousMatchId);
      logMatchFound('🎮 [MatchFound] MatchId atual:', currentMatchId);
      logMatchFound('🎮 [MatchFound] Timer ativo:', !!this.countdownTimer);
      logMatchFound('🎮 [MatchFound] Accept time atual:', this.acceptTimeLeft);

      // ✅ CORREÇÃO: Verificações mais rigorosas para evitar reprocessamento
      const isExactSameData = previousMatchData && currentMatchData &&
        JSON.stringify(previousMatchData) === JSON.stringify(currentMatchData);

      if (isExactSameData) {
        logMatchFound('🎮 [MatchFound] Dados idênticos - ignorando ngOnChanges');
        return;
      }

      // ✅ CORREÇÃO: Só processar se realmente é uma nova partida
      const isNewMatch = previousMatchId !== currentMatchId && currentMatchId !== undefined;
      const isFirstTime = !previousMatchId && currentMatchId && !this.countdownTimer;

      logMatchFound('🎮 [MatchFound] Análise de mudança:', {
        isNewMatch,
        isFirstTime,
        sameId: previousMatchId === currentMatchId,
        hasTimer: !!this.countdownTimer
      });

      if (isNewMatch || isFirstTime) {
        logMatchFound('🎮 [MatchFound] ✅ NOVA PARTIDA CONFIRMADA - configurando timer');

        // ✅ CORREÇÃO: Limpar timer anterior se existir
        if (this.countdownTimer) {
          logMatchFound('🎮 [MatchFound] Limpando timer anterior');
          clearInterval(this.countdownTimer);
          this.countdownTimer = undefined;
        }

        // ✅ CORREÇÃO: Configurar timer apenas se backend não está controlando
        if (this.matchData && this.matchData.phase === 'accept') {
          // ✅ CORREÇÃO: Usar acceptanceTimer do backend primeiro, depois acceptTimeout como fallback
          const backendTimer = this.matchData.acceptanceTimer || this.matchData.acceptTimeout || 30;

          logMatchFound('🎮 [MatchFound] Timer recebido do backend:', backendTimer);
          this.acceptTimeLeft = backendTimer;
          this.isTimerUrgent = this.acceptTimeLeft <= 10;

          // ✅ CORREÇÃO: Timer local apenas como fallback após 2 segundos
          setTimeout(() => {
            const expectedTimer = this.matchData?.acceptanceTimer || this.matchData?.acceptTimeout || 30;
            if (this.acceptTimeLeft === expectedTimer) {
              logMatchFound('🎮 [MatchFound] Backend não enviou timer, iniciando timer local');
              this.startAcceptCountdown();
            }
          }, 2000);
        }

        this.updateSortedTeams();
        // ✅ NOVO: Re-identificar usuário atual quando dados da partida mudarem
        this.identifyCurrentUser();
      } else {
        logMatchFound('🎮 [MatchFound] ❌ MESMA PARTIDA - ignorando ngOnChanges');
        logMatchFound('🎮 [MatchFound] Motivo: previousMatchId =', previousMatchId, ', currentMatchId =', currentMatchId);
      }
    }
  }

  ngOnDestroy() {
    logMatchFound('🧹 [MatchFound] Destruindo componente - limpando recursos');

    // ✅ CORREÇÃO: Garantir que timer local não existe (não deve existir)
    if (this.countdownTimer) {
      logMatchFound('⚠️ [MatchFound] Timer local encontrado - removendo');
      clearInterval(this.countdownTimer);
      this.countdownTimer = undefined;
    }

    // ✅ CORREÇÃO: Remover listener de timer do backend
    document.removeEventListener('matchTimerUpdate', this.onTimerUpdate);

    logMatchFound('✅ [MatchFound] Recursos limpos com sucesso');
  }

  private updateSortedTeams(): void {
    logMatchFound('🎯 [MatchFound] === updateSortedTeams CHAMADO ===');
    logMatchFound('🎯 [MatchFound] matchData presente:', !!this.matchData);

    if (!this.matchData) {
      logMatchFound('🎯 [MatchFound] matchData é null - limpando times');
      this.sortedBlueTeam = [];
      this.sortedRedTeam = [];
      return;
    }

    logMatchFound('🎯 [MatchFound] Dados do matchData:', {
      matchId: this.matchData.matchId,
      playerSide: this.matchData.playerSide,
      teammatesCount: this.matchData.teammates?.length || 0,
      enemiesCount: this.matchData.enemies?.length || 0
    });

    const blueTeamPlayers = this.getBlueTeamPlayers();
    const redTeamPlayers = this.getRedTeamPlayers();

    logMatchFound('🎯 [MatchFound] Blue team players:', blueTeamPlayers.map(p => ({
      name: p.summonerName,
      assignedLane: p.assignedLane,
      teamIndex: p.teamIndex,
      isAutofill: p.isAutofill
    })));

    logMatchFound('🎯 [MatchFound] Red team players:', redTeamPlayers.map(p => ({
      name: p.summonerName,
      assignedLane: p.assignedLane,
      teamIndex: p.teamIndex,
      isAutofill: p.isAutofill
    })));

    this.sortedBlueTeam = this.getSortedPlayersByLane(blueTeamPlayers);
    this.sortedRedTeam = this.getSortedPlayersByLane(redTeamPlayers);

    logMatchFound('🎯 [MatchFound] Times ordenados:', {
      blueTeam: this.sortedBlueTeam.map(p => ({ name: p.summonerName, lane: p.assignedLane })),
      redTeam: this.sortedRedTeam.map(p => ({ name: p.summonerName, lane: p.assignedLane }))
    });
  }

  // ✅ NOVO: Configurar listener para atualizações de timer do backend
  private setupTimerListener(): void {
    document.addEventListener('matchTimerUpdate', this.onTimerUpdate);
  }

  // ✅ CORREÇÃO: Handler para atualizações de timer do backend - ÚNICA fonte de verdade
  private readonly onTimerUpdate = (event: any): void => {
    if (event.detail && this.matchData) {
      logMatchFound('⏰ [MatchFound] Timer atualizado pelo backend:', event.detail);

      // Verificar se a atualização é para esta partida
      if (event.detail.matchId && event.detail.matchId !== this.matchData.matchId) {
        logMatchFound('⏰ [MatchFound] Timer para partida diferente - ignorando');
        return;
      }

      // ✅ CORREÇÃO: Backend é a ÚNICA fonte de verdade - sempre atualizar
      const newTimeLeft = event.detail.timeLeft;
      const oldTimeLeft = this.acceptTimeLeft;

      logMatchFound(`⏰ [MatchFound] Timer do backend: ${oldTimeLeft}s → ${newTimeLeft}s`);
      this.acceptTimeLeft = newTimeLeft;
      this.isTimerUrgent = event.detail.isUrgent || newTimeLeft <= 10;

      // ✅ CORREÇÃO: Garantir que timer local não existe
      if (this.countdownTimer) {
        logMatchFound('⏰ [MatchFound] Removendo timer local - backend é a fonte de verdade');
        clearInterval(this.countdownTimer);
        this.countdownTimer = undefined;
      }

      // Auto-decline se tempo esgotar
      if (this.acceptTimeLeft <= 0) {
        logMatchFound('⏰ [MatchFound] Timer expirou via backend - auto-decline');
        this.onDeclineMatch();
      }
    }
  }

  /**
   * Obtém a URL do ícone de perfil para um jogador
   */
  getPlayerProfileIconUrl(player: PlayerInfo): Observable<string> {
    const identifier = (player.riotIdGameName && player.riotIdTagline)
      ? `${player.riotIdGameName}#${player.riotIdTagline}`
      : player.summonerName;
    return this.profileIconService.getProfileIconUrl(identifier);
  }

  /**
   * Retorna o Observable da URL do ícone de perfil se for humano, ou null se for bot
   */
  getPlayerProfileIconUrlIfHuman(player: PlayerInfo): Observable<string | null> {
    // Checa se é bot pelo nome
    if (this.botService.isBot(player)) {
      return of(null);
    }
    const identifier = (player.riotIdGameName && player.riotIdTagline)
      ? `${player.riotIdGameName}#${player.riotIdTagline}`
      : player.summonerName;
    return this.profileIconService.getProfileIconUrl(identifier);
  }

  /**
   * Handler para erro de carregamento de imagem de perfil
   */
  onProfileIconError(event: Event, player: PlayerInfo): void {
    this.profileIconService.onProfileIconError(event, player.profileIconId);
  }

  private startAcceptCountdown(): void {
    // ✅ CORREÇÃO: REMOVIDO - Timer local não é mais necessário
    // O backend é a única fonte de verdade para o timer
    logMatchFound('⏰ [MatchFound] Timer local REMOVIDO - usando apenas timer do backend');

    // ✅ CORREÇÃO: Inicializar com valor do backend
    const backendTimer = this.matchData?.acceptanceTimer || this.matchData?.acceptTimeout || 30;
    this.acceptTimeLeft = typeof backendTimer === 'number' ? backendTimer : 30;
    this.isTimerUrgent = this.acceptTimeLeft <= 10;

    logMatchFound('⏰ [MatchFound] Timer inicializado com valor do backend:', this.acceptTimeLeft, 'segundos');
  }

  onAcceptMatch(): void {
    if (this.matchData) {
      logMatchFound('✅ [MatchFound] Emitindo aceitação para:', this.matchData.matchId);
      this.acceptMatch.emit(this.matchData.matchId);

      // ✅ CORREÇÃO: Garantir que timer local não existe (não deve existir)
      if (this.countdownTimer) {
        logMatchFound('⚠️ [MatchFound] Timer local encontrado ao aceitar - removendo');
        clearInterval(this.countdownTimer);
        this.countdownTimer = undefined;
      }
    }
  }

  onDeclineMatch(): void {
    if (this.matchData) {
      logMatchFound('❌ [MatchFound] Emitindo recusa para:', this.matchData.matchId);
      this.declineMatch.emit(this.matchData.matchId);

      // ✅ CORREÇÃO: Garantir que timer local não existe (não deve existir)
      if (this.countdownTimer) {
        logMatchFound('⚠️ [MatchFound] Timer local encontrado ao recusar - removendo');
        clearInterval(this.countdownTimer);
        this.countdownTimer = undefined;
      }
    }
  }

  getLaneName(lane: string): string {
    if (!lane) {
      return 'Desconhecido';
    }

    // ✅ CORREÇÃO: Normalizar lane para minúsculas e mapear para nome
    const normalizedLane = lane.toLowerCase().trim();
    const mappedLane = normalizedLane === 'adc' ? 'bot' : normalizedLane;

    const names: { [key: string]: string } = {
      'top': 'Topo',
      'jungle': 'Selva',
      'mid': 'Meio',
      'bot': 'Atirador',
      'support': 'Suporte',
      'fill': 'Preenchimento'
    };

    const name = names[mappedLane];
    return name || lane;
  }

  getLaneIcon(lane: string): string {
    if (!lane) {
      return '❓';
    }

    // ✅ CORREÇÃO: Normalizar lane para minúsculas e mapear para ícone
    const normalizedLane = lane.toLowerCase().trim();
    const mappedLane = normalizedLane === 'adc' ? 'bot' : normalizedLane;

    const icons: { [key: string]: string } = {
      'top': '⚔️',
      'jungle': '🌲',
      'mid': '⚡',
      'bot': '🏹',
      'support': '🛡️',
      'fill': '🎲'
    };

    const icon = icons[mappedLane];
    return icon || '❓';
  }

  getAssignedLaneDisplay(player: PlayerInfo): string {
    logMatchFound('🎯 [MatchFound] getAssignedLaneDisplay chamado para:', {
      name: player.summonerName,
      assignedLane: player.assignedLane,
      isAutofill: player.isAutofill,
      teamIndex: player.teamIndex
    });

    if (!player.assignedLane) {
      console.warn('⚠️ [MatchFound] assignedLane está vazio para:', player.summonerName);
      return '❓ Desconhecido';
    }

    if (player.isAutofill) {
      return `${this.getLaneIcon(player.assignedLane)} ${this.getLaneName(player.assignedLane)} (Auto)`;
    }
    return `${this.getLaneIcon(player.assignedLane)} ${this.getLaneName(player.assignedLane)}`;
  }

  getLanePreferencesDisplay(player: PlayerInfo): string {
    const primary = `${this.getLaneIcon(player.primaryLane)} ${this.getLaneName(player.primaryLane)}`;
    const secondary = `${this.getLaneIcon(player.secondaryLane)} ${this.getLaneName(player.secondaryLane)}`;
    return `${primary} • ${secondary}`;
  }

  /**
   * Retorna o tipo de badge de lane (primary, secondary, autofill)
   */
  getLaneBadgeType(player: PlayerInfo): 'primary' | 'secondary' | 'autofill' | null {
    if (!player.assignedLane) return null;

    const assignedLane = player.assignedLane.toLowerCase().trim();
    const primaryLane = (player.primaryLane || '').toLowerCase().trim();
    const secondaryLane = (player.secondaryLane || '').toLowerCase().trim();

    // Normalizar lanes (adc -> bot)
    const normalizedAssigned = assignedLane === 'adc' ? 'bot' : assignedLane;
    const normalizedPrimary = primaryLane === 'adc' ? 'bot' : primaryLane;
    const normalizedSecondary = secondaryLane === 'adc' ? 'bot' : secondaryLane;

    if (normalizedAssigned === normalizedPrimary) {
      return 'primary';
    } else if (normalizedAssigned === normalizedSecondary) {
      return 'secondary';
    } else {
      return 'autofill';
    }
  }

  /**
   * Retorna o texto do badge
   */
  getLaneBadgeText(type: 'primary' | 'secondary' | 'autofill' | null): string {
    switch (type) {
      case 'primary': return '1ª Lane';
      case 'secondary': return '2ª Lane';
      case 'autofill': return 'Auto-fill';
      default: return '';
    }
  }

  /**
   * Ordena jogadores por teamIndex (0-4) conforme o draft espera
   */
  getSortedPlayersByLane(players: PlayerInfo[]): PlayerInfo[] {
    logMatchFound('🎯 [MatchFound] Ordenando jogadores por lane:', players.map(p => ({
      name: p.summonerName,
      teamIndex: p.teamIndex,
      assignedLane: p.assignedLane,
      primaryLane: p.primaryLane
    })));

    // ✅ CORREÇÃO: Usar teamIndex se disponível, senão ordenar por lane
    return [...players].sort((a, b) => {
      // Se ambos têm teamIndex, usar ele para ordenação
      if (a.teamIndex !== undefined && b.teamIndex !== undefined) {
        logMatchFound(`🎯 [MatchFound] Ordenando por teamIndex: ${a.summonerName}(${a.teamIndex}) vs ${b.summonerName}(${b.teamIndex})`);
        return a.teamIndex - b.teamIndex;
      }

      // ✅ CORREÇÃO: Normalizar lanes para minúsculas e mapear ADC -> bot
      const normalizeAndMapLane = (lane: string) => {
        const normalized = lane.toLowerCase();
        return normalized === 'adc' ? 'bot' : normalized;
      };

      const laneA = normalizeAndMapLane(a.assignedLane || a.primaryLane || 'fill');
      const laneB = normalizeAndMapLane(b.assignedLane || b.primaryLane || 'fill');

      // ✅ CORREÇÃO: Ordenar por ordem das lanes (top, jungle, mid, bot, support)
      const laneOrder = ['top', 'jungle', 'mid', 'bot', 'support'];
      const indexA = laneOrder.indexOf(laneA);
      const indexB = laneOrder.indexOf(laneB);

      logMatchFound(`🎯 [MatchFound] Ordenando por lane: ${a.summonerName}(${laneA}:${indexA}) vs ${b.summonerName}(${laneB}:${indexB})`);

      if (indexA === -1 && indexB === -1) return 0;
      if (indexA === -1) return 1;
      if (indexB === -1) return -1;
      return indexA - indexB;
    });
  }

  getTeamSideName(side: 'blue' | 'red'): string {
    return side === 'blue' ? 'Time Azul' : 'Time Vermelho';
  }

  getTeamColor(side: 'blue' | 'red'): string {
    return side === 'blue' ? '#3498db' : '#e74c3c';
  }

  getBalanceRating(mmrDiff: number): string {
    if (mmrDiff <= 50) return 'Excelente';
    if (mmrDiff <= 100) return 'Bom';
    if (mmrDiff <= 150) return 'Regular';
    return 'Desbalanceado';
  }

  // Métodos auxiliares para cálculos matemáticos no template
  getRoundedMMR(mmr: number): number {
    return Math.round(mmr);
  }

  getMMRDifference(): number {
    if (!this.matchData) return 0;
    return Math.abs(Math.round(this.matchData.averageMMR.yourTeam - this.matchData.averageMMR.enemyTeam));
  }

  isExcellentBalance(): boolean {
    return this.getMMRDifference() <= 50;
  }

  isGoodBalance(): boolean {
    const diff = this.getMMRDifference();
    return diff <= 100 && diff > 50;
  }

  isFairBalance(): boolean {
    return this.getMMRDifference() > 100;
  }

  /**
   * Determina se um jogador é o jogador atual
   * ✅ ATUALIZADO: Agora usa a identificação via LCU
   */
  isCurrentPlayer(player: PlayerInfo): boolean {
    // Usar a nova lógica de identificação via LCU
    return this.isCurrentUser(player);
  }

  /**
   * Retorna os jogadores do time azul (sempre à esquerda)
   */
  getBlueTeamPlayers(): PlayerInfo[] {
    if (!this.matchData) return [];

    // Time azul = sempre teamIndex 0-4 (independente de onde o jogador está)
    // Combinar teammates e enemies para ter todos os jogadores
    const allPlayers = [...this.matchData.teammates, ...this.matchData.enemies];

    // Filtrar apenas jogadores com teamIndex 0-4 (time azul)
    const blueTeam = allPlayers.filter(player =>
      player.teamIndex !== undefined && player.teamIndex >= 0 && player.teamIndex <= 4
    );

    return blueTeam;
  }

  /**
   * Retorna os jogadores do time vermelho (sempre à direita)
   */
  getRedTeamPlayers(): PlayerInfo[] {
    if (!this.matchData) return [];

    // Time vermelho = sempre teamIndex 5-9 (independente de onde o jogador está)
    // Combinar teammates e enemies para ter todos os jogadores
    const allPlayers = [...this.matchData.teammates, ...this.matchData.enemies];

    // Filtrar apenas jogadores com teamIndex 5-9 (time vermelho)
    const redTeam = allPlayers.filter(player =>
      player.teamIndex !== undefined && player.teamIndex >= 5 && player.teamIndex <= 9
    );

    return redTeam;
  }

  /**
   * Retorna o MMR médio do time azul
   */
  getBlueTeamMMR(): number {
    if (!this.matchData) return 0;

    // Time azul = sempre teamIndex 0-4
    // Se o jogador está no time azul (playerSide === 'blue'), usar yourTeam
    // Se o jogador está no time vermelho (playerSide === 'red'), usar enemyTeam
    return this.matchData.playerSide === 'blue'
      ? this.matchData.averageMMR.yourTeam
      : this.matchData.averageMMR.enemyTeam;
  }

  /**
   * Retorna o MMR médio do time vermelho
   */
  getRedTeamMMR(): number {
    if (!this.matchData) return 0;

    // Time vermelho = sempre teamIndex 5-9
    // Se o jogador está no time azul (playerSide === 'blue'), usar enemyTeam
    // Se o jogador está no time vermelho (playerSide === 'red'), usar yourTeam
    return this.matchData.playerSide === 'blue'
      ? this.matchData.averageMMR.enemyTeam
      : this.matchData.averageMMR.yourTeam;
  }

  // ✅ NOVO: Métodos para gerenciar status de aceitação

  /**
   * Retorna o status de aceitação de um jogador
   */
  getPlayerAcceptanceStatus(player: PlayerInfo): 'pending' | 'accepted' | 'declined' | 'timeout' {
    return player.acceptanceStatus || 'pending';
  }

  /**
   * Retorna se um jogador aceitou a partida
   */
  hasPlayerAccepted(player: PlayerInfo): boolean {
    return this.getPlayerAcceptanceStatus(player) === 'accepted';
  }

  /**
   * Retorna se um jogador recusou a partida
   */
  hasPlayerDeclined(player: PlayerInfo): boolean {
    return this.getPlayerAcceptanceStatus(player) === 'declined';
  }

  /**
   * Retorna se um jogador está pendente (não respondeu ainda)
   */
  isPlayerPending(player: PlayerInfo): boolean {
    return this.getPlayerAcceptanceStatus(player) === 'pending';
  }

  /**
   * Retorna se um jogador teve timeout
   */
  hasPlayerTimeout(player: PlayerInfo): boolean {
    return this.getPlayerAcceptanceStatus(player) === 'timeout';
  }

  /**
   * Retorna o ícone do status de aceitação
   */
  getAcceptanceStatusIcon(player: PlayerInfo): string {
    const status = this.getPlayerAcceptanceStatus(player);
    switch (status) {
      case 'accepted': return '✅';
      case 'declined': return '❌';
      case 'timeout': return '⏰';
      case 'pending':
      default: return '⏳';
    }
  }

  /**
   * Retorna a classe CSS para o status de aceitação
   */
  getAcceptanceStatusClass(player: PlayerInfo): string {
    const status = this.getPlayerAcceptanceStatus(player);
    return `acceptance-status-${status}`;
  }

  /**
   * Retorna se deve mostrar informações completas do jogador (não borradas)
   * NO MATCH_FOUND: Só mostra detalhes do usuário atual, blur permanente até o draft
   */
  shouldShowPlayerDetails(player: PlayerInfo): boolean {
    // Só mostrar detalhes se for o usuário atual
    return this.isCurrentUser(player);
  }

  /**
   * Retorna se deve aplicar blur nas informações do jogador
   * NO MATCH_FOUND: Blur permanente para todos exceto o usuário atual
   */
  shouldBlurPlayerInfo(player: PlayerInfo): boolean {
    // Aplicar blur se não for o usuário atual (blur permanente até o draft)
    return !this.isCurrentUser(player);
  }

  /**
   * Retorna o texto do tooltip para jogadores borrados
   */
  getBlurredPlayerTooltip(player: PlayerInfo): string {
    const status = this.getPlayerAcceptanceStatus(player);
    switch (status) {
      case 'pending': return 'Jogador aguardando aceitação...';
      case 'declined': return 'Jogador recusou a partida';
      case 'timeout': return 'Jogador não respondeu a tempo';
      case 'accepted': return 'Jogador aceitou a partida';
      default: return 'Status desconhecido';
    }
  }

  /**
   * Retorna o número de jogadores que aceitaram
   */
  getAcceptedPlayersCount(): number {
    if (!this.matchData) return 0;

    const allPlayers = [...this.getBlueTeamPlayers(), ...this.getRedTeamPlayers()];
    return allPlayers.filter(player => this.hasPlayerAccepted(player)).length;
  }

  /**
   * Retorna o número total de jogadores
   */
  getTotalPlayersCount(): number {
    if (!this.matchData) return 0;

    const allPlayers = [...this.getBlueTeamPlayers(), ...this.getRedTeamPlayers()];
    return allPlayers.length;
  }

  /**
   * Retorna se todos os jogadores aceitaram
   */
  haveAllPlayersAccepted(): boolean {
    return this.getAcceptedPlayersCount() === this.getTotalPlayersCount();
  }

  /**
   * Retorna o progresso de aceitação (0-100)
   */
  getAcceptanceProgress(): number {
    const total = this.getTotalPlayersCount();
    if (total === 0) return 0;

    const accepted = this.getAcceptedPlayersCount();
    return Math.round((accepted / total) * 100);
  }

  // ✅ NOVO: Métodos para integração com LCU

  /**
   * Identifica o usuário atual via LCU e marca os jogadores correspondentes
   */
  private identifyCurrentUser(): void {
    // Tentar obter dados do usuário atual do window.appComponent
    const appComponent = (window as any).appComponent;
    if (appComponent?.currentPlayer) {
      const currentUser = appComponent.currentPlayer;
      logMatchFound('🔍 [MatchFound] Usuário atual identificado via LCU:', {
        displayName: currentUser.displayName,
        summonerName: currentUser.summonerName,
        gameName: currentUser.gameName,
        tagLine: currentUser.tagLine
      });

      this.markCurrentUserInPlayers(currentUser);
    } else {
      logMatchFound('⚠️ [MatchFound] Usuário atual não disponível via LCU');
    }
  }

  /**
   * Marca o jogador atual nos dados da partida
   */
  private markCurrentUserInPlayers(currentUser: any): void {
    if (!this.matchData) return;

    const allPlayers = [...this.getBlueTeamPlayers(), ...this.getRedTeamPlayers()];

    allPlayers.forEach(player => {
      if (this.isPlayerCurrentUser(player, currentUser)) {
        player.isCurrentUser = true;
        logMatchFound('✅ [MatchFound] Jogador marcado como usuário atual:', player.summonerName);
      }
    });

    // Atualizar os times ordenados
    this.updateSortedTeams();
  }

  /**
   * Verifica se um jogador é o usuário atual baseado nos dados do LCU
   */
  private isPlayerCurrentUser(player: PlayerInfo, currentUser: any): boolean {
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
      const currentUserRiotId = `${currentUser.gameName}#${currentUser.tagLine}`;
      if (player.summonerName === currentUserRiotId) {
        return true;
      }
    }

    // Comparar apenas gameName (sem tag)
    if (currentUser.gameName && player.summonerName.includes('#')) {
      const playerGameName = player.summonerName.split('#')[0];
      if (playerGameName === currentUser.gameName) {
        return true;
      }
    }

    return false;
  }

  /**
   * Retorna se um jogador é o usuário atual (para uso no template)
   */
  isCurrentUser(player: PlayerInfo): boolean {
    return player.isCurrentUser || false;
  }
}
