# Implementation Plan

This document outlines the milestones and tasks for the implementation of Project 01.

## Milestones

### 1. Project Setup & Basic UI
- [x] Set up the Android project in Android Studio.
- [x] Create the basic UI for the main screen, including a placeholder for the video view and player list.
- [x] Implement basic navigation between screens (e.g., main screen, settings screen).

### 2. Networking & Connectivity
- [x] Implement device discovery using Wi-Fi Direct (p2p).
- [x] Implement connection management between devices.
- [x] Display a list of connected devices in the UI.

### 3. Game Session Management
- [x] Implement the creation of a new game session by a Game Master.
- [x] Implement a password protection for the game session.
- [x] Implement the functionality for players to join an existing game session.

### 4. Game Master/Player Roles
- [x] Implement the logic to assign the Game Master role to the device that creates the session.
- [x] Differentiate between Game Master and Player functionality within the app.

### 5. Video Playlist Management
- [x] Implement the UI for the video playlist.
- [x] Allow Game Masters to add videos from their device to the playlist.
- [x] Implement the logic to synchronize the playlist and video files to all connected devices.
  - [x] Synchronize the playlist (list of video titles).
  - [x] Synchronize the video files.
    - [x] Handle file transfer failures.
    - [x] Show file transfer progress.
- [x] Allow Game Masters to reorder and remove videos from the playlist.

### 6. Video Playback Control
- [x] Implement the video player.
- [x] Implement the functionality for the Game Master to start, pause, and skip videos for all players.
- [x] Ensure that video playback is synchronized across all devices.

### 7. Advanced Controls
- [x] Implement the functionality for the Game Master to turn off the screen on all connected devices.
- [x] Implement the functionality for the Game Master to deactivate the torch on all connected devices.

### 8. Bluetooth Remote Control
- [x] Implement Bluetooth connectivity for a remote control.
- [x] Map remote control buttons to video playback actions (next, previous).

### 9. Invisible UI for Game Masters
- [x] Implement an invisible button or gesture for the Game Master to resume video playback.
- [x] Ensure the UI for Players and Game Masters looks identical to maintain the Game Master's anonymity.

### 10. Background Service
- [x] Implement a background service to maintain the network connection when the screen is off.
- [x] Ensure that the app can still receive commands from the Game Master when in the background.

### 12. UI Customization
- [x] Main screen during a started game session is entirely blue.

### 11. Testing and Deployment
- [x] Thoroughly test all features of the app.
  - [x] **Note:** Unit test for `GameSync.kt` has been fixed.
- [x] Prepare the APK for manual installation.
