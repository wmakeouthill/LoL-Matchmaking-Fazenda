# Discord Integration Service

The `DiscordIntegrationService` (`spring-backend/frontend/src/app/services/discord-integration.service.ts`) is an Angular service responsible for managing the real-time communication between the frontend application and the backend's Discord bot functionalities, primarily through WebSockets. It handles connection lifecycle, presence updates, and specific Discord-related actions like joining/leaving queues.

## Responsibilities

- **WebSocket Connection Management:** Establishes and maintains a WebSocket connection to the backend, including robust reconnection logic, heartbeat mechanisms, and connection timeouts.
- **Discord User Presence:** Tracks the status of Discord users (e.g., who is online or in a specific channel).
- **Real-time Updates:** Processes messages received from the backend's Discord bot, updating the frontend with relevant information (e.g., queue status, match found notifications).
- **Queue Interaction (Frontend Triggered):** Provides methods for the frontend to initiate actions related to Discord queues, such as joining or leaving.
- **LCU Data Submission:** Can send League of Legends Client Update (LCU) data to the backend via WebSocket for player identification.
- **Observables for UI Updates:** Exposes `BehaviorSubject` and `Subject` to allow Angular components to react to changes in connection status and lists of online Discord users.

## Architecture and Technologies

- **Angular `Injectable`:** Marks the service as injectable, allowing it to be provided across the Angular application.
- **WebSockets API:** Directly uses the browser's native `WebSocket` API for low-level, high-performance real-time communication.
- **RxJS (`BehaviorSubject`, `Observable`, `Subject`):** Leveraged for reactive programming to manage and emit asynchronous data streams (e.g., connection status, user lists).
- **TypeScript:** Ensures strong typing for messages and data structures.
- **`ApiService` Dependency:** Relies on `ApiService` to obtain the base URL for WebSocket connections, promoting configuration consistency.

## Key Properties

- `private ws?: WebSocket`: The WebSocket instance for communication.
- `private isBackendConnected: boolean`: Current connection status to the backend WebSocket.
- `private discordUsersOnline: any[]`: Array of currently online Discord users.
- `private currentDiscordUser: any`: Information about the currently identified Discord user.
- `private isInDiscordChannel: boolean`: Indicates if the bot is active in a Discord channel.
- `private usersSubject: BehaviorSubject<any[]>`: Emits updates to the list of online Discord users.
- `private connectionSubject: BehaviorSubject<boolean>`: Emits updates on the WebSocket connection status.
- **Reconnection Logic Variables:** `reconnectAttempts`, `MAX_RECONNECT_ATTEMPTS`, `INITIAL_RECONNECT_DELAY`, `MAX_RECONNECT_DELAY`, `reconnectTimeout`, `heartbeatInterval`, `HEARTBEAT_INTERVAL`, `lastHeartbeat`, `connectionTimeout`, `CONNECTION_TIMEOUT`, `autoUpdateInterval`, `AUTO_UPDATE_INTERVAL`, `lastAutoUpdate` - manage the robust reconnection and heartbeat system.

## Key Methods

### Connection Management

- `private connectToWebSocket()`: Initiates or re-establishes the WebSocket connection. Includes robust error handling, connection timeouts, and reconnection scheduling.
- `private scheduleReconnect()`: Schedules a reconnection attempt with exponential backoff if the connection is lost.
- `private startHeartbeat()` / `private stopHeartbeat()`: Manages sending `ping` messages and expecting `pong` replies to keep the WebSocket connection alive and detect disconnections.
- `private clearReconnectTimeout()` / `private clearConnectionTimeout()`: Utility methods to clear timeouts.

### Message Handling

- `private handleBotMessage(data: any)`: Processes incoming WebSocket messages from the backend, dispatching actions based on the `data.type`.
- `requestDiscordStatus()`: Sends a request to the backend to get the current status of the Discord bot and connected users.
- `requestChannelStatus()`: Sends a request to the backend to get the Discord channel status.

### User & Queue Interaction

- `joinDiscordQueue(primaryLane: string, secondaryLane: string, username: string, lcuData?: { gameName: string, tagLine: string })`: Sends a message to the backend to join the Discord queue.
- `leaveDiscordQueue()`: Sends a message to the backend to leave the Discord queue.
- `sendLCUData(lcuData: {}): boolean`: Sends LCU (League Client Update) data to the backend for player identification, often used after login.

### Status & Observables

- `sendWebSocketMessage(message: any): boolean`: Sends a generic JSON message over the WebSocket.
- `isConnected(): boolean`: Checks if the WebSocket is in an `OPEN` state.
- `isDiscordBackendConnected(): boolean`: Returns the `isBackendConnected` status.
- `isInChannel(): boolean`: Returns the `isInDiscordChannel` status.
- `getCurrentDiscordUser(): any`: Returns the `currentDiscordUser` object.
- `getDiscordUsersOnline(): any[]`: Returns the `discordUsersOnline` array.
- `onUsersUpdate(): Observable<any[]>`: Returns an observable for changes in the `discordUsersOnline` list.
- `onConnectionChange(): Observable<boolean>`: Returns an observable for changes in the WebSocket connection status.

### Lifecycle

- `forceReconnect()`: Manually triggers a WebSocket reconnection.
- `ngOnDestroy()`: Cleans up intervals and timeouts when the service is destroyed to prevent memory leaks.

## Usage

Components requiring Discord integration, real-time updates from the Discord bot, or needing to interact with Discord queues will inject and use this service. It abstracts away the complexities of WebSocket communication.

```typescript
import { Component, OnInit, OnDestroy } from '@angular/core';
import { DiscordIntegrationService } from '../../services/discord-integration.service';
import { Subscription } from 'rxjs';

@Component({
  selector: 'app-discord-status',
  templateUrl: './discord-status.html',
  styleUrls: ['./discord-status.scss']
})
export class DiscordStatusComponent implements OnInit, OnDestroy {
  isConnectedToDiscord = false;
  onlineUsers: any[] = [];
  private connectionSubscription!: Subscription;
  private usersSubscription!: Subscription;

  constructor(private discordService: DiscordIntegrationService) {}

  ngOnInit() {
    this.connectionSubscription = this.discordService.onConnectionChange().subscribe(status => {
      this.isConnectedToDiscord = status;
      console.log(`Discord Backend Connected: ${status}`);
    });

    this.usersSubscription = this.discordService.onUsersUpdate().subscribe(users => {
      this.onlineUsers = users;
      console.log(`Online Discord Users: ${users.length}`);
    });

    // Optionally force a connection check or status request on init
    this.discordService.checkConnection();
    this.discordService.requestDiscordStatus();
  }

  joinQueue() {
    this.discordService.joinDiscordQueue('mid', 'top', 'MySummonerName');
  }

  leaveQueue() {
    this.discordService.leaveDiscordQueue();
  }

  ngOnDestroy() {
    this.connectionSubscription.unsubscribe();
    this.usersSubscription.unsubscribe();
  }
}
```

## Considerations

- **Responsibility Separation:** While this service manages WebSocket communication for Discord, ensure that core matchmaking queue logic (not specifically tied to Discord) resides in `MatchmakingService` on the backend and is consumed appropriately by the frontend (e.g., via `ApiService`). The comments in the code indicate a good separation has already been attempted.
- **Error Handling:** Robust error handling is implemented for WebSocket connection issues, including retries. Ensure that specific message parsing errors are also handled gracefully.
- **Scalability:** For a very large number of concurrent users, the backend's WebSocket server and its handling of Discord messages would need further optimization.
- **Security:** Ensure that data sent via WebSockets is appropriately sanitized and validated on both frontend and backend to prevent injection attacks or malicious data.
