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
