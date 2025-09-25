import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, retry, map } from 'rxjs/operators';
import { Player, RefreshPlayerResponse } from '../interfaces';
import { ApiService } from './api';

@Injectable({
  providedIn: 'root'
})
export class PlayerSearchService {
  private baseUrl: string;

  constructor(private http: HttpClient, private apiService: ApiService) {
    this.baseUrl = this.apiService.getBaseUrl();
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'Erro desconhecido';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Erro: ${error.error.message}`;
    } else {
      errorMessage = error.error?.error || error.message || `Erro ${error.status}`;
    }
    console.error('Erro na API:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }

  // ========== MÉTODOS MAIS AMIGÁVEIS PARA BUSCAR JOGADOR ==========

  // 1. MÉTODO PRINCIPAL: Buscar por Riot ID (GameName#TagLine) - MAIS AMIGÁVEL
  searchByRiotId(riotId: string, region: string = 'br1'): Observable<Player> {
    if (!riotId.includes('#')) {
      return throwError(() => new Error('Formato inválido. Use: NomeDoJogo#TAG (ex: Player#BR1)'));
    }

    return this.http.get<Player>(`${this.baseUrl}/player/details/${encodeURIComponent(riotId)}?region=${region}`)
      .pipe(
        retry(1),
        map(response => this.mapApiResponseToPlayer(response)),
        catchError(this.handleError)
      );
  }

  // 2. MÉTODO AUTOMÁTICO: Buscar do cliente LoL conectado - MAIS CONVENIENTE
  searchFromLCU(): Observable<Player> {
    return this.http.get<any>(`${this.baseUrl}/player/current-details`)
      .pipe(
        retry(1),
        map(response => {
          if (response.success && response.data) {
            return this.mapApiResponseToPlayer(response.data);
          }
          throw new Error('Nenhum jogador conectado no League of Legends');
        }),
        catchError(this.handleError)
      );
  }

  // 3. BUSCA INTELIGENTE: Detecta automaticamente o tipo de identificador
  smartSearch(identifier: string, region: string = 'br1'): Observable<Player> {
    const cleanId = identifier.trim();

    if (cleanId.includes('#')) {
      // É um Riot ID (GameName#TagLine)
      return this.searchByRiotId(cleanId, region);
    } else if (cleanId.match(/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/)) {
      // É um PUUID
      return this.searchByPuuid(cleanId, region);
    } else {
      // É um summoner name legado - tentar buscar via API da Riot
      return this.searchBySummonerName(cleanId, region);
    }
  }

  // 4. Buscar por PUUID (para casos específicos)
  searchByPuuid(puuid: string, region: string): Observable<Player> {
    return this.http.get<Player>(`${this.baseUrl}/player/puuid/${puuid}?region=${region}`)
      .pipe(
        retry(1),
        map(response => this.mapApiResponseToPlayer(response)),
        catchError(this.handleError)
      );
  }

  // 5. Buscar por Summoner Name legado
  searchBySummonerName(summonerName: string, region: string): Observable<Player> {
    return this.http.get<any>(`${this.baseUrl}/riot/summoner/${region}/${encodeURIComponent(summonerName)}`)
      .pipe(
        retry(1),
        map(response => this.mapApiResponseToPlayer(response)),
        catchError(this.handleError)
      );
  }

  // ========== MÉTODOS DE ATUALIZAÇÃO ==========

  refreshPlayerData(riotId: string, region: string): Observable<RefreshPlayerResponse> {
    return this.http.post<RefreshPlayerResponse>(`${this.baseUrl}/player/refresh-by-riot-id`, {
      riotId,
      region
    }).pipe(
      map(response => {
        if ((response as any).success && (response as any).data) {
          return {
            success: true,
            player: this.mapApiResponseToPlayer((response as any).data),
            message: (response as any).message || 'Dados atualizados com sucesso'
          };
        }
        return response;
      }),
      catchError(this.handleError)
    );
  }

  // ========== MÉTODOS AUXILIARES ==========

  private mapApiResponseToPlayer(data: any): Player {
    // Lidar com diferentes formatos de resposta da API
    const lcuData = data.lcu || data.lcuData || {};
    const riotData = data.riotApi || data.riotData || data.riotAccount || data;

    // Priorizar dados da API da Riot quando disponíveis
    const gameName = riotData.gameName || lcuData.displayName || riotData.name || 'Unknown';
    const tagLine = riotData.tagLine || null;

    return {
      id: riotData.id || lcuData.summonerId || 0,
      summonerName: gameName,
      tagLine: tagLine,
      summonerId: riotData.id || lcuData.summonerId?.toString() || '0',
      puuid: riotData.puuid || lcuData.puuid || '',
      profileIconId: riotData.profileIconId || lcuData.profileIconId || 1,
      summonerLevel: riotData.summonerLevel || lcuData.summonerLevel || 30,
      region: riotData.region || 'br1',
      currentMMR: this.calculateMMRFromData(riotData),
      rank: this.extractRankData(riotData),
      wins: riotData.soloQueue?.wins || riotData.wins,
      losses: riotData.soloQueue?.losses || riotData.losses,
      lastMatchDate: riotData.lastMatchDate ? new Date(riotData.lastMatchDate) : undefined,
      rankedData: {
        soloQueue: riotData.soloQueue,
        flexQueue: riotData.flexQueue
      }
    };
  }

  private calculateMMRFromData(data: any): number {
    const soloQueue = data.soloQueue || data.rankedData?.soloQueue;
    if (!soloQueue || !soloQueue.tier) return 1200;

    const tierValues: { [key: string]: number } = {
      'IRON': 800, 'BRONZE': 1000, 'SILVER': 1200, 'GOLD': 1400,
      'PLATINUM': 1700, 'EMERALD': 2000, 'DIAMOND': 2300,
      'MASTER': 2600, 'GRANDMASTER': 2800, 'CHALLENGER': 3000
    };

    const rankValues: { [key: string]: number } = {
      'IV': 0, 'III': 50, 'II': 100, 'I': 150
    };

    const baseMMR = tierValues[soloQueue.tier] || 1200;
    const rankBonus = rankValues[soloQueue.rank] || 0;
    const lpBonus = (soloQueue.leaguePoints || 0) * 0.8;

    return Math.round(baseMMR + rankBonus + lpBonus);
  }

  private extractRankData(data: any): any {
    const soloQueue = data.soloQueue || data.rankedData?.soloQueue;
    if (!soloQueue || !soloQueue.tier) return undefined;

    return {
      tier: soloQueue.tier,
      rank: soloQueue.rank,
      lp: soloQueue.leaguePoints,
      wins: soloQueue.wins,
      losses: soloQueue.losses,
      display: `${soloQueue.tier} ${soloQueue.rank}`
    };
  }

  // ========== MÉTODOS DE UTILIDADE ==========

  // Validar formato de Riot ID
  isValidRiotId(riotId: string): boolean {
    return riotId.includes('#') && riotId.split('#').length === 2 &&
      riotId.split('#')[0].length > 0 && riotId.split('#')[1].length > 0;
  }

  // Validar formato de PUUID
  isValidPuuid(puuid: string): boolean {
    return /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(puuid);
  }

  // Sugerir formato correto baseado na entrada
  suggestFormat(input: string): string {
    if (!input) return 'Digite: NomeJogador#TAG ou conecte-se ao LoL';

    if (input.includes('#')) {
      const parts = input.split('#');
      if (parts.length !== 2) return 'Formato: NomeJogador#TAG (apenas um #)';
      if (parts[0].length === 0) return 'Nome do jogador não pode estar vazio';
      if (parts[1].length === 0) return 'TAG não pode estar vazia';
      return 'Formato correto!';
    }

    if (this.isValidPuuid(input)) return 'PUUID detectado - será usado automaticamente';

    return `Para "${input}", use: ${input}#TAG (ex: ${input}#BR1)`;
  }
}
