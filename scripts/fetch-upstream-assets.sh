#!/bin/bash
# 从上游 GitHub Release 下载 Sub-Store 后端 bundle 与前端 dist，放到 assets/runtime/
# 用法：BACKEND_VER=2.43.0 FRONTEND_VER=2.34.1 ./scripts/fetch-upstream-assets.sh
# 不传版本时自动取 latest。
set -e
cd "$(dirname "$0")/.."
DEST="app/src/main/assets/runtime"
mkdir -p "$DEST/frontend"
UA="CatSub-Build"

latest_tag() {
    curl -s -H "User-Agent: $UA" "https://api.github.com/repos/sub-store-org/$1/releases/latest" \
        | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -1
}

BACKEND_VER="${BACKEND_VER:-$(latest_tag Sub-Store)}"
FRONTEND_VER="${FRONTEND_VER:-$(latest_tag Sub-Store-Front-End)}"
echo "后端版本：$BACKEND_VER / 前端版本：$FRONTEND_VER"

curl -sL -H "User-Agent: $UA" -o "$DEST/sub-store.bundle.js" \
    "https://github.com/sub-store-org/Sub-Store/releases/download/$BACKEND_VER/sub-store.bundle.js"

TMP="$(mktemp -d)"
curl -sL -H "User-Agent: $UA" -o "$TMP/dist.zip" \
    "https://github.com/sub-store-org/Sub-Store-Front-End/releases/download/$FRONTEND_VER/dist.zip"
rm -rf "$DEST/frontend"
mkdir -p "$DEST/frontend"
unzip -q "$TMP/dist.zip" -d "$TMP/dist"
# dist.zip 可能带顶层 dist/ 目录，也可能直接是文件树
if [ -f "$TMP/dist/index.html" ]; then SRC="$TMP/dist"; else SRC="$TMP/dist/dist"; fi
cp -r "$SRC/." "$DEST/frontend/"
rm -rf "$TMP"
echo "完成：$DEST/sub-store.bundle.js + $DEST/frontend/（含 index.html: $(test -f "$DEST/frontend/index.html" && echo yes)）"
