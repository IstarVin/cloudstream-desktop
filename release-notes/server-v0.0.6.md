# Server 0.0.6

- Add local authentication without an external Worker: claim the server through a one-time link in its log, approve device codes from a signed-in browser or Android device, and optionally set a password.
- Manage plugin repositories from paired devices, with validated URLs or shortcodes, repository icons and errors, and separate installation and settings for same-named plugins from different repositories.
- Add browser-extension sign-in and title playback on connected devices, resuming the next episode when appropriate.
- Save NSFW visibility per device and allow a cast receiver to load the NSFW provider explicitly chosen by its sender.
- Add IntroDB intro, recap and outro skip times for TV shows with IMDb metadata.
- Keep the sources sheet in sync with remote episode changes, prevent competing browser tabs from repeatedly reconnecting, and fix title pages with duplicate cast or recommendation entries. Add links to the original provider page.
- Improve the Docker browser setup with Google Chrome by default on amd64, weekly browser refreshes, fonts, language and timezone configuration, child-process cleanup, and a configurable Java heap through JAVA_OPTS.

## Upgrade

- Plugins now require explicit selection and start disabled. Open Settings and enable the plugins you want after upgrading. Removing a repository also removes its installed plugins.
- Local sign-in is the default when CLOUDSTREAM_AUTH_URL is unset. Use the one-time sign-in link from the server log for the first browser. Existing Worker deployments can retain CLOUDSTREAM_AUTH_URL and CLOUDSTREAM_AUTH_SECRET; changing authentication modes signs out all devices.
- Use Android 0.0.7 or newer for local sign-in, repository-aware plugin management and device-specific NSFW controls. Browser extension 0.0.1 requires server 0.0.6 or newer.
- Set TZ to your local timezone for the Docker browser; LANGUAGE can also be configured.
