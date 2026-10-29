# Notes for Claude

## Saved versions
- **"Non-Browser" version** = branch `non-browser` (and the `non-browser` tag/release CI creates from it): the app as it was before the built-in browser
  (stats around a web page) was added. It has everything up to single bets, parlays without money,
  fantasy mode, phone sync, Player Prop Finder import, prop bars in pop-ups, and so on.
  - Its APK is a permanent GitHub release:
    `https://github.com/brian-comerford/Google-TV-Command-Center/releases/download/non-browser/SportsCommandCenter.apk`
  - Never push to or rewrite the `non-browser` branch.
  - If the user asks to "revert to the Non-Browser version", bring the working branch back to that tree
    with a normal commit (never rewrite history), so a new "latest" APK is built:
    `git fetch origin non-browser && git revert --no-commit origin/non-browser..HEAD && git commit`,
    then check `git diff origin/non-browser -- app core web` is empty.
  - (The cloud sandbox's git proxy refuses tag pushes; GitHub-side branch creation and CI-created
    release tags work.)

## Building and testing
- The Android SDK isn't available in the cloud sandbox; CI (`.github/workflows/build-apk.yml`) builds the APK.
- `core/` is plain Kotlin and can be tested locally with a Gradle build that includes only `:core`.
