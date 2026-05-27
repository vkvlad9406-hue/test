#!/usr/bin/env bash
# Build a debug APK of the Duty Scheduler app on a clean Linux box (Ubuntu 22/24).
#
# It will:
#   1. Install OpenJDK 17 + curl + unzip if missing.
#   2. Download Android cmdline-tools, accept licenses, install platform 35 + build-tools 35.0.0.
#   3. Generate gradle-wrapper.jar (so the repo doesn't need to ship the binary).
#   4. Run ./gradlew assembleDebug and copy the APK to the project root.
#
# Idempotent: re-running just rebuilds.
#
# Usage:
#   bash tools/build-apk.sh [/abs/path/to/project]
#
# Outputs:
#   <project>/duty-scheduler-debug.apk
#   <project>/app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

PROJECT_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
echo "[build-apk] PROJECT_DIR=$PROJECT_DIR"

# 1. JDK + tools
if ! command -v java >/dev/null || ! java -version 2>&1 | grep -qE 'version "(17|21)'; then
  echo "[build-apk] Installing OpenJDK 17…"
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-17-jdk-headless unzip curl
fi
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
echo "[build-apk] JAVA_HOME=$JAVA_HOME"

# 2. Android SDK
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
CMDLINE_DIR="$ANDROID_HOME/cmdline-tools/latest"
if [ ! -x "$CMDLINE_DIR/bin/sdkmanager" ]; then
  echo "[build-apk] Installing Android cmdline-tools to $ANDROID_HOME"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmpzip="$(mktemp /tmp/cmdline-XXXXXX.zip)"
  curl -fsSL -o "$tmpzip" "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q -o "$tmpzip" -d "$ANDROID_HOME/cmdline-tools"
  rm -f "$tmpzip"
  # cmdline-tools unpacks as "cmdline-tools/"; rename to "latest/"
  if [ -d "$ANDROID_HOME/cmdline-tools/cmdline-tools" ]; then
    rm -rf "$CMDLINE_DIR"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$CMDLINE_DIR"
  fi
fi
export PATH="$CMDLINE_DIR/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses >/dev/null 2>&1 || true
echo "[build-apk] Installing platform-tools, platform 35, build-tools 35.0.0…"
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null

# 3. Gradle wrapper
cd "$PROJECT_DIR"
if [ ! -f gradlew ]; then
  echo "[build-apk] Generating gradle wrapper…"
  # Use a temporary gradle install
  GRADLE_VERSION="8.7"
  GRADLE_DIST="$HOME/.gradle-bootstrap"
  if [ ! -x "$GRADLE_DIST/gradle-$GRADLE_VERSION/bin/gradle" ]; then
    mkdir -p "$GRADLE_DIST"
    tmpzip="$(mktemp /tmp/gradle-XXXXXX.zip)"
    curl -fsSL -o "$tmpzip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    unzip -q -o "$tmpzip" -d "$GRADLE_DIST"
    rm -f "$tmpzip"
  fi
  "$GRADLE_DIST/gradle-$GRADLE_VERSION/bin/gradle" --no-daemon wrapper --gradle-version "$GRADLE_VERSION"
fi

# 4. Build
echo "[build-apk] Running ./gradlew assembleDebug…"
chmod +x gradlew
./gradlew --no-daemon assembleDebug
cp -f app/build/outputs/apk/debug/app-debug.apk "$PROJECT_DIR/duty-scheduler-debug.apk"
ls -la "$PROJECT_DIR/duty-scheduler-debug.apk"
echo "[build-apk] DONE"
