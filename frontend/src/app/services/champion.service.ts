import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, BehaviorSubject } from 'rxjs';
import { map, catchError, tap } from 'rxjs/operators';

export interface ChampionData {
  id: string;
  key: string;
  name: string;
  title: string;
  image: {
    full: string;
    sprite: string;
    group: string;
    x: number;
    y: number;
    w: number;
    h: number;
  };
  tags: string[];
}

export interface ChampionResponse {
  type: string;
  format: string;
  version: string;
  data: { [key: string]: ChampionData };
}

@Injectable({
  providedIn: 'root'
})
export class ChampionService {
  private readonly DD_VERSION = '15.19.1';
  private readonly DD_BASE_URL = `https://ddragon.leagueoflegends.com/cdn/${this.DD_VERSION}`;

  private championsCache = new Map<string, ChampionData>();
  private championsLoaded = false;
  private loadingSubject = new BehaviorSubject<boolean>(false);

  constructor(private http: HttpClient) { }

  /**
   * Get champion data by ID (number from LCU)
   */
  getChampionById(championId: number): Observable<ChampionData | null> {
    if (this.championsCache.has(championId.toString())) {
      return of(this.championsCache.get(championId.toString())!);
    }

    if (!this.championsLoaded) {
      return this.loadChampions().pipe(
        map(() => this.championsCache.get(championId.toString()) || null)
      );
    }

    return of(null);
  }

  /**
   * Get champion image URL
   */
  getChampionImageUrl(championId: number): string {
    const champion = this.championsCache.get(championId.toString());
    if (champion) {
      return `${this.DD_BASE_URL}/img/champion/${champion.image.full}`;
    }
    return `${this.DD_BASE_URL}/img/champion/champion_placeholder.png`;
  }

  /**
   * Get champion name by ID
   */
  getChampionName(championId: number): string {
    const champion = this.championsCache.get(championId.toString());
    return champion ? champion.name : `Champion ${championId}`;
  }

  /**
   * Get champion ID by name (case-insensitive)
   */
  getChampionIdByName(championName: string): string | null {
    if (!championName) return null;

    // Normalizar o nome: remover espaços e caracteres especiais
    const normalizedSearchName = championName.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();

    // Procurar no cache
    for (const champion of this.championsCache.values()) {
      const normalizedChampionId = champion.id.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
      const normalizedChampionName = champion.name.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();

      if (normalizedChampionId === normalizedSearchName || normalizedChampionName === normalizedSearchName) {
        return champion.id;
      }
    }

    return null;
  }

  /**
   * Get champion image URL by name
   */
  getChampionImageUrlByName(championName: string): string {
    const championId = this.getChampionIdByName(championName);
    if (championId) {
      return `${this.DD_BASE_URL}/img/champion/${championId}.png`;
    }
    return `${this.DD_BASE_URL}/img/champion/champion_placeholder.png`;
  }

  /**
   * ✅ NOVO: Obter todos os campeões do cache
   */
  getAllChampions(): ChampionData[] {
    return Array.from(this.championsCache.values());
  }

  /**
   * Load all champions from Data Dragon
   */
  private loadChampions(): Observable<ChampionResponse> {
    if (this.championsLoaded) {
      return of({} as ChampionResponse);
    }

    this.loadingSubject.next(true);

    return this.http.get<ChampionResponse>(`${this.DD_BASE_URL}/data/pt_BR/champion.json`).pipe(
      tap(response => {
        console.log('🔍 [ChampionService] Loaded champions from Data Dragon:', Object.keys(response.data).length);

        // Cache champions by their key (ID number)
        Object.values(response.data).forEach(champion => {
          this.championsCache.set(champion.key, champion);
        });

        this.championsLoaded = true;
        this.loadingSubject.next(false);
      }),
      catchError(error => {
        console.error('❌ [ChampionService] Error loading champions:', error);
        this.loadingSubject.next(false);
        return of({} as ChampionResponse);
      })
    );
  }

  /**
   * Check if champions are loaded
   */
  isLoaded(): boolean {
    return this.championsLoaded;
  }

  /**
   * Get loading status
   */
  isLoading(): Observable<boolean> {
    return this.loadingSubject.asObservable();
  }

  /**
   * Preload champions (call this in app initialization)
   */
  preloadChampions(): Observable<boolean> {
    if (this.championsLoaded) {
      return of(true);
    }

    return this.loadChampions().pipe(
      map(() => true),
      catchError(() => of(false))
    );
  }
}
