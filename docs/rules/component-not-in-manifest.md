# component-not-in-manifest

Default: **error**. App code sends an explicit Intent to an activity, service or broadcast receiver of the project (`Intent(context, DetailActivity::class.java)`, `intent.setClass(...)`, `ComponentName(...)`), but no `AndroidManifest.xml` in the project declares that class.

## Why it matters

Android starts only components the manifest declares, and the compiler does not check it. Starting an undeclared activity throws `ActivityNotFoundException` ("Unable to find explicit activity class ...; have you declared this activity in your AndroidManifest.xml?"). An undeclared service never runs (`startService` returns null, `bindService` returns false), and an undeclared receiver never gets the broadcast; neither says why. Android Lint's similar check (`Registered`) is off by default and does not look at what the code starts.

## How to fix

Declare the component in the manifest of its module (or the app's):

```xml
<application>
    <activity android:name=".ui.DetailActivity" />
    <service android:name=".sync.SyncService" android:exported="false" />
</application>
```

If it is declared only in a variant's manifest (`src/debug/AndroidManifest.xml`), the other variants still crash: declare it in `src/main`.
