# WatchTunes 🎵⌚

An Android app that plays music on the **speaker of a calling smartwatch**,
such as the Titan Heritage.

## Why this is needed

Watches like this only take **call audio** from the phone (Bluetooth hands-free
profile). They don't accept **music audio** (A2DP), so songs normally keep
playing on the phone. WatchTunes treats the music like a call, which sends it
to the watch speaker.

## Features

- Pick songs from your phone and play them on the watch speaker
- Play, pause, next and previous, from the app or the notification
- Keeps playing with the screen off (foreground service)
- Loudness boost of up to +15 dB, because watch speakers are quiet
- Watch volume buttons (the phone's volume keys also control the watch)
- Pauses when the watch disconnects, so music doesn't suddenly play on the phone speaker
- **Experimental:** send *all* phone audio (YouTube, Spotify…) to the watch.
  It works on many Android phones but not all.

## Limitations

- Sound is **mono at phone-call quality**, which is fine for talk, podcasts and devotional songs.
- Songs play from the phone, which must stay within Bluetooth range (about 10 m).
- The watch drains faster while playing.
- You can't take a normal phone call while music is on the watch.
- Android only. iPhone doesn't allow apps to do this.

## Get the APK

Every push builds the app on GitHub Actions:

1. Open the repo's **Actions** tab, then the latest **Build APK** run.
2. Download the **WatchTunes-debug-apk** artifact and unzip it.
3. Copy `app-debug.apk` to your phone and install it. Allow "Install unknown apps" when asked.

Or build it yourself with Android Studio (open this folder and run), or with:

```bash
./gradlew assembleDebug
```

## How to use

1. Connect the watch to the phone as usual. **Bluetooth calling must work.**
2. Open WatchTunes and allow the Bluetooth and notification permissions.
3. Tap **Choose songs from phone** and pick one or more audio files.
4. Music plays on the watch. Use the boost slider and the watch volume buttons to make it louder.

## How it works

`WatchAudioRouter.kt` puts the phone into communication mode and selects the
watch's Bluetooth call device (`setCommunicationDevice` on Android 12+,
`startBluetoothSco` on older versions). `MusicService.kt` then plays songs with
the voice-communication audio usage, so Android sends them down that call
channel to the watch.
