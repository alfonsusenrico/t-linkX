#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
NDK_CLANG="$SDK/ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android35-clang"

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="/tmp/tlinkx-companion-build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/res_compiled" "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/apk_stage/lib/arm64-v8a"

echo "[1/7] Compiling native arm64 touch bridge (libtlinkx_touchd.so)..."
"$NDK_CLANG" -O2 -Wall -Wextra -Werror \
  -o "$BUILD_DIR/apk_stage/lib/arm64-v8a/libtlinkx_touchd.so" \
  "$ROOT_DIR/native/tlinkx-touchd.c"
chmod 755 "$BUILD_DIR/apk_stage/lib/arm64-v8a/libtlinkx_touchd.so"

echo "[2/7] Compiling resources..."
"$BT/aapt2" compile --dir "$ROOT_DIR/res" -o "$BUILD_DIR/res_compiled/res.zip"

echo "[3/7] Linking resources..."
"$BT/aapt2" link \
  -I "$PLATFORM" \
  --manifest "$ROOT_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  -o "$BUILD_DIR/unsigned.apk" \
  "$BUILD_DIR/res_compiled/res.zip"

echo "[4/7] Compiling Java..."
find "$ROOT_DIR/src" "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
javac -source 11 -target 11 -classpath "$PLATFORM" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"

echo "[5/7] Converting to DEX..."
"$BT/d8" --min-api 26 --lib "$PLATFORM" --output "$BUILD_DIR/dex" $(find "$BUILD_DIR/classes" -name "*.class")

echo "[6/7] Packaging native binary + DEX and aligning..."
cp "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/with_dex.apk"
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/with_dex.apk" classes.dex)
(cd "$BUILD_DIR/apk_stage" && zip -q -0 "$BUILD_DIR/with_dex.apk" lib/arm64-v8a/libtlinkx_touchd.so)
"$BT/zipalign" -f -p 4 "$BUILD_DIR/with_dex.apk" "$BUILD_DIR/aligned.apk"

KEYSTORE="/tmp/tlinkx-companion-debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias tlinkxcomp -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TLinkX Companion, O=Local, C=ID"
fi

echo "[7/7] Signing..."
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --ks-key-alias tlinkxcomp \
  --out "$BUILD_DIR/tlinkx-companion.apk" \
  "$BUILD_DIR/aligned.apk"

"$BT/apksigner" verify --verbose "$BUILD_DIR/tlinkx-companion.apk" >/dev/null
ls -lh "$BUILD_DIR/tlinkx-companion.apk"
echo "DONE: $BUILD_DIR/tlinkx-companion.apk"
