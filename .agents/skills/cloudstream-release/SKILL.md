---
name: cloudstream-release
description: Prepare or publish Cloudstream Desktop Android and server releases, including independent version bumps, reviewed release notes, checks, tags, and workflow verification. Use for requests to bump or release Android, server, or both in this repository.
---

# Cloudstream releases

Run from the Cloudstream Desktop repository. Release mechanics live in `scripts/release.py`.

## Intent and version selection

- “Bump” means prepare notes, validate, and commit the intended release changes locally. “Release” or “publish” additionally authorizes pushing the release commit and tags. Respect any more specific session instructions.
- Select Android, server, or both from the request. Ask only if the target is missing and cannot be inferred.
- Default unspecified bumps to patch. Both components increment independently; honor separate increments or exact versions. No previous tag means a 0.0.0 baseline (first patch is 0.0.1). Use an explicit version for a different first release.
- The script fetches origin tags before planning and fails if it cannot. Inspect the working tree; preserve unrelated user changes and never force-push, move existing tags, or include unrelated edits in release commits.
- Run `python3 scripts/release.py plan TARGET [patch|minor|major|VERSION]`. For different versions: `plan both --android minor --server patch`. Plan emits JSON with previous tags, chosen versions, and note paths. Capture these exact versions for subsequent commands.
- Highest valid SemVer tag is the baseline, including prereleases. Automatic bumps increment its numeric base; use an exact version to promote a prerelease or advance its suffix.

## Prepare notes and validate

Run `prepare` with the selected exact versions; it creates missing notes without overwriting existing notes. For example:

```bash
python3 scripts/release.py prepare both --android 0.2.1 --server 0.3.0
```

Review commits and the full diff from each previous component tag to the intended release commit; for a first release review the current implementation and history. Android scope includes `android/` and applicable `shared/` changes. Server includes `server/`, `web/`, `compat/`, `mpv/`, `docker/`, packaging, and applicable `shared/` changes. Review build/workflow changes as relevant. Path filters are aids, not evidence that a shared or cross-component change is irrelevant.

Replace scaffolding with concise, factual user-visible notes. Include additions, fixes, known issues, and breaking/upgrade requirements when applicable; omit empty sections. Do not invent features from commit titles. Call out minimum counterpart versions when evidence requires them.

Validate the selected targets using the checks in `.github/workflows/ci.yml` and release workflows. Android: release build/lint with configured signing, `:android:testDebugUnitTest`, and `:shared:test`; if signing is unavailable, run debug build/lint and unit tests and report that signed validation remains in CI. Server: shared/server tests, web checks/tests, and `:server:shadowJar` (includes web packaging). Report environmental blockers accurately. Passing CI for the exact commit can satisfy equivalent checks.

Commit the completed notes and intended release changes before tagging. A single preparation commit can contain both notes. Do not edit Android Gradle defaults to set the published version: CI derives versionName from the tag and versionCode from its run number. Server versions are Docker tags. For a bump-only request, return selected versions, commit, and validation results without pushing.

## Publish and verify

Publish from clean `main` after the release preparation has been merged; the script refuses if local `main` is behind or diverged from origin. It does not query CI, so ensure checks passed for the intended commit and the exact selected notes exist there. Run, for example:

```bash
python3 scripts/release.py publish both --android 0.2.1 --server 0.3.0
```

The script checks committed finished notes and unused versions, creates annotated tags, and atomically pushes main and those tags to origin. Both tags point to the same commit. Android tags trigger signed APK/checksum releases; server tags trigger Docker publishing followed by a GitHub Release with image instructions.

Use GitHub CLI to identify runs by workflow, tag, and release commit, monitor completion, and inspect releases/assets. Verify Android APK and SHA256SUMS, or the server image tag/digest and release pull instructions. Return release links and actual outcomes. Main pushes also publish the server development image under the existing Docker workflow.

If a push or workflow fails, inspect its state before retrying. Do not automatically bump another version, delete a tag/release, or overwrite assets. The script deletes local tags it created when tagging or the atomic push fails and they are not on origin (if origin cannot be queried it keeps them): check `git tag` and `git ls-remote --tags origin` before retrying. Android manual dispatch can retry a failed build before a release exists. An existing GitHub Release makes the creation step fail; repair only the failed stage within the user's authorization.
