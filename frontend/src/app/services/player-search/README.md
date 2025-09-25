# Player Search Service

The `PlayerSearchService` (`spring-backend/frontend/src/app/services/player-search.ts`) is an Angular service responsible for handling all player lookup and data refreshing operations in the frontend application. It interacts with the backend API to fetch player details using various identifiers (Riot ID, PUUID, Summoner Name) and integrates with the League of Legends Client Update (LCU) API for automatic player detection.

## Responsibilities

- **Player Data Retrieval:** Provides methods to search for players using their Riot ID (GameName#TagLine), PUUID, or legacy Summoner Name.
- **LCU Integration:** Automatically fetches current player details directly from the connected League of Legends client.
- **Intelligent Search:** Offers a `smartSearch` method that automatically determines the type of player identifier provided and dispatches the appropriate search method.
- **Player Data Refresh:** Allows for explicit refreshing of a player's data from the Riot API via the backend.
- **Data Mapping and Transformation:** Maps raw API responses into a consistent `Player` interface, including calculating an estimated MMR and extracting ranked data.
- **Error Handling:** Implements centralized error handling for API calls, providing user-friendly messages.
- **Input Validation:** Includes utility methods for validating Riot ID and PUUID formats and suggesting correct formats to the user.

## Architecture and Technologies

- **Angular `Injectable`:** Marks the service for dependency injection.
- **HttpClient:** Uses `@angular/common/http` for making API calls to the backend.
- **RxJS (`Observable`, `throwError`, `catchError`, `retry`, `map`):** Heavily utilized for asynchronous operations, error propagation, retries, and data transformation.
- **TypeScript:** Ensures strong typing for player data structures (`Player`, `RefreshPlayerResponse`) and service methods.
- **`ApiService` Dependency:** Relies on `ApiService` to obtain the base URL for backend API calls, maintaining configuration consistency.

## Key Interfaces

- `Player` (from `../interfaces.ts`): Represents a player's detailed profile, including Riot ID, PUUID, summoner level, region, MMR, ranked data (solo queue, flex queue), wins, losses, etc. (defined in `spring-backend/frontend/src/app/interfaces.ts`).
- `RefreshPlayerResponse` (from `../interfaces.ts`): Defines the structure of the response when refreshing player data, typically including `success`, `player` object, and a `message`.

## Key Properties

- `private baseUrl: string`: The base URL for backend API requests, obtained from `ApiService`.

## Key Methods

### Player Search

- `searchByRiotId(riotId: string, region: string = 'br1'): Observable<Player>`:
  - Searches for a player using their new Riot ID format (GameName#TagLine).
  - Validates the Riot ID format before making the API call.
- `searchFromLCU(): Observable<Player>`:
  - Attempts to retrieve the currently logged-in player's details directly from the League of Legends client via the backend.
- `smartSearch(identifier: string, region: string = 'br1'): Observable<Player>`:
  - An intelligent search method that automatically detects if the `identifier` is a Riot ID, PUUID, or legacy Summoner Name and calls the appropriate search function.
- `searchByPuuid(puuid: string, region: string): Observable<Player>`:
  - Searches for a player using their PUUID.
- `searchBySummonerName(summonerName: string, region: string): Observable<Player>`:
  - Searches for a player using their legacy Summoner Name (less preferred due to Riot's ID changes).

### Player Data Updates

- `refreshPlayerData(riotId: string, region: string): Observable<RefreshPlayerResponse>`:
  - Sends a request to the backend to refresh a player's data from the Riot API, updating their profile in the application.

### Utility Methods

- `private handleError(error: HttpErrorResponse)`:
  - Centralized error handling for HTTP requests, logs the error, and re-throws a user-friendly `Error` object.
- `private mapApiResponseToPlayer(data: any): Player`:
  - Transforms raw API response data into the standardized `Player` interface.
  - Prioritizes Riot API data over LCU data for consistency.
  - Calculates an estimated `currentMMR` based on ranked tier, rank, and League Points (LP).
- `private calculateMMRFromData(data: any): number`:
  - Calculates a numerical MMR approximation based on the player's ranked tier, division, and LP.
- `private extractRankData(data: any): any`:
  - Extracts and formats ranked queue information (tier, rank, LP, wins, losses).
- `isValidRiotId(riotId: string): boolean`:
  - Validates the format of a Riot ID (e.g., `GameName#TagLine`).
- `isValidPuuid(puuid: string): boolean`:
  - Validates the format of a PUUID.
- `suggestFormat(input: string): string`:
  - Provides user-friendly suggestions for correct identifier formats based on the input.

## Usage

This service is primarily used by UI components that allow users to search for, view, and manage player profiles, such as player lookup forms, profile pages, or matchmaking setup screens.

```typescript
import { Component, OnInit } from '@angular/core';
import { PlayerSearchService } from '../../services/player-search';
import { Player } from '../../interfaces';

@Component({
  selector: 'app-player-search',
  template: `
    <input [(ngModel)]="searchIdentifier" placeholder="NomeDoJogo#TAG, PUUID ou Summoner Name">
    <button (click)="searchPlayer()">Buscar Jogador</button>
    <div *ngIf="player">{{ player.summonerName }} (MMR: {{ player.currentMMR }})</div>
    <div *ngIf="error">Erro: {{ error }}</div>
  `,
})
export class PlayerSearchComponent implements OnInit {
  searchIdentifier: string = '';
  player: Player | null = null;
  error: string | null = null;

  constructor(private playerSearchService: PlayerSearchService) {}

  ngOnInit() {
    // Optionally search for current LCU player on init
    this.playerSearchService.searchFromLCU().subscribe({
      next: (player) => { this.player = player; this.error = null; },
      error: (err) => { this.error = err.message; this.player = null; }
    });
  }

  searchPlayer() {
    this.error = null;
    this.playerSearchService.smartSearch(this.searchIdentifier, 'br1').subscribe({
      next: (player) => { this.player = player; },
      error: (err) => { this.error = err.message; this.player = null; }
    });
  }
}
```

## Considerations

- **Riot ID Transition:** The service handles both new Riot IDs and legacy Summoner Names. As Riot phases out Summoner Names, the reliance on `searchBySummonerName` should diminish.
- **MMR Estimation:** The `calculateMMRFromData` method provides an *estimated* MMR. Riot's actual MMR is private. This estimation should be clearly communicated to users if displayed.
- **Rate Limiting:** Backend API calls (especially to Riot API via the backend) are subject to rate limits. The `retry` operator in RxJS helps with transient errors, but sustained high volume might require more advanced rate limiting strategies.
- **Error Messages:** Ensure error messages from `handleError` are user-friendly and actionable.
- **Data Consistency:** The service plays a role in keeping player data (like MMR) up-to-date. Regular refreshing or event-driven updates could be considered for long-lived sessions.
