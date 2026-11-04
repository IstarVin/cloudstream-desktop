# Cloudstream design system

The web implementation is the visual reference for both clients. Catalog browsing is artwork-led; search, library and settings prioritize scanning and predictable controls. The app uses a quiet dark canvas with warm orange reserved for actions, selection, focus and watch progress.

## Shared foundations

| Role | Value | Use |
| --- | --- | --- |
| Background | `#0E0F13` | Page canvas |
| Surface | `#16181E` | Sidebar, source band, text fields |
| Surface 2 | `#1E2129` | Fields, artwork placeholders, secondary controls |
| Surface 3 | `#282C36` | Raised/interactive surfaces |
| Text | `#ECEEF3` | Titles and body copy |
| Muted | `#979CAB` | Metadata and supporting copy |
| Accent | `#FF8A4C` | Primary actions, selected states, progress |
| Accent ink | `#1D0E05` | Text on filled orange controls |
| Accent soft | Accent at 14% | Selected navigation and filters |
| Divider | White at 10% | Subtle separation and artwork borders |
| Success / error | `#53D3A3` / `#FF6170` | Status feedback |

Source tokens live in `web/src/app.css`; Android maps them to Material roles in `android/src/main/kotlin/com/istarvin/cloudstream/ui/Theme.kt`. Keep this brand palette fixed rather than replacing it with wallpaper-derived colors. Text over artwork uses white and a dark scrim.

Use the platform sans-serif family, bold page headings, strong section headings, semibold poster labels and quieter metadata. Web headings scale with viewport width. Android uses scalable `sp`: 30/36 for page headings, 36/42 on expanded catalog panes, 20/26 for section headings, 14/20 for poster labels, and 13/18 for supporting text. TV retains its larger body and label scale. Page and section headings use slight negative tracking; body text uses the platform defaults.

Use 8-unit spacing steps with 4-unit refinements. Artwork and source selectors have 12-unit corners; smaller fields use 8. Primary and outlined buttons use 8dp corners on Android, matching the web’s compact rectangular controls. Navigation selections also use softly rounded rectangles. Bottom sheets retain native shapes. Avoid adding containers around every shelf: the artwork supplies the structure.

## Layout and platform translation

| Web pattern | Android adaptation |
| --- | --- |
| Page heading and short introduction | Shared `ScreenHeading`, wrapping supporting text and 48dp native action targets |
| Bottom navigation below 900px; sidebar above | Compact bottom navigation below 600dp window width; labeled 200dp sidebar above, 96dp rail with labels below icons on TV |
| Orange active link, muted inactive links | Outlined icons in both states; orange selected icon/label with a soft orange rectangle (40 × 28dp behind bottom-navigation icons) |
| Centered content with a 1400px maximum | Catalog area capped at 1400dp; extra side spacing above 900dp of available pane width |
| Home search and source band | Search beside the heading on expanded panes or full-width below on compact panes; bordered source band with source selector and plugin management |
| Horizontal poster shelves with desktop arrows | Swipeable lazy rows; D-pad focus and scrolling on TV |
| Responsive artwork sizing | 140dp portrait / 248dp landscape on compact panes; 176dp / 304dp at 720dp pane width or on TV |
| Library and collection grids | Adaptive grid cells with a 140dp compact minimum (156dp on expanded panes or TV), 16dp column gaps and 24dp row gaps |
| Landscape resume thumbnails | 16:9 landscape posters with title and provider/episode/remaining-time metadata below, plus an artwork-edge progress track |
| Title backdrop with gradient and poster | Scrimmed hero, 96dp portrait artwork (80dp in short windows), native play and bookmark controls |
| Settings sidebar | Category strip on compact panes; category sidebar on wide panes |
| Web dialogs and source pickers | Material bottom sheets; focus-contained dialogs on TV |

Android layout decisions use available window/pane width, not device model. Title details use one scrolling column with episodes below the show information on all window sizes and TV. Preserve predictive Back transitions, scroll restoration, touch refresh, system/keyboard insets and TV focus outlines. Do not copy web hover interactions onto touch screens.

## Component rules

- App identity uses an orange cloud silhouette with a transparent play cutout on the dark background. The canonical artwork is `web/static/favicon.svg`; run `python3 scripts/generate_icons.py` (ImageMagick required; DejaVu Sans for the TV label) to regenerate the web PNGs, Android adaptive/themed foreground and TV banner. Maskable and Apple icons use full-bleed backgrounds; Android keeps the mark within its adaptive-icon safe zone.

- Shelf titles have more space above than below; Android home shelves use 16dp above section headings and 8dp between card targets. Web shelves retain their existing spacing.
- Poster text sits below artwork, with at most two lines and ellipsis for titles and muted source/year metadata. Android shelves reserve two title lines to align metadata, without reserving unused metadata lines below it. Failed images retain initials and the same aspect ratio.
- Loading shelves share the loaded poster width so content does not change scale when it arrives.
- Primary actions use filled orange with 8dp corners; outlined actions share the same corners. Secondary actions, unselected tabs and supporting text stay quiet.
- Text fields use a filled surface, subtle border and 8dp corners; focus changes the border to orange. Retain native keyboard actions and remote text entry.
- Bottom navigation preserves labels, selection semantics and minimum touch targets. Sidebar destinations place outlined icons beside labels; the compact TV rail stacks labels below icons and uses the play mark alone for identity. Both retain a full-item soft orange selected background and visible TV focus.
- Headings wrap with system font scaling. Controls retain Android's minimum touch targets and built-in accessibility semantics.
- Preserve factual copy and real catalog imagery. Do not introduce decorative heroes or fabricated featured titles.

## Verification

Compare live web and Android renders with the same catalog. Check phone, wide/tablet and TV layouts, a 1.3 font scale, title details, library grids, settings, source pickers and empty/error states. Run the Android build, unit tests, lint and the existing TV navigation/focus and refresh instrumentation tests after changes to shared components.
