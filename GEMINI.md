# GEMINI.md

## Project Overview

This project is an Android application that allows a group of people to play a game together. The game involves a "game master" who controls the experience for the other players. The game master can play videos on all connected smartphones, turn off their screens, and deactivate their torches. The app uses Wi-Fi Direct (P2P) to connect the devices, and a custom TCP-based protocol to synchronize the game state.

### Key Features:

*   **P2P Networking:** Devices connect to each other using Wi-Fi Direct.
*   **Game Master/Player Roles:** One device acts as the game master, controlling the game for all other players.
*   **Video Synchronization:** The game master can upload videos to a playlist, which is then synchronized to all player devices.
*   **Playback Control:** The game master can control video playback (play, pause, next, previous) for all players.
*   **Advanced Controls:** The game master can turn off the screen and deactivate the torch on all player devices.
*   **Bluetooth Remote Control:** The game master can use a Bluetooth remote to control video playback.
*   **Invisible UI:** The game master's controls are hidden to maintain their anonymity.
*   **Background Service:** A background service maintains the network connection when the screen is off.

### Architecture:

The application is a single-activity Android app written in Kotlin. The main components are:

*   **`MainActivity.kt`:** The main entry point of the application. It handles the UI, user input, and the main game logic.
*   **`GameSync.kt`:** A custom class that manages the network communication between devices. It uses a `ServerSocket` to listen for incoming connections and `Socket` to communicate with other devices. It uses object serialization to send and receive data.
*   **`FileTransfer.kt`:** A class that handles file transfers between devices. It uses a separate `ServerSocket` and `Socket` to transfer files.
*   **`p2p/` package:** Contains classes related to Wi-Fi Direct networking.
*   **`session/` package:** Contains data classes and dialogs related to the game session.
*   **`ConnectionService.kt`:** A background service that keeps the network connection alive when the screen is off.

## Building and Running

To build and run the project, you can use the following command:

```bash
./project_01_android/gradlew -p ./project_01_android assembleDebug
```

This will generate a debug APK in the `project_01_android/app/build/outputs/apk/debug/` directory. You can then install this APK on an Android device.

## Development Conventions

*   **Kotlin:** The project is written entirely in Kotlin.
*   **Android Jetpack:** The project uses several Android Jetpack libraries, including `ViewModel`, `LiveData`, `Navigation`, and `media3`.
*   **Custom Networking:** The project uses a custom TCP-based networking protocol for game state synchronization.
*   **UI:** The UI is built using XML layouts and `ConstraintLayout`.
