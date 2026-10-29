# Notes for Claude

## Saved versions
- **"Non-Browser" version** = git tag `non-browser`: the app as it was before the built-in browser
  (stats around a web page) was added. It has everything up to single bets, parlays without money,
  fantasy mode, phone sync, Player Prop Finder import, prop bars in pop-ups, and so on.
  - Its APK is a permanent GitHub release:
    `https://github.com/brian-comerford/Google-TV-Command-Center/releases/download/non-browser/SportsCommandCenter.apk`
  - If the user asks to "revert to the Non-Browser version", restore the tree from that tag on the
    working branch and push a normal commit (never rewrite history), so a new "latest" APK is built:
    `git checkout non-browser -- . && git rm -r --cached --ignore-unmatch <files added since>`, or simply
    `git revert --no-commit non-browser..HEAD && git commit`. Check `git diff non-browser` is empty
    for app/, core/ and web/ afterwards.

## Building and testing
- The Android SDK isn't available in the cloud sandbox; CI (`.github/workflows/build-apk.yml`) builds the APK.
- `core/` is plain Kotlin and can be tested locally with a Gradle build that includes only `:core`.
