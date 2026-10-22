#!/bin/bash
# Sub-Store 安卓客户端手工构建（aapt2 + kotlinc + d8 + apksigner，不走 Gradle）
# 前置：assets/runtime 已放入后端 bundle + 前端 dist；jniLibs 已放入 patched Node 运行时
set -e
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$HOME/ohos-build/jdk17}"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-build/sdk}"
KOTLINC_HOME="${KOTLINC_HOME:-$HOME/android-build/kotlinc}"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
JAVA="$JAVA_HOME/bin/java"
export PATH="$JAVA_HOME/bin:$PATH"
APP="app"
BUILD="$APP/build-manual"
JNILIBS_SRC="${JNILIBS_SRC:-$(pwd)/runtime-libs/jniLibs}"
# 签名：优先用环境变量 SUBSTORE_KS_PASS；否则读维护者本地的 signing.local.txt（不入库）
KS_PASS="${SUBSTORE_KS_PASS:-$(sed -n 's/^storepass: //p' "$HOME/workspace/substore-build/signing.local.txt" 2>/dev/null)}"
[ -n "$KS_PASS" ] || { echo "缺少签名密码：设置 SUBSTORE_KS_PASS 或准备 signing.local.txt"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$BUILD/compiled.zip"

echo "[2/6] aapt2 link (unsigned apk + R.java)"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/src/main/AndroidManifest.xml" \
    -A "$APP/src/main/assets" \
    --java "$BUILD/gen" \
    --version-code 7 --version-name 1.1.3 \
    --min-sdk-version 26 --target-sdk-version 34 \
    "$BUILD/compiled.zip"

echo "[3/6] kotlinc (in-process, no daemon)"
"$JAVA" -cp "$KOTLINC_HOME/lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -cp "$ANDROID_JAR" \
    -jvm-target 17 \
    -d "$BUILD/classes" \
    $(find "$APP/src/main/java" -name "*.kt") \
    "$BUILD/gen/com/bosh/substore/R.java"

echo "[4/6] javac R.java + jar"
"$JAVA_HOME/bin/javac" -cp "$ANDROID_JAR:$BUILD/classes" \
    -d "$BUILD/classes" "$BUILD/gen/com/bosh/substore/R.java"
(cd "$BUILD/classes" && "$JAVA_HOME/bin/jar" cf "../classes.jar" .)

echo "[5/6] d8 dex + 打入 dex 与 jniLibs"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" --min-api 26 \
    --output "$BUILD/dex" \
    "$BUILD/classes.jar" "$KOTLINC_HOME/lib/kotlin-stdlib.jar"
python3 - "$BUILD/app-unsigned.apk" "$BUILD/dex/classes.dex" "$JNILIBS_SRC" <<'PYEOF'
import os, sys, zipfile
apk, dex, jni = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
    for root, _, files in os.walk(jni):
        for f in files:
            full = os.path.join(root, f)
            arc = os.path.relpath(full, jni)
            z.write(full, "lib/" + arc)
print("dex + jniLibs added")
PYEOF

echo "[6/6] zipalign + sign + verify"
"$BT/zipalign" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"
"$BT/apksigner" sign \
    --ks keystore/substore.keystore \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$BUILD/SubStore.apk" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$BUILD/SubStore.apk" | head -3
ls -la "$BUILD/SubStore.apk"
echo "BUILD OK: $BUILD/SubStore.apk"
