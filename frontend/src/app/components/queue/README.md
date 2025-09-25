# `QueueComponent` Documentation

## Overview

The `QueueComponent` is the central hub for the matchmaking queue system within the application. It displays real-time queue status, player information, and provides functionalities for joining and leaving the queue, as well as integrating with Discord for enhanced social features. It aims to provide a comprehensive and engaging user experience during the waiting phase for a match.

## Structure

The component is built using Angular and comprises three main files:

* **`queue.ts` (Component Logic)**: Manages the component's state, handles user interactions, integrates with various services (Discord, API, Queue State, Profile Icon), and contains the core logic for the queue timer, player data processing, and display utilities. It implements `OnInit`, `OnDestroy`, and `OnChanges` lifecycle hooks for robust state management.
* **`queue.html` (Template)**: Defines the user interface for the queue. It dynamically displays queue statistics, current player details, a list of players in the queue, and Discord users. It includes sections for joining/leaving the queue, a lane selector, and tabs to switch between queue and Discord lobby views.
* **`queue.scss` (Styles)**: Provides the styling for the component. It uses a dark theme with vibrant accents, responsive design principles (media queries), and a variety of animations to enhance the user experience.

## Functionality

1. **Queue Status Display**: Shows the current number of players in the queue, estimated wait time, and overall system status (online/offline).
2. **Current Player Information**: Displays the logged-in player's profile icon, summoner level, MMR, rank, wins/losses, and their selected lane preferences.
3. **Queue Management**: Allows users to join the queue by selecting their primary and secondary lane preferences. Once in the queue, it displays a countdown timer. Users can also leave the queue.
4. **Discord Integration**: Displays online Discord users in connected voice channels, indicating if they have the application open and if their League of Legends account is linked. It provides options to invite users to link their accounts or join the queue.
5. **Dynamic Player List**: Shows a real-time list of players currently in the queue, including their summoner name, lanes, MMR, and time spent in the queue.
6. **Auto-Refresh**: An option to automatically refresh queue data at regular intervals.
7. **Responsiveness**: Adapts its layout and elements for optimal viewing on various screen sizes, from desktop to mobile.

## Key Interfaces & Services

* **`Player`, `QueueStatus`, `QueuePreferences` (from `../../interfaces`)**: Define the data structures used throughout the component for player details, queue statistics, and user lane preferences.
* **`DiscordIntegrationService`**: Handles communication with the Discord backend, managing connection status and fetching Discord user data.
* **`QueueStateService`**: Manages the application's queue state, often syncing with backend data.
* **`ApiService`**: Used for making general API calls, likely related to queue actions.
* **`ProfileIconService`**: Responsible for fetching and displaying League of Legends profile icons.

## Styling Details (`queue.scss`)

* **Variables**: Utilizes CSS variables for consistent theming (colors, spacing, font sizes, border-radius).
* **Layout**: Employs a combination of Flexbox and CSS Grid for flexible and responsive layouts across different sections (header, stats, tables, action area).
* **Theming**: Features a dark, League of Legends-inspired theme with gold, blue, and green accents for various UI elements and statuses.
* **Animations**: Incorporates a range of animations such as `fadeInUp`, `pulse`, `spin`, `rotate`, `slideInDown`, and `bounceIn` for dynamic visual feedback on elements like the queue health indicator, refresh button, and modal entry.
* **Table Design**: Provides distinct styles for queue players and Discord lobby tables, including alternating row colors, hover effects, and clear column headers.
* **Responsive Adjustments**: Extensive use of media queries (`@media (max-width: ...px)`) to optimize the layout for smaller screens, collapsing elements and adjusting font sizes to maintain usability.

## Potential Improvements

* **Advanced Filtering/Sorting**: Implement more robust filtering and sorting options for the player lists.
* **Notifications**: Add browser notifications for match found events, even when the app is in the background.
* **Queue Customization**: Allow users to select different queue types (e.g., ARAM, Flex Queue) if applicable.
* **Backend Integration Details**: Enhance documentation on the specific API endpoints and WebSocket events that this component consumes from the backend.
* **Accessibility**: Further improve accessibility features for users with disabilities.
