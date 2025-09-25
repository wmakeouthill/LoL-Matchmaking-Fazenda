# Match Linking Service

The `MatchLinkingService` (`spring-backend/frontend/src/app/services/match-linking.ts`) is an Angular service responsible for bridging the gap between custom matches created within the application's matchmaking queue and the actual game results obtained from the League of Legends Client Update (LCU) API. It manages the lifecycle of a match linking session, from its creation to the processing and saving of post-game results.

## Responsibilities

- **Session Management:** Creates, tracks, and updates active linking sessions for custom matches, allowing the frontend to monitor the progress of a linked game.
- **LCU Integration:** Periodically queries the LCU API (via `ApiService`) to detect when a linked game has started and ended.
- **Pick/Ban Data Integration:** Integrates pick/ban phase results into the linking session.
- **Post-Game Result Processing:** Extracts relevant information from LCU post-game data (e.g., winner, duration, player statistics) and transforms it for backend consumption.
- **Backend Communication:** Sends linking session updates and processed post-game results to the backend API for persistence.
- **Session Cleanup:** Cleans up linking sessions once the game results have been successfully processed.
- **Observables for UI Updates:** Provides an `Observable` for the current linking session, allowing components to react to session state changes.

## Architecture and Technologies

- **Angular `Injectable`:** Marks the service for dependency injection.
- **HttpClient:** Utilizes `@angular/common/http` for making API calls to the backend.
- **RxJS (`Observable`, `BehaviorSubject`):** Extensively used for asynchronous operations, managing data streams, and providing reactive updates to components.
- **TypeScript:** Ensures strong typing and improves code maintainability.
- **`ApiService` Dependency:** Relies on the `ApiService` to abstract backend API interactions, including LCU API calls.

## Key Interfaces

- `MatchLinkingSession`:
  - Represents the state of an active match linking session.
  - Key fields: `id`, `customMatchId`, `queueMatchId`, `players`, `pickBanResult`, `gameStarted`, `gameEnded`, `riotGameId`, `linkedAt`, `completedAt`.
- `PostGameLinking`:
  - Defines the structure of data sent to the backend after a game is completed and linked.
  - Key fields: `queueMatchId`, `riotGameId`, `playerResults`, `winner`, `duration`, `success`.

## Key Properties

- `private currentSession$ = new BehaviorSubject<MatchLinkingSession | null>(null)`: An observable that emits the current active linking session.
- `private activeSessions: Map<string, MatchLinkingSession>`: A map to store and manage multiple active linking sessions by their ID.

## Key Methods

### Session Management

- `createLinkingSession(queueMatch: any, customMatchId: string): MatchLinkingSession`:
  - Creates a new linking session when a custom match is initiated from the queue.
  - Stores the session locally and sends it to the backend for persistence.
- `updateWithPickBan(sessionId: string, pickBanResult: any): void`:
  - Updates an active session with the results of the pick/ban phase.
- `markGameStarted(sessionId: string, riotGameId?: string): void`:
  - Marks a session as `gameStarted` when the linked game begins in League of Legends.
  - Optionally updates with the Riot Game ID and starts monitoring for game completion.
- `markGameCompleted(sessionId: string, gameResults: any): void`:
  - Marks a session as `gameEnded` and triggers the post-game linking process.
- `private cleanupSession(sessionId: string): void`:
  - Removes a session from `activeSessions` after its results are processed.

### LCU Interaction & Game Monitoring

- `private startGameMonitoring(sessionId: string): void`:
  - Initiates an interval to periodically check the LCU's current game phase.
  - Clears the interval when the game ends or after a timeout.
- `private captureGameResults(sessionId: string): void`:
  - Retrieves the most recent match from LCU history and attempts to link it to the current session.
- `private isOurMatch(session: MatchLinkingSession, matchData: any): boolean`:
  - Determines if a given LCU match data corresponds to the current linking session by comparing player IDs (at least 80% match).

### Data Processing

- `private processPostGameLinking(session: MatchLinkingSession, gameResults: any): void`:
  - Prepares the extracted `gameResults` for submission to the backend.
- `private extractPlayerResults(session: MatchLinkingSession, gameResults: any): any[]`:
  - Extracts relevant player-specific results (kills, deaths, assists, items, gold, damage, etc.) from the LCU game data.
  - Handles cases where a player might not be found in the game (e.g., dodged).
- `private determineWinner(session: MatchLinkingSession, gameResults: any): number`:
  - Determines the winning team from the LCU game results.

### Backend Communication (Internal)

- `private saveLinkingSession(session: MatchLinkingSession): Observable<any>`: Makes an API call to save a new linking session.
- `private updateLinkingSession(session: MatchLinkingSession): Observable<any>`: Makes an API call to update an existing linking session.
- `private linkPostGameResults(postGameData: PostGameLinking): Observable<any>`: Makes an API call to send post-game results to the backend for final processing and saving.

### Public Accessors

- `getCurrentSession(): Observable<MatchLinkingSession | null>`: Returns an observable of the current session.
- `getActiveSession(): MatchLinkingSession | null`: Returns the current active session synchronously.
- `getLinkedMatches(playerId: string): Observable<any>`: Fetches linked matches for a specific player from the backend.
- `getLinkingStats(): Observable<any>`: Fetches linking statistics from the backend.

## Usage

This service is typically used by components involved in the matchmaking and game progression flow. For example, a dashboard component might subscribe to `getCurrentSession()` to display the status of an ongoing linked match.

```typescript
import { Component, OnInit, OnDestroy } from '@angular/core';
import { MatchLinkingService, MatchLinkingSession } from '../../services/match-linking';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-match-status',
  template: `<div *ngIf="currentMatchSession">Match ID: {{ currentMatchSession.customMatchId }} - Game Started: {{ currentMatchSession.gameStarted }}</div>`,
})
export class MatchStatusComponent implements OnInit, OnDestroy {
  currentMatchSession: MatchLinkingSession | null = null;
  private sessionSubscription!: Subscription;

  constructor(private matchLinkingService: MatchLinkingService) {}

  ngOnInit() {
    this.sessionSubscription = this.matchLinkingService.getCurrentSession().subscribe(session => {
      this.currentMatchSession = session;
      console.log('Current Linking Session:', session);
    });
  }

  ngOnDestroy() {
    this.sessionSubscription.unsubscribe();
  }

  // Example of how a component might trigger a session update (e.g., after pick/ban)
  updatePickBan(sessionId: string, pickBanData: any) {
    this.matchLinkingService.updateWithPickBan(sessionId, pickBanData);
  }
}
```

## Considerations

- **Robustness of LCU Integration:** The `startGameMonitoring` and `captureGameResults` methods rely on the LCU API, which can be unstable or have rate limits. Robust error handling and retry mechanisms are crucial.
- **Player Matching Logic:** The `isOurMatch` function uses an 80% player match threshold. This might need fine-tuning based on observed data to minimize false positives/negatives.
- **Scalability:** For a very high volume of concurrent custom matches, the polling mechanism for LCU status might need optimization or a more event-driven approach if the LCU API supports it.
- **Error Reporting:** Enhance error reporting to the user if linking fails or if game results cannot be retrieved.
- **Security:** Ensure that data exchanged with the backend for linking sessions is validated to prevent integrity issues.
