import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ChampionService, ChampionData } from '../../services/champion.service';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';

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
  imports: [CommonModule],
  templateUrl: './draft-champion-modal.html',
  styleUrl: './draft-champion-modal.scss'
  // ✅ CORREÇÃO: OnPush REMOVIDO - o componente acessa window.appComponent.draftTimer
  // que é atualizado externamente via WebSocket, então precisa da estratégia Default
  // changeDetection: ChangeDetectionStrategy.OnPush
})
export class DraftanyModalComponent implements OnInit, OnDestroy, OnChanges {
  @Input() session: CustomPickBanSession | null = null;
  @Input() currentPlayer: any = null;
  @Input() isVisible: boolean = false;
  @Input() isEditingMode: boolean = false; // ✅ NOVO: Receber modo de edição
  // ✅ REMOVIDO: Timer não é mais @Input, vem do window.appComponent
  // @Input() timeRemaining: number = 30;
  @Output() onClose = new EventEmitter<void>();
  @Output() onanySelected = new EventEmitter<unknown>();

  champions: any[] = [];
  championsByRole: any = {};
  searchFilter: string = '';
  selectedany: ChampionData | null = null;
  selectedRole: string = 'all';
  // ✅ REMOVIDO: Timer local - agora vem do componente principal
  // timeRemaining: number = 30;
  // ✅ REMOVIDO: Timer local - não precisamos mais
  // modalTimer: any = null;

  // ✅ CORREÇÃO #4: Subject para debounce na pesquisa
  private readonly searchSubject = new Subject<string>();
  filteredChampions: any[] = [];

  // PROPRIEDADES PARA CACHE
  private _cachedBannedanys: any[] | null = null;
  private _cachedBlueTeamPicks: any[] | null = null;
  private _cachedRedTeamPicks: any[] | null = null;
  private _cachedModalFilteredanys: any[] | null = null;
  private _lastCacheUpdate: number = 0;
  private readonly CACHE_DURATION = 5000;
  private _lastSessionHash: string = '';
  private _lastCacheKey: string = ''; // ✅ NOVO: Chave para cache mais eficiente

  constructor(private readonly championService: ChampionService) { }

  ngOnInit() {
    this.loadanys();

    // ✅ CORREÇÃO #4: Setup do debounce para pesquisa em tempo real
    this.searchSubject
      .pipe(
        debounceTime(50), // Reduzido para 50ms para resposta mais rápida
        distinctUntilChanged()
      )
      .subscribe((searchTerm) => {
        this.searchFilter = searchTerm;
        this.invalidateCache(); // Invalidar cache apenas quando o termo de pesquisa muda
        // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
        setTimeout(() => {}, 0);
      });

    // Inicializa com todos os campeões
    this.filteredChampions = [...this.champions];
  }

  ngOnDestroy() {
    this.searchSubject.complete();
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

    // ✅ REMOVIDO: Timer não é mais @Input, não precisa detectar mudanças aqui
    // O timer vem direto do window.appComponent.draftTimer
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
            console.log('🔍 [DraftanyModal] Primeiros 5 campeões:', this.champions.slice(0, 5).map(c => ({ id: c.id, name: c.name })));
            this.organizeChampionsByRole();
            // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
            setTimeout(() => {}, 0);

            // ✅ CORREÇÃO: Verificar status do timer após carregar campeões
            setTimeout(() => {
              this.checkTimerStatus();
            }, 100);
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
    // ✅ CORREÇÃO: Implementar organização por role manualmente
    console.log('🔄 [DraftanyModal] Organizando campeões por role...');

    this.championsByRole = {
      top: this.champions.filter(c => c.tags?.includes('Fighter') || c.tags?.includes('Tank')),
      jungle: this.champions.filter(c => c.tags?.includes('Fighter') || c.tags?.includes('Assassin')),
      mid: this.champions.filter(c => c.tags?.includes('Mage') || c.tags?.includes('Assassin')),
      adc: this.champions.filter(c => c.tags?.includes('Marksman')),
      support: this.champions.filter(c => c.tags?.includes('Support'))
    };

    console.log('✅ [DraftanyModal] Campeões organizados por role:', {
      top: this.championsByRole.top.length,
      jungle: this.championsByRole.jungle.length,
      mid: this.championsByRole.mid.length,
      adc: this.championsByRole.adc.length,
      support: this.championsByRole.support.length
    });

    // ✅ CORREÇÃO: Forçar atualização após organizar por role
    // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);
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
  // ✅ CORREÇÃO #3: Método corrigido para usar estrutura correta do backend
  getBannedChampions(): any[] {
    if (this.isCacheValid() && this._cachedBannedanys) {
      return this._cachedBannedanys;
    }

    if (!this.session) {
      return [];
    }

    let bannedanys: any[] = [];

    // ✅ CORREÇÃO: Backend envia "phases" (que contém actions) com estrutura: { type, team, championId, byPlayer }
    if (this.session.phases && this.session.phases.length > 0) {
      console.log('🔍 [getBannedChampions] Buscando bans nas phases (actions do backend)');

      const banActions = this.session.phases.filter((action: any) => {
        // ✅ CORREÇÃO: Backend envia "type" não "action"
        const isBan = action.type === 'ban';
        // ✅ CORREÇÃO: Backend envia "championId" e "byPlayer", não "champion" e "locked"
        const hasChampion = action.championId && action.byPlayer;
        return isBan && hasChampion;
      });

      console.log(`🔍 [getBannedChampions] Encontrados ${banActions.length} bans`);

      // ✅ Converter championId para objeto champion
      bannedanys = banActions
        .map((action: any) => {
          const championId = parseInt(action.championId, 10);
          const champion = this.getChampionFromCache(championId);
          if (!champion) {
            console.warn(`⚠️ [getBannedChampions] Campeão ${championId} não encontrado no cache`);
          }
          return champion;
        })
        .filter(champion => champion !== null);

      console.log(`✅ [getBannedChampions] ${bannedanys.length} campeões banidos carregados`);
    }

    this._cachedBannedanys = bannedanys;
    this._lastCacheUpdate = Date.now();

    return bannedanys;
  }

  // ✅ NOVO: Método auxiliar para buscar campeão do cache
  private getChampionFromCache(championId: number): any {
    const cache = (this.championService as any).championsCache as Map<string, any>;
    if (!cache) {
      console.warn('⚠️ [getChampionFromCache] Cache não disponível');
      return null;
    }

    // Tentar buscar diretamente pelo key
    for (const champ of cache.values()) {
      if (champ.key === championId.toString() || parseInt(champ.key, 10) === championId) {
        return champ;
      }
    }

    console.warn(`⚠️ [getChampionFromCache] Campeão ${championId} não encontrado em ${cache.size} campeões`);
    return null;
  }

  // ✅ CORREÇÃO #2: Método para obter URL correta da imagem do campeão
  getChampionImageUrl(champion: any): string {
    if (!champion) {
      return 'assets/img/champion_placeholder.png';
    }

    const DD_VERSION = '15.19.1';
    const DD_BASE_URL = `https://ddragon.leagueoflegends.com/cdn/${DD_VERSION}`;

    // Tentar usar champion.image.full (formato padrão do Data Dragon)
    if (champion.image?.full) {
      return `${DD_BASE_URL}/img/champion/${champion.image.full}`;
    }

    // Fallback: tentar usar champion.id (nome interno do campeão)
    if (champion.id) {
      return `${DD_BASE_URL}/img/champion/${champion.id}.png`;
    }

    // Último fallback: usar placeholder
    return 'assets/img/champion_placeholder.png';
  }

  // ✅ CORREÇÃO #3: Método corrigido para usar estrutura correta do backend
  getTeamPicks(team: 'blue' | 'red'): any[] {
    // ✅ CACHE: Verificar cache primeiro
    if (team === 'blue' && this.isCacheValid() && this._cachedBlueTeamPicks) {
      return this._cachedBlueTeamPicks;
    }
    if (team === 'red' && this.isCacheValid() && this._cachedRedTeamPicks) {
      return this._cachedRedTeamPicks;
    }

    if (!this.session) return [];

    let teamPicks: any[] = [];

    // ✅ CORREÇÃO: Backend envia "phases" com estrutura: { type, team, championId, byPlayer }
    if (this.session.phases && this.session.phases.length > 0) {
      const teamNumber = team === 'blue' ? 1 : 2;
      console.log(`🔍 [getTeamPicks] Buscando picks do time ${team} (teamNumber: ${teamNumber})`);

      const pickActions = this.session.phases.filter((action: any) => {
        // ✅ CORREÇÃO: Backend envia "type" não "action"
        const isPick = action.type === 'pick';
        // ✅ CORREÇÃO: Backend envia "team" como número (1 ou 2)
        const isCorrectTeam = action.team === teamNumber;
        // ✅ CORREÇÃO: Backend envia "championId" e "byPlayer"
        const hasChampion = action.championId && action.byPlayer;
        return isPick && isCorrectTeam && hasChampion;
      });

      console.log(`🔍 [getTeamPicks] Encontrados ${pickActions.length} picks para time ${team}`);

      // ✅ Converter championId para objeto champion
      teamPicks = pickActions
        .map((action: any) => {
          const championId = parseInt(action.championId, 10);
          const champion = this.getChampionFromCache(championId);
          if (!champion) {
            console.warn(`⚠️ [getTeamPicks] Campeão ${championId} não encontrado no cache`);
          }
          return champion;
        })
        .filter(champion => champion !== null);

      console.log(`✅ [getTeamPicks] ${teamPicks.length} picks do time ${team} carregados`);
    }

    // ✅ CACHE: Atualizar cache
    if (team === 'blue') {
      this._cachedBlueTeamPicks = teamPicks;
    } else {
      this._cachedRedTeamPicks = teamPicks;
    }
    this._lastCacheUpdate = Date.now();

    return teamPicks;
  }

  // ✅ CORREÇÃO #3: Método corrigido para comparação robusta
  isChampionBanned(champion: any): boolean {
    const bannedChampions = this.getBannedChampions();

    // ✅ DEBUG: Log para investigação
    if (champion.name === 'K\'Sante' || champion.name === 'Fizz' || champion.name === 'Xin Zhao' || champion.name === 'Cho\'Gath') {
      console.log(`🔍 [isChampionBanned] Verificando ${champion.name} (key: ${champion.key})`, {
        bannedCount: bannedChampions.length,
        bannedKeys: bannedChampions.map(b => b.key)
      });
    }

    // ✅ CORREÇÃO: Comparação usando key numérico (padrão do Data Dragon)
    const isBanned = bannedChampions.some(banned => {
      // Prioridade 1: Comparar por key numérico
      const bannedKey = banned.key?.toString();
      const championKey = champion.key?.toString();
      if (bannedKey && championKey && bannedKey === championKey) {
        return true;
      }

      // Prioridade 2: Comparar por ID (nome do campeão)
      if (banned.id && champion.id && banned.id === champion.id) {
        return true;
      }

      // Prioridade 3: Comparar por nome (fallback)
      if (banned.name && champion.name && banned.name === champion.name) {
        return true;
      }

      return false;
    });

    return isBanned;
  }

  // ✅ CORREÇÃO #3: Método corrigido para comparação robusta
  isChampionPicked(champion: any): boolean {
    const bluePicks = this.getTeamPicks('blue');
    const redPicks = this.getTeamPicks('red');

    // ✅ CORREÇÃO: Comparação usando key numérico (padrão do Data Dragon)
    const isPicked = [...bluePicks, ...redPicks].some(picked => {
      // Prioridade 1: Comparar por key numérico
      const pickedKey = picked.key?.toString();
      const championKey = champion.key?.toString();
      if (pickedKey && championKey && pickedKey === championKey) {
        return true;
      }

      // Prioridade 2: Comparar por ID (nome do campeão)
      if (picked.id && champion.id && picked.id === champion.id) {
        return true;
      }

      // Prioridade 3: Comparar por nome (fallback)
      if (picked.name && champion.name && picked.name === champion.name) {
        return true;
      }

      return false;
    });

    return isPicked;
  }

  // MÉTODOS PARA FILTRAGEM
  getModalFilteredChampions(): any[] {
    // ✅ OTIMIZAÇÃO: Verificar mudanças de sessão apenas quando necessário
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

    // ✅ OTIMIZAÇÃO: Usar cache mais eficientemente
    const cacheKey = `${this.searchFilter}-${this.selectedRole}-${this.session?.currentAction || 0}`;
    if (this.isCacheValid() && this._cachedModalFilteredanys && this._lastCacheKey === cacheKey) {
      return this._cachedModalFilteredanys;
    }

    let filtered = this.champions;

    // ✅ OTIMIZAÇÃO: Filtrar por role primeiro (mais eficiente)
    if (this.selectedRole !== 'all') {
      filtered = this.getChampionsByRole(this.selectedRole);
    }

    // ✅ OTIMIZAÇÃO: Filtrar por busca apenas se houver termo
    if (this.searchFilter?.trim()) {
      const searchTerm = this.searchFilter.toLowerCase().trim();
      filtered = filtered.filter(champion =>
        champion.name.toLowerCase().includes(searchTerm)
      );
    }

    // ✅ OTIMIZAÇÃO: Atualizar cache com chave
    this._cachedModalFilteredanys = filtered;
    this._lastCacheUpdate = Date.now();
    this._lastCacheKey = cacheKey;

    return filtered;
  }

  // ✅ NOVO: Método otimizado para buscar campeões por role
  private getChampionsByRole(role: string): any[] {
    switch (role) {
      case 'top':
        return this.championsByRole.top || [];
      case 'jungle':
        return this.championsByRole.jungle || [];
      case 'mid':
        return this.championsByRole.mid || [];
      case 'adc':
        return this.championsByRole.adc || [];
      case 'support':
        return this.championsByRole.support || [];
      default:
        return this.champions;
    }
  }

  // MÉTODOS PARA SELEÇÃO
  selectRoleInModal(role: string): void {
    this.selectedRole = role;
    this.invalidateCache(); // ✅ Necessário invalidar quando role muda
    // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);
  }

  // ✅ CORREÇÃO #4: Método para pesquisa em tempo real com debounce
  onSearchChange(searchTerm: string): void {
    this.searchSubject.next(searchTerm);
  }

  // ✅ NOVO: Propriedade computada para evitar múltiplas chamadas no template
  get filteredChampionsList(): any[] {
    return this.getModalFilteredChampions();
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
    console.log('👉 [onanyCardClick] Campeão selecionado, aguardando confirmação do usuário...');
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
    // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);
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
    console.log('🟣 [CHAMPION-MODAL] === CONFIRMANDO SELEÇÃO ===');
    console.log('🟣 [CHAMPION-MODAL] selectedChampion existe?', !!this.selectedany);

    if (!this.selectedany) {
      console.log('❌ [CHAMPION-MODAL] Nenhum campeão selecionado');
      return;
    }

    console.log('🟣 [CHAMPION-MODAL] Champion:', this.selectedany);
    console.log('🟣 [CHAMPION-MODAL] Champion.name:', this.selectedany.name);
    console.log('🟣 [CHAMPION-MODAL] Champion.id:', this.selectedany.id);
    console.log('🟣 [CHAMPION-MODAL] Champion.key:', this.selectedany.key);

    if (this.isChampionBanned(this.selectedany) || this.isChampionPicked(this.selectedany)) {
      console.log('❌ [CHAMPION-MODAL] Campeão banido ou já escolhido');
      return;
    }

    // ✅ CORREÇÃO: Log detalhado da seleção para debug
    console.log('🎯 [confirmModalSelection] === CONFIRMANDO SELEÇÃO ===');
    console.log('🎯 [confirmModalSelection] Campeão selecionado:', this.selectedany.name);
    console.log('🎯 [confirmModalSelection] ID do campeão:', this.selectedany.id);
    console.log('🎯 [confirmModalSelection] Sessão atual:', this.session?.currentAction);
    console.log('🎯 [confirmModalSelection] Fase atual:', this.session?.phases?.[this.session?.currentAction || 0]);

    // ✅ CRÍTICO: Guardar referência antes de limpar
    const championToEmit = this.selectedany;
    console.log('🟣 [CHAMPION-MODAL] Champion a ser emitido:', championToEmit);

    // ✅ CORREÇÃO: Emitir o campeão selecionado ANTES de fechar
    console.log('📤 [CHAMPION-MODAL] EMITINDO EVENTO onChampionSelected...');
    console.log('📤 [confirmModalSelection] EMITINDO EVENTO onanySelected...');
    this.onanySelected.emit(championToEmit);
    console.log('✅ [CHAMPION-MODAL] Evento onChampionSelected EMITIDO!');
    console.log('✅ [confirmModalSelection] Evento emitido com sucesso');

    // ✅ CORREÇÃO: Fechar modal com delay para garantir que o evento seja processado
    setTimeout(() => {
      console.log('🚪 [confirmModalSelection] Fechando modal...');

      // ✅ CORREÇÃO: Limpar seleção e cache
      this.selectedany = null;
      this.invalidateCache();

      // ✅ CORREÇÃO: Fechar modal
      this.closeModal();

      // ✅ CORREÇÃO: Forçar atualização
      // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);

      console.log('✅ [confirmModalSelection] Modal fechado');
    }, 100); // Delay de 100ms para garantir processamento do evento
  }

  cancelModalSelection(): void {
    this.closeModal();
  }

  // MÉTODOS PARA CONTROLE DO MODAL
  openModal(): void {
    this.isVisible = true;

    this.invalidateCache();

    this.loadanys();

    // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);
  }

  closeModal(): void {
    this.isVisible = false;

    this.selectedany = null;
    this.searchFilter = '';
    this.selectedRole = 'all';

    this.onClose.emit();

    // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);
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
      console.log('🔄 [DraftanyModal] Modal aberto - recarregando campeões...');
      console.log('⏰ [DraftanyModal] Timer atual no modal:', this.getDraftTimer());
      this.invalidateCache();
      this.loadanys(); // ✅ CORREÇÃO: Recarregar campeões quando modal abrir
      // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);

      // ✅ CORREÇÃO: Forçar atualização adicional para garantir que o timer seja exibido
      setTimeout(() => {
        // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);
        console.log('⏰ [DraftanyModal] Timer após timeout:', this.getDraftTimer());
      }, 100);

      // ✅ CORREÇÃO: Verificar status do timer
      setTimeout(() => {
        this.checkTimerStatus();
      }, 200);
    }
  }

  // ✅ NOVO: Método para forçar atualização do timer
  forceTimerUpdate(): void {
    console.log('⏰ [DraftanyModal] Forçando atualização do timer:', this.getDraftTimer());
    // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);
    // ✅ CORREÇÃO: Usar setTimeout para forçar detecção de mudanças
    setTimeout(() => {}, 0);
  }

  // ✅ NOVO: Método para verificar se o timer está funcionando
  checkTimerStatus(): void {
    console.log('⏰ [DraftanyModal] Status do timer:', {
      timeRemaining: this.getDraftTimer(),
      isVisible: this.isVisible,
      session: !!this.session,
      currentAction: this.session?.currentAction
    });
  }

  /**
   * ✅ TIMER: Pega timer do app.ts (variável separada)
   */
  getDraftTimer(): number {
    return (window as any).appComponent?.draftTimer || 30;
  }
}
