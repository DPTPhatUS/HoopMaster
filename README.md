# Hoop Master Android

Hoop Master is an Android app that streams camera frames to the Hoop Master backend and speaks live throw-form feedback using TextToSpeech.

## Features
- Home screen with weekly progress ring and quick stats
- Practice flow navigation: Home -> Volume Test -> Current Session -> Session Results
- Camera preview with CameraX on the current-session screen
- Automatic backend session setup (default URL: `http://10.0.2.2:8000`)
- Live backend event handling from WebSocket and frame streaming to video WebSocket
- Session results with stats and mistake log
- TextToSpeech voice feedback for each `throw_event`

## Quick Start
1. Start backend at `http://127.0.0.1:8000`.
2. Build and run app (the app auto-connects to `http://10.0.2.2:8000` on the session screen).

## Notes
- `CAMERA` and `INTERNET` permissions are required.
- Local cleartext HTTP is enabled for development.
- The app uses the back camera and streams compressed JPEG frames every ~300ms.
