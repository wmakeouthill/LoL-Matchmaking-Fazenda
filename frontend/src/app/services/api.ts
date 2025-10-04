import { Injectable, Inject } from '@angular/core';
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
  // Reconnection and heartbeat state
  private wsReconnectAttempts = 0;
  private readonly wsMaxReconnectAttempts = 30; // cap attempts
  private wsManualClose = false;
  private wsReconnectTimer: any = null;
  private wsHeartbeatTimer: any = null;
  private readonly wsHeartbeatIntervalMs = Number((window as any).WS_HEARTBEAT_MS || 45000); // 45 segundos
  private readonly wsBaseBackoffMs = 2000; // 2s
  private readonly wsMaxBackoffMs = 60000; // 60s
  private wsLastMessageAt = 0;
  private readonly wsMessageQueue: any[] = [];

  constructor(@Inject(HttpClient) private readonly http: HttpClient) {
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

    // call setupUnloadHandler once during service construction
    // Ensure unload handler closes WS cleanly when the page/app is unloaded
    try { this.setupUnloadHandler(); } catch { }
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

    // Preferir explicitamente o 'getBackendApiUrl' exposto pelo preload (já contém /api)
    const productionApiUrl = (window as any).electronAPI?.getBackendApiUrl?.();
    if (productionApiUrl) {
      console.log('🚀 [Electron] production API URL (from preload) detected:', productionApiUrl);
      let normalizedApi = String(productionApiUrl).trim();
      normalizedApi = normalizedApi.replace(/\/+$/, '');
      return normalizedApi;
    }

    // If getBackendApiUrl is not present, fall back to file:// / hostname heuristics below

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
      // Build full URL safely to avoid double slashes or missing slashes
      const base = url.endsWith('/') ? url : `${url}/`;
      const normalizedEndpoint = endpoint.replace(/^\/+/, '');
      const fullUrl = new URL(normalizedEndpoint, base).toString();
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

    return this.http.get<any>(url)
      .pipe(
        map(response => {
          // Converter resposta do novo backend para formato esperado
          return {
            playersInQueue: response.playersInQueue || 0,
            playersInQueueList: response.playersInQueueList || [],
            averageWaitTime: response.averageWaitTime || 0,
            estimatedMatchTime: response.estimatedMatchTime || 0,
            isActive: response.isActive || false
          } as QueueStatus;
        }),
        catchError(this.handleError)
      );
  }

  // New: join queue (used by app.ts)
  joinQueue(player: Player, preferences?: any): Observable<any> {
    const payload: any = {
      playerId: player?.id || null,
      summonerName: player?.displayName || player?.summonerName,
      region: player?.region || 'br1',
      customLp: preferences?.customLp || null,
      primaryLane: preferences?.primaryLane || null,
      secondaryLane: preferences?.secondaryLane || null
    };

    return this.http.post(`${this.baseUrl}/queue/join`, payload)
      .pipe(catchError(this.handleError));
  }

  // New: leave queue (used by app.ts)
  leaveQueue(playerId?: number | null, summonerName?: string): Observable<any> {
    const payload: any = {
      playerId: playerId || null,
      summonerName: summonerName || null
    };

    return this.http.post(`${this.baseUrl}/queue/leave`, payload)
      .pipe(catchError(this.handleError));
  }

  refreshQueueCache(): Observable<any> {
    return this.http.post(`${this.baseUrl}/queue/refresh`, {})
      .pipe(catchError(this.handleError));
  }

  forceMySQLSync(): Observable<any> {
    return this.http.post(`${this.baseUrl}/queue/force-sync`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * ✅ NOVO: Busca partida ativa (draft ou in_progress) do jogador
   * Usado para restaurar estado ao reabrir app
   */
  getMyActiveMatch(summonerName: string): Observable<any> {
    const params = { summonerName };
    return this.http.get<any>(`${this.baseUrl}/queue/my-active-match`, { params })
      .pipe(catchError(this.handleError));
  }

  // Match endpoints (accept/decline)
  acceptMatch(matchId: number, playerId: number | null, playerName: string): Observable<any> {
    const payload = { matchId, playerName };
    return this.http.post(`${this.baseUrl}/match/accept`, payload).pipe(catchError(this.handleError));
  }

  declineMatch(matchId: number, playerId: number | null, playerName: string): Observable<any> {
    const payload = { matchId, playerName };
    return this.http.post(`${this.baseUrl}/match/decline`, payload).pipe(catchError(this.handleError));
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

  // ✅ NOVO: Cancelar partida em progresso (draft/in_progress)
  cancelMatchInProgress(matchId: number): Observable<any> {
    return this.http.delete(`${this.baseUrl}/match/${matchId}/cancel`)
      .pipe(
        catchError(this.handleError)
      );
  }

  // ✅ NOVO: Simular última partida do LCU como partida customizada (para testes)
  simulateLastLcuMatch(lcuMatchData: any): Observable<any> {
    return this.http.post(`${this.baseUrl}/debug/simulate-last-match`, lcuMatchData)
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

  // Simulation helpers (frontend triggers a simulated match payload to the backend)
  simulateMatch(simulatedMatch: any): Observable<any> {
    // If there's no dedicated endpoint, use /match/finish for simulation if payload fits.
    // But backend doesn't expose a generic simulate endpoint; try /matches/simulate if exists, else /match/finish.
    const simulateUrl = `${this.baseUrl}/matches/simulate`;
    return this.http.post(simulateUrl, simulatedMatch).pipe(
      catchError((err) => {
        // Fallback to finish endpoint
        return this.http.post(`${this.baseUrl}/match/finish`, simulatedMatch).pipe(catchError(this.handleError));
      })
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

  // Get match history from LCU (single page)
  getLCUMatchHistory(offset: number = 0, limit: number = 10): Observable<any> {
    console.log('🔍 [API] getLCUMatchHistory called, isElectron:', this.isElectron());
    console.log('🔍 [API] electronAPI available:', !!(window as any).electronAPI);
    console.log('🔍 [API] electronAPI.lcu available:', !!(window as any).electronAPI?.lcu);
    console.log('🔍 [API] electronAPI.lcu.getMatchHistory available:', !!(window as any).electronAPI?.lcu?.getMatchHistory);

    // Use Electron gateway WebSocket for LCU communication (like dashboard does)
    if (this.isElectron() && (window as any).electronAPI?.lcu?.getMatchHistory) {
      console.log('🔍 [API] Using electronAPI.lcu.getMatchHistory');
      return new Observable(observer => {
        (window as any).electronAPI.lcu.getMatchHistory()
          .then((response: any) => {
            console.log('🔍 [API] electronAPI.lcu.getMatchHistory success:', response);
            observer.next(response);
            observer.complete();
          })
          .catch((error: any) => {
            console.error('❌ [ElectronAPI] Erro ao obter histórico do LCU:', error);
            observer.error(error);
          });
      });
    }

    console.log('🔍 [API] Using HTTP fallback');
    // Fallback: Backend exposes /api/lcu/match-history which returns a CompletableFuture<ResponseEntity<Map>>
    return this.http.get(`${this.baseUrl}/lcu/match-history`).pipe(catchError(this.handleError));
  }

  // NEW: Get LCU game details by gameId (via Electron gateway when available)
  getLCUGameDetails(gameId: number | string): Observable<any> {
    // Prefer Electron local gateway if available
    if (this.isElectron() && (window as any).electronAPI?.lcu?.request) {
      return new Observable(observer => {
        (window as any).electronAPI.lcu.request(`/lol-match-history/v1/games/${gameId}`, 'GET')
          .then((data: any) => { observer.next(data); })
          .catch((err: any) => { observer.error(err); })
          .finally(() => observer.complete());
      }).pipe(catchError(this.handleError));
    }

    // HTTP fallback through backend proxy if exposed
    return this.tryWithFallback(`/lcu/games/${gameId}`, 'GET').pipe(
      catchError(this.handleError)
    );
  }

  // Aggregate helper used by UI to request LCU history with pagination/flags
  getLCUMatchHistoryAll(offset: number = 0, limit: number = 10, includePickBan: boolean = false): Observable<any> {
    return this.getLCUMatchHistory(offset, limit).pipe(
      map((resp: any) => {
        if (!resp) return { success: false, matches: [] };

        // If using Electron gateway, resp is direct LCU data
        if (this.isElectron() && (window as any).electronAPI?.lcu?.getMatchHistory) {
          // Electron returns LCU data directly - extract games array from the correct structure
          console.log('🔍 [API] Processing LCU response structure:', resp);
          let games = [];

          if (resp.games && resp.games.games && Array.isArray(resp.games.games)) {
            // Structure: { games: { games: [...] } }
            games = resp.games.games;
          } else if (resp.games && Array.isArray(resp.games)) {
            // Structure: { games: [...] }
            games = resp.games;
          } else if (Array.isArray(resp)) {
            // Structure: [...]
            games = resp;
          }

          console.log('🔍 [API] Extracted games array:', games.length, 'matches');
          return { success: true, matches: games };
        }

        // Backend returns an object with matchHistory key; normalize to { success, matches }
        const matches = resp.matchHistory || resp.matchHistory?.matchHistory || resp.matches || resp.matchIds || resp.data?.matchHistory || resp.matchHistory?.matches || resp.matchHistory;
        return { success: true, matches: matches || [] };
      }),
      catchError(this.handleError)
    );
  }

  // Special User Tools - Admin/Debug endpoints
  addBotToQueue(): Observable<any> {
    const url = `${this.baseUrl}/queue/add-bot`;
    return this.http.post(url, {}).pipe(
      catchError((err) => {
        console.warn('⚠️ [API] Falha no endpoint /queue/add-bot:', err);
        return this.http.post(`${this.baseUrl}/admin/add-bot`, {}).pipe(
          catchError(() => this.http.post(`${this.baseUrl}/debug/add-bot`, {}).pipe(catchError(this.handleError)))
        );
      })
    );
  }

  resetBotCounter(): Observable<any> {
    const url = `${this.baseUrl}/queue/reset-bot-counter`;
    return this.http.post(url, {}).pipe(
      catchError((err) => {
        console.warn('⚠️ [API] Falha no endpoint /queue/reset-bot-counter:', err);
        return this.http.post(`${this.baseUrl}/admin/reset-bot-counter`, {}).pipe(
          catchError(() => this.http.post(`${this.baseUrl}/debug/reset-bot-counter`, {}).pipe(catchError(this.handleError)))
        );
      })
    );
  }

  simulateLastMatch(): Observable<any> {
    const url = `${this.baseUrl}/matches/simulate-last`;
    return this.http.post(url, {}).pipe(
      catchError((err) => {
        console.warn('⚠️ [API] Falha no endpoint /matches/simulate-last:', err);
        return this.http.post(`${this.baseUrl}/admin/simulate-last-match`, {}).pipe(
          catchError(() => this.http.post(`${this.baseUrl}/debug/simulate-last-match`, {}).pipe(catchError(this.handleError)))
        );
      })
    );
  }

  // Cleanup test matches (admin/debug) - try multiple endpoints
  cleanupTestMatches(): Observable<any> {
    const url = `${this.baseUrl}/matches/cleanup`;
    return this.http.post(url, {}).pipe(catchError((err) => {
      // fallback to /matches/clear or /matches/reset
      return this.http.post(`${this.baseUrl}/matches/clear`, {}).pipe(catchError(this.handleError));
    }));
  }

  // Check sync status for a given player identifier
  checkSyncStatus(identifier: string): Observable<any> {
    const url = `${this.baseUrl}/sync/check?identifier=${encodeURIComponent(identifier)}`;
    return this.http.get(url).pipe(catchError(this.handleError));
  }

  // Get current game data via LCU
  getCurrentGame(): Observable<any> {
    return this.http.get(`${this.baseUrl}/lcu/game-data`).pipe(
      map((resp: any) => {
        if (!resp) return { success: false };
        // Normalize shapes
        if (resp.gameData) return { success: true, ...resp.gameData };
        if (resp.data?.gameData) return { success: true, ...resp.data.gameData };
        return resp;
      }),
      catchError(this.handleError)
    );
  }

  // Custom matches listing (used in dashboard/match-history)
  getCustomMatches(identifier: string, offset: number = 0, limit: number = 20): Observable<any> {
    const url = `${this.baseUrl}/matches/custom/${encodeURIComponent(identifier)}?offset=${offset}&limit=${limit}`;
    return this.http.get<any>(url).pipe(catchError(this.handleError));
  }

  getCustomMatchesCount(identifier: string): Observable<any> {
    const url = `${this.baseUrl}/matches/custom/${encodeURIComponent(identifier)}/count`;
    return this.http.get<any>(url).pipe(catchError(this.handleError));
  }

  // Riot match history retrieval wrapper
  getPlayerMatchHistoryFromRiot(puuid: string, region: string = 'br1', limit: number = 20): Observable<any> {
    return this.http.get(`${this.baseUrl}/riot/matches/${encodeURIComponent(puuid)}?region=${encodeURIComponent(region)}&count=${limit}`)
      .pipe(catchError(this.handleError));
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

  setDiscordChannel(channelId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/config/discord-channel`, { channelId })
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

  // Método para obter usuários do Discord
  getDiscordUsers(): Observable<any> {
    return this.http.get(`${this.baseUrl}/discord/users`)
      .pipe(
        catchError(this.handleError)
      );
  }

  // Método para registrar comandos slash do Discord
  registerDiscordCommands(): Observable<any> {
    return this.http.post(`${this.baseUrl}/discord/register-commands`, {})
      .pipe(
        catchError(this.handleError)
      );
  }

  // Métodos para Discord via Electron Gateway
  getDiscordStatusFromGateway(): Observable<any> {
    if (this.isElectron() && (window as any).electronAPI?.lcu?.request) {
      return new Observable(observer => {
        // Send WebSocket message to gateway
        this.sendWebSocketMessage({ type: 'get_discord_status' });

        // Listen for response
        const subscription = this.onWebSocketMessage().subscribe({
          next: (message) => {
            if (message.type === 'discord_status') {
              observer.next(message.data);
              observer.complete();
              subscription.unsubscribe();
            }
          },
          error: (err) => {
            observer.error(err);
            subscription.unsubscribe();
          }
        });
      });
    }

    // Fallback to HTTP
    return this.getDiscordStatus();
  }

  getDiscordUsersFromGateway(): Observable<any> {
    if (this.isElectron() && (window as any).electronAPI?.lcu?.request) {
      return new Observable(observer => {
        // Send WebSocket message to gateway
        this.sendWebSocketMessage({ type: 'get_discord_users' });

        // Listen for response
        const subscription = this.onWebSocketMessage().subscribe({
          next: (message) => {
            if (message.type === 'discord_users') {
              observer.next(message.users || []);
              observer.complete();
              subscription.unsubscribe();
            }
          },
          error: (err) => {
            observer.error(err);
            subscription.unsubscribe();
          }
        });
      });
    }

    // Fallback to HTTP
    return this.http.get(`${this.baseUrl}/discord/users`)
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
    // Em Electron, preferir o backend central (que pode usar gateway/RPC) e
    // só cair para o conector local se o backend falhar. Isso mantém uma única
    // fonte de verdade e garante que o formato retornado seja o mesmo do modo web.
    if (this.isElectron()) {
      return this.http.get<any>(`${this.baseUrl}/player/current-details`).pipe(
        map(response => {
          if (response && response.success && response.player) {
            const playerData = response.player;
            // Process ranked data from LCU (like old frontend)
            const lcuRankedStats = playerData.lcuRankedStats || null;
            console.log('🔍 [FRONTEND] LCU Ranked Stats received:', lcuRankedStats);
            const rankedData = this.processRankedData(playerData, lcuRankedStats);
            console.log('🔍 [FRONTEND] Processed ranked data:', rankedData);

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
              currentMMR: this.calculateMMRFromData(playerData, lcuRankedStats),
              displayName: playerData.gameName && playerData.tagLine ? `${playerData.gameName}#${playerData.tagLine}` : playerData.summonerName || 'Unknown',
              registeredAt: new Date().toISOString(),
              updatedAt: new Date().toISOString(),
              // ✅ ADICIONADO: Mapear dados de ranked processados do LCU
              rank: rankedData.soloQueue ? this.extractRankData(rankedData.soloQueue) : { tier: 'UNRANKED', rank: '', lp: 0, wins: 0, losses: 0 },
              wins: rankedData.soloQueue?.wins || playerData.wins || 0,
              losses: rankedData.soloQueue?.losses || playerData.losses || 0,
              // ✅ ADICIONADO: Campo rankedData para compatibilidade com dashboard
              rankedData: rankedData
            };
            return player;
          }

          // If backend didn't provide player, fall through to local electron API
          throw new Error(response?.error || 'Backend não retornou dados do jogador');
        }),
        catchError(err => {
          // Fallback: try direct electronAPI (local LCU) to avoid blocking UI
          if ((window as any).electronAPI?.lcu?.getCurrentSummoner) {
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
                .catch((e: any) => observer.error(e))
                .finally(() => observer.complete());
            }).pipe(catchError(this.handleError));
          }
          return throwError(() => err);
        })
      );
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
    const connected = this.webSocket !== null && this.webSocket.readyState === WebSocket.OPEN;
    if (connected) {
      console.log('✅ [WebSocket] Status: Conectado');
    } else {
      console.log(`❌ [WebSocket] Status: Desconectado (readyState: ${this.webSocket?.readyState})`);
    }
    return connected;
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
    // Avoid attempts if we purposely closed the socket
    if (this.wsManualClose) {
      console.log('🛑 [WebSocket] Conexão manualmente fechada, não reconectando');
      return;
    }

    if (this.webSocket && this.webSocket.readyState === WebSocket.OPEN) {
      console.log('✅ [WebSocket] Já conectado, ignorando nova tentativa');
      return;
    }

    const wsUrl = this.getWebSocketUrl();
    console.log('🔄 [WebSocket] Tentando conectar em:', wsUrl);

    try {
      // Clear any pending reconnect timer
      if (this.wsReconnectTimer) { clearTimeout(this.wsReconnectTimer); this.wsReconnectTimer = null; }

      this.webSocket = new WebSocket(wsUrl);

      this.webSocket.onopen = () => {
        this.wsReconnectAttempts = 0;
        this.wsLastMessageAt = Date.now();
        console.log('✅ [WebSocket] Conectado com sucesso');
        // Flush queued messages
        while (this.webSocket && this.webSocket.readyState === WebSocket.OPEN && this.wsMessageQueue.length > 0) {
          try { const m = this.wsMessageQueue.shift(); this.webSocket.send(JSON.stringify(m)); } catch { break; }
        }
        this.webSocketMessageSubject.next({ type: 'backend_connection_success', data: { ts: Date.now() } });

        // Start heartbeat
        try {
          if (this.wsHeartbeatTimer) clearInterval(this.wsHeartbeatTimer); this.wsHeartbeatTimer = setInterval(() => {
            try {
              // If we haven't received any message within 3x heartbeat, consider connection stale
              const now = Date.now();
              if (now - this.wsLastMessageAt > Math.max(this.wsHeartbeatIntervalMs * 3, 120000)) {
                try { if (this.webSocket) this.webSocket.close(); } catch { };
                return;
              }
              if (this.webSocket && this.webSocket.readyState === WebSocket.OPEN) {
                try { this.webSocket.send(JSON.stringify({ type: 'ping', ts: new Date().toISOString() })); } catch { }
              }
            } catch { }
          }, this.wsHeartbeatIntervalMs);
        } catch { }
      };

      this.webSocket.onmessage = (ev) => {
        try {
          this.wsLastMessageAt = Date.now();
          let parsed: any = null;
          try { parsed = JSON.parse(ev.data); } catch { parsed = ev.data; }
          // Respond to pings if backend expects it (optional)
          if (parsed && parsed.type === 'ping') {
            try { if (this.webSocket && this.webSocket.readyState === WebSocket.OPEN) this.webSocket.send(JSON.stringify({ type: 'pong', ts: new Date().toISOString() })); } catch { }
          }
          this.webSocketMessageSubject.next(parsed);
        } catch (err) { /* ignore */ }
      };

      this.webSocket.onerror = (err) => {
        console.error('❌ [WebSocket] Erro na conexão:', err);
      };

      this.webSocket.onclose = (ev) => {
        try {
          if (this.wsHeartbeatTimer) { clearInterval(this.wsHeartbeatTimer); this.wsHeartbeatTimer = null; }
        } catch { }

        console.log(`🔌 [WebSocket] Desconectado (código: ${ev.code}, motivo: ${ev.reason})`);

        // If manual close, don't reconnect
        if (this.wsManualClose) {
          console.log('🔌 [WebSocket] Fechamento manual, não reconectando');
          return;
        }

        // ✅ NOVO: Lógica de reconexão mais inteligente
        const shouldReconnect = this.shouldAttemptReconnect(ev.code, this.wsReconnectAttempts);
        if (!shouldReconnect) {
          console.log('🛑 [WebSocket] Não tentando reconectar devido a condições específicas');
          return;
        }

        // Exponential backoff reconnect com jitter
        this.wsReconnectAttempts = Math.min(this.wsReconnectAttempts + 1, this.wsMaxReconnectAttempts);
        const baseBackoff = Math.min(this.wsBaseBackoffMs * Math.pow(1.5, this.wsReconnectAttempts), this.wsMaxBackoffMs);
        const jitter = Math.random() * 1000; // Adicionar até 1s de jitter
        const backoff = baseBackoff + jitter;

        console.log(`🔄 [WebSocket] Tentativa ${this.wsReconnectAttempts}/${this.wsMaxReconnectAttempts} de reconexão em ${Math.round(backoff)}ms`);
        this.wsReconnectTimer = setTimeout(() => {
          try {
            this.connectWebSocket();
          } catch (e) {
            console.warn('⚠️ [WebSocket] Erro na tentativa de reconexão:', e);
          }
        }, backoff);
      };

    } catch (e) {
      console.error('WS connect error:', e);
      // schedule next attempt
      this.wsReconnectAttempts = Math.min(this.wsReconnectAttempts + 1, this.wsMaxReconnectAttempts);
      const backoff = Math.min(this.wsBaseBackoffMs * Math.pow(1.5, this.wsReconnectAttempts), this.wsMaxBackoffMs);
      if (this.wsReconnectTimer) clearTimeout(this.wsReconnectTimer);
      this.wsReconnectTimer = setTimeout(() => this.connectWebSocket(), backoff);
    }
  }

  sendWebSocketMessage(message: any): void {
    try {
      const payload = typeof message === 'string' ? message : JSON.stringify(message);
      if (this.webSocket && this.webSocket.readyState === WebSocket.OPEN) {
        this.webSocket.send(payload);
        return;
      }
      // Queue the message to be sent when WS reconnects
      this.wsMessageQueue.push(typeof message === 'string' ? message : JSON.parse(payload));
    } catch (e) {
      // swallow
    }
  }

  connect(): Observable<any> {
    if (!this.isWebSocketConnected()) this.connectWebSocket();
    return this.onWebSocketMessage();
  }

  // ✅ NOVO: Lógica inteligente para decidir se deve tentar reconectar
  private shouldAttemptReconnect(closeCode: number, attemptCount: number): boolean {
    // Não reconectar se excedeu o limite de tentativas
    if (attemptCount >= this.wsMaxReconnectAttempts) {
      console.log('🛑 [WebSocket] Limite de tentativas de reconexão excedido');
      return false;
    }

    // Não reconectar para códigos de erro específicos
    const nonRetryableCodes = [1002, 1003, 1006, 1011, 1012, 1013, 1014, 1015];
    if (nonRetryableCodes.includes(closeCode)) {
      console.log(`🛑 [WebSocket] Código de fechamento ${closeCode} não permite reconexão`);
      return false;
    }

    // Não reconectar se a página está sendo descarregada
    if (document.visibilityState === 'hidden') {
      console.log('🛑 [WebSocket] Página oculta, não reconectando');
      return false;
    }

    // Reconectar para outros casos (1000, 1001, 1005, etc.)
    return true;
  }

  // Graceful shutdown helper used by renderer to close WS before unload
  private closeWebSocketGracefully(): void {
    try {
      this.wsManualClose = true;
      if (this.wsReconnectTimer) { clearTimeout(this.wsReconnectTimer); this.wsReconnectTimer = null; }
      if (this.wsHeartbeatTimer) { clearInterval(this.wsHeartbeatTimer); this.wsHeartbeatTimer = null; }
      if (this.webSocket) {
        try { if (this.webSocket.readyState === WebSocket.OPEN) this.webSocket.close(1000, 'app-shutdown'); } catch { };
        try { this.webSocket.onopen = null; this.webSocket.onmessage = null; this.webSocket.onclose = null; this.webSocket.onerror = null; } catch { }
        this.webSocket = null;
      }
    } catch { }
  }

  // Ensure we close WS on page unload (Electron or browser)
  private setupUnloadHandler(): void {
    try {
      if (typeof window !== 'undefined' && !(window as any).__apiUnloadHandlerInstalled) {
        (window as any).__apiUnloadHandlerInstalled = true;
        window.addEventListener('beforeunload', () => { this.closeWebSocketGracefully(); });
        // In Electron, also listen to app-level shutdown via electronAPI if available
        try {
          const eAPI = (window as any).electronAPI;
          if (eAPI && typeof eAPI.removeAllListeners === 'function') {
            // no-op; renderer receives shutdown via main DOM/IPC
          }
        } catch { }
      }
    } catch { }
  }

  // ========== MÉTODOS DE PROCESSAMENTO DE DADOS DE RANKED (do frontend antigo) ==========

  private processRankedData(riotApi: any, lcuRankedStats: any): any {
    const result = {
      soloQueue: null as any,
      flexQueue: null as any
    };

    // Priority 1: Use Riot API data if available
    if (riotApi?.soloQueue || riotApi?.rankedData?.soloQueue) {
      result.soloQueue = riotApi.soloQueue || riotApi.rankedData.soloQueue;
    }

    if (riotApi?.flexQueue || riotApi?.rankedData?.flexQueue) {
      result.flexQueue = riotApi.flexQueue || riotApi.rankedData.flexQueue;
    }

    // Priority 2: Use LCU ranked stats as fallback or supplement
    if (lcuRankedStats?.queues) {
      lcuRankedStats.queues.forEach((queue: any) => {
        if (queue.queueType === 'RANKED_SOLO_5x5' && !result.soloQueue) {
          result.soloQueue = {
            tier: queue.tier,
            rank: queue.division,
            leaguePoints: queue.leaguePoints,
            wins: queue.wins,
            losses: queue.losses,
            isProvisional: queue.isProvisional || false
          };
        } else if (queue.queueType === 'RANKED_FLEX_SR' && !result.flexQueue) {
          result.flexQueue = {
            tier: queue.tier,
            rank: queue.division,
            leaguePoints: queue.leaguePoints,
            wins: queue.wins,
            losses: queue.losses,
            isProvisional: queue.isProvisional || false
          };
        }
      });
    }

    return result;
  }

  private calculateMMRFromData(data: any, lcuRankedStats?: any): number {
    // Try Riot LCU data first
    if (lcuRankedStats?.queues) {
      const lcuSoloQueue = lcuRankedStats.queues.find((q: any) => q.queueType === 'RANKED_SOLO_5x5');
      if (lcuSoloQueue?.tier) {
        return this.calculateMMRFromRankData({
          tier: lcuSoloQueue.tier,
          rank: lcuSoloQueue.division,
          leaguePoints: lcuSoloQueue.leaguePoints
        });
      }
      // Try API ranked stats as fallback
      const soloQueue = data.soloQueue || data.rankedData?.soloQueue;
      if (soloQueue?.tier) {
        return this.calculateMMRFromRankData(soloQueue);
      }
    }

    return 1200; // Default MMR
  }

  private calculateMMRFromRankData(rankData: any): number {
    if (!rankData?.tier) return 1200;

    const tierValues: { [key: string]: number } = {
      'IRON': 800, 'BRONZE': 1000, 'SILVER': 1200, 'GOLD': 1400,
      'PLATINUM': 1700, 'EMERALD': 2000, 'DIAMOND': 2300,
      'MASTER': 2600, 'GRANDMASTER': 2800, 'CHALLENGER': 3000
    };

    const rankValues: { [key: string]: number } = {
      'IV': 0, 'III': 50, 'II': 100, 'I': 150
    };

    const baseMMR = tierValues[rankData.tier] || 1200;
    const rankBonus = rankValues[rankData.rank] || 0;
    const lpBonus = (rankData.leaguePoints || 0) * 0.8;

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
}
