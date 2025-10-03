import { Component, Input, Output, EventEmitter, OnInit, OnDestroy, ChangeDetectorRef, ChangeDetectionStrategy, OnChanges, SimpleChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ChampionService } from '../../services/champion.service';
import { BotService } from '../../services/bot.service';
import { DraftanyModalComponent } from './draft-champion-modal';
import { DraftConfirmationModalComponent } from './draft-confirmation-modal';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../services/api';

// ✅ MELHORADO: Sistema de logs mais robusto
function logDraft(...args: any[]) {
  const fs = (window as any).electronAPI?.fs;
  const path = (window as any).electronAPI?.path;
  const process = (window as any).electronAPI?.process;
  const logPath = path && process ? path.join(process.cwd(), 'draft.log') : '';
  const logLine = `[${new Date().toISOString()}] [DraftPickBan] ` + args.map(a => (typeof a === 'object' ? JSON.stringify(a) : a)).join(' ') + '\n';
  if (fs && logPath) {
    fs.appendFile(logPath, logLine, (err: any) => {
      if (err) console.error('Erro ao escrever log:', err);
    });
  }
  console.log('[DraftPickBan]', ...args);
}

// ✅ NOVO: Função para salvar logs na raiz do projeto
function saveLogToRoot(message: string, filename: string = 'draft-debug.log') {
  const fs = (window as any).electronAPI?.fs;
  const path = (window as any).electronAPI?.path;
  const process = (window as any).electronAPI?.process;

  if (fs && path && process) {
    const rootPath = process.cwd();
    const logPath = path.join(rootPath, filename);
    const timestamp = new Date().toISOString();
    const logLine = `[${timestamp}] ${message}\n`;

    fs.appendFile(logPath, logLine, (err: any) => {
      if (err) {
        console.error('Erro ao salvar log na raiz:', err);
      } else {
        console.log(`Log salvo em: ${logPath}`);
      }
    });
  }
}

@Component({
  selector: 'app-draft-pick-ban',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    DraftanyModalComponent,
    DraftConfirmationModalComponent
  ],
  templateUrl: './draft-pick-ban.html',
  styleUrl: './draft-pick-ban.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DraftPickBanComponent implements OnInit, OnDestroy, OnChanges {
  @Input() matchData: any = null;
  @Input() currentPlayer: any = null;
  @Output() draftComplete = new EventEmitter<any>();
  @Output() onPickBanComplete = new EventEmitter<any>();
  @Output() onPickBanCancel = new EventEmitter<void>();
  @Output() onOpenanyModal = new EventEmitter<void>();
  @Output() onOpenConfirmationModal = new EventEmitter<void>();

  // ✅ PROPRIEDADES SIMPLIFICADAS - apenas para exibição
  session: any = null;
  champions: any[] = [];
  championsByRole: any = {};
  showChampionModal: boolean = false; // ✅ CORREÇÃO: Nome correto para o HTML
  showConfirmationModal: boolean = false;
  confirmationData: any = null; // ✅ NOVO: Dados de confirmação dos jogadores
  isMyTurn: boolean = false;
  isWaitingBackend: boolean = false;
  timeRemaining: number = 30; // ✅ CORREÇÃO: Este valor será sempre atualizado pelo backend via WebSocket
  matchId: number | null = null;
  currentPlayerTurn: any = null;
  realTimeSyncTimer: number | null = null;
  championsLoaded: boolean = false;

  // ✅ NOVO: Propriedades para modo de edição
  isEditingMode: boolean = false;
  currentEditingPlayer: { playerId: string, phaseIndex: number } | null = null;

  // ✅ NOVO: Controles de concorrência
  private ngOnChangesDebounceTimer: number | null = null;
  private isInitializing: boolean = false;
  private lastMatchDataHash: string = '';
  private updateInProgress: boolean = false;

  @ViewChild('confirmationModal') confirmationModal!: DraftConfirmationModalComponent;
  private readonly baseUrl: string;

  constructor(
    public championService: ChampionService,
    public botService: BotService,
    public cdr: ChangeDetectorRef,
    private readonly http: HttpClient,
    private readonly apiService: ApiService
  ) {
    this.baseUrl = this.apiService.getBaseUrl();
    logDraft('[DraftPickBan] Constructor inicializado');
  }

  ngOnInit() {
    logDraft('🚀 [DraftPickBan] ngOnInit iniciado');
    saveLogToRoot(`🚀 [DraftPickBan] ngOnInit iniciado`);

    // ✅ NOVO: Marcar que estamos inicializando para evitar conflitos
    this.isInitializing = true;

    // ✅ CRÍTICO: Carregar campeões do Data Dragon imediatamente
    this.loadChampionsForDraft();

    // ✅ Configurar listener para mensagens do backend
    this.setupBackendListeners();

    // ✅ NOVO: Aguardar ngOnChanges processar primeiro se matchData existir
    if (this.matchData) {
      // ✅ NOVO: Aguardar um pouco para ngOnChanges processar primeiro
      setTimeout(() => {
        this.finishInitialization();
      }, 100);
    } else {
      this.finishInitialization();
    }
  }

  private finishInitialization(): void {
    try {
      if (this.matchData) {
        logDraft('🚀 [DraftPickBan] matchData recebido:', this.matchData);
        saveLogToRoot(`🚀 [finishInitialization] matchData recebido`);

        // ✅ CORREÇÃO: Garantir que matchId seja sempre definido
        this.matchId = this.matchData.matchId || this.matchData.id || null;
        saveLogToRoot(`🚀 [finishInitialization] matchId definido: ${this.matchId}`);

        // ✅ NOVO: Só inicializar se session não foi criada por ngOnChanges
        if (!this.session) {
          saveLogToRoot(`🚀 [finishInitialization] Session não existe - inicializando`);
          this.initializeSessionFromMatchData();
        } else {
          saveLogToRoot(`✅ [finishInitialization] Session já existe - pulando inicialização`);
        }

        // ✅ NOVO: Tentar recuperar backup se disponível
        this.tryRestoreFromBackup();

        // ✅ Carregar campeões
        this.loadChampions();

        // ✅ Iniciar sincronização periódica da sessão
        this.startSessionSync();

        // ✅ CORREÇÃO: Garantir que o estado inicial seja processado
        setTimeout(() => {
          if (this.session) {
            saveLogToRoot(`🔄 [finishInitialization] Processando estado inicial do draft`);
            this.updateDraftState();
          }
        }, 100);
      } else {
        saveLogToRoot(`❌ [finishInitialization] matchData não recebido`);
      }
    } finally {
      // ✅ NOVO: Marcar inicialização como concluída
      this.isInitializing = false;
      saveLogToRoot(`✅ [finishInitialization] Inicialização concluída`);
    }
  }

  ngOnDestroy() {
    logDraft('[DraftPickBan] === ngOnDestroy INICIADO ===');
    saveLogToRoot(`🔄 [ngOnDestroy] Destruindo componente. Session exists: ${!!this.session}, actions: ${this.session?.actions?.length || 0}`);

    // ✅ NOVO: Preservar dados importantes antes da destruição
    if (this.session?.actions?.length > 0) {
      saveLogToRoot(`💾 [ngOnDestroy] Preservando ${this.session.actions.length} ações do draft antes da destruição`);
      saveLogToRoot(`💾 [ngOnDestroy] Ações preservadas: ${JSON.stringify(this.session.actions.map((a: any) => ({ champion: a.champion?.name, action: a.action, player: a.playerName })))}`);

      // Salvar no localStorage como backup
      try {
        const backupData = {
          matchId: this.matchId,
          session: this.session,
          timestamp: Date.now()
        };
        localStorage.setItem(`draftBackup_${this.matchId}`, JSON.stringify(backupData));
        saveLogToRoot(`💾 [ngOnDestroy] Backup salvo no localStorage para match ${this.matchId}`);
      } catch (error) {
        saveLogToRoot(`❌ [ngOnDestroy] Erro ao salvar backup: ${error}`);
      }
    }

    // ✅ NOVO: Limpar timers de debounce
    if (this.ngOnChangesDebounceTimer) {
      clearTimeout(this.ngOnChangesDebounceTimer);
      this.ngOnChangesDebounceTimer = null;
    }

    this.stopSessionSync();
    saveLogToRoot(`✅ [ngOnDestroy] Componente destruído com sucesso`);
    logDraft('[DraftPickBan] === ngOnDestroy CONCLUÍDO ===');
  }

  ngOnChanges(changes: SimpleChanges) {
    // ✅ CRÍTICO: Processar imediatamente (sem debounce) para OnPush funcionar
    console.log('🔥🔥🔥 [ngOnChanges] CHAMADO!', {
      hasMatchData: !!changes['matchData'],
      matchDataValue: changes['matchData']?.currentValue,
      currentAction: changes['matchData']?.currentValue?.currentAction,
      currentPlayer: changes['matchData']?.currentValue?.currentPlayer,
      timeRemaining: changes['matchData']?.currentValue?.timeRemaining,
      previousValue: changes['matchData']?.previousValue?.currentAction,
      isFirstChange: changes['matchData']?.firstChange
    });
    this.processNgOnChanges(changes);

    // ✅ DESABILITADO: Debouncing estava atrasando a atualização
    /*
    if (this.ngOnChangesDebounceTimer) {
      clearTimeout(this.ngOnChangesDebounceTimer);
    }
    this.ngOnChangesDebounceTimer = window.setTimeout(() => {
      this.processNgOnChanges(changes);
    }, 50);
    */
  }

  private processNgOnChanges(changes: SimpleChanges) {
    if (this.updateInProgress) {
      saveLogToRoot(`⚠️ [processNgOnChanges] Update em progresso - ignorando mudança`);
      return;
    }

    if (changes['matchData']?.currentValue) {
      const currentValue = changes['matchData'].currentValue;

      // ✅ NOVO: Verificar se dados realmente mudaram usando hash
      const dataHash = JSON.stringify(currentValue).substring(0, 100); // Primeiros 100 chars como hash
      if (dataHash === this.lastMatchDataHash) {
        saveLogToRoot(`⏭️ [processNgOnChanges] Dados idênticos - ignorando`);
        return;
      }
      this.lastMatchDataHash = dataHash;

      logDraft('🔄 [DraftPickBan] === ngOnChanges CHAMADO ===');
      saveLogToRoot(`🔄 [processNgOnChanges] matchData alterado (hash: ${dataHash.substring(0, 20)}...)`);

      this.updateInProgress = true;

      try {
        // ✅ CORREÇÃO: Garantir que matchId seja sempre atualizado
        const newMatchId = currentValue.matchId || currentValue.id || null;
        if (newMatchId !== this.matchId) {
          this.matchId = newMatchId;
          saveLogToRoot(`🔄 [processNgOnChanges] matchId atualizado: ${this.matchId}`);
        }

        // ✅ MELHORADO: Só inicializar se não estivermos no meio de uma inicialização
        if (!this.session || (this.session.currentAction === undefined && !this.isInitializing)) {
          // ✅ CORREÇÃO CRÍTICA: Atualizar matchData ANTES de inicializar
          this.matchData = currentValue;
          saveLogToRoot(`🔄 [processNgOnChanges] matchData atualizado, chamando initializeSessionFromMatchData()`);

          // ✅ Chamar método que faz mapeamento correto de team1/team2 → blueTeam/redTeam
          this.initializeSessionFromMatchData();

          saveLogToRoot(`✅ [processNgOnChanges] Session inicializada via initializeSessionFromMatchData: matchId=${this.matchId}`);
          saveLogToRoot(`  - blueTeam: ${this.session?.blueTeam?.length || 0} jogadores`);
          saveLogToRoot(`  - redTeam: ${this.session?.redTeam?.length || 0} jogadores`);

          this.updateDraftState();
        } else {
          // ✅ CORREÇÃO: Mesclar novos dados com os existentes, mas PERMITIR atualização de currentAction e phases
          const oldBlueTeam = this.session.blueTeam || [];
          const oldRedTeam = this.session.redTeam || [];

          // ✅ LOGS DETALHADOS para debug
          console.log('🔍 [processNgOnChanges] currentValue recebido:', {
            hasPhases: !!currentValue.phases,
            phasesLength: currentValue.phases?.length || 0,
            hasActions: !!currentValue.actions,
            actionsLength: currentValue.actions?.length || 0,
            currentAction: currentValue.currentAction,
            currentIndex: currentValue.currentIndex,
            currentPlayer: currentValue.currentPlayer,
            phasesFirst3: currentValue.phases?.slice(0, 3) || []
          });

          this.session = {
            ...this.session,
            ...currentValue,
            // ✅ ATUALIZAR phases/actions se vierem novos valores com elementos
            phases: (currentValue.phases && currentValue.phases.length > 0) ? currentValue.phases :
              (currentValue.actions && currentValue.actions.length > 0) ? currentValue.actions :
                (this.session.phases && this.session.phases.length > 0) ? this.session.phases : [],
            // ✅ ATUALIZAR currentAction se vier novo valor
            currentAction: currentValue.currentAction !== undefined ? currentValue.currentAction :
              currentValue.currentIndex !== undefined ? currentValue.currentIndex :
                this.session.currentAction,
            // ✅ ATUALIZAR currentPlayer se vier novo valor
            currentPlayer: currentValue.currentPlayer !== undefined ? currentValue.currentPlayer : this.session.currentPlayer,
            // ✅ PRESERVAR times se não vierem novos
            blueTeam: (currentValue.blueTeam && currentValue.blueTeam.length > 0) ? currentValue.blueTeam :
              (currentValue.team1 && currentValue.team1.length > 0) ? currentValue.team1 :
                oldBlueTeam,
            redTeam: (currentValue.redTeam && currentValue.redTeam.length > 0) ? currentValue.redTeam :
              (currentValue.team2 && currentValue.team2.length > 0) ? currentValue.team2 :
                oldRedTeam
          };

          // ✅ CORREÇÃO CRÍTICA: Atualizar timer via @Input (OnPush funciona)
          if (currentValue.timeRemaining !== undefined) {
            this.timeRemaining = currentValue.timeRemaining;
            console.log(`⏰ [processNgOnChanges] Timer atualizado via @Input: ${this.timeRemaining}s`);
          }

          // ✅ NOVA ESTRUTURA HIERÁRQUICA: Processar teams.blue/red se existirem
          if (currentValue.teams) {
            console.log('🔨 [processNgOnChanges] Estrutura hierárquica detectada:', {
              hasBlue: !!currentValue.teams.blue,
              hasRed: !!currentValue.teams.red,
              bluePlayers: currentValue.teams.blue?.players?.length || 0,
              redPlayers: currentValue.teams.red?.players?.length || 0,
              currentPhase: currentValue.currentPhase,
              currentTeam: currentValue.currentTeam
            });

            // ✅ Armazenar estrutura hierárquica na session
            this.session.teams = currentValue.teams;
            this.session.currentPhase = currentValue.currentPhase;
            this.session.currentTeam = currentValue.currentTeam;
            this.session.currentActionType = currentValue.currentActionType;

            // ✅ ATUALIZAR blueTeam/redTeam a partir da estrutura hierárquica
            if (currentValue.teams.blue?.players?.length > 0) {
              this.session.blueTeam = currentValue.teams.blue.players;
            }
            if (currentValue.teams.red?.players?.length > 0) {
              this.session.redTeam = currentValue.teams.red.players;
            }

            console.log('✅ [processNgOnChanges] Estrutura hierárquica processada:', {
              blueTeamSize: this.session.blueTeam?.length || 0,
              redTeamSize: this.session.redTeam?.length || 0,
              currentPhase: this.session.currentPhase,
              currentTeam: this.session.currentTeam
            });
          }

          console.log('✅ [processNgOnChanges] Session após atualização:', {
            phasesLength: this.session.phases?.length || 0,
            currentAction: this.session.currentAction,
            currentPlayer: this.session.currentPlayer,
            hasTeams: !!this.session.teams,
            timeRemaining: this.timeRemaining,
            phasesFirst3: this.session.phases?.slice(0, 3) || []
          });

          saveLogToRoot(`⏭️ [processNgOnChanges] Session atualizada: currentAction=${this.session.currentAction}, currentPlayer=${this.session.currentPlayer}, phases=${this.session.phases?.length || 0}`);
        }
      } finally {
        this.updateInProgress = false;
      }
    }

    if (changes['currentPlayer']) {
      const currentValue = changes['currentPlayer'].currentValue;
      saveLogToRoot(`🔄 [processNgOnChanges] currentPlayer alterado: ${JSON.stringify(currentValue)}`);
    }
  }

  // ✅ CONFIGURAÇÃO DE LISTENERS DO BACKEND
  private setupBackendListeners(): void {
    console.log('🎯 [setupBackendListeners] Configurando listeners - matchId atual:', this.matchId);

    // Listener para mensagens de timer via WebSocket
    document.addEventListener('draftTimerUpdate', (event: any) => {
      console.log('⏰⏰⏰ [draftTimerUpdate] EVENTO RECEBIDO!', {
        eventMatchId: event.detail?.matchId,
        componentMatchId: this.matchId,
        timeRemaining: event.detail?.timeRemaining,
        matches: event.detail?.matchId === this.matchId
      });

      // ✅ CRÍTICO: Comparar como números (converter strings)
      const eventMatchId = Number(event.detail?.matchId);
      const componentMatchId = Number(this.matchId);

      if (eventMatchId === componentMatchId) {
        console.log('✅ [draftTimerUpdate] MatchId BATE! Atualizando timer...');
        logDraft('⏰ [DraftPickBan] Timer atualizado via WebSocket:', event.detail);
        this.updateTimerFromBackend(event.detail);
      } else {
        console.warn('⚠️ [draftTimerUpdate] MatchId NÃO BATE!', {
          eventMatchId,
          componentMatchId,
          eventType: typeof event.detail?.matchId,
          componentType: typeof this.matchId
        });
      }
    });

    // Listener para timeouts via WebSocket
    document.addEventListener('draftTimeout', (event: any) => {
      if (event.detail?.matchId === this.matchId) {
        logDraft('⏰ [DraftPickBan] Timeout recebido via WebSocket:', event.detail);
        this.handleDraftTimeout(event.detail);
      }
    });

    // Listener para draftUpdate via WebSocket
    document.addEventListener('draftUpdate', (event: any) => {
      console.log('🔄🔄🔄 [draftUpdate] EVENTO RECEBIDO!', {
        eventMatchId: event.detail?.matchId,
        componentMatchId: this.matchId,
        hasPhases: !!(event.detail?.phases || event.detail?.actions),
        currentAction: event.detail?.currentAction,
        currentIndex: event.detail?.currentIndex,
        currentPlayer: event.detail?.currentPlayer,
        timeRemaining: event.detail?.timeRemaining,
        matches: event.detail?.matchId === this.matchId
      });

      // ✅ CRÍTICO: Comparar como números (converter strings)
      const eventMatchId = Number(event.detail?.matchId);
      const componentMatchId = Number(this.matchId);

      if (eventMatchId === componentMatchId) {
        console.log('✅ [draftUpdate] MatchId BATE! Processando update...');
        logDraft('🔄 [DraftPickBan] draftUpdate recebido via WebSocket:', event.detail);
        saveLogToRoot(`🚀 [WebSocket] draftUpdate recebido - atualizando session diretamente`);

        // ✅ CRÍTICO: Atualizar session diretamente como fallback (caso ngOnChanges não dispare)
        const updateData = event.detail;
        if (this.session) {
          const newPhases = (updateData.phases && updateData.phases.length > 0) ? updateData.phases :
            (updateData.actions && updateData.actions.length > 0) ? updateData.actions :
              this.session.phases;

          const newCurrentAction = updateData.currentAction !== undefined ? updateData.currentAction :
            updateData.currentIndex !== undefined ? updateData.currentIndex :
              this.session.currentAction;

          const newCurrentPlayer = updateData.currentPlayer !== undefined ? updateData.currentPlayer : this.session.currentPlayer;
          const newTimeRemaining = updateData.timeRemaining !== undefined ? updateData.timeRemaining : this.timeRemaining;

          console.log('🔄 [draftUpdate] Valores extraídos:', {
            newPhases: newPhases?.length || 0,
            newCurrentAction,
            newCurrentPlayer,
            newTimeRemaining
          });

          console.log('🔄 [draftUpdate] Atualizando session:', {
            oldPhases: this.session.phases?.length || 0,
            newPhases: newPhases?.length || 0,
            oldCurrentAction: this.session.currentAction,
            newCurrentAction: newCurrentAction,
            oldCurrentPlayer: this.session.currentPlayer,
            newCurrentPlayer: newCurrentPlayer,
            newTimeRemaining: newTimeRemaining
          });

          // ✅ Criar novo objeto session para garantir detecção de mudança
          this.session = {
            ...this.session,
            phases: newPhases,
            actions: newPhases,
            currentAction: newCurrentAction,
            currentIndex: newCurrentAction,
            currentPlayer: newCurrentPlayer
          };

          console.log(`⏰ [draftUpdate] Timer recebido: ${newTimeRemaining}s`);

          // ✅ CORREÇÃO: Atualizar timer usando método dedicado (evita duplicação)
          if (updateData.timeRemaining !== undefined) {
            this.updateTimerFromBackend({ timeRemaining: newTimeRemaining });
          } else {
            // Fallback se timeRemaining não vier no updateData
            this.timeRemaining = newTimeRemaining;
            this.cdr.markForCheck();
          }

          // ✅ Atualizar estado do draft
          this.updateDraftState();

          // ✅ CRÍTICO: Forçar detecção de mudanças SEMPRE
          this.cdr.markForCheck();
          this.cdr.detectChanges();
        } else {
          console.warn('⚠️ [draftUpdate] session é null!');
        }
      } else {
        console.warn('⚠️ [draftUpdate] MatchId não bate!', {
          eventMatchId: event.detail?.matchId,
          componentMatchId: this.matchId
        });
      }
    });

    // ✅ NOVO: Listener para draft_action (principal evento do backend)
    document.addEventListener('draft_action', (event: any) => {
      if (event.detail?.matchId === this.matchId) {
        logDraft('🎯 [DraftPickBan] draft_action recebido via WebSocket:', event.detail);
        saveLogToRoot(`🚀 [WebSocket] draft_action recebido - dados atualizados via ngOnChanges`);
        // ✅ CORREÇÃO: NÃO sincronizar - dados chegam via draft_updated
        this.cdr.detectChanges();
      }
    });

    // ✅ NOVO: Listener para ações de draft específicas (mais granular)
    document.addEventListener('draftActionCompleted', (event: any) => {
      if (event.detail?.matchId === this.matchId) {
        logDraft('🎯 [DraftPickBan] draftActionCompleted recebido via WebSocket:', event.detail);
        saveLogToRoot(`🚀 [WebSocket] draftActionCompleted recebido - dados atualizados via ngOnChanges`);
        // ✅ CORREÇÃO: NÃO sincronizar - dados chegam via draft_updated
        this.cdr.detectChanges();
      }
    });

    // ✅ NOVO: Listener para mudanças de fase
    document.addEventListener('draftPhaseChanged', (event: any) => {
      if (event.detail?.matchId === this.matchId) {
        logDraft('🔄 [DraftPickBan] draftPhaseChanged recebido via WebSocket:', event.detail);
        saveLogToRoot(`🚀 [WebSocket] draftPhaseChanged recebido - dados atualizados via ngOnChanges`);
        // ✅ CORREÇÃO: NÃO sincronizar - dados chegam via draft_updated
        this.cdr.detectChanges();
      }
    });

    // ✅ NOVO: Listener para confirmações do draft
    document.addEventListener('draft_confirmation_update', (event: any) => {
      if (event.detail?.matchId === this.matchId) {
        logDraft('🎯 [DraftPickBan] draft_confirmation_update recebido via WebSocket:', event.detail);
        saveLogToRoot(`✅ [WebSocket] Confirmação do draft atualizada - sincronizando`);

        // ✅ NOVO: Atualizar dados de confirmação diretamente
        this.confirmationData = {
          confirmations: event.detail.confirmations,
          allConfirmed: event.detail.allConfirmed
        };

        logDraft('🔄 [DraftPickBan] Dados de confirmação atualizados:', this.confirmationData);
        this.cdr.detectChanges();

        // ✅ CRÍTICO: NÃO sincronizar - dados já estão atualizados via WebSocket
        // this.syncSessionWithBackend();
      }
    });

    // ✅ NOVO: Listener para quando todos confirmaram (jogo pronto)
    document.addEventListener('game_ready', (event: any) => {
      if (event.detail?.matchId === this.matchId) {
        logDraft('🎯 [DraftPickBan] game_ready recebido via WebSocket:', event.detail);
        saveLogToRoot(`✅ [WebSocket] Jogo pronto - todos confirmaram`);

        // ✅ NOVO: Fechar modal de confirmação e emitir evento de conclusão
        this.showConfirmationModal = false;
        this.confirmationData = event.detail.pickBanData || this.confirmationData;

        this.onPickBanComplete.emit({
          matchData: this.matchData,
          session: this.session,
          confirmationData: this.confirmationData,
          status: 'in_progress'
        });

        this.cdr.detectChanges();
      }
    });

    // ✅ NOVO: Listener para alterações de pick
    document.addEventListener('draft_pick_changed', (event: any) => {
      if (event.detail?.matchId === this.matchId) {
        logDraft('🔄 [DraftPickBan] draft_pick_changed recebido via WebSocket:', event.detail);
        saveLogToRoot(`🔄 [WebSocket] Pick alterado - aguardando ngOnChanges`);
        // ✅ CRÍTICO: NÃO sincronizar - dados já estão atualizados via WebSocket
        // this.syncSessionWithBackend();
      }
    });
  }

  // ✅ NOVO: Tentar restaurar dados do backup
  private tryRestoreFromBackup(): void {
    if (!this.matchId) {
      saveLogToRoot(`❌ [tryRestoreFromBackup] matchId não disponível`);
      return;
    }

    try {
      const backupKey = `draftBackup_${this.matchId}`;
      const backupData = localStorage.getItem(backupKey);

      if (backupData) {
        const backup = JSON.parse(backupData);
        const now = Date.now();
        const backupAge = now - backup.timestamp;

        // Só usar backup se for de menos de 5 minutos
        if (backupAge < 5 * 60 * 1000) {
          saveLogToRoot(`🔄 [tryRestoreFromBackup] Backup encontrado (${Math.round(backupAge / 1000)}s atrás), restaurando dados`);

          if (backup.session?.actions?.length > 0) {
            // Mesclar dados do backup com a sessão atual
            this.session = {
              ...this.session,
              actions: backup.session.actions || [],
              team1Picks: backup.session.team1Picks || [],
              team1Bans: backup.session.team1Bans || [],
              team2Picks: backup.session.team2Picks || [],
              team2Bans: backup.session.team2Bans || [],
              currentAction: backup.session.currentAction || this.session?.currentAction || 0
            };

            saveLogToRoot(`✅ [tryRestoreFromBackup] ${backup.session.actions.length} ações restauradas do backup`);
            saveLogToRoot(`✅ [tryRestoreFromBackup] currentAction: ${this.session.currentAction}`);
          }

          // Não remover o backup ainda, só em caso de sucesso
        } else {
          saveLogToRoot(`⏰ [tryRestoreFromBackup] Backup muito antigo (${Math.round(backupAge / 1000)}s), ignorando`);
          localStorage.removeItem(backupKey);
        }
      } else {
        saveLogToRoot(`ℹ️ [tryRestoreFromBackup] Nenhum backup encontrado para match ${this.matchId}`);
      }
    } catch (error) {
      saveLogToRoot(`❌ [tryRestoreFromBackup] Erro ao restaurar backup: ${error}`);
    }
  }

  // ✅ MELHORADO: Inicialização da sessão com logs detalhados
  private initializeSessionFromMatchData(): void {
    if (!this.matchData) {
      saveLogToRoot(`❌ [initializeSessionFromMatchData] matchData não disponível`);
      return;
    }

    logDraft('🚀 [DraftPickBan] Inicializando sessão com dados do matchData');
    saveLogToRoot(`🚀 [initializeSessionFromMatchData] Inicializando sessão`);

    // ✅ Extrair phases/actions com verificação
    const phases = (this.matchData.phases && this.matchData.phases.length > 0) ? this.matchData.phases :
      (this.matchData.actions && this.matchData.actions.length > 0) ? this.matchData.actions : [];

    const currentAction = this.matchData.currentAction !== undefined ? this.matchData.currentAction :
      this.matchData.currentIndex !== undefined ? this.matchData.currentIndex : 0;

    console.log('🎯 [initializeSessionFromMatchData] Dados recebidos:', {
      hasPhases: !!this.matchData.phases,
      phasesLength: this.matchData.phases?.length,
      hasActions: !!this.matchData.actions,
      actionsLength: this.matchData.actions?.length,
      extractedPhasesLength: phases.length,
      currentAction: currentAction,
      currentPlayer: this.matchData.currentPlayer  // ✅ Log do currentPlayer
    });

    saveLogToRoot(`🎯 [initializeSessionFromMatchData] phases=${phases.length}, currentAction=${currentAction}, currentPlayer=${this.matchData.currentPlayer}, matchData.phases=${this.matchData.phases?.length}, matchData.actions=${this.matchData.actions?.length}`);

    this.session = {
      id: this.matchData.matchId || 0,
      blueTeam: this.matchData.blueTeam || this.matchData.team1 || [],
      redTeam: this.matchData.redTeam || this.matchData.team2 || [],
      phases: phases,
      currentAction: currentAction,
      currentPlayer: this.matchData.currentPlayer,  // ✅ CRÍTICO: Incluir jogador da vez
      currentPlayerIndex: 0,
      extendedTime: 0,
      phase: this.matchData.phase || 'bans'
    };

    // ✅ Normalizar dados dos times
    this.session.blueTeam = (this.session.blueTeam || []).map((p: any) => ({
      ...p,
      summonerName: p.summonerName || p.name,
      teamIndex: 1
    }));

    this.session.redTeam = (this.session.redTeam || []).map((p: any) => ({
      ...p,
      summonerName: p.summonerName || p.name,
      teamIndex: 2
    }));

    const sessionInfo = {
      blueTeamCount: this.session?.blueTeam?.length || 0,
      redTeamCount: this.session?.redTeam?.length || 0,
      phasesCount: this.session?.phases?.length || 0,
      currentAction: this.session?.currentAction || 0,
      phases: this.session?.phases || []
    };

    logDraft('🚀 [DraftPickBan] Sessão inicializada:', sessionInfo);
    saveLogToRoot(`🚀 [initializeSessionFromMatchData] Sessão inicializada: ${JSON.stringify(sessionInfo)}`);

    // ✅ NOVO: Atualizar estado imediatamente após inicialização
    this.updateDraftState();
  }

  // ✅ CARREGAMENTO DE CAMPEÕES
  private async loadChampions() {
    try {
      logDraft('🔄 [loadChampions] Carregando campeões...');
      this.championService.preloadChampions().subscribe({
        next: (loaded: boolean) => {
          if (loaded) {
            this.champions = []; // TODO: Implementar getAllChampions se necessário
            this.championsLoaded = true;
            logDraft(`✅ [loadChampions] Champions data loaded`);
            this.organizeChampionsByRole();
          }
        },
        error: (error: any) => {
          logDraft('❌ [loadChampions] Erro ao carregar campeões:', error);
        }
      });
    } catch (error) {
      logDraft('❌ [loadChampions] Erro ao carregar campeões:', error);
    }
  }

  private organizeChampionsByRole() {
    // TODO: Implementar getChampionsByRole se necessário
    this.championsByRole = {}; // Temporário
    /*
    this.championService.getChampionsByRole().subscribe({
      next: (championsByRole) => {
        this.championsByRole = championsByRole;
      },
      error: (error) => {
        logDraft('❌ [organizeChampionsByRole] Erro ao organizar campeões por role:', error);
      }
    });
    */
  }

  // ✅ SINCRONIZAÇÃO OTIMIZADA - 100% via WebSocket + ngOnChanges
  private startSessionSync(): void {
    logDraft('[startSessionSync] 🔄 Modo 100% reativo ativado');
    saveLogToRoot('[startSessionSync] ✅ Confiando 100% em WebSocket + ngOnChanges (sem polling HTTP)');

    // ✅ CRÍTICO: DESABILITADO - ngOnChanges já recebe os dados quando draftData muda
    // this.syncSessionWithBackend();

    // ✅ CRÍTICO: DESABILITADO - Polling HTTP estava causando conflitos com WebSocket
    // this.realTimeSyncTimer = window.setInterval(...);

    logDraft('[startSessionSync] ✅ Modo reativo configurado - aguardando ngOnChanges');
  }

  private stopSessionSync(): void {
    if (this.realTimeSyncTimer) {
      clearInterval(this.realTimeSyncTimer);
      this.realTimeSyncTimer = null;
      logDraft('[stopSessionSync] ⏹️ Sincronização periódica parada');
    }
  }

  private validateSyncConditions(): { isValid: boolean; effectiveMatchId?: string } {
    const effectiveMatchId = this.matchId || this.matchData?.matchId || this.matchData?.id;
    if (!effectiveMatchId || !this.currentPlayer) {
      saveLogToRoot(`❌ [syncSessionWithBackend] matchId ou currentPlayer não disponível: matchId=${effectiveMatchId}, currentPlayer=${!!this.currentPlayer}`);
      logDraft('[syncSessionWithBackend] ❌ matchId ou currentPlayer não disponível');
      return { isValid: false };
    }

    // Update matchId if necessary
    if (effectiveMatchId !== this.matchId) {
      this.matchId = effectiveMatchId;
      saveLogToRoot(`🔄 [syncSessionWithBackend] matchId corrigido: ${this.matchId}`);
    }

    return { isValid: true, effectiveMatchId };
  }

  private mergeSessionData(response: any): void {
    const backendCurrentAction = response.session.currentAction;
    const currentCurrentAction = this.session?.currentAction || 0;

    logDraft(`🔄 [mergeSessionData] === INICIANDO MERGE ===`);
    logDraft(`🔄 [mergeSessionData] currentAction: ${currentCurrentAction} → ${backendCurrentAction}`);
    saveLogToRoot(`🔄 [mergeSessionData] currentAction: ${currentCurrentAction} → ${backendCurrentAction}`);

    if (backendCurrentAction === undefined || backendCurrentAction < 0) {
      logDraft(`❌ [mergeSessionData] backendCurrentAction inválido: ${backendCurrentAction}`);
      saveLogToRoot(`❌ [mergeSessionData] backendCurrentAction inválido: ${backendCurrentAction}`);
      return;
    }

    const currentActions = this.session?.actions || [];
    const backendActions = response.session.actions || [];

    // ✅ CORREÇÃO: Sempre usar dados do backend como fonte de verdade
    // Isso resolve o problema de atualização atrasada
    const actionsToUse = backendActions;

    // ✅ CORREÇÃO: Log detalhado da sincronização de ações
    if (backendActions.length !== currentActions.length) {
      saveLogToRoot(`🔄 [mergeSessionData] Ações diferentes: current=${currentActions.length}, backend=${backendActions.length}`);
      const actionSummary = backendActions.map((action: any) => `${action.playerName}→${action.champion?.name}`);
      saveLogToRoot(`🔄 [mergeSessionData] Backend actions: ${JSON.stringify(actionSummary)}`);
    }

    // ✅ NOVO: Log antes da atualização
    const oldSession = { ...this.session };

    this.session = {
      ...this.session,
      // ✅ SEMPRE usar dados do backend
      actions: actionsToUse,
      team1Picks: response.session.team1Picks || [],
      team1Bans: response.session.team1Bans || [],
      team2Picks: response.session.team2Picks || [],
      team2Bans: response.session.team2Bans || [],
      currentAction: backendCurrentAction,
      phase: response.session.phase || 'bans',
      // ✅ CORREÇÃO: Usar teams do backend para garantir consistência
      blueTeam: response.session.blueTeam || response.session.team1 || this.session?.blueTeam || [],
      redTeam: response.session.redTeam || response.session.team2 || this.session?.redTeam || [],
      phases: response.session.phases || this.session?.phases || []
    };

    // ✅ NOVO: Extrair dados de confirmação
    this.confirmationData = response.session.confirmations ? {
      confirmations: response.session.confirmations,
      allConfirmed: response.session.allConfirmed || false
    } : null;

    logDraft(`✅ [mergeSessionData] Dados de confirmação extraídos:`, this.confirmationData);

    // ✅ NOVO: Log após a atualização
    logDraft(`✅ [mergeSessionData] Merge concluído - currentAction atualizado: ${oldSession.currentAction} → ${this.session.currentAction}`);
    saveLogToRoot(`✅ [mergeSessionData] Ações atualizadas: ${actionsToUse.length}, currentAction: ${backendCurrentAction}`);
    saveLogToRoot(`🔄 [mergeSessionData] Session.currentAction agora é: ${this.session.currentAction}`);
  }

  private async syncSessionWithBackend(): Promise<void> {
    const validation = this.validateSyncConditions();
    if (!validation.isValid) return;

    try {
      const url = `${this.baseUrl}/match/${validation.effectiveMatchId}/draft-session`;
      logDraft(`🔄 [syncSessionWithBackend] === INICIANDO SINCRONIZAÇÃO ===`);
      logDraft(`🔄 [syncSessionWithBackend] URL: ${url}`);
      saveLogToRoot(`🔄 [syncSessionWithBackend] Sincronizando com: ${url}`);

      const response: any = await firstValueFrom(this.http.get(url));

      // ✅ CORREÇÃO: Backend retorna dados diretamente (sem wrapper "session")
      if (!response || response.exists === false) {
        logDraft('[syncSessionWithBackend] ❌ Resposta inválida do backend ou draft não existe');
        return;
      }

      const oldCurrentAction = this.session?.currentAction || 0;
      // ✅ Aceitar currentAction OU currentIndex (backend envia currentIndex)
      const newCurrentAction = response.currentAction !== undefined ? response.currentAction : response.currentIndex || 0;
      const oldPhasesCount = this.session?.phases?.length || 0;
      // ✅ Aceitar phases OU actions (backend envia actions)
      const newPhasesCount = (response.phases || response.actions)?.length || 0;

      logDraft(`🔄 [syncSessionWithBackend] Comparando: old=${oldCurrentAction}, new=${newCurrentAction}`);
      saveLogToRoot(`🔄 [syncSessionWithBackend] Resposta recebida: oldCurrentAction=${oldCurrentAction}, newCurrentAction=${newCurrentAction}, oldPhases=${oldPhasesCount}, newPhases=${newPhasesCount}`);
      saveLogToRoot(`🔄 [syncSessionWithBackend] Resposta completa: ${JSON.stringify(response)}`);

      // ✅ Passar resposta direta (não response.session)
      this.mergeSessionData({ session: response });

      // ✅ CORREÇÃO: SEMPRE atualizar na primeira sincronização ou quando há mudanças
      const isFirstSync = !this.session || this.session.currentAction === undefined;
      const hasChanges = oldCurrentAction !== newCurrentAction || oldPhasesCount !== newPhasesCount;

      if (isFirstSync || hasChanges) {
        logDraft(`✅ [syncSessionWithBackend] ${isFirstSync ? 'Primeira sincronização' : 'Mudanças detectadas'} (${oldCurrentAction}→${newCurrentAction}) - atualizando interface`);
        saveLogToRoot(`✅ [syncSessionWithBackend] ${isFirstSync ? 'Primeira sincronização' : 'Mudanças detectadas'} - atualizando interface`);
        this.updateDraftState();
        this.cdr.detectChanges();
      } else {
        logDraft(`⏭️ [syncSessionWithBackend] Sem mudanças (${oldCurrentAction}→${newCurrentAction}) - ignorando atualização`);
        saveLogToRoot(`⏭️ [syncSessionWithBackend] Sem mudanças - ignorando atualização`);
        // ✅ CORREÇÃO: Mesmo sem mudanças, garantir que o estado está correto na interface
        this.updateDraftState();
      }

      logDraft('[syncSessionWithBackend] ✅ Interface atualizada após sincronização');
      saveLogToRoot(`✅ [syncSessionWithBackend] Interface atualizada após sincronização`);
    } catch (error) {
      logDraft('[syncSessionWithBackend] ❌ Erro ao sincronizar com backend:', error);
      saveLogToRoot(`❌ [syncSessionWithBackend] Erro: ${error}`);
    }
  }

  // ✅ NOVO: Sincronização imediata com retry automático
  private async syncSessionWithRetry(maxRetries: number = 3, retryDelay: number = 500): Promise<void> {
    logDraft(`🔄 [syncSessionWithRetry] Iniciando sincronização imediata (max retries: ${maxRetries})`);
    saveLogToRoot(`🔄 [syncSessionWithRetry] Iniciando sincronização imediata após ação`);

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        const validation = this.validateSyncConditions();
        if (!validation.isValid) {
          logDraft(`❌ [syncSessionWithRetry] Tentativa ${attempt}: Condições de sync inválidas`);
          return;
        }

        const url = `${this.baseUrl}/match/${validation.effectiveMatchId}/draft-session`;
        const response: any = await firstValueFrom(this.http.get(url));

        // ✅ CORREÇÃO: Backend retorna dados diretamente (sem wrapper)
        if (response && response.exists !== false) {
          logDraft(`✅ [syncSessionWithRetry] Tentativa ${attempt}: Sucesso`);
          saveLogToRoot(`✅ [syncSessionWithRetry] Sincronização imediata bem-sucedida na tentativa ${attempt}`);

          // ✅ Passar resposta direta (não response.session)
          this.mergeSessionData({ session: response });
          this.updateDraftState();
          return; // Sucesso, sair do loop
        } else {
          throw new Error('Resposta inválida do backend');
        }
      } catch (error) {
        logDraft(`❌ [syncSessionWithRetry] Tentativa ${attempt} falhou:`, error);
        saveLogToRoot(`❌ [syncSessionWithRetry] Tentativa ${attempt} falhou: ${JSON.stringify(error)}`);

        if (attempt < maxRetries) {
          logDraft(`⏳ [syncSessionWithRetry] Aguardando ${retryDelay}ms antes da próxima tentativa`);
          await new Promise(resolve => setTimeout(resolve, retryDelay));
          retryDelay *= 1.5; // Aumentar o delay exponencialmente
        } else {
          logDraft(`❌ [syncSessionWithRetry] Todas as ${maxRetries} tentativas falharam`);
          saveLogToRoot(`❌ [syncSessionWithRetry] Falha após ${maxRetries} tentativas`);
        }
      }
    }
  }

  // ✅ MELHORADO: Atualização de estado com logs detalhados e controle de concorrência
  private updateDraftState(): void {
    if (!this.session) {
      saveLogToRoot(`❌ [updateDraftState] Session não existe`);
      logDraft('🔄 [updateDraftState] ❌ Session não existe');
      return;
    }

    if (this.updateInProgress) {
      saveLogToRoot(`⚠️ [updateDraftState] Update já em progresso - ignorando`);
      return;
    }

    this.updateInProgress = true;

    try {
      logDraft('🔄 [updateDraftState] === INICIANDO ATUALIZAÇÃO DE ESTADO ===');
      saveLogToRoot(`🔄 [updateDraftState] Iniciando atualização de estado. currentAction=${this.session.currentAction}, phases.length=${this.session.phases?.length || 0}, isEditingMode=${this.isEditingMode}`);

      // ✅ CRÍTICO: Se estamos em modo de edição, NÃO fazer atualizações que alterem a interface
      if (this.isEditingMode) {
        logDraft('🎯 [updateDraftState] MODO EDIÇÃO ATIVO - saltando atualizações da interface');
        saveLogToRoot(`🎯 [updateDraftState] MODO EDIÇÃO ATIVO - preservando estado dos modais`);
        return;
      }

      // ✅ CORREÇÃO: Verificar se currentAction é válido
      if (this.session.currentAction === undefined || this.session.currentAction === null) {
        saveLogToRoot(`⚠️ [updateDraftState] currentAction undefined/null (${this.session.currentAction}), aguardando ngOnChanges...`);
        logDraft('⚠️ [updateDraftState] currentAction undefined/null, aguardando ngOnChanges...');
        // ✅ CRÍTICO: NÃO forçar sync HTTP - aguardar WebSocket + ngOnChanges
        // this.syncSessionWithBackend();
        return;
      }

      // ✅ CORREÇÃO: Verificar se draft foi completado ANTES de considerar currentAction inválido
      if (this.session.currentAction >= this.session.phases.length) {
        const isDraftCompleted = this.session.phase === 'completed' ||
          this.session.currentAction >= this.session.phases.length;

        if (isDraftCompleted) {
          logDraft('🎯 [updateDraftState] Draft completado detectado - exibindo modal de confirmação');
          saveLogToRoot(`✅ [updateDraftState] Draft completado (currentAction=${this.session.currentAction}, phases=${this.session.phases.length})`);

          // ✅ LOGS DETALHADOS: Investigar dados da sessão para o modal
          console.log('🔍 [Modal Confirmação] Dados da sessão:', {
            currentAction: this.session.currentAction,
            phase: this.session.phase,
            phasesCount: this.session.phases?.length,
            phasesWithanys: this.session.phases?.filter((phase: any) => phase.champion)?.length || 0,
            blueTeamLength: this.session.blueTeam?.length || 0,
            redTeamLength: this.session.redTeam?.length || 0,
            confirmationData: this.confirmationData
          });

          // Log detalhado das fases
          this.session.phases?.forEach((phase: any, index: number) => {
            console.log(`🔍 [Modal Confirmação] Fase ${index}:`, {
              action: phase.action,
              team: phase.team,
              playerId: phase.playerId,
              playerName: phase.playerName,
              hasany: !!phase.champion,
              championName: phase.champion?.name || 'N/A'
            });
          });

          // Atualizar estado da interface para mostrar modal de confirmação
          this.isMyTurn = false;
          this.showChampionModal = false;
          this.showConfirmationModal = true;
          this.cdr.detectChanges();
          return;
        }

        saveLogToRoot(`⚠️ [updateDraftState] currentAction inválido (${this.session.currentAction}), aguardando ngOnChanges...`);
        logDraft('⚠️ [updateDraftState] currentAction inválido, aguardando ngOnChanges...');
        // ✅ CRÍTICO: NÃO forçar sync HTTP - aguardar WebSocket + ngOnChanges
        // this.syncSessionWithBackend();
        return;
      }

      // ✅ CORREÇÃO: Verificar se matchId está disponível
      if (!this.matchId) {
        saveLogToRoot(`⚠️ [updateDraftState] matchId não disponível, tentando corrigir`);
        const effectiveMatchId = this.matchData?.matchId || this.matchData?.id;
        if (effectiveMatchId) {
          this.matchId = effectiveMatchId;
          saveLogToRoot(`✅ [updateDraftState] matchId corrigido: ${this.matchId}`);
        } else {
          saveLogToRoot(`❌ [updateDraftState] Não foi possível corrigir matchId`);
          return;
        }
      }

      const currentPhase = this.session.phases[this.session.currentAction];
      if (!currentPhase) {
        saveLogToRoot(`❌ [updateDraftState] Fase atual não existe para currentAction=${this.session.currentAction}`);
        logDraft('🔄 [updateDraftState] ❌ Fase atual não existe para currentAction:', this.session.currentAction);
        return;
      }

      // ✅ MELHORADO: Atualizar jogador da vez com mais informações
      this.currentPlayerTurn = {
        playerId: currentPhase.byPlayer, // ✅ CORREÇÃO: byPlayer ao invés de playerId
        playerName: currentPhase.byPlayer, // ✅ CORREÇÃO: byPlayer contém o nome
        team: currentPhase.team,
        action: currentPhase.type, // ✅ CRÍTICO: type ao invés de action (estrutura do backend)
        actionIndex: currentPhase.index, // ✅ CORREÇÃO: index ao invés de actionIndex
        playerIndex: currentPhase.index // ✅ CORREÇÃO: index serve como playerIndex também
      };

      saveLogToRoot(`🔄 [updateDraftState] CurrentPlayerTurn definido: ${JSON.stringify(this.currentPlayerTurn)}`);

      // ✅ MELHORADO: Verificar se é a vez do jogador atual
      this.isMyTurn = this.checkIfMyTurn();

      saveLogToRoot(`🔄 [updateDraftState] isMyTurn=${this.isMyTurn}`);

      // ✅ MELHORADO: Atualizar estado da interface
      this.updateInterfaceState();

      // ✅ NOVO: Forçar detecção de mudanças
      this.cdr.detectChanges();

      logDraft('✅ [updateDraftState] Estado do draft atualizado com sucesso');
      saveLogToRoot(`✅ [updateDraftState] Estado do draft atualizado com sucesso`);
    } finally {
      this.updateInProgress = false;
    }
  }

  private updateInterfaceState(): void {
    logDraft('🔄 [updateInterfaceState] === ATUALIZANDO INTERFACE ===');
    saveLogToRoot(`🔄 [updateInterfaceState] Atualizando interface. isMyTurn=${this.isMyTurn}, currentPlayerTurn.action=${this.currentPlayerTurn?.action}, isEditingMode=${this.isEditingMode}`);

    // ✅ CRÍTICO: Se está em modo de edição, NÃO alterar estados dos modais
    if (this.isEditingMode) {
      logDraft('🔄 [updateInterfaceState] Modo de edição ativo - mantendo modal de campeão aberto');
      saveLogToRoot(`🎯 [updateInterfaceState] Modo de edição ativo - preservando estado do modal`);
      saveLogToRoot(`🔍 [updateInterfaceState] Estado preservado: showChampionModal=${this.showChampionModal}, showConfirmationModal=${this.showConfirmationModal}`);

      // ✅ NOVO: Garantir que o modal de campeão está aberto durante edição
      if (!this.showChampionModal) {
        saveLogToRoot(`⚠️ [updateInterfaceState] CORREÇÃO: Modal de campeão estava fechado durante edição - reabrindo`);
        this.showChampionModal = true;
      }

      // ✅ NOVO: Garantir que o modal de confirmação está fechado durante edição
      if (this.showConfirmationModal) {
        saveLogToRoot(`⚠️ [updateInterfaceState] CORREÇÃO: Modal de confirmação estava aberto durante edição - fechando`);
        this.showConfirmationModal = false;
      }

      saveLogToRoot(`🎯 [updateInterfaceState] Estado final modo edição: showChampionModal=${this.showChampionModal}, showConfirmationModal=${this.showConfirmationModal}`);
      this.cdr.detectChanges();
      return;
    }

    // ✅ NOVO: Verificar se draft foi completado
    const isDraftCompleted = this.session.phase === 'completed' ||
      this.session.currentAction >= (this.session.phases?.length || 0);

    if (isDraftCompleted) {
      logDraft('🎯 [updateInterfaceState] Draft completado - mostrando modal de confirmação');
      saveLogToRoot(`✅ [updateInterfaceState] Draft completado - mostrando modal de confirmação`);
      this.showChampionModal = false;
      this.showConfirmationModal = true;
      this.cdr.detectChanges();
      return;
    }

    // ✅ MELHORADO: Verificar se deve mostrar o modal de campeões
    const actionIsPick = this.currentPlayerTurn?.action === 'pick';
    const actionIsBan = this.currentPlayerTurn?.action === 'ban';
    const actionIsValid = actionIsPick || actionIsBan;
    const hasSession = !!this.session;
    const isBeforeEnd = this.session && this.session.currentAction < (this.session.phases?.length || 0);

    const shouldShowModal = this.isMyTurn && actionIsValid && hasSession && isBeforeEnd;

    // ✅ DEBUG CRÍTICO: Logs SUPER detalhados
    console.log('🔍🔍🔍 [updateInterfaceState] DEBUG COMPLETO DO MODAL:', {
      '1_isMyTurn': this.isMyTurn,
      '2_currentPlayerTurn': this.currentPlayerTurn,
      '3_action': this.currentPlayerTurn?.action,
      '4_actionIsPick': actionIsPick,
      '5_actionIsBan': actionIsBan,
      '6_actionIsValid': actionIsValid,
      '7_hasSession': hasSession,
      '8_currentAction': this.session?.currentAction,
      '9_phasesLength': this.session?.phases?.length || 0,
      '10_isBeforeEnd': isBeforeEnd,
      '11_shouldShowModal': shouldShowModal,
      '12_currentPlayer_local': this.currentPlayer?.summonerName || this.currentPlayer?.displayName,
      '13_currentPlayer_session': this.session?.currentPlayer
    });

    saveLogToRoot(`🔍 [updateInterfaceState] MODAL DEBUG: isMyTurn=${this.isMyTurn}, action=${this.currentPlayerTurn?.action}, shouldShowModal=${shouldShowModal}`);

    if (shouldShowModal) {
      console.log('✅ [updateInterfaceState] ABRINDO MODAL DE CAMPEÕES!');
      logDraft('🔄 [updateInterfaceState] É minha vez de pick ou ban - mostrando modal');
      saveLogToRoot(`✅ [updateInterfaceState] Mostrando modal de campeões para ação: ${this.currentPlayerTurn?.action}`);
      this.showChampionModal = true;
      this.showConfirmationModal = false;
    } else {
      console.log('❌ [updateInterfaceState] NÃO ABRIR MODAL');
      logDraft('🔄 [updateInterfaceState] Não é minha vez ou condições não atendidas - ocultando modal');
      saveLogToRoot(`❌ [updateInterfaceState] Ocultando modal. isMyTurn=${this.isMyTurn}, action=${this.currentPlayerTurn?.action}, currentAction=${this.session?.currentAction}, phasesLength=${this.session?.phases?.length || 0}`);
      this.showChampionModal = false;
    }

    // ✅ NOVO: Log detalhado do estado da interface
    const interfaceState = {
      showChampionModal: this.showChampionModal,
      showConfirmationModal: this.showConfirmationModal,
      isMyTurn: this.isMyTurn,
      currentAction: this.session?.currentAction,
      phasesLength: this.session?.phases?.length || 0,
      currentPhaseAction: this.currentPlayerTurn?.action,
      shouldShowModal,
      isDraftCompleted,
      isEditingMode: this.isEditingMode
    };

    logDraft('🔄 [updateInterfaceState] Interface atualizada:', interfaceState);
    saveLogToRoot(`🔄 [updateInterfaceState] Interface atualizada: ${JSON.stringify(interfaceState)}`);

    // ✅ NOVO: Forçar detecção de mudanças
    this.cdr.detectChanges();
  }

  // ✅ MELHORADO: Verificação de turno com logs detalhados
  private checkIfMyTurn(): boolean {
    if (!this.session || !this.currentPlayer) {
      saveLogToRoot(`❌ [checkIfMyTurn] Session ou currentPlayer não disponível: session=${!!this.session}, currentPlayer=${!!this.currentPlayer}`);
      return false;
    }

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) {
      saveLogToRoot(`❌ [checkIfMyTurn] Fase atual não encontrada: currentAction=${this.session.currentAction}, phases.length=${this.session.phases?.length || 0}`);
      return false;
    }

    // ✅ MELHORADO: Múltiplas formas de identificar o jogador atual
    const currentPlayerIdentifiers = [
      this.currentPlayer.puuid,
      this.currentPlayer.summonerName,
      this.currentPlayer.displayName,
      this.currentPlayer.name,
      this.currentPlayer.gameName ? `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}` : null
    ].filter(Boolean);

    // ✅ PRIORIDADE MÁXIMA: Usar currentPlayer do backend (enviado pelo DraftFlowService)
    const turnPlayerIdentifiers = [
      this.session.currentPlayer,  // ✅ CRÍTICO: Backend calcula corretamente quem é o jogador da vez
      currentPhase.byPlayer, // ✅ CORREÇÃO: byPlayer ao invés de playerId/playerName
      currentPhase.byPlayer // ✅ Garantir que está no array
    ].filter(Boolean);

    // ✅ NOVO: Log detalhado para debug
    const debugInfo = {
      currentAction: this.session.currentAction,
      currentPhaseAction: currentPhase.type, // ✅ CORREÇÃO: type ao invés de action
      currentPhaseTeam: currentPhase.team,
      currentPlayerIdentifiers,
      turnPlayerIdentifiers,
      currentPlayer: {
        puuid: this.currentPlayer.puuid,
        summonerName: this.currentPlayer.summonerName,
        displayName: this.currentPlayer.displayName,
        name: this.currentPlayer.name,
        gameName: this.currentPlayer.gameName,
        tagLine: this.currentPlayer.tagLine
      },
      currentPhase: {
        byPlayer: currentPhase.byPlayer, // ✅ CORREÇÃO: byPlayer ao invés de playerId
        championId: currentPhase.championId,
        type: currentPhase.type, // ✅ CORREÇÃO: type ao invés de action
        team: currentPhase.team,
        index: currentPhase.index
      }
    };

    console.log('🔍 [checkIfMyTurn] DEBUG COMPLETO:', debugInfo);
    console.log('🔍 [checkIfMyTurn] Comparando:', {
      currentPlayerIdentifiers,
      turnPlayerIdentifiers,
      sessionCurrentPlayer: this.session.currentPlayer
    });
    logDraft('🔄 [checkIfMyTurn] Verificando turno:', debugInfo);
    saveLogToRoot(`🔍 [checkIfMyTurn] Debug: ${JSON.stringify(debugInfo)}`);

    // ✅ MELHORADO: Comparação mais robusta
    const isMyTurn = currentPlayerIdentifiers.some(currentId =>
      turnPlayerIdentifiers.some(turnId =>
        currentId === turnId ||
        currentId?.toLowerCase() === turnId?.toLowerCase()
      )
    );

    if (isMyTurn) {
      saveLogToRoot(`✅ [checkIfMyTurn] É minha vez! currentPlayerIdentifiers=${currentPlayerIdentifiers}, turnPlayerIdentifiers=${turnPlayerIdentifiers}`);
    } else {
      saveLogToRoot(`❌ [checkIfMyTurn] Não é minha vez. currentPlayerIdentifiers=${currentPlayerIdentifiers}, turnPlayerIdentifiers=${turnPlayerIdentifiers}`);
    }

    return isMyTurn;
  }

  // ✅ MÉTODOS DE EXIBIÇÃO
  getBannedChampions(): any[] {
    if (!this.session) return [];

    const bannedChampions: any[] = [];

    // ✅ Usar allBans da estrutura hierárquica (mais simples e direto)
    if (this.session.teams) {
      // Time azul
      const blueBans = this.session.teams.blue?.allBans || [];
      blueBans.forEach((championId: string) => {
        const champion = this.getChampionFromCache(parseInt(championId, 10));
        if (champion) {
          bannedChampions.push(champion);
        }
      });

      // Time vermelho
      const redBans = this.session.teams.red?.allBans || [];
      redBans.forEach((championId: string) => {
        const champion = this.getChampionFromCache(parseInt(championId, 10));
        if (champion) {
          bannedChampions.push(champion);
        }
      });

      console.log(`✅ [getBannedChampions] Encontrados ${bannedChampions.length} bans:`, bannedChampions.map(c => c.name));
    }
    // FALLBACK: Estrutura antiga (phases)
    else if (this.session.phases) {
      const bannedFromPhases = this.session.phases
        .filter((phase: any) => phase.action === 'ban' && phase.champion && phase.locked)
        .map((phase: any) => phase.champion!)
        .filter((champion: any, index: number, self: any[]) =>
          index === self.findIndex((c: any) => c.id === champion.id)
        );
      bannedChampions.push(...bannedFromPhases);
    }

    return bannedChampions;
  }

  // ✅ NOVO: Buscar campeão do cache do ChampionService (síncrono)
  private getChampionFromCache(championId: number): any {
    const cache = (this.championService as any).championsCache as Map<string, any>;
    if (!cache) return null;

    // ✅ Tentar buscar diretamente pelo key
    let champion = cache.get(championId.toString());

    if (!champion) {
      // ✅ FALLBACK: Buscar em todos os campeões pelo ID alternativo
      // O Data Dragon tem tanto "key" quanto "id" para cada campeão
      for (const [key, champ] of cache.entries()) {
        // championId do backend pode corresponder ao "key" (número) ou precisar conversão
        if (champ.key === championId.toString() ||
          parseInt(champ.key, 10) === championId) {
          champion = champ;
          console.log(`✅ [getChampionFromCache] Campeão ${championId} encontrado via fallback: ${champ.name} (key: ${champ.key})`);
          break;
        }
      }
    } else {
      console.log(`✅ [getChampionFromCache] Campeão ${championId} encontrado: ${champion.name}`);
    }

    if (!champion) {
      console.warn(`⚠️ [getChampionFromCache] Campeão ${championId} NÃO encontrado em ${cache.size} campeões`);
      // Apenas logar as primeiras 5 entradas para debug
      let count = 0;
      for (const [key, champ] of cache.entries()) {
        if (count++ < 5) {
          console.log(`  - key: ${key}, name: ${champ.name}, championKey: ${champ.key}`);
        }
      }
    }

    return champion || null;
  }

  // ✅ NOVO: Carregar campeões no início do draft
  private loadChampionsForDraft(): void {
    // ✅ Usar getChampionById para forçar carregamento do cache
    this.championService.getChampionById(1).subscribe({
      next: () => {
        this.championsLoaded = true;
        const cacheSize = (this.championService as any).championsCache?.size || 0;
        console.log('✅ [loadChampionsForDraft] Campeões carregados:', cacheSize);
      },
      error: (err: any) => {
        console.error('❌ [loadChampionsForDraft] Erro ao carregar campeões:', err);
      }
    });
  }

  getSortedTeamByLane(team: 'blue' | 'red'): any[] {
    if (!this.session) {
      return [];
    }

    const teamPlayers = this.isBlueTeam(team) ? this.session.blueTeam : this.session.redTeam;
    const sortedPlayers = this.sortPlayersByLane(teamPlayers);

    return sortedPlayers;
  }

  private sortPlayersByLane(players: any[]): any[] {
    const laneOrder = ['top', 'jungle', 'mid', 'bot', 'support'];  // ✅ Incluir 'bot' também
    const playersCopy = [...players];

    playersCopy.sort((a, b) => {
      // ✅ CORREÇÃO: Usar assignedLane primeiro (vem do backend), depois lane como fallback
      let laneA = a.assignedLane || a.lane || 'unknown';
      let laneB = b.assignedLane || b.lane || 'unknown';

      // ✅ Normalizar "adc" para "bot"
      if (laneA === 'adc') laneA = 'bot';
      if (laneB === 'adc') laneB = 'bot';

      const indexA = laneOrder.indexOf(laneA);
      const indexB = laneOrder.indexOf(laneB);

      if (indexA === -1 && indexB === -1) return 0;
      if (indexA === -1) return 1;
      if (indexB === -1) return -1;

      return indexA - indexB;
    });

    const sortedPlayers = playersCopy;

    return sortedPlayers;
  }

  getPlayerLaneDisplayForPlayer(player: any): string {
    const lane = player.lane || 'unknown';
    return this.getLaneDisplayName(lane);
  }

  getLaneDisplayName(lane: string): string {
    const laneNames: { [key: string]: string } = {
      'top': '🛡️ Top',
      'jungle': '🌲 Jungle',
      'mid': '⚡ Mid',
      'adc': '🏹 ADC',
      'bot': '🏹 ADC',  // ✅ CORREÇÃO: Backend envia 'bot', mapear para ADC
      'support': '💎 Support',
      'unknown': '❓ Unknown'
    };
    return laneNames[lane] || laneNames['unknown'];
  }

  isCurrentPlayer(player: any): boolean {
    if (!this.currentPlayer || !player) return false;
    return this.botService.comparePlayers(this.currentPlayer, player);
  }

  isCurrentPlayerForPick(team: 'blue' | 'red', pickIndex: number): boolean {
    if (!this.currentPlayer || !this.session) return false;

    const teamPlayers = this.isBlueTeam(team) ? this.session.blueTeam : this.session.redTeam;
    const player = teamPlayers.find((p: any) => p.teamIndex === pickIndex);

    return player ? this.botService.comparePlayers(this.currentPlayer, player) : false;
  }

  isChampionBanned(champion: any): boolean {
    return this.getBannedChampions().some(c => c.id === champion.id);
  }

  isChampionPicked(champion: any): boolean {
    const bluePicks = this.getTeamPicks('blue');
    const redPicks = this.getTeamPicks('red');

    // ✅ CORREÇÃO: getTeamPicks agora retorna string[] (championIds), não objects
    const allPicks = [...bluePicks, ...redPicks];
    return allPicks.some(championId => championId === champion.id || championId === champion.key);
  }

  private checkPlayerMatch(teamPlayer: any, searchPlayer: any): boolean {
    // Prioridade 1: PUUID (jogadores humanos)
    if (searchPlayer.puuid && teamPlayer.puuid && teamPlayer.puuid === searchPlayer.puuid) {
      saveLogToRoot(`🎯 [checkPlayerMatch] Match por PUUID: ${searchPlayer.puuid}`);
      return true;
    }

    // Prioridade 2: summonerName (bots e jogadores)
    if (searchPlayer.summonerName && teamPlayer.summonerName && teamPlayer.summonerName === searchPlayer.summonerName) {
      saveLogToRoot(`🎯 [checkPlayerMatch] Match por summonerName: ${searchPlayer.summonerName}`);
      return true;
    }

    // Prioridade 3: gameName#tagLine (jogadores humanos)
    if (searchPlayer.gameName && searchPlayer.tagLine && teamPlayer.gameName && teamPlayer.tagLine) {
      const searchName = `${searchPlayer.gameName}#${searchPlayer.tagLine}`;
      const teamName = `${teamPlayer.gameName}#${teamPlayer.tagLine}`;
      if (searchName === teamName) {
        saveLogToRoot(`🎯 [checkPlayerMatch] Match por gameName#tagLine: ${searchName}`);
        return true;
      }
    }

    // Prioridade 4: displayName (fallback)
    if (searchPlayer.displayName && teamPlayer.displayName && teamPlayer.displayName === searchPlayer.displayName) {
      saveLogToRoot(`🎯 [checkPlayerMatch] Match por displayName: ${searchPlayer.displayName}`);
      return true;
    }

    return false;
  }

  private findPlayerInTeam(team: 'blue' | 'red', player: any): any {
    if (!this.session) return null;

    const teamPlayers = team === 'blue' ? this.session.blueTeam : this.session.redTeam;
    return teamPlayers.find((p: any) => this.checkPlayerMatch(p, player));
  }

  private findPickInActions(foundPlayer: any, team: 'blue' | 'red'): any | null {
    if (!this.session.phases?.length) return null; // ✅ CORREÇÃO: usar phases (que contém actions)

    const teamNumber = team === 'blue' ? 1 : 2;

    // ✅ CRÍTICO: Buscar na estrutura nova do backend
    const pickAction = this.session.phases.find((action: any) => {
      const isCorrectTeam = action.team === teamNumber; // ✅ NOVO: team ao invés de teamIndex
      const isPickAction = action.type === 'pick'; // ✅ NOVO: type ao invés de action
      const hasChampion = action.championId && action.byPlayer; // ✅ NOVO: championId/byPlayer ao invés de champion/locked

      // ✅ CORREÇÃO: Comparar jogador pelo byPlayer (nome do jogador)
      const isCorrectPlayer =
        action.byPlayer === foundPlayer.summonerName ||
        action.byPlayer === foundPlayer.name ||
        action.byPlayer === `${foundPlayer.gameName}#${foundPlayer.tagLine}`;

      return isCorrectTeam && isCorrectPlayer && isPickAction && hasChampion;
    });

    if (pickAction) {
      // ✅ CRÍTICO: Buscar campeão do ChampionService
      const championId = parseInt(pickAction.championId, 10);
      const champion = this.getChampionFromCache(championId);

      if (champion) {
        return {
          id: champion.key,
          name: champion.name,
          image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`
        };
      }

      // Fallback
      return {
        id: pickAction.championId,
        name: `Champion ${pickAction.championId}`,
        image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/Unknown.png`
      };
    }

    return null;
  }

  private findPickInPhases(foundPlayer: any, team: 'blue' | 'red'): any | null {
    // ✅ DESABILITADO: findPickInActions já busca corretamente na nova estrutura
    return null;
  }

  private isPlayerPhase(foundPlayer: any, pickPhase: any, team: 'blue' | 'red'): boolean {
    if (pickPhase.playerIndex !== undefined && foundPlayer.teamIndex !== undefined) {
      const expectedTeamIndex = team === 'blue' ? pickPhase.playerIndex : pickPhase.playerIndex + 5;
      if (foundPlayer.teamIndex === expectedTeamIndex) return true;
    }

    return this.botService.comparePlayerWithId(foundPlayer, pickPhase.playerId || '') ||
      this.botService.comparePlayerWithId(foundPlayer, pickPhase.playerName || '');
  }

  getPlayerPick(team: 'blue' | 'red', player: any): any | null {
    const foundPlayer = this.findPlayerInTeam(team, player);
    if (!foundPlayer) return null;

    // Try to find pick in actions first
    const pickFromActions = this.findPickInActions(foundPlayer, team);
    if (pickFromActions) return pickFromActions;

    // Fallback to phases method
    return this.findPickInPhases(foundPlayer, team);
  }

  // ✅ REMOVIDO: Método duplicado getPlayerBans() - usar getPlayerBans(playerName, teamColor) na seção de métodos hierárquicos

  getCurrentPlayerName(): string {
    // ✅ LOG DEBUG: Sempre logar a chamada do método
    const debugInfo = {
      isEditingMode: this.isEditingMode,
      currentEditingPlayer: this.currentEditingPlayer,
      sessionExists: !!this.session,
      currentAction: this.session?.currentAction,
      phasesLength: this.session?.phases?.length
    };

    // ✅ CRITICAL: Usar console.log para garantir que aparece nos logs
    console.log('🎯 [getCurrentPlayerName] CHAMADO:', debugInfo);
    saveLogToRoot(`🎯 [getCurrentPlayerName] CHAMADO: ${JSON.stringify(debugInfo)}`);

    if (!this.session) {
      logDraft('❌ [getCurrentPlayerName] Session não existe');
      console.log('❌ [getCurrentPlayerName] Session não existe');
      saveLogToRoot('❌ [getCurrentPlayerName] Session não existe');
      return 'Jogador Desconhecido';
    }

    // ✅ PRIORIDADE MÁXIMA: Usar currentPlayer do backend se disponível (exceto em modo de edição)
    if (!this.isEditingMode && this.session.currentPlayer && typeof this.session.currentPlayer === 'string') {
      logDraft('✅ [getCurrentPlayerName] Usando currentPlayer do backend:', this.session.currentPlayer);
      console.log('✅ [getCurrentPlayerName] Usando currentPlayer do backend:', this.session.currentPlayer);
      saveLogToRoot(`✅ [getCurrentPlayerName] Usando currentPlayer do backend: ${this.session.currentPlayer}`);
      return this.session.currentPlayer;
    } else {
      console.log('⚠️ [getCurrentPlayerName] currentPlayer NÃO disponível:', {
        isEditingMode: this.isEditingMode,
        hasCurrentPlayer: !!this.session.currentPlayer,
        currentPlayerValue: this.session.currentPlayer,
        currentPlayerType: typeof this.session.currentPlayer
      });
      saveLogToRoot(`⚠️ [getCurrentPlayerName] currentPlayer NÃO disponível: isEditingMode=${this.isEditingMode}, currentPlayer=${this.session.currentPlayer}`);
    }

    // ✅ PRIORIDADE ALTA: Se estamos em modo de edição, SEMPRE usar os dados do jogador sendo editado
    if (this.isEditingMode && this.currentEditingPlayer) {
      console.log('🚨 [getCurrentPlayerName] MODO EDIÇÃO DETECTADO!', this.currentEditingPlayer);
      saveLogToRoot(`🚨 [getCurrentPlayerName] MODO EDIÇÃO DETECTADO: ${JSON.stringify(this.currentEditingPlayer)}`);

      logDraft('🎯 [getCurrentPlayerName] MODO EDIÇÃO ATIVO - ignorando currentAction, buscando jogador editado:', this.currentEditingPlayer);

      // PRIMEIRO: Tentar encontrar a fase baseada no phaseIndex
      if (this.currentEditingPlayer.phaseIndex !== undefined &&
        this.session.phases &&
        this.session.phases[this.currentEditingPlayer.phaseIndex]) {
        const editPhase = this.session.phases[this.currentEditingPlayer.phaseIndex];
        logDraft('✅ [getCurrentPlayerName] [EDIÇÃO] Encontrado por phaseIndex:', editPhase.playerName || editPhase.playerId);
        return editPhase.playerName || editPhase.playerId || 'Jogador em Edição';
      }

      // SEGUNDO: Buscar por playerId nas fases (busca mais flexível)
      if (this.session.phases) {
        const editPhase = this.session.phases.find((phase: any) => {
          // Comparar diferentes formatos de playerId
          const phasePlayerId = phase.playerId || phase.playerName;
          const editPlayerId = this.currentEditingPlayer?.playerId;

          logDraft('🔍 [getCurrentPlayerName] [EDIÇÃO] Comparando:', phasePlayerId, 'vs', editPlayerId);

          return phasePlayerId === editPlayerId ||
            phase.playerName === editPlayerId ||
            phase.playerId === editPlayerId;
        });

        if (editPhase) {
          logDraft('✅ [getCurrentPlayerName] [EDIÇÃO] Encontrado por playerId:', editPhase.playerName || editPhase.playerId);
          return editPhase.playerName || editPhase.playerId || 'Jogador em Edição';
        }
      }

      // TERCEIRO: Se ainda não encontrou, usar o playerId diretamente
      logDraft('⚠️ [getCurrentPlayerName] [EDIÇÃO] Jogador não encontrado nas fases, usando ID direto:', this.currentEditingPlayer.playerId);
      return this.currentEditingPlayer.playerId || 'Jogador em Edição';
    }

    // ✅ CORREÇÃO: Verificar se currentAction é válido (apenas quando NÃO está em modo de edição)
    if (this.session.currentAction === undefined || this.session.currentAction === null) {
      logDraft('❌ [getCurrentPlayerName] currentAction inválido:', this.session.currentAction);
      return 'Jogador Desconhecido';
    }

    // ✅ NOVO: Se o draft está completo (currentAction >= phases.length), não há fase atual
    if (this.session.currentAction >= this.session.phases.length) {
      const debugInfo = {
        currentAction: this.session.currentAction,
        phasesLength: this.session.phases.length,
        phases: this.session.phases,
        hasPhases: !!this.session.phases,
        phasesIsArray: Array.isArray(this.session.phases)
      };
      logDraft('🏁 [getCurrentPlayerName] Draft completado - sem fase atual:', debugInfo);
      console.error('❌ [DraftPickBan] PHASES VAZIO OU INCORRETO:', debugInfo);
      saveLogToRoot(`❌ [getCurrentPlayerName] PROBLEMA: currentAction=${this.session.currentAction}, phasesLength=${this.session.phases.length}, phases=${JSON.stringify(this.session.phases?.slice(0, 3))}`);
      return 'Draft Completo';
    }

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) {
      logDraft('❌ [getCurrentPlayerName] Fase atual não encontrada:', {
        currentAction: this.session.currentAction,
        phasesLength: this.session.phases?.length || 0,
        phases: this.session.phases?.slice(0, 3) // Log das primeiras 3 fases para debug
      });
      return 'Jogador Desconhecido';
    }

    // ✅ MELHORADO: Log detalhado da fase atual
    logDraft('🔍 [getCurrentPlayerName] Fase atual:', {
      currentAction: this.session.currentAction,
      phase: currentPhase,
      playerName: currentPhase.playerName,
      playerId: currentPhase.playerId,
      playerIndex: currentPhase.playerIndex,
      actionIndex: currentPhase.actionIndex,
      team: currentPhase.team,
      action: currentPhase.action
    });

    // ✅ MELHORADO: Priorizar playerName/playerId da fase atual
    if (currentPhase.playerName) {
      logDraft('✅ [getCurrentPlayerName] Usando playerName da fase:', currentPhase.playerName);
      return currentPhase.playerName;
    }

    if (currentPhase.playerId) {
      logDraft('✅ [getCurrentPlayerName] Usando playerId da fase:', currentPhase.playerId);
      return currentPhase.playerId;
    }

    // ✅ FALLBACK: Buscar por playerIndex nos times
    const teamPlayers = currentPhase.team === 'blue' || currentPhase.team === 1 ? this.session.blueTeam : this.session.redTeam;

    if (currentPhase.playerIndex !== undefined && teamPlayers?.[currentPhase.playerIndex]) {
      const player = teamPlayers[currentPhase.playerIndex];
      const playerName = player.summonerName || player.name || player.displayName || player.id;

      if (playerName) {
        logDraft('✅ [getCurrentPlayerName] Encontrado por playerIndex:', playerName);
        return playerName;
      }
    }

    // ✅ ÚLTIMO FALLBACK: Buscar por actionIndex nas fases
    if (currentPhase.actionIndex !== undefined) {
      const phaseByActionIndex = this.session.phases.find((p: any) => p.actionIndex === currentPhase.actionIndex);
      if (phaseByActionIndex && (phaseByActionIndex.playerName || phaseByActionIndex.playerId)) {
        const playerName = phaseByActionIndex.playerName || phaseByActionIndex.playerId;
        logDraft('✅ [getCurrentPlayerName] Encontrado por actionIndex:', playerName);
        return playerName;
      }
    }

    logDraft('❌ [getCurrentPlayerName] Nenhum nome de jogador encontrado para fase:', currentPhase);
    return 'Jogador Desconhecido';
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

  isPlayerBot(player: any): boolean {
    return this.botService.isBot(player);
  }

  getPlayerTeam(): 'blue' | 'red' {
    if (!this.currentPlayer || !this.session) return 'blue';

    const blueTeamPlayer = this.session.blueTeam.find((p: any) => this.botService.comparePlayers(p, this.currentPlayer));
    return blueTeamPlayer ? 'blue' : 'red';
  }

  getPhaseProgress(): number {
    if (!this.session?.phases?.length) return 0;
    return Math.floor((this.session.currentAction / this.session.phases.length) * 100);
  }

  // ✅ NOVO: Verifica se estamos na última ação (vigésima) para mostrar botão de confirmação
  isLastAction(): boolean {
    if (!this.session?.phases?.length) return false;
    return this.session.currentAction >= this.session.phases.length - 1;
  }

  // ✅ MÉTODOS DE INTERFACE
  onImageError(event: any, champion: any): void {
    event.target.src = 'assets/images/champion-placeholder.svg';
  }

  openChampionModal(): void {
    console.log('🎯🎯🎯 [openChampionModal] BOTÃO CLICADO - Abrindo modal!');
    logDraft('🎯 [openChampionModal] === ABRINDO MODAL DE CAMPEÕES ===');
    saveLogToRoot(`🎯 [openChampionModal] Tentando abrir modal de campeões`);

    if (!this.session) {
      console.log('❌ [openChampionModal] Session não existe');
      logDraft('❌ [openChampionModal] Session não existe - não abrindo modal');
      saveLogToRoot(`❌ [openChampionModal] Session não existe`);
      return;
    }

    // ✅ CORREÇÃO: Forçar atualização do estado antes de verificar se é a vez
    this.updateDraftState();

    // ✅ PERMITIR reabrir modal mesmo se não for mais a vez (caso tenha fechado sem querer)
    console.log('✅ [openChampionModal] Abrindo modal - isMyTurn:', this.isMyTurn);

    if (this.session.phase === 'completed' || this.session.currentAction >= this.session.phases.length) {
      console.log('❌ [openChampionModal] Draft completado');
      logDraft('❌ [openChampionModal] Sessão completada ou inválida - não abrindo modal');
      saveLogToRoot(`❌ [openChampionModal] Sessão completada ou inválida. phase=${this.session.phase}, currentAction=${this.session.currentAction}, phases.length=${this.session.phases.length}`);
      return;
    }

    this.showChampionModal = true;
    this.cdr.markForCheck();
    this.cdr.detectChanges();
    console.log('✅ [openChampionModal] Modal ABERTO! showChampionModal =', this.showChampionModal);
    console.log('⏰ [openChampionModal] Timer atual no modal:', this.timeRemaining);
    logDraft('🎯 [openChampionModal] === FIM DA ABERTURA DO MODAL ===');

    // ✅ CORREÇÃO: Forçar atualização adicional para garantir que o timer seja exibido
    setTimeout(() => {
      this.cdr.detectChanges();
      console.log('⏰ [openChampionModal] Timer após timeout:', this.timeRemaining);
    }, 100);

    // ✅ CORREÇÃO: Forçar atualização adicional para garantir que o timer seja exibido
    setTimeout(() => {
      this.cdr.detectChanges();
      console.log('⏰ [openChampionModal] Timer após segundo timeout:', this.timeRemaining);
    }, 500);
    saveLogToRoot(`✅ [openChampionModal] Modal aberto com sucesso. showChampionModal=${this.showChampionModal}`);
  }

  openConfirmationModal(): void {
    logDraft('🎯 [openConfirmationModal] === ABRINDO MODAL DE CONFIRMAÇÃO ===');

    // ✅ NOVO: Sincronizar dados antes de abrir modal
    this.syncConfirmationData();

    this.showConfirmationModal = true;
    this.cdr.markForCheck();
  }

  // ✅ NOVO: Método para sincronizar dados de confirmação
  private async syncConfirmationData(): Promise<void> {
    try {
      if (!this.matchId) return;

      logDraft('🔄 [syncConfirmationData] Sincronizando dados de confirmação...');

      const response = await firstValueFrom(this.http.get(`${this.baseUrl}/match/${this.matchId}/confirmation-status`));

      if (response && (response as any).confirmationData) {
        this.confirmationData = (response as any).confirmationData;
        logDraft('✅ [syncConfirmationData] Dados de confirmação sincronizados:', this.confirmationData);
        this.cdr.detectChanges();
      }
    } catch (error) {
      logDraft('❌ [syncConfirmationData] Erro ao sincronizar dados de confirmação:', error);
    }
  }

  // ✅ MÉTODOS DE AÇÃO
  async onanySelected(champion: any): Promise<void> {
    console.log('🚀🚀🚀 [onanySelected] MÉTODO CHAMADO!');
    console.log('🚀 Champion:', champion);
    console.log('🚀 currentPlayer:', this.currentPlayer);
    console.log('🚀 matchId:', this.matchId);

    logDraft('🎯 [onanySelected] === CAMPEÃO SELECIONADO ===');
    logDraft('🎯 [onanySelected] Campeão selecionado:', champion.name);

    // ✅ LOGS DETALHADOS DE DEBUG
    const currentPhasePreview = this.session?.phases?.[this.session?.currentAction];
    saveLogToRoot(`\n========== [onanySelected] INÍCIO DA AÇÃO ==========`);
    saveLogToRoot(`🎯 Campeão: ${champion.name} (ID: ${champion.id})`);
    saveLogToRoot(`🎯 MatchId: ${this.matchId}`);
    saveLogToRoot(`🎯 Current Action Index: ${this.session?.currentAction}`);
    saveLogToRoot(`🎯 Fase Atual: ${currentPhasePreview?.action} (Team: ${currentPhasePreview?.team})`);
    saveLogToRoot(`🎯 Jogador da Vez: ${currentPhasePreview?.playerName} (ID: ${currentPhasePreview?.playerId})`);
    saveLogToRoot(`🎯 Current Player: ${this.currentPlayer?.summonerName || this.currentPlayer?.displayName}`);
    saveLogToRoot(`🎯 Is Editing Mode: ${this.isEditingMode}`);
    saveLogToRoot(`🎯 Total Phases: ${this.session?.phases?.length || 0}`);
    saveLogToRoot(`====================================================\n`);

    if (!this.session) {
      logDraft('❌ [onanySelected] Session não existe');
      saveLogToRoot(`❌ [onanySelected] Session não existe`);
      return;
    }

    // ✅ NOVO: Verificar se estamos em modo de edição
    if (this.isEditingMode && this.currentEditingPlayer) {
      logDraft('🔄 [onanySelected] Modo edição - sobrescrevendo pick anterior');
      saveLogToRoot(`🔄 [onanySelected] Editando pick de ${this.currentEditingPlayer.playerId} para ${champion.name}`);

      await this.updatePlayerPick(this.currentEditingPlayer.playerId, champion);

      // Resetar modo de edição
      this.isEditingMode = false;
      this.currentEditingPlayer = null;
      this.showChampionModal = false;
      this.showConfirmationModal = true; // Voltar para modal de confirmação
      this.cdr.detectChanges();
      return;
    }

    // ✅ NOVO: Verificar se estamos em draft completado (modo confirmação)
    const isDraftCompleted = this.session.phase === 'completed' ||
      this.session.currentAction >= (this.session.phases?.length || 0);

    if (isDraftCompleted) {
      logDraft('🔄 [onanySelected] Draft completado - editando pick via botão');
      saveLogToRoot(`🔄 [onanySelected] Draft completado - editando pick para campeão ${champion.name}`);
      await this.changePlayerPick(Number(champion.id));
      return;
    }

    const currentPhase = this.session.phases[this.session.currentAction];
    if (!currentPhase) {
      logDraft('❌ [onanySelected] Fase atual não existe');
      saveLogToRoot(`❌ [onanySelected] Fase atual não existe para currentAction=${this.session.currentAction}`);
      return;
    }

    // ✅ NOVO: Log detalhado da fase atual
    saveLogToRoot(`🎯 [onanySelected] Fase atual: ${JSON.stringify(currentPhase)}`);

    // ✅ MELHORADO: Atualizar fase local
    currentPhase.champion = champion;
    currentPhase.locked = true;
    currentPhase.timeRemaining = 0;
    this.showChampionModal = false;

    if (!this.botService.isBot(this.currentPlayer)) {
      this.isWaitingBackend = true;
    }

    // ✅ CORREÇÃO: Garantir que matchId seja sempre válido
    const effectiveMatchId = this.matchId || this.matchData?.matchId || this.matchData?.id;
    if (effectiveMatchId) {
      saveLogToRoot(`🎯 [onanySelected] Usando matchId: ${effectiveMatchId}`);
      try {
        // 🔍 DEBUG: Mostrar TODO o conteúdo de currentPlayer
        saveLogToRoot(`🔍 [onanySelected] currentPlayer COMPLETO: ${JSON.stringify(this.currentPlayer, null, 2)}`);
        saveLogToRoot(`🔍 [onanySelected] currentPhase.playerName: ${currentPhase.playerName}`);
        saveLogToRoot(`🔍 [onanySelected] currentPhase.playerId: ${currentPhase.playerId}`);

        // ✅ CORREÇÃO CRÍTICA: Priorizar gameName#tagLine que é o formato usado no backend
        let playerIdentifier = '';

        // Prioridade 1: gameName#tagLine (formato completo com tag, usado no backend)
        if (this.currentPlayer?.gameName && this.currentPlayer?.tagLine) {
          playerIdentifier = `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`;
          saveLogToRoot(`🎯 [onanySelected] Usando gameName#tagLine: ${playerIdentifier}`);
        } else if (this.currentPlayer?.displayName) {
          // Prioridade 2: displayName (pode incluir tag)
          playerIdentifier = this.currentPlayer.displayName;
          saveLogToRoot(`🎯 [onanySelected] Usando displayName: ${playerIdentifier}`);
        } else if (currentPhase.playerName) {
          // Prioridade 3: playerName da fase (vem do backend com formato correto)
          playerIdentifier = currentPhase.playerName;
          saveLogToRoot(`🎯 [onanySelected] Usando playerName da fase: ${playerIdentifier}`);
        } else if (currentPhase.playerId) {
          // Prioridade 4: playerId da fase
          playerIdentifier = currentPhase.playerId;
          saveLogToRoot(`🎯 [onanySelected] Usando playerId da fase: ${playerIdentifier}`);
        } else if (this.currentPlayer?.summonerName) {
          // Prioridade 5: summonerName (pode não ter tag)
          playerIdentifier = this.currentPlayer.summonerName;
          saveLogToRoot(`🎯 [onanySelected] Usando summonerName: ${playerIdentifier}`);
        } else if (this.currentPlayer?.puuid) {
          // Última opção: PUUID
          playerIdentifier = this.currentPlayer.puuid;
          saveLogToRoot(`🎯 [onanySelected] Usando PUUID como fallback: ${playerIdentifier}`);
        }

        const url = `${this.baseUrl}/match/draft-action`;
        const requestData = {
          matchId: effectiveMatchId,
          playerId: playerIdentifier,
          championId: champion.id,  // ✅ Usando nome do campeão (ex: "Ahri")
          action: currentPhase.action,
          actionIndex: this.session.currentAction
        };

        console.log('📡📡📡 [onanySelected] ENVIANDO PARA BACKEND!');
        console.log('📡 URL:', url);
        console.log('📡 Request:', requestData);
        logDraft('🎯 [onanySelected] Enviando ação para backend:', requestData);
        saveLogToRoot(`🎯 [onanySelected] === ENVIANDO POST === URL: ${url}`);
        saveLogToRoot(`🎯 [onanySelected] Request Data: ${JSON.stringify(requestData)}`);

        const response = await firstValueFrom(this.http.post(url, requestData, {
          headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
          }
        }));

        console.log('✅✅✅ [onanySelected] RESPOSTA RECEBIDA:', response);

        // ✅ LOGS DETALHADOS DA RESPOSTA
        saveLogToRoot(`\n========== [onanySelected] RESPOSTA DO BACKEND ==========`);
        saveLogToRoot(`✅ Status: SUCCESS`);
        saveLogToRoot(`✅ Resposta completa: ${JSON.stringify(response, null, 2)}`);
        saveLogToRoot(`=========================================================\n`);

        logDraft('✅ [onanySelected] Ação enviada para backend com sucesso');

        // ✅ OTIMIZAÇÃO: Sincronização imediata após ação + retry automático
        this.syncSessionWithRetry();

        // ✅ MELHORADO: Aguardar um pouco antes de parar o loading
        setTimeout(() => {
          this.isWaitingBackend = false;
          logDraft('✅ [onanySelected] Loading finalizado');
          saveLogToRoot(`✅ [onanySelected] Loading finalizado`);
        }, 1000);
      } catch (error: any) {
        logDraft('❌ [onanySelected] Erro ao enviar para backend:', error);
        saveLogToRoot(`❌ [onanySelected] Erro: ${error}`);

        // ✅ NOVO: Fallback para erro de autorização
        if (error?.error?.error?.includes('não autorizado')) {
          saveLogToRoot(`🔄 [onanySelected] Erro de autorização detectado - tentando com playerId da fase`);

          // Reconstruir URL e requestData no escopo correto
          const fallbackUrl = `${this.baseUrl}/match/draft-action`;
          const fallbackRequestData = {
            matchId: effectiveMatchId,
            playerId: currentPhase.playerName || currentPhase.playerId,
            championId: champion.id,  // ✅ Usando nome do campeão (ex: "Ahri")
            action: currentPhase.action,
            actionIndex: this.session.currentAction
          };

          try {
            saveLogToRoot(`🔄 [onanySelected] Tentativa fallback: ${JSON.stringify(fallbackRequestData)}`);
            const fallbackResponse = await firstValueFrom(this.http.post(fallbackUrl, fallbackRequestData, {
              headers: { 'Content-Type': 'application/json' }
            }));
            saveLogToRoot(`✅ [onanySelected] Fallback bem-sucedido: ${JSON.stringify(fallbackResponse)}`);

            // Se o fallback funcionar, seguir o fluxo normal
            this.syncSessionWithRetry();
            setTimeout(() => {
              this.isWaitingBackend = false;
              logDraft('✅ [onanySelected] Loading finalizado (fallback)');
              saveLogToRoot(`✅ [onanySelected] Loading finalizado (fallback)`);
            }, 1000);
            return;
          } catch (fallbackError) {
            saveLogToRoot(`❌ [onanySelected] Fallback também falhou: ${fallbackError}`);
          }
        }

        this.isWaitingBackend = false;
      }
    } else {
      logDraft('❌ [onanySelected] Nenhum matchId disponível');
      saveLogToRoot(`❌ [onanySelected] Nenhum matchId disponível`);
      this.isWaitingBackend = false;
    }
  }

  // ✅ REMOVIDO: onEditRequested - não é mais necessário, o backend controla tudo

  completePickBan() {
    this.onPickBanComplete.emit({
      session: this.session,
      blueTeam: this.session?.blueTeam,
      redTeam: this.session?.redTeam
    });
  }

  cancelPickBan() {
    this.onPickBanCancel.emit();
  }

  // ✅ NOVO: Métodos para modal de confirmação
  onConfirmationModalClose(): void {
    logDraft('🎯 [onConfirmationModalClose] Fechando modal de confirmação');
    this.showConfirmationModal = false;
    this.cdr.detectChanges();
  }

  async onConfirmationModalConfirm(): Promise<void> {
    logDraft('🎯 [onConfirmationModalConfirm] === CONFIRMANDO DRAFT ===');
    saveLogToRoot(`✅ [onConfirmationModalConfirm] Jogador confirmando draft`);

    try {
      if (!this.matchId || !this.currentPlayer) {
        throw new Error('matchId ou currentPlayer não disponível');
      }

      const playerId = this.currentPlayer.id?.toString() ||
        (this.currentPlayer.gameName && this.currentPlayer.tagLine
          ? `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`
          : this.currentPlayer.summonerName || this.currentPlayer.name);

      const response = await firstValueFrom(this.http.post(`${this.baseUrl}/match/confirm-draft`, {
        matchId: this.matchId,
        playerId: playerId
      }));

      logDraft('✅ [onConfirmationModalConfirm] Draft confirmado com sucesso:', response);
      saveLogToRoot(`✅ [onConfirmationModalConfirm] Draft confirmado com sucesso: ${JSON.stringify(response)}`);

      // ✅ NOVO: Atualizar estado do modal de confirmação
      const responseData = response as any;
      if (this.confirmationModal) {
        this.confirmationModal.updateConfirmationState(
          responseData.success || false,
          responseData.allConfirmed || false
        );
      }

      // Modal será fechado automaticamente quando receber WebSocket de que todos confirmaram
      // ou permanecerá aberto aguardando outros jogadores
    } catch (error) {
      logDraft('❌ [onConfirmationModalConfirm] Erro ao confirmar draft:', error);
      saveLogToRoot(`❌ [onConfirmationModalConfirm] Erro: ${JSON.stringify(error)}`);

      // ✅ NOVO: Atualizar estado em caso de erro
      if (this.confirmationModal) {
        this.confirmationModal.updateConfirmationState(false, false);
      }
    }
  }

  onConfirmationModalCancel(): void {
    logDraft('🎯 [onConfirmationModalCancel] === CANCELANDO PARTIDA ===');
    saveLogToRoot(`❌ [onConfirmationModalCancel] Jogador cancelando partida`);
    this.showConfirmationModal = false;
    this.onPickBanCancel.emit();
  }

  async onConfirmationModalEditPick(data: { playerId: string, phaseIndex: number }): Promise<void> {
    logDraft('🎯 [onConfirmationModalEditPick] === EDITANDO PICK ===', data);
    saveLogToRoot(`✏️ [onConfirmationModalEditPick] Editando pick: ${JSON.stringify(data)}`);

    // ✅ PRIMEIRO: Log do estado atual ANTES de qualquer alteração
    saveLogToRoot(`🔍 [onConfirmationModalEditPick] ANTES: showanyModal=${this.showChampionModal}, showConfirmationModal=${this.showConfirmationModal}, isEditingMode=${this.isEditingMode}`);

    // ✅ CRÍTICO: Definir modo de edição PRIMEIRO para bloquear updateInterfaceState
    this.isEditingMode = true;
    saveLogToRoot(`🔒 [onConfirmationModalEditPick] MODO EDIÇÃO ATIVADO: isEditingMode=${this.isEditingMode}`);

    // ✅ Armazenar dados da edição ANTES de alterar modais
    this.currentEditingPlayer = {
      playerId: data.playerId,
      phaseIndex: data.phaseIndex
    };
    saveLogToRoot(`� [onConfirmationModalEditPick] Dados de edição armazenados: ${JSON.stringify(this.currentEditingPlayer)}`);

    // ✅ VERIFICAR: Buscar informações do jogador nas fases para validação
    if (this.session?.phases && data.phaseIndex !== undefined && this.session.phases[data.phaseIndex]) {
      const targetPhase = this.session.phases[data.phaseIndex];
      saveLogToRoot(`🔍 [onConfirmationModalEditPick] Jogador encontrado: ${JSON.stringify({
        phaseIndex: data.phaseIndex,
        playerId: targetPhase.playerId,
        playerName: targetPhase.playerName,
        action: targetPhase.action,
        champion: targetPhase.champion?.name
      })}`);
    } else {
      saveLogToRoot(`⚠️ [onConfirmationModalEditPick] AVISO: Fase não encontrada para phaseIndex=${data.phaseIndex}`);
    }

    // ✅ ALTERAR MODAIS: Fechar confirmação e abrir campeão
    this.showConfirmationModal = false;
    this.showChampionModal = true;
    saveLogToRoot(`🔄 [onConfirmationModalEditPick] MODAIS ALTERADOS: showanyModal=${this.showChampionModal}, showConfirmationModal=${this.showConfirmationModal}`);

    logDraft('✅ [onConfirmationModalEditPick] Modal de edição configurado');
    saveLogToRoot(`✅ [onConfirmationModalEditPick] Modo edição ativado para jogador: ${data.playerId}`);

    // ✅ DIAGNÓSTICO: Verificar condições do template ANTES de detectChanges
    const sessionOk = this.session ? 'true' : 'false';
    const phaseOk = this.session?.phase !== 'completed' ? 'true' : 'false';
    const actionOk = this.session ? this.session.currentAction < this.session.phases.length : 'session_null';
    saveLogToRoot(`🔍 [onConfirmationModalEditPick] CONDIÇÕES TEMPLATE: isVisible=${this.showChampionModal}, session=${sessionOk}, phase!='completed'=${phaseOk}, currentAction<phases.length=${actionOk}`);

    // ✅ FORÇAR detecção de mudanças IMEDIATAMENTE
    this.cdr.detectChanges();
    saveLogToRoot(`🔄 [onConfirmationModalEditPick] Detecção de mudanças forçada`);

    // ✅ VERIFICAÇÃO: Timeout para garantir que o estado não foi sobrescrito
    setTimeout(() => {
      saveLogToRoot(`🕒 [onConfirmationModalEditPick] VERIFICAÇÃO (100ms): showanyModal=${this.showChampionModal}, showConfirmationModal=${this.showConfirmationModal}, isEditingMode=${this.isEditingMode}`);

      // ✅ DIAGNÓSTICO: Verificar condições do template APÓS timeout
      const sessionOk2 = this.session ? 'true' : 'false';
      const phaseOk2 = this.session?.phase !== 'completed' ? 'true' : 'false';
      const actionOk2 = this.session ? this.session.currentAction < this.session.phases.length : 'session_null';
      saveLogToRoot(`🔍 [onConfirmationModalEditPick] CONDIÇÕES TEMPLATE (100ms): isVisible=${this.showChampionModal}, session=${sessionOk2}, phase!='completed'=${phaseOk2}, currentAction<phases.length=${actionOk2}`);

      if (!this.showChampionModal || this.showConfirmationModal || !this.isEditingMode) {
        saveLogToRoot(`🚨 [onConfirmationModalEditPick] ESTADO INCORRETO - CORRIGINDO`);
        this.isEditingMode = true;
        this.showChampionModal = true;
        this.showConfirmationModal = false;
        this.cdr.detectChanges();
        saveLogToRoot(`🔧 [onConfirmationModalEditPick] Estado corrigido`);
      }
    }, 100);
    saveLogToRoot(`� [onConfirmationModalEditPick] Detecção de mudanças forçada`);
  }

  // ✅ NOVO: Método para atualizar estado do draft
  async onConfirmationModalRefresh(): Promise<void> {
    logDraft('🔄 [onConfirmationModalRefresh] === ATUALIZANDO ESTADO DO DRAFT ===');
    saveLogToRoot(`🔄 [onConfirmationModalRefresh] Solicitando atualização do estado do draft`);

    try {
      // ✅ CRÍTICO: NÃO forçar sync HTTP - aguardar WebSocket + ngOnChanges
      // await this.syncSessionWithBackend();

      // Invalidar caches do modal de confirmação
      this.cdr.detectChanges();

      logDraft('✅ [onConfirmationModalRefresh] Estado do draft atualizado com sucesso');
      saveLogToRoot(`✅ [onConfirmationModalRefresh] Estado atualizado`);
    } catch (error) {
      logDraft('❌ [onConfirmationModalRefresh] Erro ao atualizar estado:', error);
      saveLogToRoot(`❌ [onConfirmationModalRefresh] Erro: ${error}`);
    }
  }

  // ✅ NOVO: Método para alterar pick no backend
  async changePlayerPick(championId: number): Promise<void> {
    logDraft('🔄 [changePlayerPick] === ALTERANDO PICK ===');
    saveLogToRoot(`🔄 [changePlayerPick] Alterando pick para campeão ${championId}`);

    try {
      if (!this.matchId || !this.currentPlayer) {
        throw new Error('matchId ou currentPlayer não disponível');
      }

      const playerId = this.currentPlayer.id?.toString() ||
        (this.currentPlayer.gameName && this.currentPlayer.tagLine
          ? `${this.currentPlayer.gameName}#${this.currentPlayer.tagLine}`
          : this.currentPlayer.summonerName || this.currentPlayer.name);

      const response = await firstValueFrom(this.http.post(`${this.baseUrl}/match/draft-action`, {
        matchId: this.matchId,
        playerId: playerId,
        championId: championId,
        action: 'pick', // Para changePlayerPick sempre é pick
        actionIndex: 0 // Para mudança de pick, usar 0 como padrão
      }));

      logDraft('✅ [changePlayerPick] Pick alterado com sucesso:', response);
      saveLogToRoot(`✅ [changePlayerPick] Pick alterado com sucesso: ${JSON.stringify(response)}`);

      // Fechar modal de campeão e reabrir modal de confirmação
      this.showChampionModal = false;
      this.showConfirmationModal = true;
      this.cdr.detectChanges();

      // ✅ CRÍTICO: NÃO forçar sync HTTP - aguardar WebSocket + ngOnChanges
      // await this.syncSessionWithBackend();
    } catch (error) {
      logDraft('❌ [changePlayerPick] Erro ao alterar pick:', error);
      saveLogToRoot(`❌ [changePlayerPick] Erro: ${JSON.stringify(error)}`);
    }
  }

  // ✅ NOVO: Método para atualizar pick específico de um jogador
  async updatePlayerPick(playerId: string, champion: any): Promise<void> {
    logDraft('🔄 [updatePlayerPick] === ATUALIZANDO PICK ===');
    saveLogToRoot(`🔄 [updatePlayerPick] Atualizando pick de ${playerId} para ${champion.name}`);

    try {
      if (!this.matchId) {
        throw new Error('matchId não disponível');
      }

      if (!this.currentEditingPlayer) {
        throw new Error('currentEditingPlayer não disponível');
      }

      // ✅ CORREÇÃO: Buscar o summonerName correto da fase que está sendo editada
      const phaseIndex = this.currentEditingPlayer.phaseIndex;
      const targetPhase = this.session?.phases?.[phaseIndex];

      if (!targetPhase) {
        throw new Error(`Fase ${phaseIndex} não encontrada`);
      }

      // ✅ USAR o playerName da fase (que é o summonerName correto)
      const correctPlayerId = targetPhase.playerName || targetPhase.playerId;
      saveLogToRoot(`🔧 [updatePlayerPick] Usando playerId correto: ${correctPlayerId} (original: ${playerId})`);

      // ✅ DEBUG: Log da URL completa
      const fullUrl = `${this.baseUrl}/match/change-pick`;
      saveLogToRoot(`🔍 [updatePlayerPick] URL completa: ${fullUrl}`);
      saveLogToRoot(`🔍 [updatePlayerPick] baseUrl: "${this.baseUrl}"`);

      const response = await firstValueFrom(this.http.post(fullUrl, {
        matchId: this.matchId,
        playerId: correctPlayerId,
        championId: Number(champion.id)
      }));

      logDraft('✅ [updatePlayerPick] Pick atualizado com sucesso:', response);
      saveLogToRoot(`✅ [updatePlayerPick] Pick atualizado: ${JSON.stringify(response)}`);

      // ✅ CRÍTICO: NÃO forçar sync HTTP - aguardar WebSocket + ngOnChanges
      // await this.syncSessionWithBackend();

      // ✅ FINALIZAR EDIÇÃO e abrir modal de confirmação
      this.isEditingMode = false;
      this.currentEditingPlayer = null;
      this.showChampionModal = false;
      this.showConfirmationModal = true; // ✅ REABRIR modal de confirmação
      this.cdr.detectChanges();
      saveLogToRoot(`✅ [updatePlayerPick] Pick atualizado e modal de confirmação reaberto`);
    } catch (error) {
      logDraft('❌ [updatePlayerPick] Erro:', error);
      saveLogToRoot(`❌ [updatePlayerPick] Erro detalhado: ${JSON.stringify(error, null, 2)}`);
      saveLogToRoot(`❌ [updatePlayerPick] Erro string: ${String(error)}`);
      if (error instanceof Error) {
        saveLogToRoot(`❌ [updatePlayerPick] Error.message: ${error.message}`);
        saveLogToRoot(`❌ [updatePlayerPick] Error.stack: ${error.stack}`);
      }
      throw error;
    }
  }

  // ✅ MÉTODOS DE TIMER E TIMEOUT
  updateTimerFromBackend(data: any): void {
    console.log('⏰ [updateTimerFromBackend] Chamado com:', data);

    // ✅ CORREÇÃO: Backend é a ÚNICA fonte de verdade para o timer
    if (!data || typeof data.timeRemaining !== 'number') {
      console.warn('⚠️ [updateTimerFromBackend] Dados inválidos:', data);
      saveLogToRoot(`⚠️ [updateTimerFromBackend] Dados inválidos: ${JSON.stringify(data)}`);
      return;
    }

    // ✅ CORREÇÃO: Sempre usar o valor do backend
    const oldTimeRemaining = this.timeRemaining;
    this.timeRemaining = data.timeRemaining;

    console.log(`⏰ [updateTimerFromBackend] Timer: ${oldTimeRemaining}s → ${data.timeRemaining}s`);
    console.log(`⏰ [updateTimerFromBackend] Modal visível: ${this.showChampionModal}`);
    saveLogToRoot(`⏰ [updateTimerFromBackend] Timer atualizado: ${oldTimeRemaining}s → ${data.timeRemaining}s`);
    logDraft(`⏰ [updateTimerFromBackend] Timer do backend: ${data.timeRemaining}s`);

    // ✅ CRÍTICO: Forçar atualização da interface
    this.cdr.markForCheck();
    this.cdr.detectChanges();

    // ✅ CORREÇÃO: Se o modal estiver aberto, forçar atualização específica
    if (this.showChampionModal) {
      console.log('⏰ [updateTimerFromBackend] Modal aberto - forçando atualização específica');
      // Forçar nova detecção de mudanças para o modal
      setTimeout(() => {
        this.cdr.detectChanges();
        console.log('⏰ [updateTimerFromBackend] Timer após timeout no modal:', this.timeRemaining);
      }, 0);
    }

    // ✅ CORREÇÃO: Verificar se timer expirou
    if (data.timeRemaining <= 0) {
      console.warn(`⏰ [updateTimerFromBackend] Timer expirou!`);
      saveLogToRoot(`⏰ [updateTimerFromBackend] Timer expirou (${data.timeRemaining}s)`);
      logDraft(`⏰ [updateTimerFromBackend] Timer expirou - aguardando backend`);
    }
  }

  handleDraftTimeout(data: any): void {
    logDraft('⏰ [DraftPickBan] === HANDLE DRAFT TIMEOUT ===');

    if (data.matchId && this.matchId && data.matchId !== this.matchId) {
      logDraft('⚠️ [DraftPickBan] Timeout para matchId diferente:', data.matchId, 'vs', this.matchId);
      return;
    }

    const isTimeoutForCurrentPlayer = data.playerId && this.currentPlayer &&
      (data.playerId === this.currentPlayer.displayName ||
        data.playerId === this.currentPlayer.summonerName ||
        data.playerId === this.currentPlayer.name);

    if (isTimeoutForCurrentPlayer) {
      logDraft('⏰ [DraftPickBan] Timeout para o jogador atual, fechando modais');
      this.showChampionModal = false;
      this.showConfirmationModal = false;
      this.isWaitingBackend = false;
    }

    this.cdr.detectChanges();
    logDraft('⏰ [DraftPickBan] Timeout processado com sucesso');
  }

  // ✅ MÉTODOS AUXILIARES

  // ✅ NOVA ESTRUTURA HIERÁRQUICA: Métodos helper para acessar dados

  /**
   * Obtém o time azul da estrutura hierárquica ou fallback para flat
   */
  getBlueTeam(): any[] {
    if (this.session?.teams?.blue?.players) {
      return this.session.teams.blue.players;
    }
    return this.session?.blueTeam || [];
  }

  /**
   * Obtém o time vermelho da estrutura hierárquica ou fallback para flat
   */
  getRedTeam(): any[] {
    if (this.session?.teams?.red?.players) {
      return this.session.teams.red.players;
    }
    return this.session?.redTeam || [];
  }

  /**
   * Obtém todas as ações (bans/picks) de um jogador específico
   */
  getPlayerActions(playerName: string, teamColor: 'blue' | 'red'): any[] {
    if (this.session?.teams?.[teamColor]?.players) {
      const player = this.session.teams[teamColor].players.find(
        (p: any) => p.summonerName === playerName
      );
      return player?.actions || [];
    }
    // Fallback: buscar na estrutura flat
    return this.session?.phases?.filter((action: any) => action.byPlayer === playerName) || [];
  }

  /**
   * Obtém apenas os bans de um jogador
   */
  getPlayerBans(playerName: string, teamColor: 'blue' | 'red'): any[] {
    if (this.session?.teams?.[teamColor]?.players) {
      const player = this.session.teams[teamColor].players.find(
        (p: any) => p.summonerName === playerName
      );
      return player?.bans || [];
    }
    // Fallback: buscar na estrutura flat
    return this.session?.phases?.filter((action: any) =>
      action.byPlayer === playerName && action.type === 'ban'
    ) || [];
  }

  /**
   * Obtém apenas os picks de um jogador
   */
  getPlayerPicks(playerName: string, teamColor: 'blue' | 'red'): any[] {
    if (this.session?.teams?.[teamColor]?.players) {
      const player = this.session.teams[teamColor].players.find(
        (p: any) => p.summonerName === playerName
      );
      return player?.picks || [];
    }
    // Fallback: buscar na estrutura flat
    return this.session?.phases?.filter((action: any) =>
      action.byPlayer === playerName && action.type === 'pick'
    ) || [];
  }

  /**
   * Obtém todos os bans de um time
   */
  getTeamBans(teamColor: 'blue' | 'red'): any[] {
    // ✅ Retornar objetos de campeão com image como URL string (igual aos picks)
    if (this.session?.teams?.[teamColor]?.allBans) {
      const bans = this.session.teams[teamColor].allBans;
      return bans.map((championId: string) => {
        const champion = this.getChampionFromCache(parseInt(championId, 10));
        if (champion) {
          // ✅ CORREÇÃO: Retornar com image como string URL (igual findPickInActions)
          return {
            id: champion.key,
            name: champion.name,
            image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`
          };
        }
        // Fallback: retornar objeto básico se não encontrar no cache
        return {
          id: championId,
          name: `Champion ${championId}`,
          image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/Unknown.png`
        };
      }).filter((c: any) => c !== null);
    }

    // Fallback: buscar na estrutura flat
    const teamNumber = teamColor === 'blue' ? 1 : 2;
    const championIds = this.session?.phases
      ?.filter((action: any) => action.type === 'ban' && action.team === teamNumber && action.championId)
      ?.map((action: any) => action.championId) || [];

    // Converter IDs em objetos de campeão com image como URL string
    return championIds.map((championId: string) => {
      const champion = this.getChampionFromCache(parseInt(championId, 10));
      if (champion) {
        return {
          id: champion.key,
          name: champion.name,
          image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`
        };
      }
      return {
        id: championId,
        name: `Champion ${championId}`,
        image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/Unknown.png`
      };
    });
  }

  /**
   * Obtém todos os picks de um time (como objetos de campeão)
   */
  getTeamPicks(teamColor: 'blue' | 'red'): any[] {
    // ✅ Retornar objetos de campeão com image como URL string (igual aos bans)
    if (this.session?.teams?.[teamColor]?.allPicks) {
      const picks = this.session.teams[teamColor].allPicks;
      return picks.map((championId: string) => {
        const champion = this.getChampionFromCache(parseInt(championId, 10));
        if (champion) {
          // ✅ CORREÇÃO: Retornar com image como string URL (igual findPickInActions)
          return {
            id: champion.key,
            name: champion.name,
            image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`
          };
        }
        // Fallback: retornar objeto básico se não encontrar no cache
        return {
          id: championId,
          name: `Champion ${championId}`,
          image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/Unknown.png`
        };
      }).filter((c: any) => c !== null);
    }

    // Fallback: buscar na estrutura flat
    const teamNumber = teamColor === 'blue' ? 1 : 2;
    const championIds = this.session?.phases
      ?.filter((action: any) => action.type === 'pick' && action.team === teamNumber && action.championId)
      ?.map((action: any) => action.championId) || [];

    // Converter IDs em objetos de campeão com image como URL string
    return championIds.map((championId: string) => {
      const champion = this.getChampionFromCache(parseInt(championId, 10));
      if (champion) {
        return {
          id: champion.key,
          name: champion.name,
          image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/${champion.id}.png`
        };
      }
      return {
        id: championId,
        name: `Champion ${championId}`,
        image: `https://ddragon.leagueoflegends.com/cdn/15.19.1/img/champion/Unknown.png`
      };
    });
  }

  /**
   * Obtém a fase atual do draft (ban1, pick1, ban2, pick2)
   */
  getCurrentPhase(): string {
    return this.session?.currentPhase || 'ban1';
  }

  /**
   * Obtém o time atual da ação (blue/red)
   */
  getCurrentTeam(): string {
    return this.session?.currentTeam || 'blue';
  }

  /**
   * Obtém o tipo da ação atual (ban/pick)
   */
  getCurrentActionType(): string {
    return this.session?.currentActionType || 'ban';
  }

  private isBlueTeam(team: any): boolean {
    if (team === 1) return true;
    if (typeof team === 'string') {
      return ['blue', 'Blue', 'BLUE', 'team1'].includes(team);
    }
    return false;
  }

  isPlayerAutofill(player: any): boolean {
    return player.isAutofill === true;
  }

  getLaneDisplayWithAutofill(player: any): string {
    const lane = this.getLaneDisplayName(player.lane || player.assignedLane);
    const autofillText = this.isPlayerAutofill(player) ? ' (Autofill)' : '';
    return lane + autofillText;
  }

  getAutofillClass(player: any): string {
    return this.isPlayerAutofill(player) ? 'autofill-player' : '';
  }

  trackByPlayer(index: number, player: any): string {
    return player.summonerName || player.name || index.toString();
  }

  trackByany(index: number, champion: any): string {
    return champion.id.toString() || champion.key || index.toString();
  }

  // ✅ NOVO: Método para receber ações do draft do backend
  handleDraftAction(data: any): void {
    logDraft('🔄 [DraftPickBan] === HANDLE DRAFT ACTION ===');
    logDraft('🔄 [DraftPickBan] Dados da ação:', data);

    // Atualizar dados da sessão com informações do backend
    if (data.currentAction !== undefined) {
      this.session = {
        ...this.session,
        currentAction: data.currentAction,
        lastAction: data.lastAction
      };
    }

    // Atualizar interface
    this.updateDraftState();
    this.updateInterfaceState();
    this.checkIfMyTurn();

    // Forçar atualização da interface
    this.cdr.detectChanges();
    logDraft('🔄 [DraftPickBan] Ação do draft processada com sucesso');
  }

  // ✅ NOVO: Método para receber mudanças de jogador do backend
  handlePlayerChange(data: any): void {
    logDraft('🔄 [DraftPickBan] === HANDLE PLAYER CHANGE ===');
    logDraft('🔄 [DraftPickBan] Dados da mudança de jogador:', data);

    // Atualizar currentAction da sessão
    if (data.currentAction !== undefined) {
      this.session = {
        ...this.session,
        currentAction: data.currentAction
      };
    }

    // Atualizar interface
    this.updateDraftState();
    this.updateInterfaceState();
    this.checkIfMyTurn();

    // Forçar atualização da interface
    this.cdr.detectChanges();
    logDraft('🔄 [DraftPickBan] Mudança de jogador processada com sucesso');
  }

  // ✅ NOVO: Método para receber sincronização de dados do draft do backend
  handleDraftDataSync(data: any): void {
    logDraft('🔄 [DraftPickBan] === HANDLE DRAFT DATA SYNC ===');
    logDraft('🔄 [DraftPickBan] Dados da sincronização:', data);
    saveLogToRoot(`🔄 [handleDraftDataSync] Dados recebidos: ${JSON.stringify(data)}`);

    // ✅ NOVO: Log detalhado do currentAction antes da atualização
    const oldCurrentAction = this.session?.currentAction || 0;
    saveLogToRoot(`🔄 [handleDraftDataSync] currentAction ANTES: ${oldCurrentAction}`);

    // Atualizar dados da sessão com informações sincronizadas
    if (data.pickBanData) {
      const pickBanData = typeof data.pickBanData === 'string'
        ? JSON.parse(data.pickBanData)
        : data.pickBanData;

      // ✅ NOVO: Log detalhado dos dados do backend
      saveLogToRoot(`🔄 [handleDraftDataSync] Backend totalActions: ${data.totalActions}`);
      saveLogToRoot(`🔄 [handleDraftDataSync] Backend currentAction: ${data.currentAction}`);
      saveLogToRoot(`🔄 [handleDraftDataSync] Backend actions.length: ${pickBanData.actions?.length || 0}`);

      this.session = {
        ...this.session,
        blueTeam: pickBanData.team1 || this.session.blueTeam || [],
        redTeam: pickBanData.team2 || this.session.redTeam || [],
        phases: pickBanData.phases || this.session.phases || [],
        currentAction: data.currentAction || 0, // ✅ CORREÇÃO: Usar currentAction do backend
        phase: pickBanData.phase || this.session.phase || 'bans',
        actions: pickBanData.actions || this.session.actions || [],
        team1Picks: pickBanData.team1Picks || this.session.team1Picks || [],
        team1Bans: pickBanData.team1Bans || this.session.team1Bans || [],
        team2Picks: pickBanData.team2Picks || this.session.team2Picks || [],
        team2Bans: pickBanData.team2Bans || this.session.team2Bans || [],
        totalActions: data.totalActions,
        totalPicks: data.totalPicks,
        totalBans: data.totalBans,
        team1Stats: data.team1Stats,
        team2Stats: data.team2Stats,
        lastAction: data.lastAction,
        lastSyncTime: Date.now()
      };
    }

    // ✅ NOVO: Log detalhado do currentAction após a atualização
    const newCurrentAction = this.session?.currentAction || 0;
    saveLogToRoot(`🔄 [handleDraftDataSync] currentAction DEPOIS: ${newCurrentAction}`);
    saveLogToRoot(`🔄 [handleDraftDataSync] Mudança: ${oldCurrentAction} → ${newCurrentAction}`);

    // Atualizar interface
    this.updateDraftState();
    this.updateInterfaceState();
    this.checkIfMyTurn();

    // Forçar atualização da interface
    this.cdr.detectChanges();
    logDraft('🔄 [DraftPickBan] Sincronização de dados processada com sucesso');
    saveLogToRoot(`✅ [handleDraftDataSync] Sincronização concluída`);
  }
}
