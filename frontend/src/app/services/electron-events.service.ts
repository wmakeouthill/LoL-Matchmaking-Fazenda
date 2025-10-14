import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';

/**
 * ✅ SERVIÇO: Eventos do Electron para o Frontend
 *
 * Este serviço escuta eventos enviados pelo Electron via IPC
 * e os converte em observables do Angular para uso nos componentes.
 */
@Injectable({
  providedIn: 'root'
})
export class ElectronEventsService {

  // === MATCH EVENTS ===
  private matchFoundSubject = new BehaviorSubject<any>(null);
  public matchFound$: Observable<any> = this.matchFoundSubject.asObservable();

  private draftStartedSubject = new BehaviorSubject<any>(null);
  public draftStarted$: Observable<any> = this.draftStartedSubject.asObservable();

  private gameInProgressSubject = new BehaviorSubject<any>(null);
  public gameInProgress$: Observable<any> = this.gameInProgressSubject.asObservable();

  private matchCancelledSubject = new BehaviorSubject<any>(null);
  public matchCancelled$: Observable<any> = this.matchCancelledSubject.asObservable();

  private draftCancelledSubject = new BehaviorSubject<any>(null);
  public draftCancelled$: Observable<any> = this.draftCancelledSubject.asObservable();

  private gameCancelledSubject = new BehaviorSubject<any>(null);
  public gameCancelled$: Observable<any> = this.gameCancelledSubject.asObservable();

  // === ACCEPTANCE EVENTS ===
  private acceptanceTimerSubject = new BehaviorSubject<any>(null);
  public acceptanceTimer$: Observable<any> = this.acceptanceTimerSubject.asObservable();

  private acceptanceProgressSubject = new BehaviorSubject<any>(null);
  public acceptanceProgress$: Observable<any> = this.acceptanceProgressSubject.asObservable();

  // === DRAFT EVENTS ===
  private draftTimerSubject = new BehaviorSubject<any>(null);
  public draftTimer$: Observable<any> = this.draftTimerSubject.asObservable();

  private draftUpdateSubject = new BehaviorSubject<any>(null);
  public draftUpdate$: Observable<any> = this.draftUpdateSubject.asObservable();

  private draftUpdatedSubject = new BehaviorSubject<any>(null);
  public draftUpdated$: Observable<any> = this.draftUpdatedSubject.asObservable();

  private pickChampionSubject = new BehaviorSubject<any>(null);
  public pickChampion$: Observable<any> = this.pickChampionSubject.asObservable();

  private banChampionSubject = new BehaviorSubject<any>(null);
  public banChampion$: Observable<any> = this.banChampionSubject.asObservable();

  private draftConfirmedSubject = new BehaviorSubject<any>(null);
  public draftConfirmed$: Observable<any> = this.draftConfirmedSubject.asObservable();

  // === GAME EVENTS ===
  private gameStartedSubject = new BehaviorSubject<any>(null);
  public gameStarted$: Observable<any> = this.gameStartedSubject.asObservable();

  private winnerModalSubject = new BehaviorSubject<any>(null);
  public winnerModal$: Observable<any> = this.winnerModalSubject.asObservable();

  private voteWinnerSubject = new BehaviorSubject<any>(null);
  public voteWinner$: Observable<any> = this.voteWinnerSubject.asObservable();

  // === SPECTATOR EVENTS ===
  private spectatorMutedSubject = new BehaviorSubject<any>(null);
  public spectatorMuted$: Observable<any> = this.spectatorMutedSubject.asObservable();

  private spectatorUnmutedSubject = new BehaviorSubject<any>(null);
  public spectatorUnmuted$: Observable<any> = this.spectatorUnmutedSubject.asObservable();

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
          this.matchFoundSubject.next(data);
        });

        // ✅ DRAFT_STARTED: Draft iniciado - ir para tela de draft
        window.electronAPI.onDraftStarted((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-started recebido:', data);
          console.log('🎯 [ElectronEvents] MatchId:', data.matchId);
          console.log('🎯 [ElectronEvents] Teams:', data.teams);
          console.log('🎯 [ElectronEvents] Team1:', data.team1);
          console.log('🎯 [ElectronEvents] Team2:', data.team2);
          this.draftStartedSubject.next(data);
        });

        // ✅ GAME_IN_PROGRESS: Partida em andamento - ir para tela de jogo
        window.electronAPI.onGameInProgress((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] game-in-progress recebido:', data);
          this.gameInProgressSubject.next(data);
        });

        // ✅ MATCH_CANCELLED: Partida cancelada - voltar para fila
        window.electronAPI.onMatchCancelled((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] match-cancelled recebido:', data);
          this.matchCancelledSubject.next(data);
        });

        // ✅ DRAFT_CANCELLED: Draft cancelado - voltar para fila
        window.electronAPI.onDraftCancelled((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-cancelled recebido:', data);
          this.draftCancelledSubject.next(data);
        });

        // ✅ GAME_CANCELLED: Jogo cancelado - voltar para fila
        window.electronAPI.onGameCancelled((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] game-cancelled recebido:', data);
          this.gameCancelledSubject.next(data);
        });

        // ✅ ACCEPTANCE_TIMER: Timer de aceitação - atualizar contador
        window.electronAPI.onAcceptanceTimer((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] acceptance-timer recebido:', data);
          this.acceptanceTimerSubject.next(data);
        });

        // ✅ ACCEPTANCE_PROGRESS: Progresso de aceitação - atualizar contadores
        window.electronAPI.onAcceptanceProgress((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] acceptance-progress recebido:', data);
          this.acceptanceProgressSubject.next(data);
        });

        // ✅ DRAFT EVENTS
        window.electronAPI.onDraftTimer((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-timer recebido:', data);
          this.draftTimerSubject.next(data);
        });

        window.electronAPI.onDraftUpdate((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-update recebido:', data);
          this.draftUpdateSubject.next(data);
        });

        window.electronAPI.onDraftUpdated((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-updated recebido:', data);
          this.draftUpdatedSubject.next(data);
        });

        window.electronAPI.onPickChampion((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] pick-champion recebido:', data);
          this.pickChampionSubject.next(data);
        });

        window.electronAPI.onBanChampion((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] ban-champion recebido:', data);
          this.banChampionSubject.next(data);
        });

        window.electronAPI.onDraftConfirmed((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] draft-confirmed recebido:', data);
          this.draftConfirmedSubject.next(data);
        });

        // ✅ GAME EVENTS
        window.electronAPI.onGameStarted((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] game-started recebido:', data);
          this.gameStartedSubject.next(data);
        });

        window.electronAPI.onWinnerModal((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] winner-modal recebido:', data);
          this.winnerModalSubject.next(data);
        });

        window.electronAPI.onVoteWinner((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] vote-winner recebido:', data);
          this.voteWinnerSubject.next(data);
        });

        // ✅ SPECTATOR EVENTS
        window.electronAPI.onSpectatorMuted((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] spectator-muted recebido:', data);
          this.spectatorMutedSubject.next(data);
        });

        window.electronAPI.onSpectatorUnmuted((event: any, data: any) => {
          console.log('🎯 [ElectronEvents] spectator-unmuted recebido:', data);
          this.spectatorUnmutedSubject.next(data);
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
   */
  public clearEvents() {
    this.matchFoundSubject.next(null);
    this.draftStartedSubject.next(null);
    this.gameInProgressSubject.next(null);
    this.matchCancelledSubject.next(null);
  }

  /**
   * ✅ Obter último evento de match_found
   */
  public getLastMatchFound(): any {
    return this.matchFoundSubject.value;
  }

  /**
   * ✅ Obter último evento de draft_started
   */
  public getLastDraftStarted(): any {
    return this.draftStartedSubject.value;
  }

  /**
   * ✅ Obter último evento de game_in_progress
   */
  public getLastGameInProgress(): any {
    return this.gameInProgressSubject.value;
  }

  /**
   * ✅ Obter último evento de match_cancelled
   */
  public getLastMatchCancelled(): any {
    return this.matchCancelledSubject.value;
  }
}
