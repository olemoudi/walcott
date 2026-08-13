# Test fixture APKs

Two throwaway apps that exist only to *appear on a child device*. The install guard's whole job
is deciding what to do about a package that turned up, so a scenario has to be able to make one
turn up — and every real app is either a system package (which the guard deliberately ignores)
or far too big to keep in a repository.

They contain no code at all (`android:hasCode="false"`), just a manifest with a package name and
a label, so nothing about them can influence the behaviour under test.

Regenerate with the SDK's build tools:

```sh
cat > AndroidManifest.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.sneaky.notapproved">
    <application android:hasCode="false" android:label="Sneaky Game" />
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

The signing key is irrelevant to what these test — any key works for a package nobody has
installed before — but reusing the repository's keystore keeps the recipe to one keystore.
