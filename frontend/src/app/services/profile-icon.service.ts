import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, BehaviorSubject } from 'rxjs';
import { map, catchError, tap, shareReplay } from 'rxjs/operators';
import { ApiService } from './api';

@Injectable({
    providedIn: 'root'
})
export class ProfileIconService {
    private profileIconCache$ = new BehaviorSubject<Map<string, number>>(new Map());
    private profileIconsCacheKey = 'shared_profile_icons_cache';
    private cacheVersion = '1.0.1'; // Bump version to invalidate old cache

    // Cache para Observables em andamento, para evitar múltiplas requisições para o mesmo jogador
    private ongoingFetches = new Map<string, Observable<number | null>>();
    private baseUrl: string;

    constructor(private http: HttpClient, private apiService: ApiService) {
        this.baseUrl = this.apiService.getBaseUrl();
        this.loadProfileIconsCache();
    }

    getProfileIconUrl(summonerIdentifier: string): Observable<string> {
        return this.getOrFetchProfileIcon(summonerIdentifier).pipe(
            map(iconId => `https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/profile-icons/${iconId || 29}.jpg`)
        );
    }

    getOrFetchProfileIcon(summonerIdentifier: string): Observable<number | null> {
        const cachedMap = this.profileIconCache$.getValue();
        if (cachedMap.has(summonerIdentifier)) {
            return of(cachedMap.get(summonerIdentifier) || null);
        }

        if (this.ongoingFetches.has(summonerIdentifier)) {
            return this.ongoingFetches.get(summonerIdentifier)!;
        }

        const fetchObservable = this.fetchProfileIcon(summonerIdentifier).pipe(
            tap(() => this.ongoingFetches.delete(summonerIdentifier)),
            shareReplay(1) // Compartilhar o resultado entre múltiplos subscribers
        );

        this.ongoingFetches.set(summonerIdentifier, fetchObservable);
        return fetchObservable;
    }

    private fetchProfileIcon(summonerIdentifier: string): Observable<number | null> {
        const endpoint = `${this.baseUrl}/summoner/profile-icon/${encodeURIComponent(summonerIdentifier)}`;
        return this.http.get<any>(endpoint).pipe(
            map(response => {
                if (response.success && response.data.profileIconId !== undefined) {
                    const profileIconId = response.data.profileIconId;
                    const currentCache = this.profileIconCache$.getValue();
                    currentCache.set(summonerIdentifier, profileIconId);
                    this.profileIconCache$.next(currentCache);
                    this.saveProfileIconsCache();
                    return profileIconId;
                }
                return null;
            }),
            catchError(error => {
                if (error.status === 404) {
                    console.log(`ℹ️ Jogador ${summonerIdentifier} não encontrado`);
                } else {
                    console.warn(`Erro ao buscar profile icon para ${summonerIdentifier}:`, error);
                }
                return of(null);
            })
        );
    }

    onProfileIconError(event: Event, profileIconId?: number): void {
        const target = event.target as HTMLImageElement;
        if (!target) return;

        const iconId = profileIconId || 29;
        const fallbackUrls = [
            `https://ddragon.leagueoflegends.com/cdn/15.14.1/img/profileicon/${iconId}.png`,
            `https://ddragon.leagueoflegends.com/cdn/14.24.1/img/profileicon/${iconId}.png`,
            `https://ddragon.leagueoflegends.com/cdn/14.23.1/img/profileicon/${iconId}.png`,
            `https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global/default/v1/profile-icons/29.jpg`,
            '/assets/images/champion-placeholder.svg' // Fallback final para minion
        ];

        const currentSrc = target.src;
        let nextIndex = 0;

        for (let i = 0; i < fallbackUrls.length; i++) {
            if (fallbackUrls[i] === currentSrc) {
                nextIndex = i + 1;
                break;
            }
        }

        if (nextIndex < fallbackUrls.length) {
            target.src = fallbackUrls[nextIndex];
        }
    }

    private loadProfileIconsCache(): void {
        try {
            const iconsCache = localStorage.getItem(this.profileIconsCacheKey);
            if (iconsCache) {
                const iconsData = JSON.parse(iconsCache);
                if (iconsData.version === this.cacheVersion) {
                    this.profileIconCache$.next(new Map(Object.entries(iconsData.icons)));
                    console.log(`📦 Cache de ícones carregado: ${this.profileIconCache$.getValue().size} ícones`);
                } else {
                    localStorage.removeItem(this.profileIconsCacheKey);
                }
            }
        } catch (error) {
            console.warn('Erro ao carregar cache de ícones:', error);
        }
    }

    private saveProfileIconsCache(): void {
        try {
            const iconsData = {
                icons: Object.fromEntries(this.profileIconCache$.getValue()),
                version: this.cacheVersion,
                timestamp: Date.now()
            };
            localStorage.setItem(this.profileIconsCacheKey, JSON.stringify(iconsData));
        } catch (error) {
            console.warn('Erro ao salvar cache de ícones:', error);
        }
    }

    clearCache(): void {
        this.profileIconCache$.next(new Map());
        localStorage.removeItem(this.profileIconsCacheKey);
        console.log('🗑️ Cache de ícones limpo');
    }
} 