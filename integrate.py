"""Merges OmniStream (dashboard, discovery, Wake-on-LAN, Xbox player) into the Moonlight engine checkout in ./engine."""
import os, re, shutil

E = "engine/app"

def patch(path, old, new, count=1):
    s = open(path, encoding="utf-8").read()
    if old not in s:
        raise SystemExit(f"Patch failed in {path}: {old!r} not found")
    open(path, "w", encoding="utf-8").write(s.replace(old, new, count))

# 1. Sources and signing key
dst = f"{E}/src/main/java/com/omnistream"
os.makedirs(dst, exist_ok=True)
for f in os.listdir("omnistream/src/com/omnistream"):
    shutil.copy(f"omnistream/src/com/omnistream/{f}", dst)
shutil.copy("omnistream/omnistream.jks", f"{E}/omnistream.jks")

# 2. Gradle: own app id + name (as Moonlight's author asks of forks), our signing key, lint off for release
g = f"{E}/build.gradle"
patch(g, 'applicationId "com.limelight"\n', 'applicationId "com.omnistream"\n')
patch(g, 'applicationIdSuffix ".unofficial"', '')
patch(g, 'resValue "string", "app_label", "Moonlight"\n', 'resValue "string", "app_label", "OmniStream"\n')
with open(g, "a", encoding="utf-8") as f:
    f.write('''
android {
    signingConfigs {
        omni { storeFile file("omnistream.jks"); storePassword "omnistream"; keyAlias "omni"; keyPassword "omnistream" }
    }
    buildTypes { release { signingConfig signingConfigs.omni } }
    lint { checkReleaseBuilds false; abortOnError false }
}
''')

# 3. Manifest: OmniStream dashboard becomes the launcher; Moonlight's PC screen stays as the in-app PC engine
m = f"{E}/src/main/AndroidManifest.xml"
s = open(m, encoding="utf-8").read()
s, n = re.subn(r'\s*<category android:name="android.intent.category.LAUNCHER" />', "", s, count=1)
if n != 1: raise SystemExit("LAUNCHER category not found")
for perm in ["ACCESS_WIFI_STATE", "CHANGE_WIFI_MULTICAST_STATE", "VIBRATE", "INTERNET"]:
    if f"android.permission.{perm}\"" not in s:
        s = s.replace("<application", f'<uses-permission android:name="android.permission.{perm}" />\n    <application', 1)
activities = '''
        <activity android:name="com.omnistream.MainActivity" android:label="OmniStream" android:exported="true"
            android:theme="@android:style/Theme.Material.NoActionBar"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name="com.omnistream.XboxActivity" android:label="Xbox Remote Play" android:exported="false"
            android:theme="@android:style/Theme.Material.NoActionBar.Fullscreen" android:hardwareAccelerated="true"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation" />
    </application>'''
s = s.replace("</application>", activities, 1)
open(m, "w", encoding="utf-8").write(s)
print("OmniStream merged into Moonlight engine")
