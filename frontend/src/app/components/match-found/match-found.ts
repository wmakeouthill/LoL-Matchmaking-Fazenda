import { Component, OnInit, OnDestroy, computed, effect, inject, input, output, ChangeDetectionStrategy } from '@angular/core';
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
  // ✅ ATIVADO: OnPush com Signals para melhor performance
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class MatchFoundComponent implements OnInit, OnDestroy {
  // ✅ MODERNIZADO: Usando input() para @Input
  matchData = input<MatchFoundData | null>(null);
  isVisible = input<boolean>(false);

  // ✅ MODERNIZADO: Usando output() para @Output
  acceptMatch = output<number>();
  declineMatch = output<number>();

  // ✅ MODERNIZADO: Computed para timer baseado em matchData
  acceptTimeLeft = computed(() => {
    const data = this.matchData();
    return data?.acceptanceTimer ?? 30;
  });

  // ✅ MODERNIZADO: Times ordenados com computed signals
  sortedBlueTeam = computed(() => {
    const data = this.matchData();
    if (!data) return [];
    return this.getSortedPlayersByLane(this.getBlueTeamPlayersInternal(data));
  });

  sortedRedTeam = computed(() => {
    const data = this.matchData();
    if (!data) return [];
    return this.getSortedPlayersByLane(this.getRedTeamPlayersInternal(data));
  });

  // ✅ MODERNIZADO: Timer urgente com computed
  isTimerUrgent = computed(() => this.acceptTimeLeft() <= 10);

  // ✅ MODERNIZADO: MMR dos times com computed
  blueTeamMMR = computed(() => {
    const data = this.matchData();
    if (!data?.averageMMR) return 0;
    return data.playerSide === 'blue' ? data.averageMMR.yourTeam : data.averageMMR.enemyTeam;
  });

  redTeamMMR = computed(() => {
    const data = this.matchData();
    if (!data?.averageMMR) return 0;
    return data.playerSide === 'blue' ? data.averageMMR.enemyTeam : data.averageMMR.yourTeam;
  });

  // ✅ MODERNIZADO: Progresso de aceitação com computed
  acceptedPlayersCount = computed(() => {
    const data = this.matchData();
    if (!data) return 0;
    const allPlayers = [...this.getBlueTeamPlayersInternal(data), ...this.getRedTeamPlayersInternal(data)];
    return allPlayers.filter(player => this.getPlayerAcceptanceStatus(player) === 'accepted').length;
  });

  totalPlayersCount = computed(() => {
    const data = this.matchData();
    if (!data) return 0;
    const allPlayers = [...this.getBlueTeamPlayersInternal(data), ...this.getRedTeamPlayersInternal(data)];
    return allPlayers.length;
  });

  acceptanceProgress = computed(() => {
    const total = this.totalPlayersCount();
    if (total === 0) return 0;
    const accepted = this.acceptedPlayersCount();
    return Math.round((accepted / total) * 100);
  });

  private countdownTimer?: number;
  private readonly playerIconMap = new Map<string, number>();

  // ✅ MODERNIZADO: Injeção de dependências com inject()
  private readonly profileIconService = inject(ProfileIconService);
  public readonly botService = inject(BotService);
  private readonly audioService = inject(AudioService);

  constructor() {
    // ✅ NOVO: Effect para reagir a mudanças em matchData
    effect(() => {
      const data = this.matchData();
      const visible = this.isVisible();

      if (data && visible) {
        logMatchFound('🎮 [MatchFound] Effect detectou nova partida:', {
          matchId: data.matchId,
          phase: data.phase,
          timer: data.acceptanceTimer
        });

        // Identificar usuário atual
        this.identifyCurrentUser();

        // Tocar som de match found
        if (data.phase === 'match_found') {
          this.playMatchFoundSound();
        }
      }
    });

    // ✅ NOVO: Effect para limpar recursos quando modal fechar
    effect(() => {
      const visible = this.isVisible();
      if (!visible) {
        this.cleanupResources();
      }
    });
  }

  ngOnInit() {
    const data = this.matchData();
    if (data && data.phase === 'match_found') {
      this.playMatchFoundSound();
    }
    this.identifyCurrentUser();
  }

  ngOnDestroy() {
    logMatchFound('🧹 [MatchFound] Destruindo componente - limpando recursos');
    this.cleanupResources();
    logMatchFound('✅ [MatchFound] Recursos limpos com sucesso');
  }

  /**
   * Limpa todos os recursos (timer, som, etc)
   */
  private cleanupResources(): void {
    // Garantir que timer local não existe (não deve existir)
    if (this.countdownTimer) {
      logMatchFound('⚠️ [MatchFound] Timer local encontrado - removendo');
      clearInterval(this.countdownTimer);
      this.countdownTimer = undefined;
    }

    // Garantir que som de match found seja parado
    try {
      this.audioService.stopMatchFound();
    } catch (err) {
      console.warn('[MatchFound] Erro ao parar som:', err);
    }
  }

  /**
   * ✅ OTIMIZADO: Método auxiliar para obter jogadores do time azul
   */
  private getBlueTeamPlayersInternal(data: MatchFoundData): PlayerInfo[] {
    if (!data?.teams?.blue?.players) return [];
    return data.teams.blue.players;
  }

  /**
   * ✅ OTIMIZADO: Método auxiliar para obter jogadores do time vermelho
   */
  private getRedTeamPlayersInternal(data: MatchFoundData): PlayerInfo[] {
    if (!data?.teams?.red?.players) return [];
    return data.teams.red.players;
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

  onAcceptMatch(): void {
    const data = this.matchData();
    if (data) {
      logMatchFound('✅ [MatchFound] Emitindo aceitação para:', data.matchId);
      // Parar som de match found antes de seguir
      try {
        this.audioService.stopMatchFound();
      } catch (err) {
        console.warn('[MatchFound] Erro ao parar som:', err);
      }
      this.acceptMatch.emit(data.matchId);

      // Limpar timer se existir
      if (this.countdownTimer) {
        logMatchFound('⚠️ [MatchFound] Timer local encontrado ao aceitar - removendo');
        clearInterval(this.countdownTimer);
        this.countdownTimer = undefined;
      }
    }
  }

  onDeclineMatch(): void {
    const data = this.matchData();
    if (data) {
      logMatchFound('❌ [MatchFound] Emitindo recusa para:', data.matchId);
      // Parar som de match found antes de seguir
      try {
        this.audioService.stopMatchFound();
      } catch (err) {
        console.warn('[MatchFound] Erro ao parar som:', err);
      }
      this.declineMatch.emit(data.matchId);

      // Limpar timer se existir
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

  // ✅ MODERNIZADO: Métodos de balanceamento com computed
  mmrDifference = computed(() => {
    const data = this.matchData();
    if (!data?.averageMMR) return 0;
    return Math.abs(Math.round(data.averageMMR.yourTeam - data.averageMMR.enemyTeam));
  });

  isExcellentBalance = computed(() => this.mmrDifference() <= 50);
  isGoodBalance = computed(() => {
    const diff = this.mmrDifference();
    return diff <= 100 && diff > 50;
  });
  isFairBalance = computed(() => this.mmrDifference() > 100);

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
    const data = this.matchData();
    if (!data?.teams?.blue?.players) return [];
    return data.teams.blue.players;
  }

  /**
   * ✅ OTIMIZADO: Acessa teams.red.players diretamente (pick_ban_data)
   */
  getRedTeamPlayers(): PlayerInfo[] {
    const data = this.matchData();
    if (!data?.teams?.red?.players) return [];
    return data.teams.red.players;
  }

  /**
   * Retorna o MMR médio do time azul (DEPRECATED - usar computed blueTeamMMR)
   */
  getBlueTeamMMR(): number {
    return this.blueTeamMMR();
  }

  /**
   * Retorna o MMR médio do time vermelho (DEPRECATED - usar computed redTeamMMR)
   */
  getRedTeamMMR(): number {
    return this.redTeamMMR();
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
   * ✅ OTIMIZAÇÃO: Verificar se é bot sem chamar service repetidamente
   */
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
   * Retorna se todos os jogadores aceitaram
   */
  haveAllPlayersAccepted(): boolean {
    return this.acceptedPlayersCount() === this.totalPlayersCount();
  }

  /**
   * Verifica se deve borrar as informações do jogador
   * (sempre borra exceto para o usuário atual)
   */
  shouldBlurPlayerInfo(player: PlayerInfo): boolean {
    const isCurrent = this.isCurrentUser(player);
    const isBot = this.isPlayerBot(player);
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
    // Tentar obter dados do usuário atual do globalThis.appComponent
    const appComponent = (globalThis as any).appComponent;
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
    const data = this.matchData();
    if (!data) {
      console.log('[MatchFound] ❌ matchData não disponível');
      return;
    }

    const allPlayers = [...this.getBlueTeamPlayersInternal(data), ...this.getRedTeamPlayersInternal(data)];
    console.log('[MatchFound] 🔍 Total de jogadores:', allPlayers.length);

    for (const player of allPlayers) {
      const isCurrent = this.isPlayerCurrentUser(player, currentUser);
      console.log(`[MatchFound] 🔍 Verificando ${player.summonerName}: isCurrent=${isCurrent}`);
      if (isCurrent) {
        player.isCurrentUser = true;
        console.log('✅ [MatchFound] Jogador marcado como usuário atual:', player.summonerName);
      }
    }
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
