import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, BehaviorSubject } from 'rxjs';
import { ApiService } from './api';

interface MatchLinkingSession {
  id: string;
  customMatchId: number;
  queueMatchId: number;
  players: any[];
  pickBanResult?: any;
  gameStarted: boolean;
  gameEnded: boolean;
  riotGameId?: string;
  linkedAt: Date;
  completedAt?: Date;
}

interface PostGameLinking {
  queueMatchId: number;
  riotGameId: string;
  playerResults: any[];
  winner: number;
  duration: number;
  success: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class MatchLinkingService {
  private currentSession$ = new BehaviorSubject<MatchLinkingSession | null>(null);
  private activeSessions: Map<string, MatchLinkingSession> = new Map();
  private baseUrl: string;

  constructor(private http: HttpClient, private apiService: ApiService) {
    this.baseUrl = this.apiService.getBaseUrl();
  }

  // Create a linking session when a custom match is created from queue
  createLinkingSession(queueMatch: any, customMatchId: number): MatchLinkingSession {
    const session: MatchLinkingSession = {
      id: `link_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      customMatchId: customMatchId,
      queueMatchId: queueMatch.id,
      players: [...queueMatch.team1Players, ...queueMatch.team2Players],
      gameStarted: false,
      gameEnded: false,
      linkedAt: new Date()
    };

    this.activeSessions.set(session.id, session);
    this.currentSession$.next(session);

    // Save to backend
    this.saveLinkingSession(session).subscribe({
      next: (response) => {
        // console.log('✅ Sessão salva no backend:', response); // Removido
      },
      error: (error) => {
        console.error('❌ Erro ao salvar sessão:', error);
      }
    });

    return session;
  }

  // Update session with pick/ban results
  updateWithPickBan(sessionId: string, pickBanResult: any): void {
    const session = this.activeSessions.get(sessionId);
    if (session) {
      session.pickBanResult = pickBanResult;
      this.currentSession$.next(session);

      // Update in backend
      this.updateLinkingSession(session).subscribe();
    }
  }

  // Mark game as started (when players enter LoL match)
  markGameStarted(sessionId: string, riotGameId?: string): void {
    const session = this.activeSessions.get(sessionId);
    if (session) {
      session.gameStarted = true;
      if (riotGameId) {
        session.riotGameId = riotGameId;
      }
      this.currentSession$.next(session);

      // Update in backend
      this.updateLinkingSession(session).subscribe();

      // Start monitoring for game completion
      this.startGameMonitoring(sessionId);
    }
  }

  // Mark game as completed and link results
  markGameCompleted(sessionId: string, gameResults: any): void {
    const session = this.activeSessions.get(sessionId);
    if (session) {
      session.gameEnded = true;
      session.completedAt = new Date();
      this.currentSession$.next(session);

      // Process post-game linking
      this.processPostGameLinking(session, gameResults);
    }
  }
  // Monitor for game completion via LCU
  private startGameMonitoring(sessionId: string): void {
    const checkInterval = setInterval(() => {
      this.apiService.getCurrentGame().subscribe({
        next: (response: any) => {
          if (response && response.success) {
            if (response.phase === 'EndOfGame' || response.phase === 'PreEndOfGame') {
              // Game ended, try to get results
              this.captureGameResults(sessionId);
              clearInterval(checkInterval);
            }
          }
        },
        error: () => {
          // LCU disconnected or error - stop monitoring
          clearInterval(checkInterval);
        }
      });
    }, 10000); // Check every 10 seconds

    // Auto-cleanup after 1 hour
    setTimeout(() => {
      clearInterval(checkInterval);
    }, 3600000);
  }

  // Capture game results when match ends
  private captureGameResults(sessionId: string): void {
    const session = this.activeSessions.get(sessionId);
    if (!session) return;

    // Get the most recent match from LCU
    this.apiService.getLCUMatchHistory(0, 1).subscribe({
      next: (response) => {
        if (response && response.success && response.matches && response.matches.length > 0) {
          const recentMatch = response.matches[0];

          // Verify this is our match by checking players
          if (this.isOurMatch(session, recentMatch)) {
            this.markGameCompleted(sessionId, recentMatch);
          }
        }
      },
      error: (error) => {
        console.error('❌ Erro ao capturar resultados:', error);
      }
    });
  }

  // Verify if a match from LCU corresponds to our session
  private isOurMatch(session: MatchLinkingSession, matchData: any): boolean {
    if (!matchData.participants) return false;

    // Check if at least 80% of our players are in the match
    const sessionPlayerIds = session.players.map(p => p.puuid || p.id);
    const matchPlayerIds = matchData.participants.map((p: any) => p.puuid);

    const commonPlayers = sessionPlayerIds.filter(id => matchPlayerIds.includes(id));
    const matchPercentage = commonPlayers.length / sessionPlayerIds.length;

    return matchPercentage >= 0.8; // 80% match required
  }

  // Process the post-game linking
  private processPostGameLinking(session: MatchLinkingSession, gameResults: any): void {
    const postGameData: PostGameLinking = {
      queueMatchId: session.queueMatchId,
      riotGameId: gameResults.gameId || session.riotGameId || '',
      playerResults: this.extractPlayerResults(session, gameResults),
      winner: this.determineWinner(session, gameResults),
      duration: gameResults.gameDuration || 0,
      success: true
    };

    // Send to backend
    this.linkPostGameResults(postGameData).subscribe({
      next: (response) => {
        this.cleanupSession(session.id);
      },
      error: (error) => {
        console.error('❌ Erro ao vincular resultados:', error);
      }
    });
  }

  // Extract player results from game data
  private extractPlayerResults(session: MatchLinkingSession, gameResults: any): any[] {
    if (!gameResults.participants) return [];

    return session.players.map(player => {
      const gameParticipant = gameResults.participants.find((p: any) =>
        p.puuid === player.puuid || p.summonerName === player.name
      );

      if (gameParticipant) {
        return {
          playerId: player.id,
          puuid: player.puuid,
          champion: gameParticipant.championName,
          kills: gameParticipant.kills || 0,
          deaths: gameParticipant.deaths || 0,
          assists: gameParticipant.assists || 0,
          won: gameParticipant.win || false,
          items: [
            gameParticipant.item0 || 0,
            gameParticipant.item1 || 0,
            gameParticipant.item2 || 0,
            gameParticipant.item3 || 0,
            gameParticipant.item4 || 0,
            gameParticipant.item5 || 0
          ],
          goldEarned: gameParticipant.goldEarned || 0,
          totalDamageDealt: gameParticipant.totalDamageDealtToChampions || 0
        };
      } else {
        // Player not found in game - they might have dodged
        return {
          playerId: player.id,
          puuid: player.puuid,
          champion: 'Unknown',
          kills: 0,
          deaths: 0,
          assists: 0,
          won: false,
          items: [0, 0, 0, 0, 0, 0],
          goldEarned: 0,
          totalDamageDealt: 0,
          dodged: true
        };
      }
    });
  }

  // Determine winning team
  private determineWinner(session: MatchLinkingSession, gameResults: any): number {
    if (!gameResults.teams) return 0;

    const winningTeam = gameResults.teams.find((team: any) => team.win);
    return winningTeam ? (winningTeam.teamId === 100 ? 1 : 2) : 0;
  }

  // Clean up session after completion
  private cleanupSession(sessionId: string): void {
    this.activeSessions.delete(sessionId);
    if (this.currentSession$.value?.id === sessionId) {
      this.currentSession$.next(null);
    }
  }

  // Backend API calls
  private saveLinkingSession(session: MatchLinkingSession): Observable<any> {
    return this.http.post(`${this.baseUrl}/match-linking/create`, session);
  }

  private updateLinkingSession(session: MatchLinkingSession): Observable<any> {
    return this.http.put(`${this.baseUrl}/match-linking/${session.id}`, session);
  }

  private linkPostGameResults(postGameData: PostGameLinking): Observable<any> {
    return this.http.post(`${this.baseUrl}/match-linking/complete`, postGameData);
  }

  // Public getters
  getCurrentSession(): Observable<MatchLinkingSession | null> {
    return this.currentSession$.asObservable();
  }

  getActiveSession(): MatchLinkingSession | null {
    return this.currentSession$.value;
  }

  // Get linked matches for a player
  getLinkedMatches(playerId: string): Observable<any> {
    return this.http.get(`${this.baseUrl}/match-linking/player/${playerId}`);
  }

  // Get statistics about linking success rate
  getLinkingStats(): Observable<any> {
    return this.http.get(`${this.baseUrl}/match-linking/stats`);
  }
}
