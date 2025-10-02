import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, OnChanges, SimpleChanges, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChampionService } from '../../services/champion.service';

interface PickBanPhase {
  team: 'blue' | 'red';
  action: 'ban' | 'pick';
  champion?: any;
  playerId?: string;
  playerName?: string;
  playerIndex?: number;
  locked: boolean;
  timeRemaining: number;
}

interface CustomPickBanSession {
  id: string;
  phase: 'bans' | 'picks' | 'completed';
  currentAction: number;
  extendedTime: number;
  phases: PickBanPhase[];
  actions?: any[]; // ✅ ADICIONADO: Para suportar dados completos do draft
  blueTeam: any[];
  redTeam: any[];
  currentPlayerIndex: number;
}

@Component({
  selector: 'app-draft-champion-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './draft-champion-modal.html',
  styleUrl: './draft-champion-modal.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DraftanyModalComponent implements OnInit, OnDestroy, OnChanges {
  @Input() session: CustomPickBanSession | null = null;
  @Input() currentPlayer: any = null;
  @Input() isVisible: boolean = false;
  @Input() isEditingMode: boolean = false; // ✅ NOVO: Receber modo de edição
  @Input() timeRemaining: number = 30; // ✅ NOVO: Receber timer do componente principal
  @Output() onClose = new EventEmitter<void>();
  @Output() onanySelected = new EventEmitter<any>();

  champions: any[] = [];
  championsByRole: any = {};
  searchFilter: string = '';
  selectedany: any | null = null;
  selectedRole: string = 'all';
  // ✅ REMOVIDO: Timer local - agora vem do componente principal
  // timeRemaining: number = 30;
  // ✅ REMOVIDO: Timer local - não precisamos mais
  // modalTimer: any = null;

  // PROPRIEDADES PARA CACHE
  private _cachedBannedanys: any[] | null = null;
  private _cachedBlueTeamPicks: any[] | null = null;
  private _cachedRedTeamPicks: any[] | null = null;
  private _cachedModalFilteredanys: any[] | null = null;
  private _lastCacheUpdate: number = 0;
  private readonly CACHE_DURATION = 5000;
  private _lastSessionHash: string = '';

  constructor(private readonly championService: ChampionService, private readonly changeDetectorRef: ChangeDetectorRef) { }

  ngOnInit() {
    this.loadanys();
  }

  ngOnDestroy() {
    // Cleanup resources if needed
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['isVisible']) {
      if (changes['isVisible'].currentValue === true) {
        this.onModalShow();
      } else {
        // Modal closed, cleanup if needed
      }
    }

    if (changes['session'] && changes['session'].currentValue !== changes['session'].previousValue) {
      this.invalidateCache();
    }

    if (changes['currentPlayer'] && changes['currentPlayer'].currentValue !== changes['currentPlayer'].previousValue) {
      this.invalidateCache();
    }
  }

  private async loadanys() {
    try {
      console.log('🔄 [DraftanyModal] Carregando campeões...');
      this.championService.preloadChampions().subscribe({
        next: (loaded: boolean) => {
          console.log('✅ [DraftanyModal] ChampionService preloaded:', loaded);
          if (loaded) {
            // ✅ CORREÇÃO CRÍTICA: Buscar TODOS os campeões do ChampionService
            this.champions = this.championService.getAllChampions();
            console.log(`✅ [DraftanyModal] ${this.champions.length} campeões carregados!`);
            this.organizeChampionsByRole();
            this.changeDetectorRef.detectChanges(); // ✅ Forçar atualização da interface
          }
        },
        error: (error: any) => {
          console.error('❌ [Modal] Erro ao carregar campeões:', error);
        }
      });
    } catch (error) {
      console.error('❌ [Modal] Erro ao carregar campeões:', error);
    }
  }

  private organizeChampionsByRole() {
    // TODO: Implementar getChampionsByRole se necessário
    this.championsByRole = {}; // Temporário - implementar quando necessário
    /*
    this.championService.getChampionsByRole().subscribe({
      next: (championsByRole) => {
        this.championsByRole = championsByRole;
      },
      error: (error) => {
        console.error('Erro ao organizar campeões por role:', error);
        // Fallback manual se necessário
        this.championsByRole = {
          top: this.champions.filter(c => c.tags?.includes('Fighter') || c.tags?.includes('Tank')),
          jungle: this.champions.filter(c => c.tags?.includes('Fighter') || c.tags?.includes('Assassin')),
          mid: this.champions.filter(c => c.tags?.includes('Mage') || c.tags?.includes('Assassin')),
          adc: this.champions.filter(c => c.tags?.includes('Marksman')),
          support: this.champions.filter(c => c.tags?.includes('Support'))
            };
          }
        });
        */
  }

  // MÉTODOS PARA COMPARAÇÃO DE JOGADORES
  private comparePlayerWithId(player: any, targetId: string): boolean {
    if (!player || !targetId) {
      return false;
    }

    const playerId = player.id?.toString();
    const playerName = player.summonerName || player.name || '';

    if (playerId === targetId) {
      return true;
    }

    if (playerName === targetId) {
      return true;
    }

    if (playerName.includes('#')) {
      const gameName = playerName.split('#')[0];
      if (gameName === targetId) {
        return true;
      }
    }

    // ✅ NOVO: Verificar teamIndex
    if (player.teamIndex !== undefined && player.teamIndex !== null) {
      const teamIndexStr = player.teamIndex.toString();
      if (teamIndexStr === targetId) {
        return true;
      }
    }

    return false;
  }

  // MÉTODOS PARA VERIFICAR ESTADO DOS CAMPEÕES
  getBannedChampions(): any[] {
    if (this.isCacheValid() && this._cachedBannedanys) {
      return this._cachedBannedanys;
    }

    if (!this.session) {
      return [];
    }

    // ✅ LOG SUPER DETALHADO: Verificar estrutura real da sessão
    console.log('🔥 [getBannedanys] SESSION STRUCTURE ANALYSIS:', {
      sessionKeys: Object.keys(this.session),
      hasActions: !!this.session.actions,
      actionsType: Array.isArray(this.session.actions),
      actionsLength: this.session.actions?.length || 0,
      firstActionSample: this.session.actions?.[0],
      hasPhases: !!this.session.phases,
      phasesLength: this.session.phases?.length || 0,
      firstPhaseSample: this.session.phases?.[0]
    });

    let bannedanys: any[] = [];

    // ✅ CORREÇÃO: Usar actions em vez de phases para obter dados reais
    if (this.session.actions && this.session.actions.length > 0) {
      console.log('🔍 [getBannedanys] Analisando actions:', this.session.actions.map((action: any, index: number) => ({
        index,
        keys: Object.keys(action),
        action: action.action,
        type: action.type,
        team: action.team,
        locked: action.locked,
        completed: action.completed,
        championId: action.championId,
        championName: action.champion?.name
      })));

      // Testar diferentes estruturas possíveis
      let banActions = [];

      // Teste 1: action.action === 'ban'
      banActions = this.session.actions.filter((action: any) =>
        action.action === 'ban' && action.champion && action.locked
      );
      console.log('🔍 [getBannedanys] Teste 1 (action.action === "ban"):', banActions.length, banActions);

      // Teste 2: action.type === 'ban'
      const banActions2 = this.session.actions.filter((action: any) =>
        action.type === 'ban' && action.champion && action.locked
      );
      console.log('🔍 [getBannedanys] Teste 2 (action.type === "ban"):', banActions2.length, banActions2);

      // Teste 3: action.completed && algum tipo de ban
      const banActions3 = this.session.actions.filter((action: any) =>
        action.completed && action.champion && (action.action === 'ban' || action.type === 'ban')
      );
      console.log('🔍 [getBannedanys] Teste 3 (action.completed):', banActions3.length, banActions3);

      // Usar o teste que retornar mais resultados
      if (banActions2.length > banActions.length) {
        banActions = banActions2;
      }
      if (banActions3.length > banActions.length) {
        banActions = banActions3;
      }

      bannedanys = banActions
        .map((action: any) => action.champion)
        .filter((champion: any, index: number, self: any[]) =>
          index === self.findIndex(champ => champ.id === champion.id)
        );
    } else {
      // ✅ FALLBACK: Usar dados diretos dos bans se disponível no session
      const sessionAny = this.session as any;
      if (sessionAny.team1Bans || sessionAny.team2Bans) {
        const team1Bans = sessionAny.team1Bans || [];
        const team2Bans = sessionAny.team2Bans || [];
        bannedanys = [...team1Bans, ...team2Bans]
          .filter((ban: any) => ban.champion)
          .map((ban: any) => ban.champion)
          .filter((champion: any, index: number, self: any[]) =>
            index === self.findIndex(champ => champ.id === champion.id || champ.key === champion.key || champ.name === champion.name)
          );

        console.log('🔍 [getBannedanys] Usando team1Bans/team2Bans:', {
          team1BansCount: team1Bans.length,
          team2BansCount: team2Bans.length,
          totalBans: bannedanys.length
        });
      } else {
        // Fallback final para phases (pode estar vazio)
        const bannedPhases = this.session.phases.filter(phase => phase.action === 'ban' && phase.champion);
        bannedanys = bannedPhases
          .map(phase => phase.champion)
          .filter((champion, index, self): champion is any =>
            champion !== undefined && index === self.findIndex(champ => champ && champ.id === champion.id)
          );

        console.log('🔍 [getBannedanys] Usando phases como fallback:', bannedPhases.length);
      }
    }

    // ✅ LOG DETALHADO: Investigar dados de ban
    console.log('🚫 [getBannedanys] Dados de ban:', {
      totalPhases: this.session.phases?.length || 0,
      totalActions: this.session.actions?.length || 0,
      bannedCount: bannedanys.length,
      banDetails: bannedanys.map(champion => ({
        championId: champion?.id,
        championName: champion?.name
      }))
    });

    this._cachedBannedanys = bannedanys;
    this._lastCacheUpdate = Date.now();

    return bannedanys;
  }

  // 🔧 [REFACTOR] Métodos auxiliares para reduzir complexidade cognitiva
  private getPickActionsFromSession(team: 'blue' | 'red'): any[] {
    if (!this.session?.actions || this.session.actions.length === 0) {
      return [];
    }

    // Testar diferentes estruturas de ação
    const test1 = this.session.actions.filter((action: any) =>
      action.team === team && action.action === 'pick' && action.champion && action.locked
    );

    const test2 = this.session.actions.filter((action: any) =>
      action.team === team && action.type === 'pick' && action.champion && action.locked
    );

    const test3 = this.session.actions.filter((action: any) =>
      action.team === team && (action.action === 'pick' || action.type === 'pick') &&
      action.champion && action.completed
    );

    console.log(`🎯 [getPickActions] TESTS (${team}):`, {
      test1: test1.length, test2: test2.length, test3: test3.length
    });

    // Retornar o teste com mais resultados
    return [test1, test2, test3].reduce((best, current) =>
      current.length > best.length ? current : best, []
    );
  }

  private getPicksFromFallback(team: 'blue' | 'red'): any[] {
    if (!this.session) return [];

    const sessionAny = this.session as any;

    // ✅ PRIORIDADE 1: Tentar usar pick_ban_data se disponível
    if (sessionAny.pick_ban_data?.actions) {
      try {
        const actions = JSON.parse(sessionAny.pick_ban_data.actions);
        const teamCode = team === 'blue' ? 'team1' : 'team2';

        const picks = actions
          .filter((action: any) => action.team === teamCode && action.type === 'pick' && action.champion)
          .map((action: any) => action.champion);

        if (picks.length > 0) {
          console.log(`🎯 [fallback] ${team} usando pick_ban_data:`, picks.length);
          return picks;
        }
      } catch (error) {
        console.warn('❌ [fallback] Erro ao parsear pick_ban_data.actions:', error);
      }
    }

    // ✅ PRIORIDADE 2: Usar team1Picks/team2Picks
    const teamPicksProperty = team === 'blue' ? 'team1Picks' : 'team2Picks';

    if (sessionAny[teamPicksProperty]) {
      return sessionAny[teamPicksProperty]
        .filter((pick: any) => pick.champion)
        .map((pick: any) => pick.champion);
    }

    // ✅ FALLBACK FINAL: Usar dados dos times
    const teamData = team === 'blue' ?
      (this.session.blueTeam || []) :
      (this.session.redTeam || []);

    return teamData
      .filter((player: any) => player.champion)
      .map((player: any) => player.champion);
  }

  getTeamPicks(team: 'blue' | 'red'): any[] {
    // ✅ CACHE: Verificar cache primeiro
    if (team === 'blue' && this.isCacheValid() && this._cachedBlueTeamPicks) {
      return this._cachedBlueTeamPicks;
    }
    if (team === 'red' && this.isCacheValid() && this._cachedRedTeamPicks) {
      return this._cachedRedTeamPicks;
    }

    if (!this.session) return [];

    console.log(`🔥 [getTeamPicks-${team}] ANALYSIS:`, {
      hasActions: !!this.session.actions,
      actionsLength: this.session.actions?.length || 0
    });

    let teamPicks: any[] = [];

    // ✅ PRIORIDADE 1: Usar actions da sessão
    const pickActions = this.getPickActionsFromSession(team);
    if (pickActions.length > 0) {
      teamPicks = pickActions.map((action: any) => action.champion);
    } else {
      // ✅ FALLBACK: Usar dados alternativos
      teamPicks = this.getPicksFromFallback(team);
    }

    console.log(`🎯 [getTeamPicks] FINAL (${team}):`, {
      count: teamPicks.length,
      details: teamPicks.map(p => ({ id: p.id, name: p.name }))
    });

    // ✅ CACHE: Atualizar cache
    if (team === 'blue') {
      this._cachedBlueTeamPicks = teamPicks;
    } else {
      this._cachedRedTeamPicks = teamPicks;
    }
    this._lastCacheUpdate = Date.now();

    return teamPicks;
  }

  isChampionBanned(champion: any): boolean {
    const bannedChampions = this.getBannedChampions();

    // ✅ CORREÇÃO: Comparação robusta considerando diferentes formatos de ID
    const isBanned = bannedChampions.some(banned => {
      // Comparar por ID string (ex: "Aatrox" vs "Aatrox")
      if (banned.id && champion.id && banned.id === champion.id) {
        return true;
      }

      // Comparar por key/championId numérico (ex: "266" vs 266 vs "266")
      const bannedKey = banned.key?.toString() || banned.id?.toString();
      const championKey = champion.key?.toString() || champion.id?.toString();
      if (bannedKey && championKey && bannedKey === championKey) {
        return true;
      }

      // Comparar por nome (fallback)
      if (banned.name && champion.name && banned.name === champion.name) {
        return true;
      }

      return false;
    });

    // ✅ LOG DETALHADO: Investigar por que bans não estão sendo detectados
    console.log('🚫 [isanyBanned] Verificando ban:', {
      championId: champion.id,
      championKey: champion.key,
      championName: champion.name,
      bannedChampionsCount: bannedChampions.length,
      bannedChampions: bannedChampions.map(banned => ({
        id: banned.id,
        key: banned.key,
        name: banned.name
      })),
      comparisons: bannedChampions.map(banned => ({
        bannedId: banned.id,
        bannedKey: banned.key,
        bannedName: banned.name,
        idMatch: banned.id === champion.id,
        keyMatch: (banned.key?.toString() || banned.id?.toString()) === (champion.key?.toString() || champion.id?.toString()),
        nameMatch: banned.name === champion.name
      })),
      isBanned: isBanned
    });

    // ✅ NOVO: Log para arquivo para debug
    if (typeof window !== 'undefined' && (window as any).electronAPI?.fs) {
      const fs = (window as any).electronAPI.fs;
      const path = (window as any).electronAPI.path;
      const process = (window as any).electronAPI.process;
      const logPath = path.join(process.cwd(), 'ban-check-debug.log');
      const logLine = `[${new Date().toISOString()}] isChampionBanned: ${champion.name} (id:${champion.id}, key:${champion.key}) - Banido: ${isBanned} - Bans: ${bannedChampions.length}\n`;
      fs.appendFile(logPath, logLine, () => { });
    }

    return isBanned;
  }

  isChampionPicked(champion: any): boolean {
    const bluePicks = this.getTeamPicks('blue');
    const redPicks = this.getTeamPicks('red');

    // ✅ CORREÇÃO: Comparação robusta considerando diferentes formatos de ID
    const isPicked = [...bluePicks, ...redPicks].some(picked => {
      // Comparar por ID string (ex: "Aatrox" vs "Aatrox")
      if (picked.id && champion.id && picked.id === champion.id) {
        return true;
      }

      // Comparar por key/championId numérico (ex: "266" vs 266 vs "266")
      const pickedKey = picked.key?.toString() || picked.id?.toString();
      const championKey = champion.key?.toString() || champion.id?.toString();
      if (pickedKey && championKey && pickedKey === championKey) {
        return true;
      }

      // Comparar por nome (fallback)
      if (picked.name && champion.name && picked.name === champion.name) {
        return true;
      }

      return false;
    });

    // ✅ LOG DETALHADO: Investigar por que picks não estão sendo detectados
    console.log('🎯 [isanyPicked] Verificando pick:', {
      championId: champion.id,
      championKey: champion.key,
      championName: champion.name,
      bluePicksCount: bluePicks.length,
      redPicksCount: redPicks.length,
      allPicks: [...bluePicks, ...redPicks].map(picked => ({
        id: picked.id,
        key: picked.key,
        name: picked.name
      })),
      comparisons: [...bluePicks, ...redPicks].map(picked => ({
        pickedId: picked.id,
        pickedKey: picked.key,
        pickedName: picked.name,
        idMatch: picked.id === champion.id,
        keyMatch: (picked.key?.toString() || picked.id?.toString()) === (champion.key?.toString() || champion.id?.toString()),
        nameMatch: picked.name === champion.name
      })),
      isPicked: isPicked
    });

    // ✅ NOVO: Log para arquivo para debug
    if (typeof window !== 'undefined' && (window as any).electronAPI?.fs) {
      const fs = (window as any).electronAPI.fs;
      const path = (window as any).electronAPI.path;
      const process = (window as any).electronAPI.process;
      const logPath = path.join(process.cwd(), 'pick-check-debug.log');
      const logLine = `[${new Date().toISOString()}] isanyPicked: ${champion.name} (id:${champion.id}, key:${champion.key}) - Pickado: ${isPicked} - Picks: ${bluePicks.length + redPicks.length}\n`;
      fs.appendFile(logPath, logLine, () => { });
    }

    return isPicked;
  }

  // MÉTODOS PARA FILTRAGEM
  getModalFilteredanys(): any[] {
    if (this.session?.currentAction !== undefined) {
      const sessionHash = JSON.stringify({
        currentAction: this.session.currentAction,
        phases: this.session.phases.map(phase => ({
          action: phase.action,
          team: phase.team,
          locked: phase.locked,
          championId: phase.champion?.id
        }))
      });

      if (sessionHash !== this._lastSessionHash) {
        this._lastSessionHash = sessionHash;
        this.invalidateCache();
      }
    }

    if (this.isCacheValid() && this._cachedModalFilteredanys) {
      return this._cachedModalFilteredanys;
    }

    let filtered = this.champions;

    // Filtrar por role
    if (this.selectedRole !== 'all') {
      filtered = filtered.filter(champion => {
        const tags = champion.tags || [];
        switch (this.selectedRole) {
          case 'top':
            return tags.includes('Fighter') || tags.includes('Tank');
          case 'jungle':
            return tags.includes('Fighter') || tags.includes('Assassin');
          case 'mid':
            return tags.includes('Mage') || tags.includes('Assassin');
          case 'adc':
            return tags.includes('Marksman');
          case 'support':
            return tags.includes('Support');
          default:
            return true;
        }
      });
    }

    // Filtrar por busca
    if (this.searchFilter.trim()) {
      const searchTerm = this.searchFilter.toLowerCase().trim();
      filtered = filtered.filter(champion =>
        champion.name.toLowerCase().includes(searchTerm)
      );
    }

    // ✅ NOVO: Log temporário removido para melhorar performance

    this._cachedModalFilteredanys = filtered;
    this._lastCacheUpdate = Date.now();

    return filtered;
  }

  // MÉTODOS PARA SELEÇÃO
  selectRoleInModal(role: string): void {
    this.selectedRole = role;
    this.invalidateCache();
    this.changeDetectorRef.markForCheck();
  }

  // ✅ NOVO: Método unificado para clique em campeão
  onanyCardClick(champion: any): void {
    // ✅ LOG: Detectar clique no campeão
    console.log('🖱️ [onanyCardClick] Click detectado no campeão:', {
      championId: champion.id,
      championName: champion.name,
      timestamp: new Date().toISOString()
    });

    // ✅ VERIFICAÇÃO: Checkar se o campeão está banido
    const isBanned = this.isChampionBanned(champion);
    console.log('🚫 [onanyCardClick] any banido?', isBanned);
    if (isBanned) {
      console.log('🚫 [onanyCardClick] Mostrando feedback de bloqueio (banido)');
      this.showanyBlockedFeedback(champion, 'banido');
      return;
    }

    // ✅ VERIFICAÇÃO: Checkar se o campeão está pickado
    const isPicked = this.isChampionPicked(champion);
    console.log('⚡ [onanyCardClick] any pickado?', isPicked);
    if (isPicked) {
      console.log('⚡ [onanyCardClick] Mostrando feedback de bloqueio (já escolhido)');
      this.showanyBlockedFeedback(champion, 'já escolhido');
      return;
    }

    console.log('✅ [onanyCardClick] any livre, selecionando...');
    this.selectany(champion);
  }

  selectany(champion: any): void {
    // ✅ CORREÇÃO: Log detalhado da seleção
    console.log('🎯 [DraftanyModal] === SELECIONANDO CAMPEÃO ===');
    console.log('🎯 [DraftanyModal] Campeão clicado:', champion.name);
    console.log('🎯 [DraftanyModal] ID do campeão:', champion.id);
    console.log('🎯 [DraftanyModal] Está banido?', this.isChampionBanned(champion));
    console.log('🎯 [DraftanyModal] Está escolhido?', this.isChampionPicked(champion));

    if (this.isChampionBanned(champion)) {
      console.log('❌ [DraftanyModal] Campeão banido - não pode ser selecionado');
      // ✅ NOVO: Feedback visual para campeão banido
      this.showanyBlockedFeedback(champion, 'banido');
      return;
    }

    if (this.isChampionPicked(champion)) {
      console.log('❌ [DraftanyModal] Campeão já escolhido - não pode ser selecionado');
      // ✅ NOVO: Feedback visual para campeão já escolhido
      this.showanyBlockedFeedback(champion, 'já escolhido');
      return;
    }

    // ✅ CORREÇÃO: Definir seleção
    this.selectedany = champion;
    console.log('✅ [DraftanyModal] Campeão selecionado:', champion.name);

    // ✅ CORREÇÃO: Forçar atualização da interface
    this.changeDetectorRef.markForCheck();
  }

  // ✅ NOVO: Feedback visual quando tenta selecionar campeão bloqueado
  showanyBlockedFeedback(champion: any, reason: string): void {
    console.log(`🚫 [DraftanyModal] Feedback: ${champion.name} está ${reason}`);

    // Criar elemento temporário de feedback
    const feedbackElement = document.createElement('div');
    feedbackElement.textContent = `${champion.name} já foi ${reason}!`;
    feedbackElement.style.cssText = `
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  background: rgba(220, 53, 69, 0.95);
  color: white;
  padding: 12px 20px;
  border-radius: 8px;
  font-weight: bold;
  z-index: 10000;
  animation: fadeInOut 2s ease;
  pointer-events: none;
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.5);
`;

    // Adicionar animação CSS
    const style = document.createElement('style');
    style.textContent = `
  @keyframes fadeInOut {
    0% { opacity: 0; transform: translate(-50%, -50%) scale(0.8); }
    15% { opacity: 1; transform: translate(-50%, -50%) scale(1); }
    85% { opacity: 1; transform: translate(-50%, -50%) scale(1); }
    100% { opacity: 0; transform: translate(-50%, -50%) scale(0.8); }
  }
`;
    document.head.appendChild(style);
    document.body.appendChild(feedbackElement);

    // Remover após 2 segundos
    setTimeout(() => {
      if (feedbackElement.parentNode) {
        feedbackElement.parentNode.removeChild(feedbackElement);
      }
      if (style.parentNode) {
        style.parentNode.removeChild(style);
      }
    }, 2000);
  }

  // MÉTODOS PARA CONFIRMAÇÃO
  confirmModalSelection(): void {
    if (!this.selectedany) {
      return;
    }

    if (this.isChampionBanned(this.selectedany) || this.isChampionPicked(this.selectedany)) {
      return;
    }

    // ✅ CORREÇÃO: Log detalhado da seleção para debug
    console.log('🎯 [DraftanyModal] === CONFIRMANDO SELEÇÃO ===');
    console.log('🎯 [DraftanyModal] Campeão selecionado:', this.selectedany.name);
    console.log('🎯 [DraftanyModal] ID do campeão:', this.selectedany.id);
    console.log('🎯 [DraftanyModal] Sessão atual:', this.session?.currentAction);
    console.log('🎯 [DraftanyModal] Fase atual:', this.session?.phases?.[this.session?.currentAction || 0]);

    // ✅ CORREÇÃO: Emitir o campeão selecionado
    this.onanySelected.emit(this.selectedany);

    // ✅ CORREÇÃO: Limpar seleção e cache
    this.selectedany = null;
    this.invalidateCache();

    // ✅ CORREÇÃO: Fechar modal
    this.closeModal();

    // ✅ CORREÇÃO: Forçar atualização
    this.changeDetectorRef.markForCheck();

    console.log('✅ [DraftanyModal] Seleção confirmada e modal fechado');
  }

  cancelModalSelection(): void {
    this.closeModal();
  }

  // MÉTODOS PARA CONTROLE DO MODAL
  openModal(): void {
    this.isVisible = true;

    this.invalidateCache();

    this.loadanys();

    this.changeDetectorRef.markForCheck();
  }

  closeModal(): void {
    this.isVisible = false;

    this.selectedany = null;
    this.searchFilter = '';
    this.selectedRole = 'all';

    this.onClose.emit();

    this.changeDetectorRef.markForCheck();
  }

  // MÉTODOS PARA INFORMAÇÕES DO JOGADOR ATUAL
  getCurrentPlayerNameForModal(): string {
    if (!this.session) return '';

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) return '';

    return currentPhase.playerName || 'Jogador Desconhecido';
  }

  getCurrentPlayerTeamForModal(): string {
    if (!this.session) return '';

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) return '';

    return currentPhase.team === 'blue' ? 'Time Azul' : 'Time Vermelho';
  }

  isCurrentPlayerForModal(): boolean {
    if (!this.currentPlayer || !this.session) return false;

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) {
      return false;
    }

    let isCurrent = false;

    // 1. Tentar por playerId
    if (currentPhase.playerId) {
      isCurrent = this.comparePlayerWithId(this.currentPlayer, currentPhase.playerId);
      if (isCurrent) {
        return true;
      }
    }

    // 2. Tentar por teamIndex se disponível
    if (currentPhase.playerIndex !== undefined && this.currentPlayer.teamIndex !== undefined) {
      if (this.currentPlayer.teamIndex === currentPhase.playerIndex) {
        return true;
      }
    }

    // 3. Tentar por nome do jogador
    if (currentPhase.playerName) {
      const currentPlayerName = this.currentPlayer.summonerName || this.currentPlayer.name;
      if (currentPlayerName === currentPhase.playerName) {
        return true;
      }
    }

    return false;
  }

  getCurrentActionText(): string {
    if (!this.session) return '';

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) return '';

    return currentPhase.action === 'ban' ? 'Banir Campeão' : 'Escolher Campeão';
  }

  getCurrentActionIcon(): string {
    if (!this.session) return '';

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) return '';

    return currentPhase.action === 'ban' ? '🚫' : '⭐';
  }

  getCurrentTeamColor(): string {
    if (!this.session) return '#5bc0de';

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) return '#5bc0de';

    return currentPhase.team === 'blue' ? '#5bc0de' : '#d9534f';
  }

  // MÉTODOS PARA CACHE
  private invalidateCache(): void {
    this._cachedBannedanys = null;
    this._cachedBlueTeamPicks = null;
    this._cachedRedTeamPicks = null;
    this._cachedModalFilteredanys = null;
    this._lastCacheUpdate = Date.now();
  }

  private isCacheValid(): boolean {
    const isValid = Date.now() - this._lastCacheUpdate < this.CACHE_DURATION;
    return isValid;
  }

  // MÉTODOS AUXILIARES
  onImageError(event: Event, champion: any): void {
    const target = event.target as HTMLImageElement;
    if (target) {
      target.src = 'assets/images/champion-placeholder.svg';
    }
  }

  // MÉTODO PARA QUANDO O MODAL SE TORNA VISÍVEL
  onModalShow(): void {
    if (this.isVisible) {
      this.invalidateCache();
    }
  }
}
