# Android audit and polish

Date: 2026-11-02. Mode: Operate. Scope: `android/`, with fixes in pairing, player gestures, collection browsing, search, library, and title details. The existing dark palette, orange accent, Material components, and adaptive navigation remain the visual authority. No formal PRODUCT.md or DESIGN.md exists.

## Platform verdict

Pass: this is a native Compose app, with Material navigation, accessible control labels, lazy catalogs, window insets, and a navigation rail at wider widths. It does not need a replacement design. The weaknesses found were lifecycle cleanup, misleading fallback content, large-text resilience, and task discoverability.

## Health score

These are scoped source-review assessments supported by selected device checks, not accessibility certification or performance measurements.

| Dimension | Score | Evidence and limits |
| --- | ---: | --- |
| Accessibility | 3/4 | Named search action, title heading semantics, Material touch targets; TalkBack traversal remains untested. |
| Performance | 3/4 | Lazy lists and cached artwork; removed blocking camera cleanup and duplicate collection merging. No frame-time trace collected. |
| Appearance and theming | 3/4 | Coherent intentional dark theme with shared tokens. Artwork contrast still depends on imagery; no light theme is offered. |
| Platform conformance | 3/4 | Native navigation and scanner Back handling; existing PiP source-rectangle lint warning remains. |
| Adaptivity | 3/4 | Phone portrait/landscape and 1.3 font scale checked; title header grows with content and pairing columns scroll. Tablet/foldable hardware remains untested. |
| **Total** | **15/20 — Good** | Remaining gaps primarily require broader device and assistive-technology verification. |

## Findings and resolutions

Eight findings: 0 P0, 2 P1, 5 P2, 1 P3. Seven addressed; the existing P3 PiP warning remains.

| Severity | Location | Impact | Resolution |
| --- | --- | --- | --- |
| P1 | `ui/PairScreen.kt`, `QrScanner` | Disposing the scanner could block the UI on an unfinished camera future. Late initialization could bind the camera after the screen closed; binding errors had no recovery. | Never wait in disposal. Ignore late initialization/results, unbind only owned use cases, refresh callbacks, and offer manual entry when camera setup fails. |
| P1 | `player/Gestures.kt` | Cancellation during a hold or scrub skipped cleanup, potentially retaining accelerated speed or paused playback. Delayed taps could also fire during a drag; opposite-side taps counted as a double tap. | Cleanup runs in `finally`; drags cancel delayed taps; multi-taps require the same side and a valid time window. |
| P2 | `ui/SectionScreen.kt` | When a requested collection was absent, a different row appeared under its name. | Keep the requested row empty, retain its recovery state, preserve coroutine cancellation, and compute merged rows once per page update. Regression coverage includes missing rows and repeated pagination. |
| P2 | `ui/TitleScreen.kt`, `Hero` | A fixed aspect-ratio container constrained long/scaled headings and metadata. Failed logos left no title; headings lacked explicit heading semantics. | Use aspect ratio as a minimum height, allow content-driven growth, reserve space for Back, and show a textual heading while logos load or fail. Loaded logos keep their original 44dp height. |
| P2 | `ui/SearchScreen.kt` | Search submission depended on the keyboard action. Long provider names crowded counts, and truncated errors concealed recovery details. | Make the search icon a named, 48dp submit target; give provider names flexible width and show full errors. |
| P2 | `ui/LibraryScreen.kt` | Returning from playback/background could leave history and bookmarks stale while the composition stayed alive. | Reload on lifecycle resume while retaining existing content during refresh. |
| P2 | `ui/PairScreen.kt` | Pairing controls allowed overlapping actions; Back left scanning instead of closing it; wide-layout instructions could be clipped vertically. | Guard duplicate connection attempts, disable conflicting controls during checks, close idle scanning with Back, and scroll both wide-layout columns. |
| P3, open | `player/PlayerActivity.kt` | Lint recommends a source rectangle for a smoother PiP transition. | Existing warning retained: `PictureInPictureIssue`; add and verify a video-view source rectangle in a dedicated PiP pass. |

The fixes follow the Android skill guidance on native task behavior, scalable type, recoverable states, and platform-owned lifecycle boundaries. Command mapping: harden for lifecycle/state failures, adapt for title/pairing layout, polish for search affordances.

## Verification

- `./gradlew :android:testDebugUnitTest :android:assembleDebug :android:lintDebug --console=plain`: passed on the final source.
- 15 unit tests passed, including two new collection identity/pagination regressions.
- Lint: zero errors, one existing PiP warning. Gradle also reports existing deprecated-feature usage.
- `git diff --check`: passed.
- Fresh debug APK installed on a connected physical Android phone without clearing app data.
- Inspected Home, title details, and Search; checked title details in portrait and landscape at 130% font size. Wider landscape uses the navigation rail.
- Tapped the new search icon with a real query; keyboard dismissed and streamed provider results appeared. Accessibility-tree inspection confirmed the named search action and disabled empty-query control.
- Original font scale and rotation settings restored. Existing night mode remained enabled.
- Device screenshots for this session: `/tmp/android-portrait.png`, `/tmp/android-title-large.png`, `/tmp/android-landscape.png`, `/tmp/android-search.png`, `/tmp/android-results.png`. These captures precede only the final refinement that lets failed-logo fallback text grow independently of the loaded logo's fixed height. The final refinement compiled and passed tests/lint; its image-failure branch was not exercised on-device.

## Boundaries and next checks

Source review and compilation establish the camera/gesture fixes; rapid camera initialization/disposal and playback cancellation were not reproduced with automated device tests. Existing pairing was preserved, so scanner permission/error paths were not exercised on the connected phone. Tablet/foldable layouts, TalkBack, minimum supported Android versions, real playback gestures, PiP transitions, and failed-logo imagery still need targeted device checks. No API contracts or backend behavior changed.

Retain shared tokens, lazy content, Material controls, explicit errors/empty states, and adaptive navigation. Next: `$impeccable adapt` for tablet/foldable and larger-text coverage, `$impeccable audit` with TalkBack and playback/camera lifecycle tests, then `$impeccable polish` for any remaining findings. Optional `$impeccable init` can capture durable product context.
