# tvbar

A remote-driven overlay, programme guide and home screen for **old vendor Android TVs**: sets on Android 5.x
(API 22) that are not Android TV or Google TV and show broadcast TV through the vendor's own tuner app.
Built on and for one Skyworth set with a Realtek chip. No root.

If your TV runs Android 8 or later, use [CleanYourTV](https://github.com/Gathin23/cleanyourtv) instead: its debloat
and launcher are far more complete. Its launcher needs API 26, so it cannot install on sets like this one,
and that gap is the reason this project exists.

## What the remote does

| Key | Where | Result |
|---|---|---|
| Hold Back (450 ms) | anywhere | Opens a lower-third panel: weather, plus optional arrivals and status sections. An amber strip fills while you hold, so the hold is visible before the panel opens. |
| Tap Back | panel open | Closes it. Everywhere else a tap is passed on as a normal Back. |
| ◀ | live TV, no menu open | Opens a programme guide over live TV. OK changes channel. ◀ again shows the channels most watched at this hour, counted on the TV. |
| Home | anywhere | Opens TV Home, a translucent home screen, so live TV keeps playing and keeps its sound. Home again goes to the vendor's home. |

## How it works

- **Keys:** an AccessibilityService with `flagRequestFilterKeyEvents` (`KeyService`). Guide and Home act on key down, which cut
  the time from press to paint roughly in half (guide 330–390 ms → 150–220 ms on the reference set).
- **Overlay:** a `TYPE_SYSTEM_ALERT` WebView (`BarService`). The pages ship inside the APK and fetch data from the server.
  On the reference set, the window manager only allows overlays from a process at foreground importance, so a transparent
  activity (`Launch`) creates it.
- **Live TV:** Realtek's `tv_manager` system service, reached by reflection: the current channel, `playChannelByNum`, and
  the broadcast (DVB EIT) EPG as a fallback for channels the online guide lacks. This part is vendor-specific.
- **TV Home:** translucent on purpose. The vendor's home releases the tuner when it stops; a translucent activity never stops
  it, so live TV keeps playing behind.
- **Frequent channels:** a per-minute sampler on the TV counts what is watched by weekday/weekend and hour. Nothing leaves the TV.

## Build and install

Requires JDK 17 and the Android SDK (build-tools 34, platform 34). No Gradle.

```bash
TVBAR_SERVER=http://<server-ip>:8093 ./build.sh
adb -s <tv-ip>:5555 install -r tvbar.apk
adb -s <tv-ip>:5555 shell settings put secure enabled_accessibility_services com.evnchn.tvbar/.KeyService
adb -s <tv-ip>:5555 shell settings put secure accessibility_enabled 1
```

The `settings put` lines replace any other enabled accessibility services; check
`settings get secure enabled_accessibility_services` first and append instead if it is not empty.

## Server

`server/tvbar_server.py` is stdlib Python. It serves the pages, `/data.json` and `/epg.json`:

- Weather from the Hong Kong Observatory open data API, and a guide from epg.pw XMLTV plus HOY's own per-channel XML.
  Both are Hong Kong examples; edit `CHANNELS`, `EPG_URL` and `weather()` for your region.
- Optional arrivals (`TVBAR_BUS_URL`) and status probes (`TVBAR_NODES`); see the docstring.

```bash
python3 server/tvbar_server.py 8093      # TVBAR_BIND to choose the address; the TV must be able to reach it
```

## Testing

`tools/test_trigger.sh` runs an end-to-end key regression on the TV (`TV=<tv-ip>:5555`). It presses keys with `tools/tvkey`,
which writes kernel input events, so they pass through the accessibility key filter like the real remote.
`adb shell input keyevent` skips that filter and cannot test it. Do not run `uiautomator dump` on these sets: it unbinds the
accessibility service.

## Security

Network ADB (port 5555) is how you install this, and also how ADB worms get in: the reference set was infected through
it before this project started. Allow only your own key, and turn network debugging off when you are done if your set allows it.

## Undo

```bash
adb -s <tv-ip>:5555 uninstall com.evnchn.tvbar
```

## Limits

- Tested on one set: Skyworth, Realtek, Android 5.1.1. The guide's live-TV parts need Realtek's `tv_manager`;
  other vendors need their own adapter (or Android's TV Input Framework where it exists).
- On API 26+ the overlay would need `TYPE_APPLICATION_OVERLAY` and the user-granted overlay permission.
- The bundled pages carry a JavaScript bridge and allow file-URL network access. Point `TVBAR_SERVER` only at a server you control.
