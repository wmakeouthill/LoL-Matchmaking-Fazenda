import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError, Subject, firstValueFrom, of } from 'rxjs';
import { catchError, retry, map, switchMap } from 'rxjs/operators';
import { Player, RefreshPlayerResponse } from '../interfaces';

interface QueueStatus {
  playersInQueue: number;
  averageWaitTime: number;
  estimatedMatchTime: number;
  isActive: boolean;
  playersInQueueList?: QueuedPlayerInfo[];
  recentActivities?: QueueActivity[];
  activeMatches?: number; // Backwards compatibility
  queuedPlayers?: any[]; // Backwards compatibility (deprecated)
}

interface QueuedPlayerInfo {
  summonerName: string;
  tagLine?: string;
  primaryLane: string;
  secondaryLane: string;
  primaryLaneDisplay: string;
  secondaryLaneDisplay: string;
  mmr: number;
  queuePosition: number;
  joinTime: Date;
}

interface QueueActivity {
  id: string;
  timestamp: Date;
  type: 'player_joined' | 'player_left' | 'match_created' | 'system_update' | 'queue_cleared';
  message: string;
  playerName?: string;
  playerTag?: string;
  lane?: string;
}

interface LCUStatus {
  isConnected: boolean;
  summoner?: any;
  gameflowPhase?: string;
  lobby?: any;
}

interface MatchHistory {
  id: number;
  matchId: number;
  team1Players: number[];
  team2Players: number[];
  winnerTeam?: number;
  createdAt: string;
  completedAt?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private readonly baseUrl: string; // ✅ readonly, inicializado no construtor
  private readonly fallbackUrls: string[] = [];

  // Error suppression system to reduce spam when services are down
  private readonly errorSuppressionCache = new Map<string, number>();
  private readonly ERROR_SUPPRESSION_DURATION = 30000; // 30 seconds

  // ✅ NOVO: Subject para mensagens WebSocket
  private readonly webSocketMessageSubject = new Subject<any>();
  private webSocket: WebSocket | null = null;

  constructor(private readonly http: HttpClient) {
    // ✅ CORREÇÃO: Inicializar baseUrl aqui, quando o contexto está pronto
    this.baseUrl = this.getBaseUrl();

    // Tentar auto-configurar LCU no Electron (antes de conectar WebSocket)
    if (this.isElectron()) {
      this.configureLCUFromElectron().catch(err => {
        console.warn('⚠️ [ApiService] Falha ao auto-configurar LCU via Electron:', err);
      });

      // Retry LCU configuration a few times in case lockfile appears late
      this.retryLCUAutoConfig();
    }

    // Log de diagnóstico inicial
    console.log('🔧 ApiService inicializado:', {
      baseUrl: this.baseUrl,
      isElectron: this.isElectron(),
      isWindows: this.isWindows(),
      fallbackUrls: this.fallbackUrls,
      userAgent: navigator.userAgent.substring(0, 100),
      hostname: window.location.hostname,
      protocol: window.location.protocol
    });

    // ✅ MELHORADO: Aguardar backend estar pronto antes de conectar WebSocket
    if (this.isElectron()) {
      // Em Electron, aguardar um pouco antes de tentar WebSocket
      console.log('🔄 [ApiService] Aguardando backend estar pronto...');
      setTimeout(() => {
        this.waitForBackendAndConnect();
      }, 3000); // Aguardar 3 segundos
    } else {
      // Em modo web, conectar imediatamente
      this.connectWebSocket();
    }
  }

  // ✅ NOVO: Configurar LCU a partir do lockfile via Electron
  private async configureLCUFromElectron(): Promise<void> {
    try {
      const electronAPI = (window as any).electronAPI;
      if (!electronAPI?.getLCULockfileInfo) {
        console.log('ℹ️ [ApiService] electronAPI.getLCULockfileInfo não disponível');
        return;
      }

      const info = electronAPI.getLCULockfileInfo();
      if (!info) {
        console.log('ℹ️ [ApiService] Lockfile do LCU não encontrado');
        return;
      }

      console.log('🔧 [ApiService] Configurando LCU via /api/lcu/configure com dados do lockfile...');
      const url = `${this.baseUrl}/lcu/configure`;
      await firstValueFrom(this.http.post(url, info).pipe(
        catchError(err => {
          console.warn('⚠️ [ApiService] Erro ao configurar LCU:', err);
          return of(null);
        })
      ));

      console.log('✅ [ApiService] Configuração de LCU enviada');
    } catch (e) {
      console.warn('⚠️ [ApiService] Exceção ao configurar LCU:', e);
    }
  }

  // ✅ NOVO: Aguardar backend estar pronto antes de conectar WebSocket
  private async waitForBackendAndConnect() {
    console.log('🔍 [ApiService] Verificando se backend está pronto...');

    for (let attempt = 1; attempt <= 10; attempt++) {
      try {
        const response = await firstValueFrom(this.http.get(`${this.baseUrl}/health`));
        if (response && (response as any).status === 'ok') {
          console.log('✅ [ApiService] Backend confirmado como pronto, conectando WebSocket...');
          this.connectWebSocket();
          return;
        }
      } catch (error) {
        console.log(`⏳ [ApiService] Backend não pronto ainda (tentativa ${attempt}/10)`);
      }

      await new Promise(resolve => setTimeout(resolve, 2000)); // Aguardar 2 segundos
    }

    console.warn('⚠️ [ApiService] Backend não ficou pronto, tentando WebSocket mesmo assim...');
    this.connectWebSocket();
  }

  // Tenta garantir que o backend central reconheça o LCU local (modo híbrido)
  private async ensureLCUConfigured(): Promise<void> {
    if (!this.isElectron()) return;
    try {
      const status: any = await firstValueFrom(this.getLCUStatus().pipe(catchError(() => of(null))));
      const connected = !!status?.isConnected || !!status?.status?.connected;
      if (connected) return;
      await this.configureLCUFromElectron();
    } catch {
      // ignore
    }
  }

  // Faz algumas tentativas de configurar o LCU automaticamente no início (Electron)
  private retryLCUAutoConfig(): void {
    let attempts = 0;
    const maxAttempts = 8;
    const tick = async () => {
      attempts++;
      await this.ensureLCUConfigured();
      try {
        const status: any = await firstValueFrom(this.getLCUStatus().pipe(catchError(() => of(null))));
        const connected = !!status?.isConnected || !!status?.status?.connected;
        if (connected || attempts >= maxAttempts) return;
      } catch {
        // ignore
      }
      setTimeout(tick, 1500);
    };
    setTimeout(tick, 500);
  }

  public getBaseUrl(): string {
    // Tenta detectar a URL base correta para o ambiente atual
    try {
      // ✅ CORREÇÃO: Verificar se está no Electron primeiro
      console.log('🔍 [ApiService] Detectando ambiente...');
      console.log('  - Protocol:', window.location.protocol);
      console.log('  - Hostname:', window.location.hostname);
      console.log('  - Port:', window.location.port);
      console.log('  - Href:', window.location.href);

      // Ambiente Electron ou empacotado
      if (this.isElectron()) {
        const electronUrl = this.getElectronBaseUrl();
        console.log('🔌 [ApiService] Electron base URL:', electronUrl);
        return electronUrl;
      }

      // Ambiente de navegador normal
      const browserUrl = this.getBrowserBaseUrl();
      console.log('🌐 [ApiService] Browser base URL:', browserUrl);
      return browserUrl;
    } catch (error) {
      console.error('❌ [ApiService] Error detecting base URL, using fallback:', error);
      return 'http://localhost:8080/api'; // Fallback seguro
    }
  }

  private getElectronBaseUrl(): string {
    // ✅ CORREÇÃO: SEMPRE usar /api em qualquer ambiente Electron

    // Se há uma variável de ambiente para produção (Google Cloud)
    const productionUrl = (window as any).electronAPI?.getBackendUrl?.();
    if (productionUrl) {
      console.log('🚀 [Electron] URL de produção detectada:', productionUrl);
      // ✅ Garantir que sempre termine com /api
      return productionUrl.endsWith('/api') ? productionUrl : `${productionUrl}/api`;
    }

    // Se estiver no protocolo file:// (app empacotado)
    if (window.location.protocol === 'file:') {
      console.log('📁 [Electron] Protocolo file:// detectado - usando localhost com /api');
      return 'http://localhost:8080/api';
    }

    // Se temos hostname específico
    if (window.location.hostname &&
        window.location.hostname !== 'null' &&
        window.location.hostname !== 'localhost' &&
        window.location.hostname !== '127.0.0.1') {
      console.log('🔧 [Electron] Hostname específico detectado:', window.location.hostname);
      return `http://${window.location.hostname}:8080/api`;
    }

    // ✅ PADRÃO: SEMPRE localhost com /api para Electron
    console.log('🔌 [Electron] Usando padrão localhost com /api');
    return 'http://localhost:8080/api';
  }

  private getBrowserBaseUrl(): string {
    const host = window.location.hostname;
    const port = '8080';
    const protocol = window.location.protocol === 'https:' ? 'https' : 'http';
    return `${protocol}://${host}:${port}/api`;
  }

  public getWebSocketUrl(): string {
    const baseUrl = this.getBaseUrl();

    // Construir URL WebSocket corretamente
    const wsUrl = baseUrl
      .replace(/^http/, 'ws')
      .replace(/^https/, 'wss')
      .replace('/api$', '') + '/ws';

    console.log('🔄 WebSocket URL:', wsUrl);
    return wsUrl;
  }

  public isElectron(): boolean {
    // Verificar se está no Electron através de múltiplas formas
    const hasElectronAPI = !!(window as any).electronAPI;
    const hasRequire = !!(window as any).require;
    const hasProcess = !!(window as any).process?.type;
    const userAgentElectron = navigator.userAgent.toLowerCase().includes('electron');
    const isFileProtocol = window.location.protocol === 'file:';
    const hasElectronProcess = !!(window as any).process?.versions?.electron;

    const isElectron = hasElectronAPI || hasRequire || hasProcess || userAgentElectron || isFileProtocol || hasElectronProcess;

    // Log para debug
    console.log('🔍 Electron detection:', {
      hasElectronAPI,
      hasRequire,
      hasProcess,
      userAgentElectron,
      isFileProtocol,
      hasElectronProcess,
      isElectron,
      protocol: window.location.protocol,
      hostname: window.location.hostname,
      userAgent: navigator.userAgent
    });

    return isElectron;
  }

  private isWindows(): boolean {
    const platform = (window as any).process?.platform || navigator.platform;
    const userAgent = navigator.userAgent;

    const isWin = platform === 'win32' ||
      platform.toLowerCase().includes('win') ||
      userAgent.includes('Windows');

    console.log('🖥️ Platform detection:', { platform, userAgent, isWindows: isWin });
    return isWin;
  } private tryWithFallback<T>(endpoint: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' = 'GET', body?: any): Observable<T> {
    const tryUrl = (url: string): Observable<T> => {
      const fullUrl = `${url}${endpoint}`;
      console.log(`🔄 Tentando requisição: ${method} ${fullUrl}`);

      switch (method) {
        case 'GET':
          return this.http.get<T>(fullUrl);
        case 'POST':
          return this.http.post<T>(fullUrl, body);
        case 'PUT':
          return this.http.put<T>(fullUrl, body);
        case 'DELETE':
          return this.http.delete<T>(fullUrl);
        default:
          return this.http.get<T>(fullUrl);
      }
    };

    // Tentar URL primária primeiro
    return tryUrl(this.baseUrl).pipe(
      catchError((primaryError: HttpErrorResponse) => {
        console.warn(`❌ Falha na URL primária (${this.baseUrl}):`, primaryError.message);

        // Se há URLs de fallback e estamos no Electron, tentar a primeira
        if (this.fallbackUrls.length > 0 && this.isElectron()) {
          console.log('🔄 Tentando primeira URL de fallback...');
          return tryUrl(this.fallbackUrls[0]).pipe(
            catchError((fallbackError: HttpErrorResponse) => {
              console.warn(`❌ Falha na URL de fallback (${this.fallbackUrls[0]}):`, fallbackError.message);
              // Retornar erro original para tratamento padrão
              return throwError(() => primaryError);
            })
          );
        }

        // Se não há fallbacks, retornar erro original
        return throwError(() => primaryError);
      })
    );
  }

  private readonly handleError = (error: HttpErrorResponse) => {
    let errorMessage = 'Erro desconhecido';
    const currentTime = Date.now();

    if (error.error instanceof ErrorEvent) {
      // Erro do lado do cliente
      errorMessage = `Erro: ${error.error.message}`;
    } else {
      // Erro do lado do servidor
      if (error.status === 503 && error.error?.error?.includes('Riot API')) {
        errorMessage = 'Chave da Riot API não configurada ou inválida. Configure uma chave válida nas configurações.';

        // Suppress repeated Riot API errors
        const errorKey = 'riot-api-503';
        const lastErrorTime = this.errorSuppressionCache.get(errorKey);
        if (!lastErrorTime || (currentTime - lastErrorTime) > this.ERROR_SUPPRESSION_DURATION) {
          console.warn('🚫 Riot API service unavailable');
          this.errorSuppressionCache.set(errorKey, currentTime);
        }
      } else if (error.status === 503 && error.error?.error?.includes('LCU')) {
        errorMessage = 'Cliente do League of Legends não está conectado. Abra o jogo e tente novamente.';

        // Suppress repeated LCU errors
        const errorKey = 'lcu-503';
        const lastErrorTime = this.errorSuppressionCache.get(errorKey);
        if (!lastErrorTime || (currentTime - lastErrorTime) > this.ERROR_SUPPRESSION_DURATION) {
          console.warn('🎮 LCU not connected');
          this.errorSuppressionCache.set(errorKey, currentTime);
        }
      } else if (error.status === 404 && error.error?.error?.includes('não encontrado')) {
        errorMessage = error.error.error;
      } else {
        errorMessage = error.error?.error || error.message || `Erro ${error.status}`;

        // Only log unexpected errors
        const errorKey = `${error.status}-${error.url}`;
        const lastErrorTime = this.errorSuppressionCache.get(errorKey);
        if (!lastErrorTime || (currentTime - lastErrorTime) > this.ERROR_SUPPRESSION_DURATION) {
          console.error('❌ API Error:', errorMessage);
          this.errorSuppressionCache.set(errorKey, currentTime);
        }
      }
    }

    return throwError(() => new Error(errorMessage));
  }

  // Health check
  checkHealth(): Observable<any> {
    // Se estamos no Electron, usar o método com fallback
    if (this.isElectron()) {
      return this.tryWithFallback('/health', 'GET').pipe(
        retry(1),
        catchError(this.handleError)
      );
    }

    // Caso contrário, usar método padrão
    return this.http.get(`${this.baseUrl}/health`)
      .pipe(
        retry(2),
        catchError(this.handleError)
      );
  }  // Player endpoints
  registerPlayer(displayName: string, region: string): Observable<Player> {
    // Use the new refresh endpoint that handles Display Name properly
    return this.refreshPlayerByDisplayName(displayName, region).pipe(
      map(response => {
        if (!response.player) {
          throw new Error('Falha ao registrar jogador');
        }
        return response.player;
      }),
      catchError(this.handleError)
    );
  }

  getPlayer(playerId: number): Observable<Player> {
    return this.http.get<Player>(`${this.baseUrl}/player/${playerId}`)
      .pipe(
        catchError(this.handleError)
      );
  }

  getPlayerBySummoner(summonerName: string): Observable<Player> {
    return this.http.get<Player>(`${this.baseUrl}/player/summoner/${summonerName}`)
      .pipe(
        catchError(this.handleError)
      );
  }

  // Electron-only: obter Player diretamente do LCU sem backend
  private getLCUOnlyPlayerData(): Observable<Player> {
    if (!(this.isElectron() && (window as any).electronAPI?.lcu?.getCurrentSummoner)) {
      return throwError(() => new Error('LCU local não disponível'));
    }

    return new Observable<Player>(observer => {
      (window as any).electronAPI.lcu.getCurrentSummoner()
        .then((lcuData: any) => {
          if (!lcuData) throw new Error('Dados do LCU não disponíveis');

          const displayName = (lcuData.gameName && lcuData.tagLine)
            ? `${lcuData.gameName}#${lcuData.tagLine}`
            : (lcuData.displayName || undefined);

          const player: Player = {
            id: lcuData.summonerId || 0,
            summonerName: lcuData.gameName || lcuData.displayName || 'Unknown',
            displayName,
            gameName: lcuData.gameName || null,
            tagLine: lcuData.tagLine || null,
            summonerId: (lcuData.summonerId || '0').toString(),
            puuid: lcuData.puuid || '',
            profileIconId: lcuData.profileIconId || 29,
            summonerLevel: lcuData.summonerLevel || 30,
            region: 'br1',
            currentMMR: 1200,
            rank: undefined,
            wins: undefined,
            losses: undefined
          };

          observer.next(player);
        })
        .catch((err: any) => observer.error(err))
        .finally(() => observer.complete());
    }).pipe(catchError(this.handleError));
  }

  getPlayerStats(playerId: number): Observable<any> {
    return this.http.get(`${this.baseUrl}/player/${playerId}/stats`)
      .pipe(
        catchError(this.handleError)
      );
  }  // Novo método para buscar dados detalhados do jogador atual
  // Endpoint para obter dados completos do jogador logado no LCU
  getCurrentPlayerDetails(): Observable<any> {
    // Se está no Electron, usar apenas LCU (não tentar Riot API)
    if (this.isElectron()) {
      return this.getCurrentSummonerFromLCU().pipe(
        map((lcuData: any) => {
          if (!lcuData) {
            throw new Error('Dados do LCU não disponíveis');
          }

          return {
            success: true,
            data: {
              lcu: lcuData,
              riotAccount: {
                gameName: lcuData.gameName || lcuData.displayName,
                tagLine: lcuData.tagLine,
                puuid: lcuData.puuid
              },
              riotApi: null, // Não usar Riot API no Electron
              partialData: true // Indicar que são dados apenas do LCU
            }
          };
        }),
        retry(1),
        catchError(this.handleError)
      );
    }

    // Em modo web, tentar LCU + Riot API (modo original)
    return this.getCurrentSummonerFromLCU().pipe(
      switchMap((lcuData: any) => {
        if (lcuData && lcuData.gameName && lcuData.tagLine) {
          const displayName = `${lcuData.gameName}#${lcuData.tagLine}`;
          // Use the working refresh endpoint
          return this.refreshPlayerByDisplayName(displayName, 'br1').pipe(
            map(response => ({
              success: true,
              data: {
                lcu: lcuData,
                riotAccount: {
                  gameName: lcuData.gameName,
                  tagLine: lcuData.tagLine,
                  puuid: lcuData.puuid
                },
                riotApi: response.player
              }
            }))
          );
        } else {
          throw new Error('Dados do LCU incompletos');
        }
      }),
      retry(1), // Tentar novamente uma vez se falhar
      catchError(this.handleError)
    );
  }
  // Método para buscar dados básicos do jogador (para modo Browser ou fallback)
  // Endpoint alternativo para obter dados do LCU
  getCurrentPlayerDebug(): Observable<any> {
    return this.http.get(`${this.baseUrl}/lcu/current-summoner`)
      .pipe(
        retry(1), // Tentar novamente uma vez se falhar
        catchError(this.handleError)
      );
  }

  // Queue endpoints
  getQueueStatus(currentPlayerDisplayName?: string): Observable<QueueStatus> {
    let url = `${this.baseUrl}/queue/status`;

    // Se temos o displayName do jogador atual, incluir na query para detecção no backend
    if (currentPlayerDisplayName) {
      const params = new URLSearchParams({ currentPlayerDisplayName });
      url += `?${params.toString()}`;
    }

    return this.http.get<QueueStatus>(url)
      .pipe(catchError(this.handleError));
  }

  forceMySQLSync(): Observable<any> {
    return this.http.post(`${this.baseUrl}/queue/force-sync`, {})
      .pipe(catchError(this.handleError));
  }

  getRecentMatches(): Observable<MatchHistory[]> {
    return this.http.get<MatchHistory[]>(`${this.baseUrl}/matches/recent`)
      .pipe(
        catchError(this.handleError)
      );
  }

  // ✅ NOVO: Cancelar partida e apagar do banco
  cancelMatch(matchId: number): Observable<any> {
    return this.http.delete(`${this.baseUrl}/matches/${matchId}`)
      .pipe(
        catchError(this.handleError)
      );
  }

  getMatchHistory(playerId: string, offset: number = 0, limit: number = 10): Observable<any> {
    return this.http.get(`${this.baseUrl}/match-history/${playerId}?offset=${offset}&limit=${limit}`)
      .pipe(
        catchError(this.handleError)
      );
  }

  // Riot API endpoints
  getSummonerData(region: string, summonerName: string): Observable<any> {
    return this.http.get(`${this.baseUrl}/riot/summoner/${region}/${summonerName}`)
      .pipe(
        catchError(this.handleError)
      );
  }
  getRankedData(region: string, summonerId: string): Observable<any> {
    return this.http.get(`${this.baseUrl}/riot/ranked/${region}/${summonerId}`)
      .pipe(
        catchError(this.handleError)
      );
  }
  // LCU endpoints
  getLCUStatus(): Observable<LCUStatus> {
    return this.http.get<LCUStatus>(`${this.baseUrl}/lcu/status`)
      .pipe(
        catchError(this.handleError)
      );
  }

  getCurrentSummonerFromLCU(): Observable<any> {
    // Em Electron, usar o conector local (preload) e não o backend central
    if (this.isElectron() && (window as any).electronAPI?.lcu?.getCurrentSummoner) {
      return new Observable(observer => {
        (window as any).electronAPI.lcu.getCurrentSummoner()
          .then((data: any) => observer.next(data))
          .catch((err: any) => observer.error(err))
          .finally(() => observer.complete());
      }).pipe(catchError(this.handleError));
    }

    // Modo web: usar backend
    return this.http.get(`${this.baseUrl}/lcu/current-summoner`)
      .pipe(
        map((resp: any) => {
          if (resp && typeof resp === 'object') {
            if (resp.summoner) return resp.summoner;
            if (resp.data?.summoner) return resp.data.summoner;
          }
          return resp;
        }),
        catchError(this.handleError)
      );
  }

  createLobby(gameMode: string = 'CLASSIC'): Observable<any> {
    return this.http.post(`${this.baseUrl}/lcu/create-lobby`, {
      gameMode
    }).pipe(
      catchError(this.handleError)
    );
  }

  invitePlayers(summonerNames: string[]): Observable<any> {
    return this.http.post(`${this.baseUrl}/lcu/invite-players`, {
      summonerNames
    }).pipe(
      catchError(this.handleError)
    );
  }

  // Settings endpoints
  saveSettings(settings: any): Observable<any> {
    return this.http.post(`${this.baseUrl}/settings`, settings)
      .pipe(
        catchError(this.handleError)
      );
  }  // Novo método para configurar a Riot API Key
  setRiotApiKey(apiKey: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/config/riot-api-key`, { apiKey })
      .pipe(
        catchError(this.handleError)
      );
  }

  // Método para configurar o Discord Bot Token
  setDiscordBotToken(token: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/config/discord-token`, { token })
      .pipe(
        catchError(this.handleError)
      );
  }

  setDiscordChannel(channelName: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/config/discord-channel`, { channelName })
      .pipe(
        catchError(this.handleError)
      );
  }

  // Método para verificar status do Discord Bot
  getDiscordStatus(): Observable<any> {
    return this.http.get(`${this.baseUrl}/discord/status`)
      .pipe(
        catchError(this.handleError)
      );
  }

  // Método para buscar configurações do banco de dados
  getConfigSettings(): Observable<any> {
    return this.http.get(`${this.baseUrl}/config/settings`)
      .pipe(
        catchError(this.handleError)
      );
  }

  getLeaderboard(limit: number): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/stats/participants-leaderboard?limit=${limit}`);
  }

  // Método para atualizar dados do jogador usando Riot ID (gameName#tagLine)
  refreshPlayerByDisplayName(displayName: string, region: string): Observable<RefreshPlayerResponse> {
    return this.http.post<RefreshPlayerResponse>(`${this.baseUrl}/player/refresh-by-display-name`, {
      displayName,
      region
    }).pipe(
      map(response => {
        // Adaptar a resposta do backend para o formato esperado pelo frontend
        if ((response as any).success && (response as any).data) {
          return {
            success: true,
            player: (response as any).data as Player,
            message: (response as any).message || 'Dados atualizados com sucesso'
          };
        }
        return response;
      }),
      catchError(this.handleError)
    );
  }
  // Get current player from LCU - UPDATED TO USE CORRECT ENDPOINT
  getCurrentPlayer(): Observable<any> {
    return this.getCurrentPlayerDetails().pipe(
      map(response => {
        if (response.success && response.data) {
          return { success: true, player: response.data.riotApi };
        }
        return { success: false, player: null };
      }),
      catchError(this.handleError)
    );
  }

  /**
   * Obtém dados do jogador atual via LCU (método usado pelo app.ts)
   */
  getPlayerFromLCU(): Observable<Player> {
    // Em Electron, ler direto do LCU via conector local
    if (this.isElectron() && (window as any).electronAPI?.lcu?.getCurrentSummoner) {
      return new Observable<Player>(observer => {
        (window as any).electronAPI.lcu.getCurrentSummoner()
          .then((lcuData: any) => {
            if (!lcuData) throw new Error('Dados do LCU não disponíveis');

            const displayName = (lcuData.gameName && lcuData.tagLine)
              ? `${lcuData.gameName}#${lcuData.tagLine}`
              : (lcuData.displayName || undefined);

            const player: Player = {
              id: lcuData.summonerId || 0,
              summonerName: lcuData.gameName || lcuData.displayName || 'Unknown',
              displayName,
              gameName: lcuData.gameName || null,
              tagLine: lcuData.tagLine || null,
              summonerId: (lcuData.summonerId || '0').toString(),
              puuid: lcuData.puuid || '',
              profileIconId: lcuData.profileIconId || 29,
              summonerLevel: lcuData.summonerLevel || 30,
              region: 'br1',
              currentMMR: 1200,
              rank: undefined,
              wins: undefined,
              losses: undefined
            };

            observer.next(player);
          })
          .catch((err: any) => observer.error(err))
          .finally(() => observer.complete());
      }).pipe(catchError(this.handleError));
    }

    // Modo web: usar backend
    return this.http.get<any>(`${this.baseUrl}/player/current-details`).pipe(
      map(response => {
        if (response.success && response.player) {
          const playerData = response.player;
          const player: Player = {
            id: playerData.id || 0,
            summonerName: playerData.summonerName || playerData.gameName || 'Unknown',
            gameName: playerData.gameName || playerData.summonerName || 'Unknown',
            tagLine: playerData.tagLine || '',
            region: playerData.region || 'br1',
            puuid: playerData.puuid || '',
            summonerId: playerData.summonerId || '',
            summonerLevel: playerData.summonerLevel || 1,
            profileIconId: playerData.profileIconId || 0,
            currentMMR: playerData.currentMMR || playerData.mmr || 1200,
            displayName: playerData.gameName && playerData.tagLine ? `${playerData.gameName}#${playerData.tagLine}` : playerData.summonerName || 'Unknown',
            registeredAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
          };
          return player;
        } else {
          throw new Error(response.error || 'LCU não conectado ou dados não disponíveis');
        }
      }),
      catchError(error => {
        console.error('❌ [ApiService] Erro ao carregar dados do jogador:', error);
        return throwError(() => error);
      })
    );
  }

  // ✅ NOVO: Aceitar ready-check do LCU localmente (Electron)
  acceptLocalReadyCheck(): Observable<any> {
    if (this.isElectron() && (window as any).electronAPI?.lcu?.request) {
      return new Observable(observer => {
        (window as any).electronAPI.lcu.request('/lol-matchmaking/v1/ready-check/accept', 'POST')
          .then((data: any) => { observer.next({ success: true, data }); })
          .catch((err: any) => { observer.error(err); })
          .finally(() => observer.complete());
      });
    }
    return throwError(() => new Error('Não suportado fora do Electron'));
  }

  // === WebSocket helpers (used across app) ===
  onWebSocketMessage(): Observable<any> {
    return this.webSocketMessageSubject.asObservable();
  }

  isWebSocketConnected(): boolean {
    return this.webSocket !== null && this.webSocket.readyState === WebSocket.OPEN;
  }

  onWebSocketReady(): Observable<boolean> {
    return new Observable<boolean>(observer => {
      const maxWaitTime = 15000;
      const checkInterval = 100;
      let elapsed = 0;
      const check = () => {
        if (this.isWebSocketConnected()) {
          observer.next(true);
          observer.complete();
          return;
        }
        elapsed += checkInterval;
        if (elapsed >= maxWaitTime) {
          observer.error(new Error('Timeout aguardando WebSocket estar pronto'));
          return;
        }
        setTimeout(check, checkInterval);
      };
      check();
    });
  }

  private connectWebSocket(): void {
    if (this.webSocket && this.webSocket.readyState === WebSocket.OPEN) return;
    const wsUrl = this.getWebSocketUrl();
    try {
      this.webSocket = new WebSocket(wsUrl);
      this.webSocket.onopen = () => {
        this.webSocketMessageSubject.next({ type: 'backend_connection_success', data: { ts: Date.now() } });
      };
      this.webSocket.onmessage = (ev) => {
        try { this.webSocketMessageSubject.next(JSON.parse(ev.data)); } catch { /* ignore */ }
      };
      this.webSocket.onerror = () => { /* handled by reconnect */ };
      this.webSocket.onclose = () => {
        setTimeout(() => this.connectWebSocket(), 5000);
      };
    } catch (e) {
      console.error('WS connect error:', e);
    }
  }

  sendWebSocketMessage(message: any): void {
    if (this.webSocket && this.webSocket.readyState === WebSocket.OPEN) {
      this.webSocket.send(JSON.stringify(message));
    }
  }

  connect(): Observable<any> {
    if (!this.isWebSocketConnected()) this.connectWebSocket();
    return this.onWebSocketMessage();
  }

  // === Queue and matchmaking endpoints ===
  joinQueue(playerData: any, preferences: any): Observable<any> {
    return this.http.post(`${this.baseUrl}/queue/join`, { player: playerData, preferences }).pipe(catchError(this.handleError));
  }

  leaveQueue(playerId?: number, summonerName?: string): Observable<any> {
    const body: any = {};
    if (playerId) body.playerId = playerId;
    if (summonerName) body.summonerName = summonerName;
    return this.http.post(`${this.baseUrl}/queue/leave`, body).pipe(catchError(this.handleError));
  }

  acceptMatch(matchId: number, playerId?: number, summonerName?: string): Observable<any> {
    const body: any = { matchId };
    if (playerId) body.playerId = playerId;
    if (summonerName) body.summonerName = summonerName;
    return this.http.post(`${this.baseUrl}/match/accept`, body).pipe(catchError(this.handleError));
  }

  declineMatch(matchId: number, playerId?: number, summonerName?: string): Observable<any> {
    const body: any = { matchId };
    if (playerId) body.playerId = playerId;
    if (summonerName) body.summonerName = summonerName;
    return this.http.post(`${this.baseUrl}/match/decline`, body).pipe(catchError(this.handleError));
  }

  addBotToQueue(): Observable<any> {
    return this.http.post(`${this.baseUrl}/queue/add-bot`, {}).pipe(catchError(this.handleError));
  }

  resetBotCounter(): Observable<any> {
    return this.http.post(`${this.baseUrl}/queue/reset-bot-counter`, {}).pipe(catchError(this.handleError));
  }

  // === Custom matches & sync ===
  saveCustomMatch(matchData: any): Observable<any> {
    return this.http.post(`${this.baseUrl}/custom_matches`, matchData).pipe(catchError(this.handleError));
  }

  checkSyncStatus(summonerName: string): Observable<any> {
    const url = `${this.baseUrl}/sync/status?summonerName=${encodeURIComponent(summonerName)}`;
    return this.http.get(url).pipe(catchError(this.handleError));
  }

  getCustomMatches(playerIdentifier: string, offset: number = 0, limit: number = 10): Observable<any> {
    return this.http.get(`${this.baseUrl}/matches/custom/${encodeURIComponent(playerIdentifier)}?offset=${offset}&limit=${limit}`).pipe(catchError(this.handleError));
  }

  getCustomMatchesCount(playerIdentifier: string): Observable<any> {
    return this.http.get(`${this.baseUrl}/matches/custom/${encodeURIComponent(playerIdentifier)}/count`).pipe(catchError(this.handleError));
  }

  // === Riot API proxies ===
  getPlayerMatchHistoryFromRiot(puuid: string, region: string, count: number = 20): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/player/match-history-riot/${puuid}?count=${count}`).pipe(catchError(this.handleError));
  }

  // === LCU helper endpoints (backend or local fallback) ===
  getCurrentGame(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/lcu/current-match-details`).pipe(catchError(this.handleError));
  }

  getLCUMatchHistory(startIndex: number = 0, count: number = 20): Observable<any> {
    if (this.isElectron()) {
      return this.tryWithFallback(`/lcu/match-history?startIndex=${startIndex}&count=${count}`, 'GET');
    }
    return this.http.get(`${this.baseUrl}/lcu/match-history?startIndex=${startIndex}&count=${count}`).pipe(catchError(this.handleError));
  }

  getLCUMatchHistoryAll(startIndex: number = 0, count: number = 5, customOnly: boolean = true): Observable<any> {
    if (this.isElectron()) {
      return this.tryWithFallback(`/lcu/match-history-all?startIndex=${startIndex}&count=${count}&customOnly=${customOnly}`, 'GET');
    }
    return this.http.get(`${this.baseUrl}/lcu/match-history-all?startIndex=${startIndex}&count=${count}&customOnly=${customOnly}`).pipe(catchError(this.handleError));
  }

  // === Simulation helpers (used by App.simulateLastMatch) ===
  createLCUBasedMatch(payload: { lcuMatchData: any; playerIdentifier: string }): Observable<any> {
    const tryBackend = () => this.http.post(`${this.baseUrl}/lcu/create-lcu-based-match`, payload).pipe(
      catchError(() => {
        // Fallback local se endpoint não existir
        const match = payload?.lcuMatchData || {};
        const simulated = {
          matchId: match.gameId || match.id || Date.now(),
          team1: match.team1 || match.blueTeam || [],
          team2: match.team2 || match.redTeam || [],
          pickBanData: match.pickBanData || match.session || null,
          detectedByLCU: true
        };
        return of(simulated);
      })
    );

    // Em Electron ou Web, tentar backend; se falhar, retorna simulado
    return tryBackend();
  }

  cleanupTestMatches(): Observable<any> {
    // Tenta chamar backend; se não houver endpoint, retorna resposta padrão
    return this.http.post(`${this.baseUrl}/matches/cleanup-test`, {}).pipe(
      catchError(() => of({ success: true, deletedCount: 0 }))
    );
  }
}
