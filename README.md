# Steam Data Widget (Android)

An Android home-screen widget for Steam developers, ported from
[Steam Data PC Widget](https://github.com/KaganAyten/Steam-Data-PC-Widget). It shows your
**daily and total wishlists**, **current players** and **24-hour peak** for your games and demos,
and **recent revenue**, all pulled from the official Steamworks APIs.

## Features

- **Summary tiles**: wishlists added today, total outstanding wishlists, players online right now, and latest revenue.
- **Automatic game discovery**: every game and demo on your Steamworks account is found through the publisher key. Demos are paired with their full game.
- **Sorted by momentum**: the game with the most wishlists today is at the top.
- **Expandable rows**: tap a game to see today / yesterday adds and deletes, total wishlists, players and 24h peak for the game and its demo, and net revenue with units sold.
- **Live player hint** on collapsed rows (`● 3 in game · 12 in demo`).
- **Detail** and **SteamDB** links on every game and demo line.
- **Clock-aligned refresh** (every hour by default, configurable) plus a manual refresh button.
- **Configurable day boundary** matching Steam's Pacific-time reporting day.
- **Resilient**: when Steam is down or you are offline, the last known numbers stay on the widget with a warning in the status line.
- Keys are stored only on the device, are excluded from Android backup, and are only ever sent to Steam's own endpoints.

## Requirements

- Android 8.0 (API 26) or newer.
- A Steamworks partner account with a **Financial API key** (wishlists and revenue) and a
  **Publisher Web API key** (game discovery and players of unreleased apps).
  See [Getting your API keys](https://github.com/KaganAyten/Steam-Data-PC-Widget#getting-your-api-keys)
  in the PC widget's README; the keys are the same.

## Install

1. Build the APK (below) or install `app/build/outputs/apk/debug/app-debug.apk` on your phone
   (`adb install app-debug.apk`, or copy it over and open it).
2. Long-press your home screen, choose **Widgets**, and add **Steam Data Widget**.
   The settings screen opens: paste your two keys and tap **Save**. The app also has an
   **Add widget to home screen** button; it works on launchers that support pin requests
   (Pixel Launcher, Nova, …) but Xiaomi's MIUI/HyperOS launcher silently ignores it, so use
   the long-press route there.
   On Xiaomi phones `adb install` also needs **Install via USB** enabled in Developer options.
3. The first refresh downloads the full wishlist history once. A game with about 1,400 days of
   history takes around 6–7 minutes, and progress is shown in the widget's status line. The app
   shows a small notification while this long download runs so Android does not stop it.
   Later refreshes take a few seconds.

You can reopen the settings any time by tapping the **Steam Stats** title on the widget or by
opening the app from the launcher.

### Compact per-game widget

The widget picker also offers **Steam Game**, a small 2×2 card for a single game. When you place
it, a list of your games appears; pick one. It shows today's net wishlist change with adds and
deletes, total wishlists, who is playing the game and its demo right now, and the latest net
revenue. Tapping it opens the game's Steamworks *Sales & Activations* detail page. Long-press →
reconfigure (where the launcher supports it) switches it to another game. The layout follows the
size you give it:

| Size | Layout |
|---|---|
| 2×2 (default) | Card: name, today's net change with adds/deletes, total wishlists, players, revenue, status |
| 4×2 or wider | The four summary tiles (Today, Wishlists, Players, Revenue) for this game, with the name as header |
| 2×1 / 4×1 | Two-line strip: name, today's change, total and players |

Per-game widgets share the main widget's data and refresh schedule, so adding several costs no
extra API calls. They work without the big widget too.

## Settings

| Setting | Default | Description |
|---|---|---|
| Financial API key | — | Required for wishlists and revenue. |
| Publisher Web API key | — | Finds your games and demos automatically, and player counts for unreleased apps. |
| Exclude App IDs | empty | Comma-separated App IDs to hide. Include the demo's ID too if you want to hide it. |
| Manual games | empty | JSON array such as `[{"name": "My Game", "appid": 123, "demo_appid": 456}]`. Merged with discovered apps; can override names or demo pairing. Use this if you have no publisher key. |
| Refresh every | 60 | Minutes, aligned to the clock (60 = top of every hour). Android may delay background refreshes slightly in battery saver or Doze. |
| Day starts at | 10 | Local hour when "today" rolls over. 10 in Türkiye (UTC+3) equals midnight Pacific Daylight Time. Use 11 from November to March, or your own local equivalent. |

**Clear cached data** in the settings deletes the downloaded wishlist history, sales, player samples and
discovered apps. Use it when switching to a different partner account.

## How the data is collected

Identical to the PC widget:

| Data | Source |
|---|---|
| Wishlists | `IPartnerFinancialsService/GetAppWishlistReporting`, per day; the total is the running sum of `adds − deletes − purchases − gifts`. Only the last 3 days are re-fetched on later refreshes. |
| Revenue | `GetChangedDatesForPartner` + `GetDetailedSales`, net USD grouped by primary App ID, last 45 days kept. Steam publishes sales with a 1–2 day delay, so the latest available day is shown. |
| Current players | `ISteamUserStats/GetNumberOfCurrentPlayers` (public); the partner endpoint with the publisher key for unreleased apps. |
| 24h peak | Collected on the device, one sample per refresh. `~` before the peak means less than 24 hours of samples. |
| Game list | `ISteamApps/GetPartnerAppListForWebAPIKey` + store `appdetails`, refreshed once a day. |

## Building

Requirements: JDK 17 or newer and the Android SDK (platform 35, build-tools 35). Point
`local.properties` at your SDK:

```
sdk.dir=C:\\Android\\sdk
```

Then:

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew assembleRelease`
produces a release build signed with the debug key; replace `signingConfig` in
`app/build.gradle.kts` with your own keystore for distribution.

### Build troubleshooting

| Symptom | Fix |
|---|---|
| `java.io.IOException: Unable to establish loopback connection` on Windows | Your temp directory path contains non-ASCII characters (for example a Windows username with `İ`). JDK 21 uses a Unix-domain socket in the temp dir and rejects such paths. Point the JVMs at an ASCII folder: create `C:\Android\tmp`, set `TEMP`/`TMP` to it, and add `-Djava.io.tmpdir=C:/Android/tmp` to `org.gradle.jvmargs` in `%USERPROFILE%\.gradle\gradle.properties` (or set `GRADLE_USER_HOME` to an ASCII folder and put the file there). |
| `Dosya adı, dizin adı veya birim etiketi sözdizimi hatalı` / `The filename, directory name, or volume label syntax is incorrect` | The `sdk.dir` path in `local.properties` is malformed. Use forward slashes: `sdk.dir=C:/Android/sdk`. |

## Project structure

```
app/src/main/java/com/steamdata/widget/
  Util.kt                 URLs, colours, formatting helpers
  Config.kt               settings (SharedPreferences) and runtime state
  Store.kt                JSON file cache, error log, HTTP client with 429 retry
  SteamApi.kt             wishlist / sales / player stores and game discovery
  Model.kt                the Snapshot the widget draws from
  Refresher.kt            one refresh cycle (discover, players, wishlists, sales)
  RefreshWorker.kt        WorkManager worker and clock-aligned scheduler
  SteamWidgetProvider.kt  the widget and its renderer
  GameListService.kt      the game rows (RemoteViews list)
  LinkActivity.kt         handles row taps and Detail / SteamDB links
  SettingsActivity.kt     settings and widget configuration screen
```

## Security notes

- The Financial key can read all sales data for your partner account. Treat it like a password.
- Keys live in the app's private storage and are excluded from cloud backup and device transfer.
- Only `partner.steam-api.com`, `api.steampowered.com` and `store.steampowered.com` are contacted.
- Consider restricting both keys to your IP addresses in Steamworks (note that a phone's IP changes often, so this may not be practical on mobile data).
