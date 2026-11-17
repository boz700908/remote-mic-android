#!/usr/bin/env bash
# 下载 sherpa-onnx 官方预编译 Android AAR 到 app/libs/（约 40 MB）
set -euo pipefail
VER="${1:-2.0.1}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs"
mkdir -p "$DEST"
OUT="$DEST/sherpa-onnx-$VER.aar"
if [ -f "$OUT" ]; then echo "已存在: $OUT"; exit 0; fi
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VER/sherpa-onnx-$VER.aar"
echo "下载 $URL"
curl -fL --retry 3 -o "$OUT" "$URL"
ls -lh "$OUT"
