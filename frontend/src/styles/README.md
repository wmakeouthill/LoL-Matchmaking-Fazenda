# Global Stylesheet

The `styles.scss` file (`spring-backend/frontend/src/styles.scss`) is the main global stylesheet for the League of Legends Matchmaking application. It defines the overall visual theme, typography, color palette, spacing, and common UI element styles that are applied across the entire frontend application. This approach ensures a consistent look and feel throughout the user interface.

## Purpose

- **Global Styling:** Establishes default styles for HTML elements (`html`, `body`, `*`) and defines a robust system of CSS variables for theming.
- **Theming:** Centralizes color palettes, gradients, shadows, spacing, and border radii, allowing for easy modification of the application's visual theme.
- **Reusable Components Styling:** Provides base styles for common UI components like buttons and cards, ensuring consistency wherever these elements are used.
- **Responsive Design Base:** Lays the groundwork for responsive design, although specific media queries might be defined within individual component stylesheets or `app.scss`.
- **Typography:** Sets the default font family and line height for the application.

## Structure and Contents

The `styles.scss` file is organized using CSS variables and global selectors:

### 1. Root Variables (`:root`)

- **Color Palette:** Defines a comprehensive set of colors, including primary brand colors (`--primary-blue`, `--primary-gold`), background colors (`--bg-primary`, `--bg-secondary`, `--bg-card`), text colors (`--text-primary`, `--text-secondary`), border colors, and status colors (`--success`, `--warning`, `--error`, `--info`).
- **League Rank Colors:** Specific colors for League of Legends ranks (e.g., `--iron`, `--bronze`, `--challenger`) for consistent display of player ranks.
- **Gradients:** Defines various linear and radial gradients (`--gradient-primary`, `--gradient-card`, `--gradient-header`) used for backgrounds and text effects.
- **Shadows:** Standardized box-shadow values (`--shadow-sm`, `--shadow-md`, `--shadow-glow`) for consistent depth and visual hierarchy.
- **Spacing:** A set of incremental spacing units (`--spacing-xs` to `--spacing-xxl`) to ensure consistent padding and margins throughout the UI.
- **Border Radius:** Defines common border radius values (`--radius-sm` to `--radius-xl`) for consistent rounded corners.
- **Transitions:** Standardized transition durations and easing functions (`--transition-fast`, `--transition-normal`, `--transition-slow`) for smooth UI animations.

### 2. Global Resets and Base Styles

- **Universal Selector (`*`):** Applies `margin: 0`, `padding: 0`, and `box-sizing: border-box` to all elements, ensuring consistent box model behavior.
- **HTML and Body:** Sets `height: 100%`, `width: 100%`, default `font-family` (Inter with fallbacks), `background` (using gradients and radial effects), `color`, and `overflow: hidden` to control global scrolling.
- **`#app` Element:** Styles the main application container (`#app`) to take full viewport height and width, set as a flex column.

### 3. Scrollbar Styling

- Customizes the appearance of scrollbars for WebKit browsers (Chrome, Edge, Safari) to match the application's theme, using defined CSS variables for track and thumb colors.

### 4. Global Button Styles (`.btn`)

- Defines a base style for all buttons, including `display`, `padding`, `border`, `border-radius`, `font`, `cursor`, and `transition`.
- Implements a subtle hover animation with a shimmering effect (`::before` pseudo-element).
- Provides modifier classes for different button types (`.btn-primary`, `.btn-secondary`, `.btn-success`, `.btn-danger`) with distinct background, text, and border colors. Also includes styles for disabled states.

### 5. Global Card Styles (`.card`)

- Defines a base style for card-like UI elements, applying background gradients, borders, border radius, padding, and box shadows. Includes a hover effect with transform and shadow changes.

### 6. Utility Classes

- Simple utility classes like `text-center`, `text-left`, `text-right`, and `flex` for common layout and text alignment needs.

### 7. Specific Overrides (e.g., `.queue-action`)

- Contains specific, sometimes `!important`, overrides for elements within certain contexts (like queue action buttons) to ensure layout and alignment are as intended.

## Usage

This stylesheet is typically imported once in the main `angular.json` configuration or directly into the root `main.ts` or `app.ts` file of the Angular application. It provides global styles that cascade down to all components.

```scss
/* Example of how it might be imported */
@import './styles.scss';

// Or in angular.json
"styles": [
  "src/styles.scss"
],
```

## Considerations

- **Global Scope:** Styles defined here are global. Care should be taken to avoid over-specifying styles that might unintentionally affect unrelated components.
- **CSS Variables:** The extensive use of CSS variables is an excellent practice for maintainability and theming. Ensure variable names are clear and consistent.
- **Browser Compatibility:** While CSS variables and modern CSS features are widely supported, ensure compatibility for target browsers, especially with `backdrop-filter`.
- **Performance:** Large global stylesheets can impact initial load time. Optimizations like minification and critical CSS extraction can be considered for production builds.
- **Font Imports:** The commented-out Google Fonts import suggests potential CSP issues or performance considerations. If custom fonts are needed, ensure they are loaded efficiently and securely.
