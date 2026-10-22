#!/bin/bash
# 准备内嵌 Node.js 运行时：下载 Termux 的 aarch64 Node 及依赖 deb，
# 用 patchelf 把版本化 SONAME/NEEDED 改为无版本名并设置 RPATH=$ORIGIN，
# 输出到 runtime-libs/jniLibs/arm64-v8a/（供 build-manual.sh 打包）。
# 依赖：curl、dpkg-deb（或 ar+tar）、patchelf。
set -e
cd "$(dirname "$0")/.."
WORK="runtime-libs"
DEBS="$WORK/debs"
OUT="$WORK/jniLibs/arm64-v8a"
BASE="https://packages-cf.termux.dev/apt/termux-main"
mkdir -p "$DEBS" "$OUT"

# Termux 仓库版本会滚动更新；如链接失效，请到
# https://packages-cf.termux.dev/apt/termux-main/pool/main/ 下找对应包的新版本文件名替换。
FILES="
pool/main/n/nodejs/nodejs_26.4.0-2_aarch64.deb
pool/main/libc/libc++/libc++_30_aarch64.deb
pool/main/o/openssl/openssl_1:3.6.5_aarch64.deb
pool/main/c/c-ares/c-ares_1.34.9_aarch64.deb
pool/main/libi/libicu/libicu_78.3_aarch64.deb
pool/main/libs/libsqlite/libsqlite_3.54.1_aarch64.deb
pool/main/z/zlib/zlib_1.3.2_aarch64.deb
pool/main/libf/libffi/libffi_3.8.0_aarch64.deb
"
for f in $FILES; do
    name="$(basename "$f")"
    [ -f "$DEBS/$name" ] || curl -sL -o "$DEBS/$name" "$BASE/$f"
    rm -rf "$DEBS/x_$name"
    mkdir -p "$DEBS/x_$name"
    dpkg-deb -x "$DEBS/$name" "$DEBS/x_$name"
done

U="data/data/com.termux/files/usr"
X="$DEBS/x_nodejs_26.4.0-2_aarch64.deb/$U"
cp "$X/bin/node" "$OUT/libnodebin.so"
cp "$DEBS/x_zlib_1.3.2_aarch64.deb/$U/lib/libz.so.1.3.2" "$OUT/libz.so"
cp "$DEBS/x_c-ares_1.34.9_aarch64.deb/$U/lib/libcares.so" "$OUT/"
cp "$DEBS/x_libsqlite_3.54.1_aarch64.deb/$U/lib/libsqlite3.so" "$OUT/"
cp "$DEBS/x_libffi_3.8.0_aarch64.deb/$U/lib/libffi.so" "$OUT/"
cp "$DEBS/x_openssl_1:3.6.5_aarch64.deb/$U/lib/libcrypto.so.3" "$OUT/libcrypto.so"
cp "$DEBS/x_openssl_1:3.6.5_aarch64.deb/$U/lib/libssl.so.3" "$OUT/libssl.so"
cp "$DEBS/x_libicu_78.3_aarch64.deb/$U/lib/libicudata.so.78.3" "$OUT/libicudata.so"
cp "$DEBS/x_libicu_78.3_aarch64.deb/$U/lib/libicuuc.so.78.3" "$OUT/libicuuc.so"
cp "$DEBS/x_libicu_78.3_aarch64.deb/$U/lib/libicui18n.so.78.3" "$OUT/libicui18n.so"
cp "$DEBS/x_libc++_30_aarch64.deb/$U/lib/libc++_shared.so" "$OUT/"

PE=patchelf
$PE --set-soname libz.so "$OUT/libz.so"
$PE --set-soname libcrypto.so "$OUT/libcrypto.so"
$PE --set-soname libssl.so "$OUT/libssl.so"
$PE --set-soname libicudata.so "$OUT/libicudata.so"
$PE --set-soname libicuuc.so "$OUT/libicuuc.so"
$PE --set-soname libicui18n.so "$OUT/libicui18n.so"
$PE --replace-needed libz.so.1 libz.so "$OUT/libsqlite3.so"
$PE --replace-needed libz.so.1 libz.so "$OUT/libnodebin.so"
$PE --replace-needed libcrypto.so.3 libcrypto.so "$OUT/libnodebin.so"
$PE --replace-needed libssl.so.3 libssl.so "$OUT/libnodebin.so"
$PE --replace-needed libicui18n.so.78 libicui18n.so "$OUT/libnodebin.so"
$PE --replace-needed libicuuc.so.78 libicuuc.so "$OUT/libnodebin.so"
$PE --replace-needed libcrypto.so.3 libcrypto.so "$OUT/libssl.so"
$PE --replace-needed libicudata.so.78 libicudata.so "$OUT/libicuuc.so"
$PE --replace-needed libicuuc.so.78 libicuuc.so "$OUT/libicui18n.so"
$PE --replace-needed libicudata.so.78 libicudata.so "$OUT/libicui18n.so" 2>/dev/null || true
$PE --set-rpath '$ORIGIN' --force-rpath "$OUT/libnodebin.so"
for f in "$OUT"/*.so; do $PE --set-rpath '$ORIGIN' --force-rpath "$f" 2>/dev/null || true; done

echo "完成：$OUT"
ls -la "$OUT"
