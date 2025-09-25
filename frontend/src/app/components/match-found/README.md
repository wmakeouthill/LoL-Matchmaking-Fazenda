# `MatchFoundComponent` Documentation

## Overview

The `MatchFoundComponent` is a crucial UI element displayed to the user when a match is found in the matchmaking system. It provides a detailed overview of the two teams (blue and red), including player information (summoner name, MMR, assigned lane, preferred lanes, profile icon) and team-wide statistics (average MMR, MMR difference). The component also includes a countdown timer for match acceptance/decline, enabling users to confirm their participation in the found match.

## Structure

This component is composed of three main files:

* **`match-found.ts` (Component Logic)**: Contains the TypeScript code that manages the component's state, fetches and processes match data, handles the acceptance countdown, and interacts with external services (e.g., `ProfileIconService`). It defines the `MatchFoundData` and `PlayerInfo` interfaces for data structuring.
* **`match-found.html` (Template)**: Defines the visual layout of the match found modal. It uses Angular directives (`*ngIf`, `*ngFor`) to dynamically render team and player information. It also includes the UI for the acceptance timer and action buttons.
* **`match-found.scss` (Styles)**: Provides the styling for the component, ensuring a visually appealing and responsive design. It defines layouts, colors, typography, animations, and media queries for different screen sizes.

## Functionality

1. **Match Data Display**: Presents a clear breakdown of the blue and red teams, showing each player's summoner name, MMR, profile icon, assigned lane, and preferred lanes. It highlights the current user's player card.
2. **Team Balancing Information**: Displays the average MMR for both teams and calculates the MMR difference, providing a qualitative rating (Excellent, Good, Fair, Unbalanced) to indicate match balance.
3. **Match Acceptance Timer**: Initiates a countdown timer, giving the user a limited time to accept or decline the match. The timer's visual appearance changes to `urgent` when time is running low.
4. **Accept/Decline Actions**: Provides distinct buttons for accepting and declining the match. Accepting the match proceeds to the next phase (e.g., draft), while declining removes the user from the queue.
5. **Lane and Icon Helpers**: Includes utility methods to translate lane shortcodes (e.g., 'top', 'mid') into display names (e.g., 'Topo', 'Meio') and retrieve corresponding icons.
6. **Responsive Design**: The layout adapts to different screen sizes, ensuring usability on both desktop and mobile devices.

## Key Interfaces

* **`MatchFoundData`**: Defines the structure of the match information received, including `matchId`, `playerSide`, `teammates`, `enemies`, `averageMMR`, `estimatedGameDuration`, `phase` (accept, draft, in_game), and timer-related properties.
* **`PlayerInfo`**: Defines the details for each player, such as `id`, `summonerName`, `mmr`, `primaryLane`, `secondaryLane`, `assignedLane`, `isAutofill`, `riotIdGameName`, `riotIdTagline`, and `profileIconId`.

## Styling Details (`match-found.scss`)

* **Theming**: Utilizes a dark, futuristic theme with blue and red accents for team differentiation, consistent with the League of Legends aesthetic.
* **Layout**: Employs CSS Grid for the main team containers and flexbox for internal player card layouts, ensuring a clean and organized presentation.
* **Animations**: Includes `fadeIn`, `slideIn`, `timerPulse`, `borderRotate`, and `textBlink` animations to provide dynamic visual feedback for the modal appearance and the countdown timer.
* **Responsiveness**: Uses media queries to adjust the layout for screens smaller than 768px and 480px, optimizing for mobile viewing by stacking team sections and simplifying player card information.

## Dependencies

* `CommonModule` from `@angular/common` for common directives.
* `ProfileIconService` (from `../../services/profile-icon.service`) for retrieving player profile icons.
* `Observable` from `rxjs` for handling asynchronous data streams, particularly for profile icons.

## Potential Improvements

* **Error State UI**: Enhance the UI to clearly communicate and handle error states during data fetching.
* **Backend Communication Robustness**: Implement more robust retry mechanisms or feedback loops for handling cases where match acceptance/decline requests fail.
* **Animations Refinement**: Further refine the animations for a smoother and more polished user experience.
* **Team Composition Analysis**: Potentially add more detailed analysis of team compositions (e.g., CC, engage, poke) to aid players in understanding the match.
