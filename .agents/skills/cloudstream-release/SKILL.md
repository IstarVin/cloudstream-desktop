---
name: cloudstream-release
description: Prepare or publish Cloudstream Desktop Android and server releases, including independent version bumps, reviewed release notes, checks, tags, and workflow verification. Use for requests to bump or release Android, server, or both in this repository.
---

# Cloudstream releases

Run from the Cloudstream Desktop repository. Release mechanics live in `scripts/release.py`.

## Intent and version selection

- Invoking this skill without a specified action means publish the selected releases, including pushing the release commit and tags. “Bump” means prepare notes, validate, and commit the intended release changes locally without publishing. Respect any more specific session instructions.
- **Target selection is automatic by default.** Use `auto` unless the user names a component explicitly ("bump Android", "release server only"). `auto` releases exactly the components with source changes since their own last tag (or since the start of history if never tagged); a component with no changes is skipped. If neither component changed, report that there is nothing to release and stop.
- Change detection uses path filters in `scripts/release.py` (`COMPONENT_PATHS`). Android: `android/`, `shared/`, `testdata/`, Gradle build files, and `android-release.yml`. Server: `server/`, `web/`, `compat/`, `mpv/`, `docker/`, `packaging/`, `shared/`, `testdata/`, `Dockerfile`, `docker-compose.yml`, Gradle build files, and `docker.yml`. Markdown, `docs/`, `release-notes/`, `scripts/`, `.agents/`, `.claude/` and CI-only changes never count. The filters are aids: still read the commits and diffs, and if the script's choice looks wrong (for example a `shared/` change that only touches the other client, or a change outside the filters that does affect a component), tell the user and adjust by naming the target explicitly rather than silently overriding it.
- Default unspecified bumps to patch. Both components increment independently; honor separate increments or exact versions. No previous tag means a 0.0.0 baseline (first patch is 0.0.1). Use an explicit version for a different first release.
- The script fetches origin tags before planning and fails if it cannot. Inspect the working tree; preserve unrelated user changes and never force-push, move existing tags, or include unrelated edits in release commits.
- Run `python3 scripts/release.py plan auto [patch|minor|major|VERSION]` (TARGET may instead be `android`, `server` or `both`). For different versions: `plan auto --android minor --server patch`. Plan emits JSON per selected component with the previous tag, chosen version, note path, `changes` (source commits since the previous tag, newest first) and `commit`, the commit that will be tagged. An empty list means nothing to release. Capture these exact versions for subsequent commands.
- **Tagged commit.** Each component's tag points at the latest commit that changed that component (its `commit` field), not at `HEAD`, so the two tags usually land on different commits and a release does not claim unrelated work. The release workflows build the tag and read `release-notes/<tag>.md` from the tagged tree, so a component's own notes commit counts as its latest change. Never tag a later commit "to be safe".
- Highest valid SemVer tag is the baseline, including prereleases. Automatic bumps increment its numeric base; use an exact version to promote a prerelease or advance its suffix.

## Prepare notes and validate

Run `prepare` with the selected exact versions; it creates missing notes without overwriting existing notes. For example:

```bash
python3 scripts/release.py prepare both --android 0.2.1 --server 0.3.0
```

Review the plan's `changes` and the full diff from each previous component tag to that component's `commit` (not to `HEAD`); for a first release review the current implementation and history. Android scope includes `android/` and applicable `shared/` changes. Server includes `server/`, `web/`, `compat/`, `mpv/`, `docker/`, packaging, and applicable `shared/` changes. Review build/workflow changes as relevant. Path filters are aids, not evidence that a shared or cross-component change is irrelevant. A commit that changes only the other component's code does not belong in this component's notes, even if it falls inside the range.

Replace scaffolding with concise, factual user-visible notes. Include additions, fixes, known issues, and breaking/upgrade requirements when applicable; omit empty sections. Do not invent features from commit titles. Call out minimum counterpart versions when evidence requires them.

Validate the selected targets using the checks in `.github/workflows/ci.yml` and release workflows. Android: release build/lint with configured signing, `:android:testDebugUnitTest`, and `:shared:test`; if signing is unavailable, run debug build/lint and unit tests and report that signed validation remains in CI. Server: shared/server tests, web checks/tests, and `:server:shadowJar` (includes web packaging). Report environmental blockers accurately. Passing CI for the exact commit can satisfy equivalent checks.

Commit the completed notes and intended release changes before tagging. **When releasing both, commit each component's notes in its own commit** (`release: prepare android X`, then `release: prepare server Y`) so each tag lands on a distinct commit; a single combined commit would make both tags point at it. Re-run `plan auto` after committing and confirm each `commit` is the one you expect: Android's should be its notes commit (or a later Android change), and likewise for server. Run validation against each tagged commit's tree (or rely on CI for that exact commit), not just `HEAD`. Do not edit Android Gradle defaults to set the published version: CI derives versionName from the tag and versionCode from its run number. Server versions are Docker tags. For a bump-only request, return selected versions, commit, and validation results without pushing.

## Publish and verify

Publish from clean `main` after the release preparation has been merged; the script refuses if local `main` is behind or diverged from origin, if a selected component has no changes since its last tag, or if its finished notes are not in the tagged commit. It does not query CI, so ensure checks passed for each tagged commit. Run, for example:

```bash
python3 scripts/release.py publish auto --android 0.2.1 --server 0.3.0
```

The script checks committed finished notes and unused versions, creates annotated tags at each component's own latest-change commit, and atomically pushes main and those tags to origin. With `auto`, give exact versions for the components `plan` selected; versions given for unselected components are ignored. Android tags trigger signed APK/checksum releases; server tags trigger Docker publishing followed by a GitHub Release with image instructions.

Use GitHub CLI to identify runs by workflow, tag, and that tag's commit, monitor completion, and inspect releases/assets. Verify Android APK and SHA256SUMS, or the server image tag/digest and release pull instructions. Return release links and actual outcomes. Main pushes also publish the server development image under the existing Docker workflow. A server tag on a commit that is no longer the tip of `main` publishes only the versioned image, not `latest` (the Docker workflow's intended behavior); mention this in the report when it applies.

If a push or workflow fails, inspect its state before retrying. Do not automatically bump another version, delete a tag/release, or overwrite assets. The script deletes local tags it created when tagging or the atomic push fails and they are not on origin (if origin cannot be queried it keeps them): check `git tag` and `git ls-remote --tags origin` before retrying. Android manual dispatch can retry a failed build before a release exists. An existing GitHub Release makes the creation step fail; repair only the failed stage within the user's authorization.
