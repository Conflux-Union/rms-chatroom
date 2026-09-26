#!/usr/bin/env bash
# Builds the on-demand Android TTS pack consumed by
# apps/android .../data/tts/TtsPackCatalog.kt.
#
# Inputs are pinned upstream artifacts:
#   - sherpa-onnx Android jniLibs (runtime .so for the downloaded device ABI)
#   - kokoro-int8-multi-lang-v1_1 (zh+en neural TTS model)
#
# Outputs (in <out-dir>, default /tmp/tts-pack-out):
#   tts-runtime-<ver>-arm64-v8a.zip   libsherpa-onnx-jni.so + libonnxruntime.so
#   tts-runtime-<ver>-x86_64.zip      same, emulator ABI
#   tts-model-kokoro-int8-v1_1.zip    model + lexicons + espeak-ng-data + dict
#
# Shipping: attach all three zips to the GitHub release tagged tts-assets-vN
# of Conflux-Union/rms-chatroom. The tag must NOT match the v* pattern (so
# the app build workflow does not fire) and the release must be created with
# --prerelease: the Android updater resolves updates through the
# /releases/latest API endpoint, and a full (non-prerelease) assets-only
# release would hijack "latest" and mask real app updates. Prerelease assets
# stay publicly downloadable. Then update the digests/sizes and
# RUNTIME_ASSET_DIR in TtsPackCatalog.kt with the script's summary.
set -euo pipefail

SHERPA_VERSION="1.13.8"
SHERPA_ANDROID_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-v${SHERPA_VERSION}-android.tar.bz2"
KOKORO_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"

OUT_DIR="${1:-/tmp/tts-pack-out}"
WORK_DIR="$(mktemp -d /tmp/tts-pack-build.XXXXXX)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> workdir: $WORK_DIR"
echo "==> outdir:  $OUT_DIR"
mkdir -p "$OUT_DIR"

if [[ ! -f "$OUT_DIR/sherpa-android.tar.bz2" ]]; then
  echo "==> downloading sherpa-onnx android runtime"
  curl -sL "$SHERPA_ANDROID_URL" -o "$OUT_DIR/sherpa-android.tar.bz2"
fi
if [[ ! -f "$OUT_DIR/kokoro-int8-multi-lang-v1_1.tar.bz2" ]]; then
  echo "==> downloading kokoro model"
  curl -sL "$KOKORO_URL" -o "$OUT_DIR/kokoro-int8-multi-lang-v1_1.tar.bz2"
fi

echo "==> extracting"
tar xjf "$OUT_DIR/sherpa-android.tar.bz2" -C "$WORK_DIR"
tar xjf "$OUT_DIR/kokoro-int8-multi-lang-v1_1.tar.bz2" -C "$WORK_DIR"

# Runtime zips: one per ABI, entries rooted at the ABI dir (the app unzips
# into filesDir/tts/runtime/). The C API .so files are not needed by the JNI
# path and are left out.
for abi in arm64-v8a x86_64; do
  mkdir -p "$WORK_DIR/rt/$abi"
  cp "$WORK_DIR/jniLibs/$abi/libonnxruntime.so" \
     "$WORK_DIR/jniLibs/$abi/libsherpa-onnx-jni.so" \
     "$WORK_DIR/rt/$abi/"
  (cd "$WORK_DIR/rt" && zip -q -r -X "$OUT_DIR/tts-runtime-$SHERPA_VERSION-$abi.zip" "$abi")
done

# Model zip: upstream archive contents at the zip root (the app unzips into
# filesDir/tts/model/). README/gitattributes dropped, LICENSE kept.
(cd "$WORK_DIR/kokoro-int8-multi-lang-v1_1" \
  && zip -q -r -X "$OUT_DIR/tts-model-kokoro-int8-v1_1.zip" . \
     -x README.md -x .gitattributes)

echo
echo "==> artifacts in $OUT_DIR (upload these to the tts-assets release):"
(cd "$OUT_DIR" && ls -lh tts-*.zip)

echo
echo "==> TtsPackCatalog.kt constants:"
for f in "$OUT_DIR"/tts-runtime-$SHERPA_VERSION-arm64-v8a.zip \
         "$OUT_DIR"/tts-runtime-$SHERPA_VERSION-x86_64.zip \
         "$OUT_DIR"/tts-model-kokoro-int8-v1_1.zip; do
  echo "# $(basename "$f")"
  echo "sha256: $(sha256sum "$f" | cut -d' ' -f1)"
  echo "bytes:  $(stat -c %s "$f")"
done
