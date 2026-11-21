# Contributing

## Mac setup (Apple Silicon)

Everything comes from Homebrew. Android Studio is optional. On an Intel Mac, Homebrew lives in
`/usr/local` instead of `/opt/homebrew`, so change the paths below.

```sh
brew install openjdk@25 pre-commit
brew install --cask android-commandlinetools
sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-25.jdk
```

The `ln` step is Homebrew's own caveat. It lets Gradle, Xcode and the git hooks find the JDK without
your shell. Then add this to `~/.zshenv` and open a new terminal.

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Once per machine, accept the SDK licences and install the SDK packages the build uses.

```sh
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;36.0.0"
```

## The gate

Once per clone, install the git hooks. Then run the gate. It must be green before a commit.

```sh
tools/setup-hooks.sh
./gradlew --no-daemon verify
```

The commit hook checks formatting, ktlint and secrets. The push hook runs the same `verify`.

`verify` runs the iOS checks too when Xcode 27 is installed. Without Xcode it skips them and prints a
notice. Put `opensensor.skipIos=true` in `~/.gradle/gradle.properties` to skip them without it.

The bundled board templates test compares the app's copy with the firmware repository. It runs
when `opensensor-space-firmware` is cloned next to this repository, or when
`OPENSENSOR_FIRMWARE_DIR` names a checkout of it, and skips otherwise. CI always runs it at the
commit `verify.yml` pins.

Why the build needs JDK 25, and the rest of the build and CI, are in
[docs/build-and-release.md](docs/build-and-release.md).

To add your own board or sensor without changing the app, start with [docs/your-first-board.md](docs/your-first-board.md).
