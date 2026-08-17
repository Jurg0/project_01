# maketestvideo

Generates the tiny video clips used by the instrumented playback tests in
`project_01_android/app/src/androidTest/assets/`.

ExoPlayer needs real media to answer questions about its own behaviour, and the app's playlist
logic depends on subtle end-of-video details that are only observable on a real device with a
real file (see `ExoPlayerEndOfItemContractTest`). These clips exist so those tests need nothing
from the phone, nothing from the network, and no camera footage: 2 seconds, 320x240, H.264,
solid colour with a brightness ramp so successive frames differ.

macOS only (AVFoundation). Swift ships with the Xcode command line tools.

## Regenerate

```bash
cd tools/maketestvideo
swiftc -O MakeTestVideo.swift -o maketestvideo

# <out.mp4> <seconds> <r> <g> <b>
./maketestvideo red_2s.mp4    2 200  40  40
./maketestvideo green_2s.mp4  2  40 200  40
./maketestvideo yellow_2s.mp4 2 200 200  40

cp *_2s.mp4 ../../project_01_android/app/src/androidTest/assets/
```

Deliberately not blue: blue is the game's safe-screen colour, and a test clip that looks like
the safe-screen would be confusing to watch.

The binary is not checked in — the clips are, since they are ~5KB each and regenerating them
requires a Mac.
