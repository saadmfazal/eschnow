# XPLC Capture

Android internal-audio capture app for Saad's XPLC workflow.

- Captures Android playback audio through the official MediaProjection + AudioPlaybackCapture APIs.
- Does not use the microphone as an audio source.
- Saves trimmed WAV recordings to `Music/XPLC Capture`.
- Android 10+ (minSdk 29).

> Playback capture only works when the source app allows Android playback capture. This project does not bypass DRM or app-level capture restrictions.
