# Contributing

## Mac setup

Everything comes from Homebrew. Android Studio is not needed.

```sh
brew install openjdk@25
sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-25.jdk
brew install --cask android-commandlinetools
```

The `ln` step is Homebrew's own caveat. It lets `/usr/libexec/java_home`, Gradle and Xcode find the JDK
without your shell, which matters for GUI git clients, IDEs and the Xcode build phase.

Add this to `~/.zshenv`, then open a new terminal.

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

```sh
java -version                        # expect 25
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
tools/setup-hooks.sh                 # once per clone, the pre-push hook runs verify
./gradlew verify
```

Build-tools 36.0.0 is the version AGP uses by default, so the build does not download it again.

For iOS, install Xcode 27 from the App Store. Without Xcode, add `-Popensensor.skipIos=true` to skip
the iOS checks. Why the build needs JDK 25 is in [docs/build-and-release.md](docs/build-and-release.md#build-jdk).
