# Browser extension 0.0.1

- First Chrome and Firefox release: connect to a Cloudstream server and sign in through device-code approval.
- Open provider links in Cloudstream from the right-click menu, or use the floating provider-page button to open a title or play it on a connected device.
- Show a toolbar badge on supported provider sites, refresh providers from the popup, and hide the floating button per site.
- Match loaded providers by site, preferring the most recently installed plugin when several serve the same host. Requests and session tokens stay in the extension background process.

## Requirements and installation

Requires server 0.0.6 or newer and enabled providers. Chrome-family browsers can load the extracted Chrome zip through Developer mode. Firefox needs site access granted during setup; if the release contains an unsigned Firefox zip, use a temporary installation through about:debugging or a Firefox edition configured to allow unsigned add-ons.

The extension recognizes provider sites, but genre, search and other non-title URLs may produce a provider error when opened or played.
