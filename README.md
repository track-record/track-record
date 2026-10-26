# Track Record

A music party game for Android. A song plays, nobody sees the title, and everyone guesses when it came out.

- **Song Order:** place each song among your own cards, in year order. The other players can veto your placement and steal the card.
- **Timeline:** everyone guesses the year, and the closest guess wins the round.

The host's phone plays the music from your own files or from Deezer previews. Other players can join from their own phones, in a browser on the same Wi-Fi or over the internet. Nothing needs installing on their side.

## Install

Download the APK from the [latest release](https://github.com/track-record/track-record/releases/latest) and open it on your phone. Android asks once to allow installs from your browser or file manager. The app needs Android 8 or newer.

The app checks this repository for new releases when it starts (you can turn that off in Settings). Keep it up to date: playing over the internet only works when the app matches the guest page, and the guest page is always the newest version.

## Songs

- **Your own music:** pick folders with audio files. The year comes from the files' tags. MusicBrainz and AcoustID can check it, and AcoustID also recognises a song by its sound.
- **Deezer:** import Deezer playlists or links, or song lists exported from Spotify (for example with [Exportify](https://github.com/watsonbox/exportify)). These songs play as Deezer's 30-second previews and need an internet connection.
- **Playlists:** make your own playlists to choose which songs a game uses.

## Building

You need Android Studio, or JDK 17 and the Android SDK, and the NDK for the fingerprint module (`fingerprint/`).

```
./gradlew assembleDebug
```

Optional files in the project root. Git ignores both.

- `secrets.properties`: defaults built into the app, such as an AcoustID application key and a contact address for MusicBrainz. Start from `secrets.properties.example`. Without it, lookups stay off until a key and an address are entered in Settings.
- `keystore.properties`: the release signing key (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it, release builds are unsigned.

The guest page for remote play is in `app/src/main/assets/remote`. `tools/publish_pages.py` publishes it to GitHub Pages.

## Third-party code

- [Chromaprint](https://github.com/acoustid/chromaprint) (LGPL 2.1) with KissFFT (BSD), in `fingerprint/src/main/cpp/chromaprint`.
- [minimp3](https://github.com/lieff/minimp3) (CC0), in `fingerprint/src/main/cpp/minimp3`.
- [Trystero](https://github.com/dmotz/trystero) (MIT), in `app/src/main/assets/remote/vendor`.
- [Roboto](https://github.com/googlefonts/roboto-3-classic) (SIL Open Font License), in `app/src/main/assets/remote/fonts`.

Track Record isn't affiliated with any commercial music game or with Deezer, Spotify, MusicBrainz or AcoustID.

## License

No license is granted: all rights reserved. You may read the code and install the released app, but you may not copy, modify or redistribute the code without permission. The third-party code above keeps its own licenses.
