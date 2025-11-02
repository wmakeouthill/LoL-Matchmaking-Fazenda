import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, OnChanges, SimpleChanges, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ProfileIconService } from '../../services/profile-icon.service';
import { Observable, of } from 'rxjs';
import { BotService } from '../../services/bot.service';
import { AudioService } from '../../services/audio.service';
import { MatchFound, UnifiedTeamPlayer } from '../../interfaces';

// ✅ OTIMIZADO: Usar MatchFound (pick_ban_data) diretamente - reduz duplicação de memória
export type MatchFoundData = MatchFound & {
  playerSide?: 'blue' | 'red'; // Campo auxiliar UI
};

// ✅ Alias para compatibilidade
export type PlayerInfo = UnifiedTeamPlayer;

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
  styleUrl: './match-found.scss',
  // ✅ CORREÇÃO: Removido OnPush porque o componente atualiza propriedades internas
  // baseado em eventos WebSocket (timer, progress, etc) que não são @Input
  // changeDetection: ChangeDetectionStrategy.OnPush
})
export class MatchFoundComponent implements OnInit, OnDestroy, OnChanges {
  @Input() matchData: MatchFoundData | null = null;
  @Input() isVisible = false;
  @Output() acceptMatch = new EventEmitter<number>();
  @Output() declineMatch = new EventEmitter<number>();

  private _acceptTimeLeft = 30;

  // ✅ NOVO: Getter que sempre lê de matchData (fonte de verdade do backend)
  get acceptTimeLeft(): number {
    if (this.matchData?.acceptanceTimer !== undefined) {
      return this.matchData.acceptanceTimer;
    }
    return this._acceptTimeLeft;
  }

  set acceptTimeLeft(value: number) {
    this._acceptTimeLeft = value;
  }
  sortedBlueTeam: PlayerInfo[] = [];
  sortedRedTeam: PlayerInfo[] = [];
  private countdownTimer?: number;
  isTimerUrgent = false;

  private readonly playerIconMap = new Map<string, number>();

  constructor(
    private readonly profileIconService: ProfileIconService,
    public botService: BotService,
    private readonly cdr: ChangeDetectorRef,
    private readonly audioService: AudioService
  ) { }

  ngOnInit() {
    if (this.matchData && this.matchData.phase === 'match_found') {
      this.startAcceptCountdown();
      // ✅ NOVO: Tocar som quando match found é exibido
      this.playMatchFoundSound();
    }
    this.updateSortedTeams();
    // ✅ NOVO: Escutar atualizações de timer do backend
    this.setupTimerListener();
    // ✅ NOVO: Identificar usuário atual via LCU
    this.identifyCurrentUser();

    // ✅ CORREÇÃO: Forçar detecção de mudanças para aplicar blur inicial
    this.cdr.detectChanges();
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

        // ✅ NOVO: Tocar som quando match found é exibido (nova partida)
        this.playMatchFoundSound();

        // ✅ CORREÇÃO: Limpar timer anterior se existir
        if (this.countdownTimer) {
          logMatchFound('🎮 [MatchFound] Limpando timer anterior');
          clearInterval(this.countdownTimer);
          this.countdownTimer = undefined;
        }

        // ✅ CORREÇÃO: Configurar timer apenas se backend não está controlando
        if (this.matchData && this.matchData.phase === 'match_found') {
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

        // ✅ CORREÇÃO: Forçar detecção de mudanças para atualizar blur
        this.cdr.detectChanges();
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

    // ✅ REMOVIDO: Listeners não são mais necessários com Default strategy
    // document.removeEventListener('matchTimerUpdate', this.onTimerUpdate);
    // Garantir que som de match found seja parado
    try {
      this.audioService.stopMatchFound();
    } catch (err) {
      // silent
    }

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
      blueCount: this.matchData.teams?.blue?.players?.length || 0,
      redCount: this.matchData.teams?.red?.players?.length || 0
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

  // ✅ REMOVIDO: Listeners de eventos customizados não são mais necessários
  // Com Default strategy, as mudanças nos @Inputs são detectadas automaticamente
  private setupTimerListener(): void {
    // Com OnPush removido, detecção de mudanças acontece automaticamente
    logMatchFound('ℹ️ [MatchFound] SetupTimerListener chamado - listeners não necessários com Default strategy');
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
      // Parar som de match found antes de seguir
      try { this.audioService.stopMatchFound(); } catch (_) { }
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
      // Parar som de match found antes de seguir
      try { this.audioService.stopMatchFound(); } catch (_) { }
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
    const primaryLane = player.primaryLane || player.assignedLane || 'fill';
    const secondaryLane = player.secondaryLane || player.assignedLane || 'fill';
    const primary = `${this.getLaneIcon(primaryLane)} ${this.getLaneName(primaryLane)}`;
    const secondary = `${this.getLaneIcon(secondaryLane)} ${this.getLaneName(secondaryLane)}`;
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
    if (!this.matchData?.averageMMR) return 0;
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
   * ✅ OTIMIZADO: Acessa teams.blue.players diretamente (pick_ban_data)
   */
  getBlueTeamPlayers(): PlayerInfo[] {
    if (!this.matchData?.teams?.blue?.players) return [];

    // ✅ OTIMIZADO: Usar teams.blue.players diretamente (pick_ban_data)
    return this.matchData.teams.blue.players;
  }

  /**
   * ✅ OTIMIZADO: Acessa teams.red.players diretamente (pick_ban_data)
   */
  getRedTeamPlayers(): PlayerInfo[] {
    if (!this.matchData?.teams?.red?.players) return [];

    // ✅ OTIMIZADO: Usar teams.red.players diretamente (pick_ban_data)
    return this.matchData.teams.red.players;
  }

  /**
   * Retorna o MMR médio do time azul
   */
  getBlueTeamMMR(): number {
    if (!this.matchData?.averageMMR) return 0;

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
    if (!this.matchData?.averageMMR) return 0;

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
    // Backend envia number, frontend usa string
    if (typeof player.acceptanceStatus === 'string') {
      return player.acceptanceStatus;
    }
    return 'pending';
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
  // ✅ OTIMIZAÇÃO: Propriedades computadas para evitar recálculos
  get blueTeamMMR(): number {
    return this.getBlueTeamMMR();
  }

  get redTeamMMR(): number {
    return this.getRedTeamMMR();
  }

  get acceptedPlayersCount(): number {
    return this.getAcceptedPlayersCount();
  }

  get totalPlayersCount(): number {
    return this.getTotalPlayersCount();
  }

  get acceptanceProgress(): number {
    return this.getAcceptanceProgress();
  }


  // ✅ OTIMIZAÇÃO: Verificar se é bot sem chamar service repetidamente
  isPlayerBot(player: PlayerInfo): boolean {
    if (!player) return false;
    const playerName = player.summonerName || '';
    const botPattern = /^Bot\d+$/i;
    return botPattern.test(playerName);
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

  /**
   * Verifica se deve borrar as informações do jogador
   * (sempre borra exceto para o usuário atual)
   */
  shouldBlurPlayerInfo(player: PlayerInfo): boolean {
    const isCurrent = this.isCurrentUser(player);
    const isBot = this.isPlayerBot(player); // ✅ Verificar se é bot
    const shouldBlur = !isCurrent && !isBot; // ✅ BOTS NUNCA são borrados
    console.log(`[MatchFound] shouldBlurPlayerInfo(${player.summonerName}): isCurrent=${isCurrent}, isBot=${isBot}, shouldBlur=${shouldBlur}`);
    return shouldBlur;
  }

  // ✅ NOVO: Métodos para integração com LCU

  /**
   * Identifica o usuário atual via LCU e marca os jogadores correspondentes
   */
  private identifyCurrentUser(): void {
    console.log('[MatchFound] 🔍 identifyCurrentUser chamado');
    // Tentar obter dados do usuário atual do window.appComponent
    const appComponent = (window as any).appComponent;
    console.log('[MatchFound] appComponent:', appComponent);
    if (appComponent?.currentPlayer) {
      const currentUser = appComponent.currentPlayer;
      console.log('🔍 [MatchFound] Usuário atual identificado via LCU:', {
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
    console.log('[MatchFound] 🔍 markCurrentUserInPlayers chamado com:', currentUser);
    if (!this.matchData) {
      console.log('[MatchFound] ❌ matchData não disponível');
      return;
    }

    const allPlayers = [...this.getBlueTeamPlayers(), ...this.getRedTeamPlayers()];
    console.log('[MatchFound] 🔍 Total de jogadores:', allPlayers.length);

    allPlayers.forEach(player => {
      const isCurrent = this.isPlayerCurrentUser(player, currentUser);
      console.log(`[MatchFound] 🔍 Verificando ${player.summonerName}: isCurrent=${isCurrent}`);
      if (isCurrent) {
        player.isCurrentUser = true;
        console.log('✅ [MatchFound] Jogador marcado como usuário atual:', player.summonerName);
      }
    });

    // Atualizar os times ordenados
    this.updateSortedTeams();

    // ✅ CORREÇÃO: Forçar detecção de mudanças para atualizar blur
    this.cdr.detectChanges();
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
    const isCurrent = player.isCurrentUser || false;
    console.log(`[MatchFound] isCurrentUser(${player.summonerName}): isCurrentUser=${isCurrent}, player.isCurrentUser=${player.isCurrentUser}`);
    return isCurrent;
  }

  /**
   * ✅ NOVO: Tocar som de match found
   */
  private playMatchFoundSound(): void {
    try {
      this.audioService.playMatchFound();
    } catch (error) {
      console.error('❌ [MatchFound] Erro ao tocar match_found via AudioService:', error);
    }
  }
}
