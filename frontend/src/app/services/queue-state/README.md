# Queue State Service

The `QueueStateService` (`spring-backend/frontend/src/app/services/queue-state.ts`) is an Angular service responsible for providing real-time updates on the matchmaking queue status to the frontend. It acts as the single source of truth for queue-related information, synchronizing its state with the `queue_players` table in the backend database through a polling mechanism.

## Responsibilities

- **Queue Status Synchronization:** Periodically fetches the current state of the matchmaking queue from the backend's `queue_players` table.
- **Player Queue Position:** Determines and tracks the current user's position within the queue.
- **Queue Metrics Calculation:** Provides metrics such as total players in queue, estimated wait time, and average wait time.
- **Reactive State Management:** Exposes the queue state via an RxJS `BehaviorSubject`, allowing UI components to reactively update when the queue status changes.
- **Player Identification:** Builds and matches player identifiers to accurately determine if the current frontend user is in the queue.
- **Polling Management:** Controls the initiation and cessation of the polling mechanism to conserve resources when queue updates are not actively needed.

## Architecture and Technologies

- **Angular `Injectable`:** Allows the service to be injected into other Angular components and services.
- **RxJS (`BehaviorSubject`, `Observable`, `interval`):** Utilized for managing the reactive queue state, creating observable streams for updates, and implementing the polling mechanism.
- **HttpClient (via `ApiService`):** Relies on `ApiService` to make HTTP requests to the backend for fetching queue data.
- **TypeScript:** Ensures strong typing for queue-related interfaces (`QueuedPlayerInfo`, `QueueActivity`, `QueueState`) and methods.
- **Polling Mechanism:** Uses `setInterval` to periodically query the backend for queue updates, establishing the `queue_players` table as the definitive source of truth.

## Key Interfaces

- `QueuedPlayerInfo`:
  - Describes an individual player currently in the queue, including `summonerName`, `tagLine`, `primaryLane`, `secondaryLane`, `mmr`, `queuePosition`, and `joinTime`.
- `QueueActivity`:
  - Represents a significant event in the queue (e.g., player joined/left, match created), used for logging or an activity feed.
- `QueueState`:
  - The core interface for the overall queue status, including `isInQueue`, `position`, `waitTime`, `estimatedTime`, `playersInQueue`, and `averageWaitTime`.

## Key Properties

- `private queueStateSubject = new BehaviorSubject<QueueState>(...)`: The central `BehaviorSubject` that holds and emits the current queue state.
- `private pollingInterval: any = null`: Stores the `setInterval` reference for the polling loop.
- `private readonly POLLING_INTERVAL_MS = 3000`: Configures the frequency of queue status polling (3 seconds).
- `private currentPlayerData: any = null`: Holds data about the currently logged-in player, used for identifying their presence in the queue.

## Key Methods

### Synchronization & Control

- `startMySQLSync(currentPlayer?: any): void`:
  - Initializes the service, optionally providing current player data. It calls `syncQueueFromDatabase` immediately but does not automatically start continuous polling.
- `startPolling(): void`:
  - Explicitly starts the continuous polling mechanism to regularly fetch queue updates from the backend.
- `stopMySQLSync(): void`:
  - Halts the continuous polling, clearing the `pollingInterval`.
- `forceSync(): void`:
  - Triggers an immediate synchronization with the backend's queue status, also initiating a backend-side synchronization operation if available.
- `private syncQueueFromDatabase(): Promise<void>`:
  - The core method that fetches the queue status from the backend API.
  - Processes the response to update the `queueStateSubject`.
  - Determines if the current user is in the queue and their position.
  - Handles cases where the queue might be empty or the API response is null.

### Player Identification

- `private buildPlayerIdentifiers(playerData: any): string[]`:
  - Generates a list of possible identifiers (e.g., summoner name, PUUID, Riot ID) for the current player to match against queue entries.
- `private matchPlayerIdentifiers(queuePlayer: any, identifiers: string[]): boolean`:
  - Compares identifiers of a player in the queue with the current user's identifiers to determine if they are the same.

### Public Accessors & State Management

- `updateCurrentPlayer(playerData: any): void`:
  - Updates the `currentPlayerData` for use in queue identification.
- `getQueueState(): Observable<QueueState>`:
  - Returns an observable of the current queue state, allowing components to subscribe to updates.
- `getCurrentState(): QueueState`:
  - Returns the current queue state synchronously.
- `resetState(): void`:
  - Resets the queue state to its initial empty values.
- `getActiveSystem(): 'centralized' | 'none'`: Indicates if a centralized queue system is active (currently always 'centralized').
- `isInQueue(): boolean`: Convenience method to check if the current user is in the queue.
- `getQueuePosition(): number | undefined`: Returns the current user's queue position.
- `getPlayersInQueue(): number | undefined`: Returns the total number of players currently in the queue.

## Usage

This service is typically injected into Angular components that display queue status information, such as a matchmaking dashboard, a queue status bar, or a dedicated queue view. Components will subscribe to `getQueueState()` to receive real-time updates.

```typescript
import { Component, OnInit, OnDestroy } from '@angular/core';
import { QueueStateService, QueueState } from '../../services/queue-state';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-queue-status',
  template: `
    <div *ngIf="queueState.isInQueue">
      Você está na fila! Posição: {{ queueState.position }} ({{ queueState.playersInQueue }} jogadores)
    </div>
    <div *ngIf="!queueState.isInQueue">
      Não está na fila. Jogadores na fila: {{ queueState.playersInQueue || 0 }}
    </div>
    <button (click)="togglePolling()">{{ isPolling ? 'Parar Atualizações' : 'Iniciar Atualizações' }}</button>
  `,
})
export class QueueStatusComponent implements OnInit, OnDestroy {
  queueState!: QueueState;
  isPolling = false;
  private queueSubscription!: Subscription;

  constructor(private queueStateService: QueueStateService) {}

  ngOnInit() {
    this.queueSubscription = this.queueStateService.getQueueState().subscribe(state => {
      this.queueState = state;
      console.log('Current Queue State:', state);
    });
    this.queueState = this.queueStateService.getCurrentState(); // Get initial state

    // Start polling automatically if desired
    this.togglePolling();
  }

  togglePolling() {
    if (this.isPolling) {
      this.queueStateService.stopMySQLSync();
    } else {
      // Pass current player data, e.g., from an authentication service
      this.queueStateService.startMySQLSync({ displayName: 'YourSummonerName', puuid: 'your-puuid' });
      this.queueStateService.startPolling(); // Start continuous polling
    }
    this.isPolling = !this.isPolling;
  }

  ngOnDestroy() {
    this.queueSubscription.unsubscribe();
    this.queueStateService.stopMySQLSync(); // Stop polling when component is destroyed
  }
}
```

## Considerations

- **Polling vs. WebSockets:** While polling is used here for simplicity and to ensure the backend's `queue_players` table is the definitive source, a more real-time and efficient approach for queue updates could involve integrating with a WebSocket system (e.g., the `DiscordIntegrationService` or a dedicated queue WebSocket in the backend). The current approach is suitable for scenarios where near real-time is sufficient and simplifies backend architecture.
- **Backend API Reliability:** The service heavily relies on the backend's `/queue-status` endpoint. Ensure this endpoint is performant and robust.
- **Player Identification:** The `buildPlayerIdentifiers` and `matchPlayerIdentifiers` functions are critical for correctly identifying the current user in the queue. They must handle various player identifier formats consistently.
- **Error Handling:** While `syncQueueFromDatabase` handles null responses, more specific error handling for API failures should be considered (e.g., displaying error messages to the user).
- **Performance:** For very large queues or extremely frequent polling intervals, the performance impact on both frontend and backend should be monitored. Adjusting `POLLING_INTERVAL_MS` is key.
