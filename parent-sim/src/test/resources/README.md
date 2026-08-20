# Test fixture APKs

Two throwaway apps that exist only to *appear on a child device*. The install guard's whole job
is deciding what to do about a package that turned up, so a scenario has to be able to make one
turn up — and every real app is either a system package (which the guard deliberately ignores)
or far too big to keep in a repository.

They contain no code at all (`android:hasCode="false"`), just a manifest with a package name, a
label and a launcher activity that could never actually start — so nothing about them can
influence the behaviour under test.

The launcher activity is not decoration. The app list a parent classifies from is built from
apps that HAVE a launcher entry (`AppInventory.launchableApps`), deliberately: it is about what
may be blocked. The install guard reads a wider list (`userPackages`) precisely because
something arriving quietly would skip having an icon. A fixture without a launcher activity can
therefore exercise the guard and nothing else, which is a confusing thing to discover twice.

Regenerate with the SDK's build tools:

```sh
cat > AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.sneaky.notapproved">
    <application android:hasCode="false" android:label="Sneaky Game">
        <activity android:name=".Main" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

aapt2 link --manifest AndroidManifest.xml \
  -I "$ANDROID_HOME/platforms/android-35/android.jar" \
  --min-sdk-version 26 --target-sdk-version 34 -o unsigned.apk
zipalign -f 4 unsigned.apk aligned.apk
apksigner sign --ks walcott-release.jks --ks-pass pass:walcott --key-pass pass:walcott \
  --ks-key-alias walcott --out unapproved-app.apk aligned.apk
```

`second-unapproved-app.apk` is the same with `com.sneaky.second` / "Second Sneak".

`startable-app.apk` (`com.sneaky.startable` / "Startable Toy") is the same recipe with one
difference that matters: its activity is `android:name="android.app.Activity"` rather than a
class of its own. A framework class resolves through the boot classloader, so an APK with no code
in it can still be brought to the FOREGROUND — which the other two cannot, and which anything
about the app a child is currently looking at needs. It comes up as a blank window and does
nothing, which is exactly right.

The signing key is irrelevant to what these test — any key works for a package nobody has
installed before — but reusing the repository's keystore keeps the recipe to one keystore.
