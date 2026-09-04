/**
 * Semantic design tokens for the mobile app.
 *
 * These tokens mirror the naming conventions used in web artifacts (index.css)
 * so that multi-artifact projects share a cohesive visual identity.
 *
 * Replace the placeholder values below with values that match the project's
 * brand. If a sibling web artifact exists, read its index.css and convert the
 * HSL values to hex so both artifacts use the same palette.
 *
 * To add dark mode, add a `dark` key with the same token names.
 * The useColors() hook will automatically pick it up.
 */

const colors = {
  light: {
    // Legacy aliases (kept for backward compatibility)
    text: '#13213C',
    tint: '#1F65E8',

    // Core surfaces
    background: '#F5F8FC',
    foreground: '#13213C',

    // Cards / elevated surfaces
    card: '#FFFFFF',
    cardForeground: '#13213C',

    // Primary action color (buttons, links, active states)
    primary: '#1F65E8',
    primaryForeground: '#ffffff',

    // Secondary / less-emphasis interactive surfaces
    secondary: '#EAF1FF',
    secondaryForeground: '#1F65E8',

    // Muted / subdued elements (dividers, timestamps, placeholders)
    muted: '#F1F4F8',
    mutedForeground: '#70809B',

    // Accent highlights (badges, selected items, focus rings)
    accent: '#DDE9FF',
    accentForeground: '#174CB0',

    // Destructive actions (delete, error states)
    destructive: '#D94B5B',
    destructiveForeground: '#ffffff',

    // Borders and input outlines
    border: '#DFE7F2',
    input: '#D8E1EE',
  },

  // Border radius (in px). Sync from the sibling web artifact's --radius
  // CSS variable. This value applies to cards, buttons, inputs, and modals.
  radius: 8,
};

export default colors;
