import { Injectable, signal, WritableSignal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';

/**
 * ✅ SERVIÇO MODERNIZADO: Eventos do Electron para o Frontend usando Signals
 *
 * Este serviço escuta eventos enviados pelo Electron via IPC
 * e os converte em signals do Angular 16+ para uso nos componentes.
 *
 * ✅ MIGRADO: BehaviorSubject → signal() (Angular 16+)
 * ✅ COMPATIBILIDADE: Também expõe Observables via toObservable() para código legado
 */
@Injectable({
  providedIn: 'root'
})
export class ElectronEventsService {

  // === MATCH EVENTS (SIGNALS) ===
  public matchFound: WritableSignal<any> = signal(null);
  public draftStarted: WritableSignal<any> = signal(null);
  public gameInProgress: WritableSignal<any> = signal(null);
  public matchCancelled: WritableSignal<any> = signal(null);
  public draftCancelled: WritableSignal<any> = signal(null);
  public gameCancelled: WritableSignal<any> = signal(null);

  // === ACCEPTANCE EVENTS (SIGNALS) ===
  public acceptanceTimer: WritableSignal<any> = signal(null);
  public acceptanceProgress: WritableSignal<any> = signal(null);

  // === DRAFT EVENTS (SIGNALS) ===
  public draftTimer: WritableSignal<any> = signal(null);
  public draftUpdate: WritableSignal<any> = signal(null);
  public draftUpdated: WritableSignal<any> = signal(null);
  public pickChampion: WritableSignal<any> = signal(null);
  public banChampion: WritableSignal<any> = signal(null);
  public draftConfirmed: WritableSignal<any> = signal(null);
  public draftConfirmationUpdate: WritableSignal<any> = signal(null);

  // === GAME EVENTS (SIGNALS) ===
  public gameStarted: WritableSignal<any> = signal(null);
  public winnerModal: WritableSignal<any> = signal(null);
  public voteWinner: WritableSignal<any> = signal(null);
  public matchVoteProgress: WritableSignal<any> = signal(null);
  public matchVoteUpdate: WritableSignal<any> = signal(null);

  // === SPECTATOR EVENTS (SIGNALS) ===
  public spectatorMuted: WritableSignal<any> = signal(null);
  public spectatorUnmuted: WritableSignal<any> = signal(null);

  // === QUEUE EVENTS (SIGNALS) ===
  public queueStatus: WritableSignal<any> = signal(null);
  public queueUpdate: WritableSignal<any> = signal(null);

  // === CONNECTION EVENTS (SIGNALS) ===
  public backendConnection: WritableSignal<any> = signal(null);
  public playerSessionUpdate: WritableSignal<any> = signal(null);

  // ✅ COMPATIBILIDADE: Observables para código legado (gerados automaticamente dos signals)
  public readonly matchFound$: Observable<any> = toObservable(this.matchFound);
  public readonly draftStarted$: Observable<any> = toObservable(this.draftStarted);
  public readonly gameInProgress$: Observable<any> = toObservable(this.gameInProgress);
  public readonly matchCancelled$: Observable<any> = toObservable(this.matchCancelled);
  public readonly draftCancelled$: Observable<any> = toObservable(this.draftCancelled);
  public readonly gameCancelled$: Observable<any> = toObservable(this.gameCancelled);
  public readonly acceptanceTimer$: Observable<any> = toObservable(this.acceptanceTimer);
  public readonly acceptanceProgress$: Observable<any> = toObservable(this.acceptanceProgress);
  public readonly draftTimer$: Observable<any> = toObservable(this.draftTimer);
  public readonly draftUpdate$: Observable<any> = toObservable(this.draftUpdate);
  public readonly draftUpdated$: Observable<any> = toObservable(this.draftUpdated);
  public readonly pickChampion$: Observable<any> = toObservable(this.pickChampion);
  public readonly banChampion$: Observable<any> = toObservable(this.banChampion);
  public readonly draftConfirmed$: Observable<any> = toObservable(this.draftConfirmed);
  public readonly draftConfirmationUpdate$: Observable<any> = toObservable(this.draftConfirmationUpdate);
  public readonly gameStarted$: Observable<any> = toObservable(this.gameStarted);
  public readonly winnerModal$: Observable<any> = toObservable(this.winnerModal);
  public readonly voteWinner$: Observable<any> = toObservable(this.voteWinner);
  public readonly matchVoteProgress$: Observable<any> = toObservable(this.matchVoteProgress);
  public readonly matchVoteUpdate$: Observable<any> = toObservable(this.matchVoteUpdate);
  public readonly spectatorMuted$: Observable<any> = toObservable(this.spectatorMuted);
  public readonly spectatorUnmuted$: Observable<any> = toObservable(this.spectatorUnmuted);
  public readonly queueStatus$: Observable<any> = toObservable(this.queueStatus);
  public readonly queueUpdate$: Observable<any> = toObservable(this.queueUpdate);
  public readonly backendConnection$: Observable<any> = toObservable(this.backendConnection);
  public readonly playerSessionUpdate$: Observable<any> = toObservable(this.playerSessionUpdate);

  constructor() {
    this.initializeElectronListeners();
  }

  /**
   * ✅ Inicializar listeners do Electron
   */
  private initializeElectronListeners() {
    // Verificar se está rodando no Electron
    if (this.isElectron()) {
      console.log('🎮 [ElectronEvents] Inicializando listeners do Electron...');

      // ✅ Verificar se os métodos de eventos estão disponíveis
      if (window.electronAPI?.onMatchFound && typeof window.electronAPI.onMatchFound === 'function') {
        console.log('✅ [ElectronEvents] Métodos de eventos disponíveis, configurando listeners...');

        // ✅ MATCH_FOUND: Partida encontrada - mostrar modal de aceitar/recusar
        window.electronAPI.onMatchFound((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] match-found recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.matchFound.set({ ...data });
        });

        // ✅ DRAFT_STARTING: Draft iniciando (evento do backend)
        window.electronAPI.onDraftStarting((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-starting recebido:', data);
          console.log('🎯 [ElectronEvents] MatchId:', data.matchId);
          console.log('🎯 [ElectronEvents] Teams:', data.teams);
          console.log('🎯 [ElectronEvents] Team1:', data.team1);
          console.log('🎯 [ElectronEvents] Team2:', data.team2);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.draftStarted.set({ ...data });
        });

        // ✅ DRAFT_STARTED: Draft iniciado - ir para tela de draft
        window.electronAPI.onDraftStarted((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-started recebido:', data);
          console.log('🎯 [ElectronEvents] MatchId:', data.matchId);
          console.log('🎯 [ElectronEvents] Teams:', data.teams);
          console.log('🎯 [ElectronEvents] Team1:', data.team1);
          console.log('🎯 [ElectronEvents] Team2:', data.team2);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.draftStarted.set({ ...data });
        });

        // ✅ GAME_IN_PROGRESS: Partida em andamento - ir para tela de jogo
        window.electronAPI.onGameInProgress((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] game-in-progress recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.gameInProgress.set({ ...data });
        });

        // ✅ MATCH_CANCELLED: Partida cancelada - voltar para fila
        window.electronAPI.onMatchCancelled((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] match-cancelled recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.matchCancelled.set({ ...data });
        });

        // ✅ DRAFT_CANCELLED: Draft cancelado - voltar para fila
        window.electronAPI.onDraftCancelled((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-cancelled recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.draftCancelled.set({ ...data });
        });

        // ✅ GAME_CANCELLED: Jogo cancelado - voltar para fila
        window.electronAPI.onGameCancelled((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] game-cancelled recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.gameCancelled.set({ ...data });
        });

        // ✅ ACCEPTANCE_TIMER: Timer de aceitação - atualizar contador
        window.electronAPI.onAcceptanceTimer((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] acceptance-timer recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.acceptanceTimer.set({ ...data });
        });

        // ✅ ACCEPTANCE_PROGRESS: Progresso de aceitação - atualizar contadores
        window.electronAPI.onAcceptanceProgress((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] acceptance-progress recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.acceptanceProgress.set({ ...data });
        });

        // ✅ DRAFT EVENTS
        window.electronAPI.onDraftTimer((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-timer recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.draftTimer.set({ ...data });
        });

        window.electronAPI.onDraftUpdate((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-update recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.draftUpdate.set({ ...data });
        });

        window.electronAPI.onDraftUpdated((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-updated recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.draftUpdated.set({ ...data });
        });

        window.electronAPI.onPickChampion((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] pick-champion recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.pickChampion.set({ ...data });
        });

        window.electronAPI.onBanChampion((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] ban-champion recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.banChampion.set({ ...data });
        });

        window.electronAPI.onDraftConfirmed((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-confirmed recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.draftConfirmed.set({ ...data });
        });

        window.electronAPI.onDraftConfirmationUpdate((event: any, data: any) => {
          console.log('📊 [ElectronEvents] draft-confirmation-update recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.draftConfirmationUpdate.set({ ...data });
        });

        // ✅ GAME EVENTS
        window.electronAPI.onGameStarted((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] game-started recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.gameStarted.set({ ...data });
        });

        window.electronAPI.onWinnerModal((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] winner-modal recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.winnerModal.set({ ...data });
        });

        window.electronAPI.onVoteWinner((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] vote-winner recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.voteWinner.set({ ...data });
        });

        window.electronAPI.onMatchVoteProgress((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] match-vote-progress recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.matchVoteProgress.set({ ...data });
        });

        window.electronAPI.onMatchVoteUpdate((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] match-vote-update recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.matchVoteUpdate.set({ ...data });
        });

        // ✅ SPECTATOR EVENTS
        window.electronAPI.onSpectatorMuted((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] spectator-muted recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.spectatorMuted.set({ ...data });
        });

        window.electronAPI.onSpectatorUnmuted((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] spectator-unmuted recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.spectatorUnmuted.set({ ...data });
        });

        // ✅ QUEUE EVENTS
        window.electronAPI.onQueueStatus((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] queue-status recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.queueStatus.set({ ...data });
        });

        window.electronAPI.onQueueUpdate((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] queue-update recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.queueUpdate.set({ ...data });
        });

        // ✅ CONNECTION EVENTS
        window.electronAPI.onBackendConnection((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] backend-connection recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.backendConnection.set({ ...data });
        });

        window.electronAPI.onPlayerSessionUpdate((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] player-session-update recebido:', data);
          // ✅ SIGNALS FIX: Criar nova referência para evitar mutação
          this.playerSessionUpdate.set({ ...data });
        });

        console.log('✅ [ElectronEvents] Listeners do Electron configurados com sucesso!');
      } else {
        console.warn('⚠️ [ElectronEvents] Métodos de eventos não estão disponíveis ainda, tentando novamente em 1s...');
        // ✅ Tentar novamente após 1 segundo (quando o preload.js terminar de carregar)
        setTimeout(() => this.initializeElectronListeners(), 1000);
      }
    } else {
      console.warn('⚠️ [ElectronEvents] Não está rodando no Electron - listeners não configurados');
    }
  }

  /**
   * ✅ Verificar se está rodando no Electron
   */
  private isElectron(): boolean {
    return !!(window.electronAPI && window.electronAPI.isElectron());
  }

  /**
   * ✅ Limpar eventos (útil para evitar memory leaks)
   * ✅ MODERNIZADO: Usar signal.set() ao invés de Subject.next()
   */
  public clearEvents() {
    this.matchFound.set(null);
    this.draftStarted.set(null);
    this.gameInProgress.set(null);
    this.matchCancelled.set(null);
    this.draftCancelled.set(null);
    this.gameCancelled.set(null);
  }

  /**
   * ✅ Obter último evento de match_found
   * ✅ MODERNIZADO: Usar signal() ao invés de Subject.value
   */
  public getLastMatchFound(): any {
    return this.matchFound();
  }

  /**
   * ✅ Obter último evento de draft_started
   * ✅ MODERNIZADO: Usar signal() ao invés de Subject.value
   */
  public getLastDraftStarted(): any {
    return this.draftStarted();
  }

  /**
   * ✅ Obter último evento de game_in_progress
   * ✅ MODERNIZADO: Usar signal() ao invés de Subject.value
   */
  public getLastGameInProgress(): any {
    return this.gameInProgress();
  }

  /**
   * ✅ Obter último evento de match_cancelled
   * ✅ MODERNIZADO: Usar signal() ao invés de Subject.value
   */
  public getLastMatchCancelled(): any {
    return this.matchCancelled();
  }
}
