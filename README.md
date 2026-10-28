# Sports Command Center (Google TV)

A Google TV / Android TV app you sideload onto an onn. Google TV box. It follows the teams and players
you have bets or fantasy stakes on, and alerts you whenever something happens.

- **Dashboard**: a live card for each team (score, clock, down & distance, last play) and each player
  (current box-score line, fantasy points, and progress bars for your prop lines).
- **Live feed and pop-ups**: scores, touchdowns, home runs, goals, a player's stats ticking up,
  "PROP HIT", and "CASHED" / "LOST" when the game ends.
- **Alerts over other apps**: while you watch the game in YouTube TV, Hulu, etc., alerts pop up in the
  top-right corner. This needs the one-time permission described below.
- **Leagues**: NFL, College Football, NBA, WNBA, College Basketball, MLB, NHL (teams and players), MLS and
  Premier League (teams only).
- **Props**: for example Over 249.5 pass yards, Under 0.5 INT, Over 24.5 PTS, PRA, H+R+RBI, SOG, or
  fantasy points (PPR / Half-PPR / DraftKings NBA).

The data comes from ESPN's public scoreboard API. You don't need an account or API key. The API is
unofficial, and it usually runs 15–60 s behind the broadcast.

## Install on the onn. Google TV

### 1. Get the APK
Every push to this repo builds the APK in GitHub Actions (**Actions → Build APK**). The APK is attached
to the run and also published as a GitHub Release.

### 2a. Easiest: the Downloader app (no computer needed)
1. On the TV, open **Settings → System → About** and click **Android TV OS build** 7 times to turn on
   developer mode.
2. Install **Downloader** (by AFTVnews) from the Play Store.
3. Go to **Settings → Apps → Security & restrictions → Unknown sources** and turn on **Downloader**.
4. In Downloader, enter:
   `https://github.com/brian-comerford/Google-TV-Command-Center/releases/latest/download/SportsCommandCenter.apk`
   (This link only works if the repository is public. If it's private, use option 2b.)
5. Install the APK. **Sports Command Center** then shows up in your apps row.

### 2b. With a computer (ADB)
1. Turn on developer mode as in step 1 above. Then go to **Settings → System → Developer options** and
   turn on **USB debugging** (on Google TV this also allows network ADB).
2. Find the TV's IP address: **Settings → Network & Internet → (your Wi-Fi)**.
3. Run:
   ```sh
   adb connect <TV-IP>:5555
   adb install -r SportsCommandCenter.apk
   ```

### 3. Turn on alerts over other apps (one time)
Open the app and go to **Settings → Open permission settings**, then allow **Display over other apps**.
If your TV has no screen for this, run the following from a computer once:
```sh
adb shell appops set com.sportscc.tv SYSTEM_ALERT_WINDOW allow
```
To check that it works, press **Send a test alert**, then press Home and open another app. The alert
should pop up over it about 6 seconds later.

## Using it
- **+ Add**: pick a league, then a team. Choose to follow the team, or pick a player from its roster.
  After you pick a player, you can add prop lines: choose the stat, set the line with the ± buttons,
  and choose Over or Under.
- **Select a card** to edit its props, move it to the top, or stop following it.
- **Settings**: refresh rate during live games (10–60 s), every update or big plays only, and
  background alerts on or off.

## Project layout
```
core/   Plain Kotlin (JVM). ESPN parsing, stat/prop/fantasy math, alert diffing, polling tracker. Has unit tests.
app/    Android TV app. Compose for TV UI, background foreground service, overlay pop-ups.
```
To build locally you need the Android SDK (Android Studio is easiest): `./gradlew :app:assembleRelease`.
Run the core tests with `./gradlew :core:test`.

All builds are signed with `app/sideload.jks` (password `sportscc`). Because the key never changes,
each new APK installs over the old one and keeps your watchlist. The key only protects your own sideload,
so don't use it for anything else.
