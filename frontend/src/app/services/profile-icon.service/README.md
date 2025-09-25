# Profile Icon Service

The `ProfileIconService` (`spring-backend/frontend/src/app/services/profile-icon.service.ts`) is an Angular service dedicated to efficiently retrieving and managing League of Legends summoner profile icons. It optimizes performance by implementing a caching mechanism and provides robust fallback strategies for loading icon images.

## Responsibilities

- **Profile Icon Retrieval:** Fetches profile icon IDs from the backend API for a given summoner identifier (summoner name or PUUID).
- **Client-Side Caching:** Stores retrieved profile icon IDs in `localStorage` to minimize redundant API calls and improve loading times. Includes a versioning mechanism to invalidate old cache entries.
- **Image URL Generation:** Constructs the full URL for a profile icon image using the retrieved ID and a base URL (e.g., from CommunityDragon or Data Dragon).
- **Fallback Image Handling:** Provides a `onProfileIconError` method to handle cases where an image fails to load, attempting multiple fallback URLs (different Data Dragon versions, CommunityDragon, and a local placeholder).
- **Concurrent Fetch Management:** Prevents multiple simultaneous API requests for the same profile icon using an `ongoingFetches` map.
- **Cache Management:** Offers methods to clear the client-side profile icon cache.

## Architecture and Technologies

- **Angular `Injectable`:** Marks the service for dependency injection across the Angular application.
- **HttpClient:** Utilizes `@angular/common/http` for making API calls to the backend to fetch icon IDs.
- **RxJS (`Observable`, `of`, `BehaviorSubject`, `map`, `catchError`, `tap`, `shareReplay`):** Extensively used for asynchronous operations, managing data streams, caching observables, and reactive updates.
- **TypeScript:** Ensures strong typing for data and methods, improving code reliability.
- **`localStorage`:** Used for persistent client-side caching of profile icon IDs.
- **`ApiService` Dependency:** Relies on `ApiService` to obtain the base URL for backend API calls.

## Key Properties

- `private profileIconCache$ = new BehaviorSubject<Map<string, number>>(new Map())`: An RxJS `BehaviorSubject` that holds the in-memory cache of profile icon IDs (mapping summoner identifier to icon ID). Components can subscribe to this for updates.
- `private profileIconsCacheKey: string`: The key used for `localStorage` to store the persistent cache.
- `private cacheVersion: string`: A version string to manage cache invalidation. Incrementing this invalidates all previously stored cache data.
- `private ongoingFetches = new Map<string, Observable<number | null>>()`: A map to track ongoing API requests for profile icons, preventing duplicate fetches.
- `private baseUrl: string`: The base URL for backend API requests, obtained from `ApiService`.

## Key Methods

### Icon Retrieval

- `getProfileIconUrl(summonerIdentifier: string): Observable<string>`:
  - The primary public method to get the full URL of a player's profile icon.
  - Internally calls `getOrFetchProfileIcon`.
- `getOrFetchProfileIcon(summonerIdentifier: string): Observable<number | null>`:
  - Checks the in-memory cache and `ongoingFetches` first.
  - If not found, calls `fetchProfileIcon`.
- `private fetchProfileIcon(summonerIdentifier: string): Observable<number | null>`:
  - Makes an HTTP GET request to the backend to retrieve the `profileIconId`.
  - On success, updates the in-memory cache and `localStorage`.
  - Handles 404 (not found) and other errors, returning `null` in case of failure.

### Cache Management

- `private loadProfileIconsCache(): void`:
  - Loads the profile icon ID cache from `localStorage` on service initialization.
  - Checks `cacheVersion` to ensure compatibility and invalidates old caches.
- `private saveProfileIconsCache(): void`:
  - Saves the current in-memory profile icon cache to `localStorage`.
- `clearCache(): void`:
  - Clears both the in-memory and `localStorage` caches, forcing a fresh fetch for all icons.

### Error Handling (Image Loading)

- `onProfileIconError(event: Event, profileIconId?: number): void`:
  - A utility method designed to be used as an `(error)` event handler on `<img>` tags.
  - Cycles through a list of fallback image URLs (CommunityDragon, various Data Dragon versions, local placeholder) when an image fails to load.

## Usage

This service is typically used in Angular components where a player's profile icon needs to be displayed dynamically. The `getProfileIconUrl` method is used to bind the image source, and `onProfileIconError` can be bound to the `(error)` event of the `<img>` tag.

```typescript
import { Component, Input, OnInit } from '@angular/core';
import { ProfileIconService } from '../../services/profile-icon.service';

@Component({
  selector: 'app-profile-icon',
  template: `<img [src]="iconUrl" (error)="profileIconService.onProfileIconError($event, defaultIconId)" alt="Profile Icon">`,
})
export class ProfileIconComponent implements OnInit {
  @Input() summonerIdentifier!: string;
  @Input() defaultIconId: number = 29; // Default icon if not found
  iconUrl: string = '';

  constructor(public profileIconService: ProfileIconService) {}

  ngOnInit() {
    this.profileIconService.getProfileIconUrl(this.summonerIdentifier).subscribe(url => {
      this.iconUrl = url;
    });
  }
}
```

## Considerations

- **Backend Dependency:** The service relies on a backend endpoint (`/summoner/profile-icon/`) to get the actual `profileIconId`. Ensure this endpoint is robust and correctly integrated.
- **Data Dragon Versions:** The fallback URLs for Data Dragon are hardcoded with specific versions. An improvement could be to dynamically fetch the latest Data Dragon version from the backend or a reliable public source.
- **Cache Management:** The `cacheVersion` mechanism is simple but effective for invalidation. For more granular control or larger datasets, consider a more sophisticated cache eviction policy (e.g., LRU).
- **Community Dragon:** The use of `raw.communitydragon.org` is a good fallback, but ensure its reliability and terms of use are compatible with the project.
- **Performance:** `shareReplay(1)` and `localStorage` caching significantly improve performance by reducing network requests. For very frequent updates or massive user bases, consider WebSockets for real-time icon updates.
