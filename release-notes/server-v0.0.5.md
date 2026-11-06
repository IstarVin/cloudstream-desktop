# Server 0.0.5

## Changes

- Configure multiple plugin repositories in desktop settings. When repositories contain the same plugin, the first repository takes priority.
- Allow more time for Chromium to start on slower systems.

## Upgrade

- Update the Android app to 0.0.6 alongside the server; the plugin source API has changed.
- Existing single-repository settings and configuration are carried forward. API clients must use `repoUrls` and `plugin_repo_urls` in place of the singular fields.
