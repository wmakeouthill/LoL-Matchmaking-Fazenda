import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export interface CurrentSummonerInfo {
    summonerName: string;      // "FZD Ratosso#fzd"
    gameName: string;           // "FZD Ratosso"
    tagLine: string;            // "fzd"
    displayName: string;        // Pode ser summonerName ou gameName#tagLine
    id?: number;
    puuid?: string;
    profileIconId?: number;
    summonerLevel?: number;
}

@Injectable({
    providedIn: 'root'
})
export class CurrentSummonerService {
    private readonly summonerSubject = new BehaviorSubject<CurrentSummonerInfo | null>(null);
    public summoner$ = this.summonerSubject.asObservable();

    constructor() { }

    /**
     * Define o summoner atual
     */
    setSummoner(summoner: CurrentSummonerInfo): void {
        console.log('[CurrentSummonerService] Setting summoner:', summoner.displayName);
        this.summonerSubject.next(summoner);
    }

    /**
     * Obtém o summoner atual (snapshot)
     */
    getSummoner(): CurrentSummonerInfo | null {
        return this.summonerSubject.value;
    }

    /**
     * Obtém o nome do summoner no formato esperado pelo backend (Header X-Summoner-Name)
     * Formato: "GameName#TagLine" ou "SummonerName" (para retrocompatibilidade)
     */
    getSummonerNameForHeader(): string | null {
        const summoner = this.summonerSubject.value;
        if (!summoner) {
            console.warn('[CurrentSummonerService] No summoner set');
            return null;
        }

        // Preferência: gameName#tagLine (Riot ID)
        if (summoner.gameName && summoner.tagLine) {
            return `${summoner.gameName}#${summoner.tagLine}`;
        }

        // Fallback: summonerName (legado)
        if (summoner.summonerName) {
            return summoner.summonerName;
        }

        console.warn('[CurrentSummonerService] Summoner has no valid name');
        return null;
    }

    /**
     * Limpa o summoner atual
     */
    clearSummoner(): void {
        console.log('[CurrentSummonerService] Clearing summoner');
        this.summonerSubject.next(null);
    }
}
