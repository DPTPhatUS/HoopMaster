# Hoop Master Android

Hoop Master is an Android app that streams camera frames to the Hoop Master backend and speaks live throw-form feedback using TextToSpeech.

## Features
- Camera preview with CameraX
- Frame streaming to backend video WebSocket
- Session controls (`connect`, `start`, `stop`, `disconnect`)
- Live backend event handling from WebSocket
- TextToSpeech voice feedback for each `throw_event`

## Backend Contract
This app follows the API in:

`/home/dptphat/HCMUS/25-26/II/Human-Computer Interaction/Assignments/PA3/hoop-master/API.md`

## Quick Start
1. Start backend at `http://127.0.0.1:8000`.
2. In Android emulator, use `http://10.0.2.2:8000` as Backend URL in the app.
3. Build and run app.

### Build
```sh
cd /home/dptphat/AndroidStudioProjects/HoopMaster
./gradlew :app:assembleDebug
```

### Install on connected device/emulator
```sh
cd /home/dptphat/AndroidStudioProjects/HoopMaster
./gradlew :app:installDebug
```

## Notes
- `CAMERA` and `INTERNET` permissions are required.
- Local cleartext HTTP is enabled for development.
- The app currently uses the back camera and streams compressed JPEG frames every ~300ms.

