# Release notes

Keep one Markdown file per release: `android-vMAJOR.MINOR.PATCH.md` or
`server-vMAJOR.MINOR.PATCH.md` (prerelease suffixes are supported).
Commit finished notes before creating the corresponding tag. Workflows reject
missing, empty, or TODO/TBD notes before building or publishing.

Describe user-visible changes, fixes, and any compatibility or upgrade requirements.
Omit empty sections. Use `python3 scripts/release.py prepare android patch` to create
an initial draft, or use the `cloudstream-release` skill to review changes and finish it.
