# UI audit and improvements

Date: 2026-11-01. Mode: Operate. Scope: title details and episode browsing, search, library, shared navigation and notifications, sources sheet, and playback remote. This pass preserves the existing dark theme, orange accent, typography, and navigation structure.

## Implementation integrity

**Pass, with isolated usability gaps in the original implementation.** The app has a coherent, product-specific interface: a desktop navigation rail, phone navigation bar, provider-based catalogs, watch progress, and playback controls. Shared tokens, native controls, lazy images, and paginated episodes provide a strong base. The main weaknesses were incomplete interaction states and keyboard behavior, rather than a need for a replacement visual design.

The Impeccable detector returned `[]` for the changed UI files. This is a mechanical result, not certification of accessibility or visual quality. Svelte's analyzer also reported no component issues after corrections; its optional suggestions about existing effects, class directives, and element references were reviewed in context. URL-driven search effects manage an external EventSource and its cleanup; the episode section reference supports focus and scrolling.

## Health score

These are scoped engineering assessments, not Lighthouse scores or a WCAG conformance claim.

| Dimension | Before | After | Evidence |
| --- | ---: | ---: | --- |
| Accessibility | 2/4 | 3/4 | Native modal focus containment, nested Escape behavior, restored focus, filter states, live search feedback, and persistent title heading. Assistive technology testing remains outside this pass. |
| Performance | 3/4 | 3/4 | Existing 20-episode pagination and lazy-loaded artwork retained. No new dependency. No production performance trace was collected. |
| Theming | 3/4 | 3/4 | Existing token palette retained; new image foreground and overlay tokens added. Some pre-existing component color literals remain. |
| Responsive design | 2/4 | 3/4 | 44px targets for the changed controls; no page overflow in tested states at 320px, 390px, and 1440px. Physical devices remain untested. |
| Implementation integrity | 3/4 | 4/4 | Clear async states and recovery actions; consistent episode identity; detector found no issues in changed UI. |
| **Total** | **13/20** | **16/20** | **Acceptable → Good** |

## Findings and resolutions

Nine findings: **0 P0, 2 P1, 7 P2, 0 P3**. All findings below were addressed in this change.

### P1: Playback overlays allowed focus into the background

- **Location:** `web/src/lib/components/SourcesSheet.svelte`, `NowPlaying.svelte`; new shared `web/src/lib/dialog.ts`.
- **Category:** Accessibility.
- **Impact:** Keyboard users could leave the apparent modal and reach background controls. Escape did not close the overlays, and closing them did not deliberately restore focus.
- **Reference:** WAI-ARIA modal dialog interaction pattern; WCAG 2.4.3 focus order.
- **Resolution:** Use native modal dialogs with an inert background and native focus containment. Restore focus after conditional unmounting. Nested Sources → Remote dialogs close one at a time with Escape. Keep the original full-height desktop sources panel and phone bottom sheet.
- **Command mapping:** `$impeccable harden`.

### P1: Search filters exposed selection only visually

- **Location:** `web/src/routes/search/+page.svelte`.
- **Category:** Accessibility.
- **Impact:** Assistive technology could not determine which content type was selected. Streamed result updates also lacked a dedicated announcement.
- **Reference:** WCAG 4.1.2 name, role, value; 4.1.3 status messages.
- **Resolution:** Add a named filter group, `aria-pressed` states, and a polite result/loading status. Add an explicit submit button, separate provider failures from no-match results, and provide retry and filter-reset actions. Remove automatic focus that could summon a phone keyboard on entry.
- **Command mapping:** `$impeccable harden`, `$impeccable clarify`.

### P2: Episodes without artwork lost their identity and progress

- **Location:** `web/src/lib/components/EpisodeBrowser.svelte`.
- **Category:** Implementation integrity / responsive design.
- **Impact:** Episode numbers and progress were rendered inside an artwork-only branch. Named episodes without artwork were harder to locate in long lists. Broken images had no fallback.
- **Resolution:** Always render the episode number and progress. Use a compact number column when the season has no artwork; retain thumbnails for seasons with artwork. Hide failed images while keeping the episode number. Label the next episode and show remaining time for in-progress episodes. Preserve original episode indexes through filtering.
- **Command mapping:** `$impeccable layout`, `$impeccable harden`.

### P2: Finding the next episode changed filters without moving focus

- **Location:** `web/src/lib/components/EpisodeBrowser.svelte`.
- **Category:** Accessibility / implementation integrity.
- **Impact:** Users could trigger the action and still need to find the intended episode in a long list.
- **Resolution:** “Jump to next episode” selects the correct season and page, clears search, scrolls to the episode, and focuses its play button. The search label explicitly identifies its season scope.
- **Command mapping:** `$impeccable clarify`, `$impeccable harden`.

### P2: Library loading and failures could look like a blank screen

- **Location:** `web/src/routes/library/+page.svelte`.
- **Category:** Implementation integrity.
- **Impact:** Users could not tell whether their library was empty, loading, or unavailable. Empty states had no direct route back to browsing.
- **Resolution:** Reuse the existing loading skeleton, expose the active view's error with a retry action, and add useful history/bookmark empty states with browsing links. Preserve the clear-history confirmation.
- **Command mapping:** `$impeccable harden`, `$impeccable onboard`.

### P2: Several secondary controls had small touch targets

- **Location:** `web/src/app.css`, episode pagination and watched buttons, library removal buttons, title controls, toast dismiss buttons, sources close button.
- **Category:** Responsive design.
- **Impact:** Small controls were harder to activate accurately on phones. Episode search used small text that can trigger input zoom on some mobile browsers.
- **Reference:** A 44px product target, beyond WCAG 2.5.8's 24px minimum; not every original small control was a WCAG failure.
- **Resolution:** Enlarge the changed controls to at least 44px, wrap phone search filters so every option is visible, and use 16px episode search text on narrow screens.
- **Command mapping:** `$impeccable adapt`.

### P2: Native form controls used a subtle focus treatment

- **Location:** `web/src/app.css`.
- **Category:** Accessibility.
- **Impact:** Inputs and selects suppressed outlines and relied on a border-color change, making keyboard location less prominent than on other controls.
- **Resolution:** Give native form controls the shared accent outline. Composite search fields retain their enclosing focus indicator.
- **Command mapping:** `$impeccable polish`.

### P2: Reduced motion relied on globally shortening animations

- **Location:** `web/src/app.css`, `SourcesSheet.svelte`, `NowPlaying.svelte`.
- **Category:** Accessibility.
- **Impact:** A global 0.01ms override removed timing without expressing an intentional reduced-motion state.
- **Resolution:** Use static loading indicators and disable overlay entrance animations, transitions, and poster zoom for reduced motion. Retain the existing static skeleton and toast alternatives. State labels and progress remain visible.
- **Command mapping:** `$impeccable animate`.

### P2: Titles with a logo omitted the heading

- **Location:** `web/src/routes/title/+page.svelte`.
- **Category:** Accessibility.
- **Impact:** Heading navigation could miss the title when a logo replaced the `h1`.
- **Reference:** Semantic heading structure; absence of an `h1` alone does not establish a WCAG violation.
- **Resolution:** Always render the title heading, visually hide it when a logo is present, and treat the adjacent logo as decorative to avoid duplicate announcements.
- **Command mapping:** `$impeccable harden`.

## Verification

- `vpr check`: zero Svelte errors and warnings.
- `vp check`: formatting, lint, and type checks passed.
- `vp test`: 24 tests passed across five files.
- `vp build`: production build passed.
- Impeccable detector: no findings in the changed UI files.
- Browser: headless Chromium at 1440px, 390px, and 320px. The phone contexts used synthesized touch taps, not just resized desktop screenshots. No page overflow or uncaught runtime errors occurred in the tested states.
- Interaction checks: episode numbering, season filters, pagination, bottom-pagination focus, jump-to-next focus, failed artwork, title logo heading, search submission and filtering, provider failure feedback, library loading/error/retry/empty/populated states, clear-history dialog, nested playback dialogs, keyboard focus containment, Escape, focus restoration, and reduced-motion remote.
- Representative token contrast calculations: muted text on background **6.99:1**, muted text on surface 3 **5.09:1**, accent on surface **7.60:1**, accent-button text **8.05:1**. These validate token pairs, not every composited pixel or image background.

The browser checks used illustrative local data and intercepted HTTP and player WebSocket traffic. They establish frontend behavior, not provider availability, media playback, or server correctness. The initial visual inspection also observed an existing player bar; subsequent verification isolated player traffic. Screenshots and the temporary browser harness are under `/tmp/cloudstream-ui-audit/` for this session.

## Patterns to retain and follow-up boundaries

Keep the shared palette, route navigation, original episode indexes, native selects, bounded episode rendering, lazy artwork, and clear destructive-action confirmation. The fixes address recurring gaps in async feedback, keyboard context, and secondary control sizes.

Remaining validation should cover physical iOS/Android devices, screen readers, browser/text zoom, real provider imagery, large libraries, and actual playback. Native seek and volume gestures were not changed or exercised under touch. Plugin settings and pairing were outside the detailed interaction audit.

For a future pass: `$impeccable adapt` for physical-device and zoom checks, `$impeccable audit` for broader assistive-technology coverage, then `$impeccable polish` after those checks. Optional `$impeccable init` can capture durable product context; this scoped improvement used the existing implementation as its visual authority.
