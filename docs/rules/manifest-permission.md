# manifest-permission

Default: **error**, only when `.droidmap.toml` has `allowed_permissions`. A permission that is not on that list: in a source manifest, or added to the merged manifest by a library.

## Why it matters

Every permission in the final APK shows on the Play Store, has to be explained in the Data safety form, and can raise questions from users and reviewers. Libraries add their own: an analytics or crash library can add `ACCESS_NETWORK_STATE` or `AD_ID` to an app that never asked for them. The merged manifest is the only place they show, and nobody reads it on every dependency update.

When a build has produced the merged manifest (`build/intermediates/merged_manifests/`), droidmap reads it, names the library that added each permission from the merger report, and says when the file is older than the build files.

## Configuration

```toml
[manifest]
allowed_permissions = ["android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS"]
```

In CI, build the manifest before droidmap runs (for example `./gradlew :app:processReleaseManifest`) to check the libraries' permissions too.

## How to fix

Remove a permission you do not want, in the app's manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

Or add it to `allowed_permissions` if the app needs it.
