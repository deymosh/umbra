#!/usr/bin/env bash
# Installs a JDK 17 and the Android SDK cmdline-tools into <repo>/toolchain/,
# entirely local to this checkout (gitignored, never touches a system-wide
# install). Re-run anytime; already-installed pieces are skipped.
#
# Linux x86_64/aarch64 only (matches the CI runner and this dev container).
# Windows/macOS dev machines are expected to use a system-installed JDK 17 +
# Android Studio's SDK manager instead, per the project's normal workflow.
set -euo pipefail

REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
TOOLCHAIN_DIR="$REPO_ROOT/toolchain"
JDK_DIR="$TOOLCHAIN_DIR/jdk-17"
SDK_DIR="$TOOLCHAIN_DIR/android-sdk"

OS="$(uname -s)"
ARCH="$(uname -m)"
if [ "$OS" != "Linux" ]; then
  echo "error: this script only supports Linux (got: $OS)" >&2
  exit 1
fi
case "$ARCH" in
  x86_64) JDK_ARCH="x64" ;;
  aarch64|arm64) JDK_ARCH="aarch64" ;;
  *) echo "error: unsupported architecture: $ARCH" >&2; exit 1 ;;
esac

mkdir -p "$TOOLCHAIN_DIR"

# --- JDK 17 (Eclipse Temurin, via Adoptium's "latest GA" API) ---
if [ -x "$JDK_DIR/bin/java" ]; then
  echo "JDK already present at $JDK_DIR — skipping download"
else
  echo "Downloading Temurin 17 ($JDK_ARCH)..."
  TMP_TAR="$TOOLCHAIN_DIR/jdk.tar.gz"
  curl -fL -o "$TMP_TAR" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse"

  TMP_EXTRACT="$TOOLCHAIN_DIR/.jdk-extract"
  rm -rf "$TMP_EXTRACT"
  mkdir -p "$TMP_EXTRACT"
  tar -xzf "$TMP_TAR" -C "$TMP_EXTRACT"
  rm -f "$TMP_TAR"

  EXTRACTED_DIR="$(find "$TMP_EXTRACT" -mindepth 1 -maxdepth 1 -type d)"
  rm -rf "$JDK_DIR"
  mv "$EXTRACTED_DIR" "$JDK_DIR"
  rmdir "$TMP_EXTRACT"
  echo "Installed JDK: $("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
fi

# --- Android SDK cmdline-tools (Google's repository index, parsed for the
#     highest available commandlinetools-linux-*.zip, since Google does not
#     publish a stable "latest" URL under the currently-working host path) ---
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMANAGER" ]; then
  echo "Android cmdline-tools already present at $SDK_DIR — skipping download"
else
  echo "Resolving latest Android cmdline-tools..."
  REPO_XML="$TOOLCHAIN_DIR/repository2-3.xml"
  curl -fL -o "$REPO_XML" "https://dl.google.com/android/repository/repository2-3.xml"
  CMDLINE_ZIP_NAME="$(grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' "$REPO_XML" \
    | sort -t- -k3 -n -u | tail -1)"
  rm -f "$REPO_XML"
  if [ -z "$CMDLINE_ZIP_NAME" ]; then
    echo "error: could not resolve a commandlinetools-linux zip from Google's repository index" >&2
    exit 1
  fi

  echo "Downloading $CMDLINE_ZIP_NAME..."
  TMP_ZIP="$TOOLCHAIN_DIR/cmdline-tools.zip"
  curl -fL -o "$TMP_ZIP" "https://dl.google.com/android/repository/$CMDLINE_ZIP_NAME"

  # No unzip/python3 in this environment — the JDK's own `jar` tool extracts
  # the zip fine, since it's a standard zip archive under the hood.
  TMP_EXTRACT="$TOOLCHAIN_DIR/.cmdline-tools-extract"
  rm -rf "$TMP_EXTRACT"
  mkdir -p "$TMP_EXTRACT"
  ( cd "$TMP_EXTRACT" && "$JDK_DIR/bin/jar" xf "$TMP_ZIP" )
  rm -f "$TMP_ZIP"

  mkdir -p "$SDK_DIR/cmdline-tools"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$TMP_EXTRACT/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rmdir "$TMP_EXTRACT"

  # `jar xf` doesn't restore the zip's executable bits, unlike `unzip`.
  chmod +x "$SDK_DIR/cmdline-tools/latest/bin/"*
fi

# --- SDK packages: platform-tools, the platform matching this project's
#     compileSdk, and the highest available build-tools ---
COMPILE_SDK="$(grep -A1 'compileSdk {' "$REPO_ROOT/app/build.gradle.kts" | grep -oE '[0-9]+' | head -1)"
if [ -z "$COMPILE_SDK" ]; then
  echo "error: could not read compileSdk from app/build.gradle.kts" >&2
  exit 1
fi

export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

echo "Accepting Android SDK licenses..."
# `yes` gets SIGPIPE once sdkmanager stops reading stdin, which pipefail
# would otherwise surface as a spurious pipeline failure.
set +o pipefail
yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null
set -o pipefail

SDK_LIST="$("$SDKMANAGER" --sdk_root="$SDK_DIR" --list 2>/dev/null)"

# Stable (non -rc/-beta) build-tools only; the trailing dash-suffix on
# pre-release entries never matches [0-9.]+ so grep -o naturally excludes them.
BUILD_TOOLS_VERSION="$(printf '%s\n' "$SDK_LIST" \
  | grep -oE 'build-tools/[0-9.]+' | sed 's#build-tools/##' | sort -V -u | tail -1)"
if [ -z "$BUILD_TOOLS_VERSION" ]; then
  echo "error: could not resolve an available build-tools version" >&2
  exit 1
fi

# Platform package IDs aren't consistently bare (e.g. "android-26") vs.
# decimal (e.g. "android-37.0") across SDK levels — try bare first, then
# the ".0" form, and take an exact package-id match either way.
PLATFORM_ID="$(printf '%s\n' "$SDK_LIST" \
  | grep -oE "platforms/android-${COMPILE_SDK}(\.0)?(\s|\$)" | head -1 | tr -d '[:space:]')"
if [ -z "$PLATFORM_ID" ]; then
  echo "error: could not resolve platforms/android-$COMPILE_SDK (or .0) from the SDK package list" >&2
  exit 1
fi

echo "Installing platform-tools, $PLATFORM_ID, build-tools/$BUILD_TOOLS_VERSION..."
"$SDKMANAGER" --sdk_root="$SDK_DIR" \
  "platform-tools" \
  "$PLATFORM_ID" \
  "build-tools/$BUILD_TOOLS_VERSION"

# --- Point Gradle at the SDK (local.properties is gitignored; regenerated
#     deterministically here so it always matches this checkout's toolchain) ---
echo "sdk.dir=$SDK_DIR" > "$REPO_ROOT/local.properties"

cat <<EOF

Toolchain ready:
  JDK:         $JDK_DIR
  Android SDK: $SDK_DIR

local.properties was updated to point at the SDK above.
To use the JDK in your current shell:

  export JAVA_HOME="$JDK_DIR"
  export PATH="\$JAVA_HOME/bin:\$PATH"
EOF
