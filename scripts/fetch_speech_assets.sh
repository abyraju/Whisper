#!/usr/bin/env bash
# Downloads the speech engine (sherpa-onnx native libs) and the models, verifying SHA-256.
# Usage: scripts/fetch_speech_assets.sh [base|tiny]     (Moonshine size, default: base)
set -euo pipefail

VARIANT="${1:-base}"
[[ "$VARIANT" == "base" || "$VARIANT" == "tiny" ]] || { echo "variant must be base or tiny" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android/app/src/main"
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

LIBS_URL="$BASE/v2.0.2/sherpa-onnx-v2.0.2-android.tar.bz2"
LIBS_SHA=08d22f7eb5c49bd161d248633eeb66e84735725706e21202b684800e5081a029
ZIP_NAME=sherpa-onnx-streaming-zipformer-en-20M-2023-02-17
ZIP_SHA=9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9
MOON_NAME="sherpa-onnx-moonshine-$VARIANT-en-quantized-2026-02-27"
if [[ "$VARIANT" == base ]]; then
  MOON_SHA=43232c1d13013d37317163baec3135bd771a186a4356f28c889bab453bb0e891
else
  MOON_SHA=9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d
fi

fetch() { # url sha out
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c - >/dev/null || { echo "CHECKSUM MISMATCH for $1" >&2; exit 1; }
}

echo "Fetching native libraries..."
fetch "$LIBS_URL" "$LIBS_SHA" "$TMP/libs.tar.bz2"
mkdir -p "$TMP/libs" && tar xjf "$TMP/libs.tar.bz2" -C "$TMP/libs"
for abi in arm64-v8a x86_64; do
  mkdir -p "$APP/jniLibs/$abi"
  cp "$TMP/libs/jniLibs/$abi/libonnxruntime.so" "$TMP/libs/jniLibs/$abi/libsherpa-onnx-jni.so" "$APP/jniLibs/$abi/"
done

echo "Fetching models (Moonshine $VARIANT)..."
fetch "$BASE/asr-models/$ZIP_NAME.tar.bz2" "$ZIP_SHA" "$TMP/zip.tar.bz2"
fetch "$BASE/asr-models/$MOON_NAME.tar.bz2" "$MOON_SHA" "$TMP/moon.tar.bz2"
mkdir -p "$TMP/m" && tar xjf "$TMP/zip.tar.bz2" -C "$TMP/m" && tar xjf "$TMP/moon.tar.bz2" -C "$TMP/m"

OUT="$APP/assets/models"
rm -rf "$OUT" && mkdir -p "$OUT/draft" "$OUT/polish"
for f in encoder-epoch-99-avg-1.int8.onnx decoder-epoch-99-avg-1.int8.onnx joiner-epoch-99-avg-1.int8.onnx tokens.txt; do
  cp "$TMP/m/$ZIP_NAME/$f" "$OUT/draft/$f"
done
for f in encoder_model.ort decoder_model_merged.ort tokens.txt LICENSE; do
  cp "$TMP/m/$MOON_NAME/$f" "$OUT/polish/$f"
done
echo "Done. Assets in $OUT"
