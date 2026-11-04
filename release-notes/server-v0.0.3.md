# Server 0.0.3

## Changes

- Give web player quality selection its own panel with an Auto option, show the current video resolution, and remember the choice to display total duration or time remaining.
- Keep web player controls hidden during swipe seeking and playback-speed holds, including while paused.
- Initialize web player track choices immediately when attaching a playback engine.
- Decrypt supported identity SAMPLE-AES fMP4 HLS streams on the server when clients request decrypted playback, and add CBCS support to fragmented MP4 decryption.
- Handle clear lead-ins, constant IVs, sample descriptions, and partial byte-range reads in supported encrypted fragmented MP4 streams.

## Limitations

HLS decryption supports identity SAMPLE-AES with fMP4 segments. Unsupported key formats and byte-range playlists retain their original protection.
