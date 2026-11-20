# LyricVault

LyricVault is a local-first Android songwriting app for capturing lyrics, context, tags, and melody ideas in one place. It uses offline matching to surface relevant notes while you write, and stores all data on the device.

## Features

- Capture text ideas with titles, context, tags, and structured blocks
- Record and replay short melody clips alongside lyrics
- Match your current song vibe or a specific line against saved ideas
- Track when and where ideas are used across songs
- Write and manage multiple songs
- Export or import vault data as JSON
- Include or exclude audio clips in JSON backups
- Import plain text files or generic JSON idea lists
- Use the app with dark or light mode

## Tech stack

- Kotlin
- Jetpack Compose
- Room
- AndroidX Navigation
- Coroutines and Flow
- Gradle Kotlin DSL

## Requirements

- Android Studio with Android SDK 36
- JDK 17
- An Android device or emulator running API 24 or newer

## Setup

1. Clone the repository:

   ```bash
   git clone https://github.com/sivab193/LyricVault.git
   cd LyricVault
   ```

2. Open the project directory in Android Studio.

3. Let Gradle sync and accept the SDK components it requests.

4. Run the `app` configuration on an emulator or connected Android device.

No API key or external account is required. LyricVault's matching is implemented locally and does not send vault content to a remote service.

For command-line builds:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Backup and restore

Open **Backup** in the app to export or import data.

- JSON exports include ideas, blocks, usage history, and songs.
- Audio is optional and is Base64-encoded in the export when enabled.
- Individual audio clips are limited to 12 MB during export.
- Plain-text imports and generic JSON arrays are supported in addition to LyricVault JSON exports.

## Permissions

Microphone access is requested only when recording an audio melody tile. Audio clips are stored in the app's private files directory.
