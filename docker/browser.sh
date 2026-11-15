#!/bin/sh
# Installed as /usr/local/bin/chromium, so the default `chromium.path = "chromium"` finds it ahead of anything in
# /usr/bin. Starts Google Chrome when the image has it, else Debian's Chromium, with the flags a container needs.
#
# Chrome refuses to start as root or inside Docker's default seccomp profile with its own sandbox; the container is
# the sandbox. Software GL (SwiftShader), not --disable-gpu: without WebGL Cloudflare's Turnstile treats the browser
# as a bot. Extra flags can be added with CHROMIUM_EXTRA_FLAGS.
if [ -x /opt/google/chrome/chrome ]; then
    browser=/opt/google/chrome/chrome
else
    browser=/usr/lib/chromium/chromium
fi
# shellcheck disable=SC2086 # CHROMIUM_EXTRA_FLAGS is a list of flags
exec "$browser" \
    --no-sandbox \
    --disable-dev-shm-usage \
    --use-gl=angle --use-angle=swiftshader --enable-unsafe-swiftshader --ignore-gpu-blocklist \
    ${CHROMIUM_EXTRA_FLAGS:-} \
    "$@"
