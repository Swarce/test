#!/bin/sh
# Builds the desktop server jar and the Android manager APK without Gradle:
#   javac -> dx (dalvik-exchange) -> aapt -> zipalign -> apksigner
# Needs a JDK, the Android API 23+ platform jar and the build tools
# (Debian/Ubuntu: apt install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner).
#
#   ANDROID_JAR   platform jar to compile against (default: /usr/lib/android-sdk/platforms/android-23/android.jar)
#   KEYSTORE      signing keystore (default: build/debug.keystore, created on first build)
#   KS_PASS       keystore password (default: android)
set -e
cd "$(dirname "$0")"
ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KEYSTORE=${KEYSTORE:-build/debug.keystore}
KS_PASS=${KS_PASS:-android}
DX=$(command -v dalvik-exchange || command -v dx)
B=build

rm -rf $B/core-classes $B/app-classes $B/apk $B/apk-assets
mkdir -p $B/core-classes/sgm/web $B/app-classes $B/apk $B/apk-assets

echo "== core"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -Xlint:-options -nowarn \
  -d $B/core-classes $(find core/src -name '*.java')
cp core/web/admin.html $B/core-classes/sgm/web/
printf 'Main-Class: sgm.core.Main\n' > $B/manifest.txt
jar cfm $B/sgm-core.jar $B/manifest.txt -C $B/core-classes .

echo "== app"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -Xlint:-options -nowarn \
  -cp $B/core-classes -d $B/app-classes $(find app/src -name '*.java')
$DX --dex --min-sdk-version=24 --output=$B/apk/classes.dex $B/core-classes $B/app-classes

cp app/assets/* $B/apk-assets/
cp core/web/admin.html $B/apk-assets/
aapt package -f -M app/AndroidManifest.xml -S app/res -A $B/apk-assets -I "$ANDROID_JAR" -F $B/unaligned.apk
(cd $B/apk && aapt add ../unaligned.apk classes.dex > /dev/null)
zipalign -f 4 $B/unaligned.apk $B/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias sgm \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=SGM Manager" > /dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --out $B/sgm-manager.apk $B/aligned.apk
rm -f $B/unaligned.apk $B/aligned.apk $B/sgm-manager.apk.idsig
apksigner verify $B/sgm-manager.apk
ls -la $B/sgm-core.jar $B/sgm-manager.apk
