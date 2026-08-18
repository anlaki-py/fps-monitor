# Surface FPS Monitor

A deliberately tiny Android 11+ utility that shows the presented FPS of the
foreground app using SurfaceFlinger TimeStats through Shizuku.

## Install and use

1. Install and start Shizuku.
2. Install the APK.
3. Open **FPS Monitor** and press **Start**.
4. Grant Shizuku, overlay, and notification permissions when requested.
5. Open a game or app. Drag the FPS button to move it.
6. Tap the FPS button to open its options. Select a currently available layer
   or leave **Auto** selected, and use the size slider to resize the FPS button.
   The fixed-size options panel scrolls when needed. Tap the FPS button again
   to close the options.

You can also press **Select app** and choose a launcher activity. In that mode,
the monitor always filters SurfaceFlinger layers for the selected package and
does not depend on foreground-app detection. Press **Use automatic detection**
to switch back.

If detection or sampling fails, open **View debug log** and use **Copy** or
**Share**. The log includes Shizuku state, focus/resumed lines, the detected or
selected package, TimeStats output size, returned layer names, matching layer
statistics, and command errors. It intentionally does not dump the complete
SurfaceFlinger report.

The automatic choice prefers SurfaceView/BLAST layers, then the layer with the
largest recent frame count. A static screen may correctly show `Idle / no data`.

## Technical notes

- Sampling interval: 500 ms
- Metric: SurfaceFlinger `averageFPS`
- Foreground source: WindowManager `mCurrentFocus`
- Privilege: Shizuku UserService (shell/root identity)
- UI: platform Android Views only
- External dependencies: Shizuku API and provider only

This [method](https://gist.github.com/anlaki-py/d9c8cf06cd54149d522adb8e665bfbd0) is OEM-dependent. It was designed from measurements made on a
Xiaomi 12T running Android 15 / HyperOS 2. 

## Build

Install Android SDK Platform 35 and Build Tools 35, then run:

```sh
./gradlew assembleDebug
```
