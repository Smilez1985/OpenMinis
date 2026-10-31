#!/bin/bash
set -euo pipefail

# ============================================================================
# afc for Android (aarch64) — the Rust core of the app_functions tool (D9)
# ============================================================================
# Builds deps/afc for aarch64-linux-android with the NDK and installs it as a
# native library, so the installer puts it into nativeLibraryDir (the only
# place an app may execute a file from, same reason as libproot.so):
#
#   src/android/app/src/main/jniLibs/arm64-v8a/libafc.so
#
# Prerequisites: Rust (rustup) with the target, Android NDK r27+ via
# $ANDROID_NDK_HOME. No crates.io access is needed: afc has no dependencies.
#
#   ./deps/build_afc_android.sh            # build + install
#   ./deps/build_afc_android.sh test       # also run the host tests first
#
# The binary is dynamic against Bionic (API 26 = minSdk), PIE, 16 KB aligned.
# It is started by the app as a child process; it never loads into the app.
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
AFC_DIR="$SCRIPT_DIR/afc"
OUT_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"
TARGET="aarch64-linux-android"
API=26

: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an NDK r27+ directory}"
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64)  HOST_TAG=linux-x86_64 ;;
  Darwin-*)      HOST_TAG=darwin-x86_64 ;;
  *) echo "unsupported build host $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
CLANG="$TOOLCHAIN/bin/${TARGET}${API}-clang"
test -x "$CLANG" || { echo "missing: $CLANG" >&2; exit 1; }

if [ "${1:-}" = "test" ]; then
  (cd "$AFC_DIR" && cargo test)
  sh "$AFC_DIR/tests/test_geraetetest.sh"
fi

rustup target add "$TARGET" >/dev/null 2>&1 || true

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CC_aarch64_linux_android="$CLANG"
# 16 KB pages (Android 15+ devices), no unwinding needed: panic=abort.
export RUSTFLAGS="${RUSTFLAGS:-} -C panic=abort -C link-arg=-Wl,-z,max-page-size=16384"

(cd "$AFC_DIR" && cargo build --release --target "$TARGET")

BIN="$AFC_DIR/target/$TARGET/release/afc"
test -s "$BIN" || { echo "build produced nothing" >&2; exit 1; }
mkdir -p "$OUT_DIR"
cp "$BIN" "$OUT_DIR/libafc.so"
"$TOOLCHAIN/bin/llvm-strip" "$OUT_DIR/libafc.so"
chmod 755 "$OUT_DIR/libafc.so"
echo "installed: $OUT_DIR/libafc.so ($(wc -c < "$OUT_DIR/libafc.so") bytes)"
