# Red TV — Android TV / Fire TV IPTV Player

A native Android TV IPTV **player** for Fire TV Stick and Android TV boxes. It is a media
player only: it plays whatever streams you point it at through your own account config.
Loading legal content (your paid IPTV subscription or free legal playlists) is your
responsibility, exactly like VLC, Kodi, or TiVimate.

Built with Kotlin + Media3/ExoPlayer + Leanback launcher support. One APK installs on both
Fire TV and Android TV.

## Features in this build
- **Two source types:** M3U / M3U8 playlist (URL or file) and Xtream Codes login.
- **Multiple sources:** save several providers as named profiles and switch between them
  instantly with the **Sources** button. Great for a main line plus a backup.
- **Remote account config:** a profile can point to a config.json URL you host online and
  edit anytime to change channels or login. The app re-reads on every launch. No rebuild.
- **EPG / TV guide:** XMLTV support. Shows Now / Next on cards and in the player overlay.
- **Favorites, categories, Continue Watching:** sidebar with Continue, Favorites, Recent, All,
  provider categories, and a Hidden list.
- **Channel management:** long-press OK on any channel to Favorite, **Pin to top** (reorder),
  or **Hide** it. Optional **Hide duplicate channels** toggle in setup.
- **Search:** filter channels and movies by name.
- **Resume / recent:** movies resume where you left off; recent channels tracked; movies in
  progress appear under Continue.
- **Player polish:** aspect-ratio toggle (Fit / Stretch / Zoom), audio-track and
  subtitle/CC selection, tuned buffering. Press **Menu** on the remote in the player for options.
- **D-pad first UI:** focus highlights, channel up/down in the player.

## 1. Build the APK (Android Studio)
1. Install **Android Studio** (latest). It bundles Gradle and JDK 17.
2. `File > Open` and select this **RedTV** folder. Let it sync (it downloads Gradle 8.7 and
   the AndroidX/Media3 libraries the first time).
3. Plug in or pair a device, or just build the APK: `Build > Build Bundle(s)/APK(s) > Build APK(s)`.
4. The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

CLI alternative (if you have Gradle installed): run `gradle wrapper` once in this folder to
generate the wrapper, then `./gradlew assembleDebug`.

## 2. Sideload to a Fire TV Stick
1. On the Firestick: **Settings > My Fire TV > Developer Options > Install unknown apps**, and
   enable it for the **Downloader** app (install Downloader from the Amazon Appstore first).
2. Put `app-debug.apk` somewhere reachable (Google Drive direct link, Dropbox direct link, or
   a small web host). Open Downloader and enter that URL to download + install.
3. Alternative over Wi-Fi with adb (PC):
   ```
   adb connect <firestick-ip>:5555
   adb install -r app-debug.apk
   ```
   Find the IP under Settings > My Fire TV > About > Network.

## 3. Sideload to an Android TV box
Either use the same **Downloader** method, or `adb install -r app-debug.apk` over USB/Wi-Fi.
The app shows up in the launcher row because it declares `LEANBACK_LAUNCHER`.

## 4. Host your config online (the "edit online" part)
The app needs a URL that returns your config.json. Easiest options:

**GitHub (free, fast):**
1. Create a repo, add `config.json` (use the editor or the example files here).
2. Open the file, click **Raw**, copy that `raw.githubusercontent.com/...` URL.
3. Paste it into the app's setup screen. To update channels/login later, edit the file in
   GitHub and the app picks it up next launch.

**Firebase Hosting / Cloudflare Pages / any static host:** upload `config.json`, use its
public URL. Same idea.

Use **`web-editor/index.html`** (open it in any browser) to build or edit the JSON visually,
then Download `config.json` and upload it to your host. Two ready examples are included:
`config.example.m3u.json` and `config.example.xtream.json`.

### config.json shape
```json
{
  "accountId": "red-001",
  "appName": "Red TV",
  "userAgent": "RedTV/1.0 (Android)",
  "source": {
    "type": "xtream",
    "host": "http://provider.com:8080",
    "username": "USER",
    "password": "PASS",
    "epgUrl": "http://provider.com:8080/xmltv.php?username=USER&password=PASS"
  }
}
```
For an M3U source use `"type": "m3u"` and `"m3uUrl": "..."` instead of host/username/password.

## 5. Quick test without a subscription
Set the source to M3U and use a free legal playlist, e.g. the public iptv-org list in
`config.example.m3u.json`. Channels load straight away so you can verify the UI and playback
on your device before pointing it at your own provider.

## Build a signed release APK (shareable, no debug warning)
A debug APK works for your own testing, but a signed release APK is what you share or
distribute. Setup is one-time:

1. Generate a keystore (run from the project root):
   - Mac/Linux: `./make-keystore.sh`
   - Windows: `make-keystore.bat`
   This creates `redtv-release.jks` and `keystore.properties`. **Back up the .jks file** -
   you need the exact same keystore to push updates later.
2. Build the signed APK:
   - Android Studio: `Build > Generate Signed Bundle / APK` (or just `Build APK` once
     `keystore.properties` exists - the release build auto-signs).
   - CLI: `./gradlew assembleRelease`
3. Output: `app/build/outputs/apk/release/app-release.apk`. Sideload it the same way as the
   debug APK.

If `keystore.properties` is absent, the project still builds fine (release just stays
unsigned), so this step is optional until you want to distribute.

## Using the app
- First launch shows **Setup**. Name the source, then paste your config URL (recommended) or
  enter an M3U / Xtream source manually.
- Browse with the D-pad. Left column = categories (Continue, Favorites, Recent, All, ...).
  OK opens a channel. **Long-press OK** on a channel for Favorite / Pin to top / Hide.
- In the player: **Channel Up/Down** or **D-pad Up/Down** switches channels. Press **Menu** for
  aspect ratio, audio track, and subtitles. Info overlay shows Now/Next from the EPG.
- **Sources** (top right) switches between saved providers or adds a new one.
- **Settings** reopens setup to edit the active source or toggle "Hide duplicate channels".

## Project layout
```
app/src/main/java/com/redtv/app/
  model/    data classes + config schema
  net/      Http, M3uParser, XtreamClient, EpgParser
  data/     Prefs (profiles, favorites, hidden, pinned, recents, resume), ContentRepository
  ui/       SetupActivity, MainActivity (browser), PlayerActivity, adapters
web-editor/ index.html   visual config.json builder
config.example.*.json    ready-to-host samples
make-keystore.sh/.bat    one-time release keystore generator
```

## Extending it (v2 ideas)
- **Series support:** add `get_series` / `get_series_info` calls in `XtreamClient` to list
  episodes (`{host}/series/{user}/{pass}/{episodeId}.{ext}`).
- **Full EPG grid:** a timeline view per channel using the parsed `EpgParser` data.
- **Timeshift / catch-up / DVR**, multi-view, parental PIN, Stalker portal source type.
- **Cloud account link:** swap the static JSON for a tiny backend (Firebase Auth + Firestore)
  so logins are tied to a real account and you can push updates per user.

## Notes
- Cleartext HTTP is allowed (`network_security_config.xml`) because many providers serve over
  http. Streams come only from URLs in your config.
- minSdk 21 covers Fire TV Stick gen 2+ and essentially all current Android TV boxes.
- This is a debug build for testing. For wider distribution, generate a signed release APK.
