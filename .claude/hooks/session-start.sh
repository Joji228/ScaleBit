#!/bin/bash
# Installs the Android SDK in Claude Code cloud sessions so ./gradlew can build, lint and run
# the unit tests. Needs dl.google.com allowed in the environment's network access settings.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-13114758_latest.zip"
PLATFORM="platforms;android-37"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL --retry 4 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses > /dev/null || true

# The newest stable build tools, so the version never has to be updated here by hand.
BUILD_TOOLS="$("$SDKMANAGER" --sdk_root="$SDK" --list 2>/dev/null \
  | grep -oE 'build-tools;[0-9]+\.[0-9]+\.[0-9]+ ' | tr -d ' ' | sort -t';' -k2 -V | tail -1)"
"$SDKMANAGER" --sdk_root="$SDK" --install "platform-tools" "$PLATFORM" "$BUILD_TOOLS" > /dev/null

echo "sdk.dir=$SDK" > "$CLAUDE_PROJECT_DIR/local.properties"
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
fi

# Fetch Gradle and the project's dependencies now, so the container caches them.
cd "$CLAUDE_PROJECT_DIR"
./gradlew --no-daemon -q :app:compileDebugUnitTestKotlin > /dev/null
