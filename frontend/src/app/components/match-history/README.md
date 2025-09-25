# `MatchHistoryComponent` Documentation

## Overview

The `MatchHistoryComponent` is responsible for displaying a comprehensive history of a player's League of Legends matches, distinguishing between matches played through the Riot API (ranked/normal games) and custom matches. It provides detailed statistics for each match, including KDA, items, gold earned, and offers summary statistics for the player's overall performance in the selected tab.

## Structure

The component is composed of three main files:

* **`match-history.ts` (Component Logic)**: This TypeScript file manages the component's state, handles data fetching from the `ApiService` (for both Riot and custom matches), processes match data, calculates various statistics (win rate, KDA, win streak, champion played), and manages the display of match details. It uses a Strategy Pattern to handle data loading and statistics calculation for different match types (Riot vs. Custom).
* **`match-history.html` (Template)**: Defines the HTML structure for the match history view. It includes tabs for switching between Riot and Custom matches, sections for summary statistics, a list of individual matches (expandable for more details), and displays for loading/error/empty states. It dynamically renders match data and detailed participant information.
* **`match-history.scss` (Styles)**: Provides the styling for the component, featuring a dark, League of Legends-themed design. It includes styles for the tab system, match list, individual match items (win/loss indicators), champion and item displays, and detailed team compositions. It incorporates animations and responsive adjustments for various screen sizes.

## Functionality

1. **Tabbed Navigation**: Allows users to switch between viewing their Riot API match history (ranked/normal games) and custom match history.
2. **Summary Statistics**: Displays aggregated statistics for the active tab, including total matches, win rate, current win streak, longest win streak, and total LP/MMR gained/lost.
3. **Match List Display**: Lists individual matches, showing key information such as game mode, duration, player's champion, KDA, items, and LP/MMR change (for custom matches).
4. **Expandable Match Details**: Each match item can be expanded to reveal more in-depth information, including:
    * Team compositions by lane (Top, Jungle, Mid, ADC, Support).
    * Detailed statistics for each participant (KDA, items, gold, damage, vision score).
    * Winner indication for each team.
5. **Lane Detection**: Implements logic to detect and display the assigned lane for each participant, even in cases where explicit lane data might be missing (e.g., ARAM, custom games).
6. **Current Game Monitoring**: Continuously checks if the player is currently in a League of Legends game and displays a status if so.
7. **Loading and Error States**: Provides clear visual feedback during data loading and displays informative error messages if data fetching fails.
8. **Responsive Design**: The layout dynamically adjusts to ensure optimal usability on different screen sizes.

## Key Interfaces & Services

* **`Player`, `Match` (from `../../interfaces`)**: Define the data structures for the current player and individual match records, respectively.
* **`ApiService`**: Used to fetch match history data from the backend, including both Riot API and custom match data.
* **`ChampionService`**: Utilized for retrieving champion names and images based on their IDs or names.
* **`Subject`, `Subscription`, `interval` (from `rxjs`)**: Used for managing asynchronous operations and setting up periodic data refreshes.

## Styling Details (`match-history.scss`)

* **Theming**: Employs a dark background with gold/brown accents (`#c89b3c`, `#f0e6d2`) and vibrant greens/reds for win/loss indicators, consistent with the League of Legends aesthetic.
* **Layout**: Uses CSS Grid for the overall layout, summary stats, and match items, providing a structured and responsive design. Flexbox is used for internal elements within cards and sections.
* **Animations**: Includes `fadeIn`, `slideIn`, `expandIn`, `pulse`, `glow`, and `spin` animations to enhance visual engagement for loading states, expanded details, and special badges (e.g., Penta Kill).
* **Tab System**: Styles distinct active and inactive states for tabs, providing clear visual cues for the selected match history type.
* **Match Item Design**: Features visually distinct styles for winning and losing matches, including left-border color indicators and background gradients. Icons and images for champions and items are styled for clarity and visual appeal.
* **Responsive Adjustments**: Extensive use of media queries to adapt the layout for smaller screens, ensuring readability and usability on mobile devices.

## Potential Improvements

* **Filtering and Sorting**: Implement advanced filtering options (e.g., by champion, game mode, date range) and sorting for match lists.
* **Performance Metrics**: Add more detailed performance metrics and visualizations (e.g., damage graphs, warding heatmaps) for individual matches.
* **Share Functionality**: Allow users to share specific match details or overall statistics.
* **Search Functionality**: Enable searching for specific matches or players within the match history.
* **Error Handling Refinement**: Provide more granular error messages and recovery options for various API call failures.
