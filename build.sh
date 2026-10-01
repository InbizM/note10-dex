#!/data/data/com.termux/files/usr/bin/bash
set -e

PROJECT_DIR="/data/data/com.termux/files/home/note10_project"
ANDROID_JAR="/data/data/com.termux/files/home/apk_build/android.jar"
KEYSTORE="/data/data/com.termux/files/home/note10.keystore"
OUTPUT_APK="/sdcard/Download/Note10.apk"

echo "=== 1. Cleaning build folders ==="
cd "$PROJECT_DIR"
rm -rf obj dex unsigned.apk aligned.apk "$PROJECT_DIR/src/com/dex/note10/R.java"
mkdir -p obj dex

echo "=== 2. Generating R.java ==="
aapt package -m -J src -M AndroidManifest.xml -S res -I "$ANDROID_JAR"

echo "=== 3. Compiling Java sources ==="
javac -encoding UTF-8 -cp "$ANDROID_JAR" -d obj $(find src -name "*.java")

echo "=== 4. Converting to DEX with d8 ==="
d8 --min-api 24 --output dex/ $(find obj -name "*.class")

echo "=== 5. Packaging APK with assets and resources ==="
aapt package -F unsigned.apk -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -A assets

echo "=== 6. Adding classes.dex to APK ==="
cd dex
aapt add ../unsigned.apk classes.dex
cd ..

echo "=== 7. Zipalign APK ==="
zipalign -v -p 4 unsigned.apk aligned.apk

echo "=== 8. Signing APK with apksigner ==="
apksigner sign --ks "$KEYSTORE" --ks-key-alias note10 --ks-pass pass:123456 --key-pass pass:123456 --out "$OUTPUT_APK" aligned.apk

echo "=== 9. Verifying APK signature ==="
apksigner verify --verbose "$OUTPUT_APK"

echo "========================================="
echo "Build Successful: $OUTPUT_APK"
ls -lh "$OUTPUT_APK"
echo "========================================="
