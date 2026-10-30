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

Typing that link with the remote is slow. Instead, open the phone website (below), expand
**Install or update the TV app** and tap **Copy**. Then paste the link into Downloader with the keyboard in
the Google TV app on your phone. The TV app's **Settings** also shows the link and your installed version.

### Updates
Once this version is installed, the app checks for a newer build when you open it and every few hours.
When there is one, it says so in the Live Feed (and over other apps if alerts are on) and shows an
**Update available** strip on the home screen. **Update now** downloads it and opens the installer. The first
time, the TV may ask you to allow installs from Sports Command Center. **Later** hides the strip until you
next open the app. **Settings → Check for updates** checks right away.

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
  After you pick a player, choose how you're following them:
  - **Fantasy**: the card leads with the live fantasy score (PPR for football, DraftKings for basketball)
    next to the live stat line, with no lines to set. Alerts include the points change, e.g. "(+5.1 PPR)".
  - **Bets**: add over/under lines: choose the stat, set the line with the ± buttons, and choose Over or Under. When
    a bet player's stats change, the pop-up shows a progress bar for each of their props, like the card does.
- **Phone**: link your phone and edit the list there (see below).
- **Select a card** to edit its props, move it, move it to the top, or stop following it.
- **Move a card**: select it, choose **Move**, then use the arrows to slide it around the grid. Press
  OK to place it, or Back to cancel. On the phone site, use the ↑ ↓ buttons on each item.
- **Settings**: refresh rate during live games (10–60 s), keep the screen awake (**During live games**, **Always** or **Never**: stops Google TV's screen saver while the app is open), card layout (**2 columns** or **1 wide column**), card order (**My order** keeps cards where
  you put them; **Live games first** lifts live games to the top), every update or big plays only,
  and background alerts on or off.

## Watch: a web page or stream with your stats around it
Press **Watch** on the home screen. A built-in browser (or video player) fills the top-left of the screen, with your cards
down the right side and live scores plus the Live Feed scrolling along the bottom.
- **Open a page:** on the phone website, type a site or search words in **Open on TV**; it opens on the
  TV within a few seconds (the TV app needs to be open). Or press **Search / URL** on the TV, or pick one of your **Saved** sites.
- **Remote:** the arrows move an on-screen pointer and OK clicks. Push past an edge to scroll, or switch
  to **Scroll** mode. Up at the top of the page reaches the toolbar. Back goes back a page, then exits.
- **Full-screen video** fills the browser area, not the whole TV, so the stats stay visible.
- **Layout:** Right + bottom, Right only, Bottom only, or Browser only. **Desktop site** asks pages
  for their desktop version, which often has a better video player.
- **Stream links (.m3u8):** paste one into **Open on TV** (or **Search** on the TV) and it plays in a
  built-in video player in the same spot, with your stats around it. It also plays DASH (.mpd), MP4 and
  MPEG-TS links. Remote: OK pauses or plays, ◀ ▶ skip 10 seconds, ▼ opens the channel list, ▲ reaches the
  toolbar, and Back returns to the browser. If a stream needs a particular User-Agent or Referer, add it
  after a pipe: `https://…/live.m3u8|User-Agent=VLC&Referer=https://example.com/`.
- **Playlists (.m3u):** paste a playlist link into **Open on TV**, or tap **Send a playlist file** on the
  phone site. The TV keeps its channels: press **Channels** to pick one, filter by group, or type part of
  a name. ☆ adds a channel to **Favorites**. **◀ Ch / Ch ▶** (or the remote's channel buttons) step through
  the list you picked from. **Refresh** downloads a linked playlist again. Channel names, logos, groups and
  `#EXTVLCOPT` User-Agent / Referer lines are read from the playlist.
- **Side by side:** **Split** puts two streams next to each other, each kept in its own shape (16:9 for
  most streams), with that game's score, situation, last play and your cards for it filling the space
  underneath (assign the stream to its game in **Games** for this). The card column on the right makes way
  so the video is bigger. ◀ ▶ move between the two sides (the
  highlighted one has the sound and the remote), ▼ picks a channel for that side, and **Single** (or Back)
  goes back to one stream, keeping the side you're on.
- **Streams for games:** press **Games** for today's games (from the leagues on your list). Play a stream
  (a playlist channel, or an .m3u8 link from your phone), then press **Assign** on its game. From the
  Channels list, **Game…** assigns a channel without playing it first. **Watch** jumps to a game's stream.
- **Auto-switch** (toolbar or Games): when a game with a stream gets exciting (a team inside the 20,
  overtime, or a close game in its final minutes), **Ask** shows "BUF in the red zone · press OK to watch",
  **Auto** switches by itself, **Off** does neither. It waits 90 seconds between switches. In split screen it
  replaces the side that isn't showing anything exciting.
- **Instant switching:** while one stream plays, the next likely one loads silently in the background and is
  kept near live: a game that's heating up (a drive past midfield, a close game late), or else the next
  channel in the list. Switching to it starts from what's already loaded. (Not in split screen, where the
  box is already playing two streams.)
- **Save** while a stream plays adds it to **Saved**. Streams that need a sign-in, or are copy-protected
  (DRM), won't play. Only use streams and playlists you're entitled to watch.
- Pop-up windows, new tabs, downloads and links that try to launch other apps are blocked.
- Paid services (YouTube TV, Hulu, Peacock, ESPN+, Netflix) generally refuse to play in an app's
  built-in browser. Plain web video players usually work.

The version before this feature is saved as **Non-Browser** (see `CLAUDE.md`), with its own APK:
`https://github.com/brian-comerford/Google-TV-Command-Center/releases/download/non-browser/SportsCommandCenter.apk`

## Phone remote (edit your list on your phone)
Typing is easier on a phone, so you can build your list on the website in `web/`, which is hosted on
GitHub Pages: `https://brian-comerford.github.io/Google-TV-Command-Center/`.

1. On the TV, press **Phone**. It shows this TV's code (like `K7QM-3XPA`) and a QR code.
2. Scan the QR code with your phone's camera, or open the site and type the code. You only do this once.
   If the TV already has a list, the site loads it.
3. Add teams and players, type your prop lines, and tap **Send to TV**. The TV picks up the new list
   within about 20 seconds (about 3 seconds while the Phone screen is open). It replaces the list on the TV.

**+ Bet** adds a single (straight) bet: a moneyline, spread, game total over/under or player prop.
On the TV it gets its own card showing the bet, the score and where it stands ("Covering by 3.5",
"Not covering · needs 4 more", "41 pts · 6 to spare"), then HIT, LOST or PUSH. There are no odds or
payouts, just whether it hit. Alerts fire when a spread or moneyline flips between winning and losing
("Now covering: BUF -3.5"), and when the bet is decided ("BET HIT", "Bet lost", "Bet pushed"). Like
parlay legs, the result is saved once decided.

**+ Parlay** builds a parlay from any mix of legs: player props, moneylines, spreads and game totals,
and shows only whether it hits, with no odds or payouts. On the TV, the parlay card lists every leg with ✓ hit,
✗ lost, ● live (green winning / amber losing) or ○ not started, plus progress bars on prop and total legs,
and a summary like "2 of 4 hit · 2 to go". Alerts fire when a leg hits (with "One leg away" when it's
down to one), when the parlay busts, and when it cashes. Each leg is saved as settled the moment it's
decided, so results don't reset when the scoreboard moves on to the next day or week.

**Sleeper** adds your Sleeper fantasy team: enter your Sleeper username (no password), pick the league,
and choose this week's starters or the whole roster. They're added as fantasy players (live stats + PPR).
With **Keep it matched to my lineup** on, the TV checks Sleeper every few hours and adds or drops players
as you change your lineup, with a "Lineup updated from Sleeper" note in the Live Feed. Players you added
yourself are never touched. Team defenses can't be tracked. **Stop** keeps the players but stops the updates.

**On pace for:** during a game, prop bars, parlay and bet legs, game totals and fantasy scores also say where
the number is headed at the current rate ("On pace for 287"), in green if that pace wins the bet and amber if
not. It's a straight-line projection over the game clock, shown once about a fifth of the game has been played.

**Import picks** loads today's recommended bets from
[Player Prop Finder](https://brian-comerford.github.io/Player-Prop-Finder/) (NFL), best edge first,
filterable by confidence. Tick the ones you bet and they're added with their lines, or tap
**Make a parlay** to turn them into one. That app uses
nflverse player ids, so players are matched to ESPN rosters by name and team (checking every roster
in case of a trade); anyone it can't match is listed so you can add them by hand.

The phone and TV pass the list through free public message relays, on a channel named after the TV's
code. Every message goes through [ntfy.sh](https://ntfy.sh) and three community-run ntfy servers
(ntfy.envs.net, ntfy.adminforge.de, ntfy.hostux.net) at once, so sync keeps working if one is down or
blocked on your network. If sync stops, **Actions → Relay check → Run workflow** shows which relays are
up. There are no accounts. Anyone who knew your code could send your TV
a list, so **New code** on the TV's Phone screen unlinks every phone. Messages are only kept on the
relays for about 12 hours, but both the TV and the phone keep their own copy of the list.

**One-time setup:** in the repo on GitHub, go to **Settings → Pages** and set **Source** to
**GitHub Actions**. After that, the *Phone website* workflow publishes `web/` on every push to the
default branch. On a free plan, GitHub Pages only works if the repository is public.

## Project layout
```
core/   Plain Kotlin (JVM). ESPN parsing, stat/prop/fantasy math, alert diffing, polling tracker. Has unit tests.
app/    Android TV app. Compose for TV UI, background foreground service, overlay pop-ups, phone sync.
web/    Phone website (plain HTML/JS, no build step), deployed to GitHub Pages.
```
To build locally you need the Android SDK (Android Studio is easiest): `./gradlew :app:assembleRelease`.
Run the core tests with `./gradlew :core:test`.

All builds are signed with `app/sideload.jks` (password `sportscc`). Because the key never changes,
each new APK installs over the old one and keeps your watchlist. The key only protects your own sideload,
so don't use it for anything else.
