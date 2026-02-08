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
*   **`p2p/` package:** Contains classes related to Wi-Fi Direct networking.
    *   `ConnectionService.kt`: A background service that keeps the network connection alive when the screen is off.
    *   `WifiDirectBroadcastReceiver.kt`: Handles Wi-Fi Direct broadcast events.
*   **`session/` package:** Contains data classes, network management, and dialogs related to the game session.
    *   `AdvancedCommand.kt`: Defines advanced game commands.
    *   `BluetoothDevicesDialogFragment.kt`: Dialog for displaying Bluetooth devices.
    *   `BluetoothRemoteControl.kt`: Manages Bluetooth remote control functionality.
    *   `CreateGameDialogFragment.kt`: Dialog for creating a new game.
    *   `FileTransfer.kt`: Handles file transfers between devices using a separate `ServerSocket` and `Socket`.
    *   `FileTransferRequest.kt`: Data class for file transfer requests.
    *   `GameMessage.kt`: Base class for game-related messages.
    *   `GameSync.kt`: Manages network communication between devices using `ServerSocket` and `Socket` for game state synchronization via object serialization.
    *   `JoinGameDialogFragment.kt`: Dialog for joining an existing game.
    *   `MessageEnvelope.kt`: Wrapper for messages sent over the network.
    *   `NetworkEvent.kt`: Defines network-related events.
    *   `NetworkManager.kt`: Interface for network operations.
    *   `PasswordChallenge.kt`: Data class for password challenges.
    *   `PasswordHasher.kt`: Utility for hashing passwords.
    *   `PasswordMessage.kt`: Message for sending passwords.
    *   `PasswordResponseMessage.kt`: Message for password challenge responses.
    *   `PlaybackCommand.kt`: Defines video playback commands.
    *   `PlaybackState.kt`: Represents the current video playback state.
    *   `Player.kt`: Data class representing a player in the game.
    *   `ReconnectionManager.kt`: Manages network reconnection attempts.
    *   `SocketNetworkManager.kt`: Implementation of `NetworkManager` using Sockets.
    *   `Video.kt`: Data class representing a video.
*   **`viewmodel/` package:** Contains classes for managing UI-related data and business logic.
    *   `GameRepository.kt`: Provides an abstraction layer for game data.
    *   `GameViewModel.kt`: Prepares and manages data for `MainActivity`.

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
