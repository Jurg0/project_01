# Field testing

Shared reference so a test run produces a definitive answer instead of another maybe.

## Target environment

| | |
|---|---|
| **Players on day one** | 8 (so 9 devices including the game master) |
| **Player fleet** | Unknown / open-ended — the app must work across a wide range of Android versions. `minSdk 24`. |
| **Game master device** | Not fixed. Currently tested on a Samsung S23; the real one will likely be a **Nothing Phone 3, which is not available for testing.** |
| **Test devices available** | Samsung S23 (game master), Samsung A20e (Android 11 / API 30), Samsung S9 (Android 10 / API 29) |
| **SIMs** | Only the game master currently has one; in the real game most phones probably will. |

Consequences that shape the code:

- **Nothing can be assumed about the host's network setup.** Different phones hand out different subnets, some publish no IPv4 gateway, some only an IPv6 one. This is why the host is found by *asking it* (a UDP probe it answers) rather than by deriving its address.
- **The real game master can never be pre-tested**, so the app has to describe its own state on-site — see Diagnostics below.
- **API-level differences are first-class.** An API 29 phone and an API 30 phone on the same hotspot failed differently; anything version-gated needs a fallback that older phones actually reach.
- **8 players is near the limit** of what phone hotspots typically accept (~8–10 clients). Worth measuring on the actual host before relying on it.

## Setup before a test

1. Game master: turn on Mobile Hotspot. Any name/password — the app doesn't need to know them. Prefer **2.4 GHz**; disable any "turn off hotspot automatically" timer.
2. Connect **every** player phone to that hotspot **before** starting the game.
3. Game master: double-tap the top-right corner → enter the prepared game's password.
4. Players: tap JOIN → enter the password.

## Diagnostics (use this instead of guessing)

**Long-press the bottom-left corner** on any device, on the start screen. (A different corner from CREATE on purpose — sharing one would let a slightly-too-slow first tap open this report in front of the players.) It reports device model, Android version and API level, every network interface and address, the gateways the link advertises, whether the host answered a discovery probe, the fallback address, and whether TCP port 8888 is actually reachable. "Copy" puts it on the clipboard.

Take this from **any phone that fails** — it is usually enough to identify the cause without adb.

With adb available:

```bash
adb logcat -s GameNet:D FileTransfer:D
```

`GameNet` covers host resolution, discovery and joining. `FileTransfer` covers video sync —
on a player you get `receiving X on port N`, its size, progress every 10%, and
`complete, NNNMB in NNs`; on the host, `sending X to <ip>`. Videos download **one at a time,
in playlist order**, so a partly-finished pre-load still leaves the early videos ready.

## Video resolution

The app downscales anything over 1920x1080 when you **add it to a playlist**, on the game
master's phone, and puts the smaller copy in the playlist. A Snackbar shows the percentage; a
big recording takes a minute or so. Nothing happens at game time.

Recording at Full HD in the first place skips that wait entirely, and is worth doing: 8K buys
nothing on a phone screen and costs about ten times the transfer time.

**Check the playlist on the phone, before the day.** Diagnostics (hold the bottom-left corner)
now lists every entry with the resolution the players will have to decode:

```
playlist: 3 video(s), 3 on this device
  1. 20260814_210013_1080p.mp4 — 1080x1920, 24MB, on this device
  2. 20260812_230726.mp4 — 1920x1080, 405MB, on this device
  3. 20260718_150016.mp4 — 7680x4320 TOO LARGE — older phones will stay blue, 215MB, on this device
```

Anything reading TOO LARGE will play on the game master's phone and show a **blue screen on
every player**, which looks exactly like broken playback sync. That was a real field test: two
8K clips, transfers all verified, three phones, an hour lost. Re-add the video (or save the
prepared game again) to convert it.

If the conversion fails you get the original plus a warning — take it seriously, and check the
diagnostics line. On a player with developer mode, `adb logcat -s GamePlay:W` shows the
playback error and `dumpsys media.metrics | grep -i codec` names the resolution that failed.

## Pre-loading videos before the day

Videos are cached permanently on each player, so large playlists can be synced ahead of time:

1. Start the game as usual and let every player join.
2. Leave them connected and open **Diagnostics on the host** (hold the bottom-left corner).
   It refreshes itself, so you can leave it open and watch each player until they all read
   `N/N videos`:

   ```
   player readiness:
     A20e — 3/5 videos, downloading anomaly4.mp4 62%, battery 74%
     S9   — 5/5 videos, battery 81%
   ```

   No adb needed. (`adb logcat -s FileTransfer:D` on a player still gives byte-level detail
   if you want it, but one device at a time — see below.)
3. End the game and hand the phones out. **The video files stay on the players.**
4. When the real game starts, players re-join and already-cached videos are reused — only
   genuinely missing ones transfer again.

A transfer interrupted by disconnecting is discarded rather than half-kept, so an
interrupted pre-load costs you that one video, not the whole playlist.

## Acceptance checklist

- [ ] Game master reaches the blue screen after entering the password.
- [ ] Every player reaches the blue screen after entering the correct password.
- [ ] A wrong password returns the player to the start screen and receives nothing.
- [ ] On the host, a password matching no prepared game starts nothing at all.
- [ ] Every video plays on the players, not just the host — check `player readiness` in the
      host's diagnostics before starting a large video; a player showing `2/3` has not
      received it yet and will sit on the blue screen while the host plays.
- [ ] Videos transfer to every player (check the playlist line in Diagnostics).
- [ ] Play/Next advances **all** devices together; **video 2 plays on players**, not just the host.
- [ ] Skipping back and forth quickly keeps devices in sync.
- [ ] Prev works; short videos don't get skipped.
- [ ] Torch and screen commands reach all players.
- [ ] A player that walks out of range and back rejoins on its own.
- [ ] End Game returns every device to the start screen.
- [ ] Record how many players the hotspot accepted before refusing.

## Reporting a failure

Include: which device failed, what you did, what it showed, and the Diagnostics text from that device. That combination has been enough to identify every failure so far; without it, a trip usually only rules out one guess.

## Status as of 2026-08-16

Confirmed working in the field, S23 host + A20e + S9 players:

- Host prepares and starts a game; a password matching no prepared game starts nothing.
- Both players join with the correct password; a wrong password returns them to the start screen.
- Sequential video sync completes, including a **405 MB file in 214 s (~1.9 MB/s)**.
- Playback is synchronized across host and both players.

Open, not yet tested:

- **8 players.** Phone hotspots commonly cap around 8–10 clients and this has never been tried.
  Measure it before relying on it — it is a hardware limit, not something app code can fix.
- **The real game master (a Nothing Phone 3) cannot be tested beforehand.** Everything host-side
  has only ever run on an S23. This is why the app must describe its own state on screen.
- **No "all players ready" signal.** You confirm a pre-load by watching `player readiness` in the
  host's diagnostics until everyone reads `N/N`. An active notification would be an improvement.
- Reconnect after walking out of range, and torch/screen commands, are still unverified this round.
