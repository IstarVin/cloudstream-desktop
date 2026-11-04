# Releases

Android and server releases use independent versions and tags. Run the commands below from the repository root.

## Preparing and publishing versions

The repository skill [cloudstream-release](../.agents/skills/cloudstream-release/SKILL.md) supports requests such as
“bump Android patch,” “bump server minor,” and “release both: Android patch, server minor.” Bump requests prepare
reviewed release notes, run relevant checks, and commit locally. Release requests additionally push the commit
and tags and verify the publishing workflows. Both components have independent versions.

The dependency-free helper can also be used directly from the repository:

```bash
python3 scripts/release.py plan both --android patch --server minor
python3 scripts/release.py prepare both --android 0.1.0 --server 0.2.0
# Replace the generated TODO with reviewed changes, run checks, and commit the notes.
# Publish from clean main after the preparation is merged:
python3 scripts/release.py publish both --android 0.1.0 --server 0.2.0
```

`plan` prints previous tags, selected versions, and notes paths without changing files. `prepare` creates missing
notes without overwriting existing ones. The default bump is patch; without tags the baseline is `0.0.0`, so use
an exact version if the first release should be `0.1.0`. Fetch origin tags before selecting a version.
Prerelease suffixes are supported; use exact versions to promote or advance prereleases.

`publish` requires exact versions and finished, committed notes. It creates annotated tags and atomically pushes
main and the selected tags. It rejects existing versions and never overwrites tags. It does not run tests itself;
the skill runs checks before publishing. A failed push can leave local tags; inspect local and remote state before
retrying the same push. Main pushes also trigger the normal Docker development-image publish.

Both release workflows reject missing, empty, or TODO/TBD notes before building. Server release descriptions
include the versioned Docker pull command and image digest. Main Docker builds do not require release notes.

## Android signing and publishing

The `Android release` workflow builds, tests and lints a signed release APK, then publishes it and `SHA256SUMS`
to a GitHub Release using the matching Markdown file in `release-notes/`. Configure these repository Actions secrets first:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore (`base64 -w0 release.jks` on Linux) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Keep the keystore backed up and reuse it for every release so installed apps can receive updates. The workflow
fails if a signing secret is missing; local builds still use the existing debug-key fallback.

Commit finished notes in `release-notes/android-v0.1.0.md`, then push an Android version tag to publish:

```bash
git tag android-v0.1.0
git push origin android-v0.1.0
```

Tags such as `android-v0.2.0-beta.1` create GitHub prereleases. The APK version name comes from the tag and its
version code is the workflow run number; publish versions in increasing order. You can also run the workflow
manually with an existing Android tag, for example to retry a failed build. Creating a release fails if that tag
already has a GitHub Release. Android tags are separate from the Docker workflow's `server-v*` tags.
