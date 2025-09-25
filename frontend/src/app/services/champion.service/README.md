# Champion Service

The `ChampionService` in the frontend application is responsible for managing and providing access to League of Legends champion data. It interacts with the backend to fetch champion information, handles caching, and provides utility methods for filtering and searching champions.

## Responsibilities

- **Champion Data Retrieval:** Fetches a comprehensive list of champions from the backend API.
- **Data Caching:** Implements a caching mechanism to store retrieved champion data (`cachedChampions` and `cachedChampionsByRole`) to minimize redundant API calls and improve performance.
- **Fallback Mechanism:** Provides a `fallbackChampions` list to ensure basic functionality even if the backend champion data cannot be loaded.
- **Champion Organization by Role:** Organizes champions into predefined roles (top, jungle, mid, adc, support) based on their tags, facilitating role-specific selections.
- **Champion Search and Filtering:** Offers methods to search for champions by name, title, or tags, and to filter them by role.
- **Random Champion Selection:** Provides a utility to select a random champion, with an option to exclude specific champion IDs.
- **Ban/Pick Status Check:** Includes methods to check if a champion is currently banned or picked in a draft.
- **Cache Management:** Allows for clearing the cached champion data.

## Key Interfaces

- `Champion`: Defines the structure for a single champion, including `id`, `key`, `name`, `title`, `image`, `tags`, and `info` (attack, defense, magic, difficulty).
- `ChampionsByRole`: Defines an object that groups champions by their respective roles (top, jungle, mid, adc, support), and also includes an `all` category.

## Key Methods

- `static getChampionNameById(championId: number | undefined): string`:
  - **Purpose:** Retrieves a champion's name by their ID. Primarily serves as a fallback, as champion data is expected to be processed by the backend.
  - **Note:** Returns 'Minion' if the ID is unknown or undefined, or if the backend is unavailable.

- `getAllChampions(): Observable<Champion[]>`:
  - **Purpose:** Fetches all champions, utilizing a cache. If the cache is empty, it makes an API call to the backend. Includes fallback to `fallbackChampions` on error.

- `getChampionsByRole(): Observable<ChampionsByRole>`:
  - **Purpose:** Retrieves champions organized by their roles, also using a cache. Falls back to `createFallbackChampionsByRole()` on error.

- `private createFallbackChampionsByRole(): ChampionsByRole`:
  - **Purpose:** Internally used to create a role-organized list of champions from the `fallbackChampions` based on a predefined `roleMapping`.

- `searchChampions(query: string, role?: string): Observable<Champion[]>`:
  - **Purpose:** Filters the list of champions based on a search query (name, title, tags) and an optional role.

- `getRandomChampion(excludeIds: string[] = []): Observable<Champion>`:
  - **Purpose:** Returns a randomly selected champion from the available list, excluding those specified by `excludeIds`.

- `isChampionBanned(championId: string, bannedChampions: Champion[]): boolean`:
  - **Purpose:** Checks if a given champion ID exists in a list of banned champions.

- `isChampionPicked(championId: string, pickedChampions: Champion[]): boolean`:
  - **Purpose:** Checks if a given champion ID exists in a list of picked champions.

- `clearCache(): void`:
  - **Purpose:** Resets the cached champion data, forcing a fresh load on the next request.

## Dependencies

- `HttpClient` (from `@angular/common/http`): Used for making HTTP requests to the backend API.
- `Observable`, `of` (from `rxjs`): For asynchronous operations and returning observable streams.
- `catchError`, `map` (from `rxjs/operators`): For error handling and data transformation in observable pipelines.
- `ApiService` (from `./api`): Utilized to get the base URL for API calls.

## Technologies

- **Angular:** The service is an `@Injectable()` Angular service.
- **TypeScript:** Written in TypeScript for strong typing and improved code maintainability.
- **RxJS:** Extensively uses RxJS for reactive programming, managing asynchronous data flows and error handling.
