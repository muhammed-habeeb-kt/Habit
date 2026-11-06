#!/usr/bin/env bash
# Downloads the two on-device AI models into the native module's assets folder.
#
#   Stage 1  nsfw_mobilenet_v2.tflite  224x224 NSFW MobileNetV2 classifier (LiteRT)
#   Stage 2  nudenet_320n.onnx         320x320 NudeNet YOLOv8n detector (ONNX Runtime)
#
# The files are gitignored (together they are ~30 MB), so this script runs in CI before
# `expo prebuild` and once locally after a fresh clone:
#
#   bash scripts/download-ai-models.sh
#
# Re-running is cheap: a file that already passes the checks is left alone. Downloads go to
# .cache/ai-models/ and the archives are deleted once extracted.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/modules/habit-native/android/src/main/assets"
CACHE="$ROOT/.cache/ai-models"
MIN_BYTES=$((1024 * 1024))

STAGE1_FILE="$ASSETS/nsfw_mobilenet_v2.tflite"
STAGE2_FILE="$ASSETS/nudenet_320n.onnx"

# The release notes use `mobilenet_v2_140_224.1.zip`; the older 1.1.0 asset is a fallback.
# (The 1.1.0 download name `mobilenet_v2_140_224.1.zip` returns 404, so it is not tried.)
STAGE1_URLS=(
  "https://github.com/GantMan/nsfw_model/releases/download/1.2.0/mobilenet_v2_140_224.1.zip"
  "https://github.com/GantMan/nsfw_model/releases/download/1.1.0/nsfw_mobilenet_v2_140_224.zip"
)
STAGE2_URLS=(
  "https://huggingface.co/zhangsongbo365/nudenet_onnx/resolve/main/320n.onnx"
  "https://huggingface.co/vladmandic/nudenet/resolve/main/nudenet.onnx"
)

log() { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

fetch() {
  # fetch <url> <output file>
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 2 --connect-timeout 20 -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$2" "$1"
  else
    fail "neither curl nor wget is available"
  fi
}

file_size() {
  wc -c <"$1" | tr -d ' '
}

is_tflite() {
  [ -f "$1" ] || return 1
  [ "$(file_size "$1")" -ge "$MIN_BYTES" ] || return 1
  # A real LiteRT flatbuffer carries the "TFL3" identifier at byte offset 4.
  [ "$(dd if="$1" bs=1 skip=4 count=4 2>/dev/null)" = "TFL3" ]
}

is_onnx() {
  [ -f "$1" ] || return 1
  [ "$(file_size "$1")" -ge "$MIN_BYTES" ] || return 1
  # First protobuf field of a serialized ONNX ModelProto is ir_version (tag byte 0x08).
  [ "$(dd if="$1" bs=1 count=1 2>/dev/null | od -An -tu1 | tr -d ' ')" = "8" ]
}

download_stage1() {
  if is_tflite "$STAGE1_FILE"; then
    log "stage 1 model already present ($(file_size "$STAGE1_FILE") bytes)"
    return
  fi

  mkdir -p "$CACHE" "$ASSETS"
  local zip="$CACHE/nsfw_model.zip"
  local url
  for url in "${STAGE1_URLS[@]}"; do
    if [ -s "$zip" ]; then
      log "reusing cached stage 1 archive"
    else
      log "downloading stage 1 model from $url"
      fetch "$url" "$zip" || { rm -f "$zip"; continue; }
    fi
    local member
    member="$(unzip -Z1 "$zip" 2>/dev/null | grep -E '(^|/)saved_model\.tflite$' | head -n 1 || true)"
    if [ -n "$member" ]; then
      unzip -p "$zip" "$member" >"$STAGE1_FILE.tmp"
      if is_tflite "$STAGE1_FILE.tmp"; then
        mv "$STAGE1_FILE.tmp" "$STAGE1_FILE"
        log "stage 1 model ready: $STAGE1_FILE ($(file_size "$STAGE1_FILE") bytes)"
        return
      fi
    fi
    rm -f "$STAGE1_FILE.tmp" "$zip"
    log "that source did not contain a usable .tflite model, trying the next one"
  done
  rm -f "$zip" "$STAGE1_FILE.tmp"
  fail "could not download the stage 1 NSFW model"
}

download_stage2() {
  if is_onnx "$STAGE2_FILE"; then
    log "stage 2 model already present ($(file_size "$STAGE2_FILE") bytes)"
    return
  fi

  mkdir -p "$CACHE" "$ASSETS"
  local url
  for url in "${STAGE2_URLS[@]}"; do
    log "downloading stage 2 model from $url"
    rm -f "$STAGE2_FILE.tmp"
    if fetch "$url" "$STAGE2_FILE.tmp" && is_onnx "$STAGE2_FILE.tmp"; then
      mv "$STAGE2_FILE.tmp" "$STAGE2_FILE"
      log "stage 2 model ready: $STAGE2_FILE ($(file_size "$STAGE2_FILE") bytes)"
      return
    fi
    rm -f "$STAGE2_FILE.tmp"
    log "that source did not return a usable .onnx model, trying the next one"
  done
  rm -f "$STAGE2_FILE.tmp"
  fail "could not download the stage 2 NudeNet model"
}

download_stage1
download_stage2

# Final gate for CI: both files must exist, be > 1 MB and carry the expected magic bytes.
is_tflite "$STAGE1_FILE" || fail "stage 1 model failed verification: $STAGE1_FILE"
is_onnx "$STAGE2_FILE" || fail "stage 2 model failed verification: $STAGE2_FILE"
log "AI models verified in $ASSETS"
