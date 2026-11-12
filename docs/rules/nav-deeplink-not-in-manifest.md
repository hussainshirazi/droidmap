# nav-deeplink-not-in-manifest

Default: **warning**. A deep link declared in navigation (`navDeepLink { uriPattern = ... }`, `navDeepLink<Route>(basePath = ...)` or `<deepLink app:uri>`) whose scheme and host no activity's intent filter accepts.

## Why it matters

Declaring a deep link in the navigation graph only tells Navigation where to go once the app has the intent. Android decides which app opens a URL from the manifest's intent filters. Without one, a link in a browser, an email or a notification built from the URL opens the browser or nothing, never the app. An explicit intent from inside the app still works, which is why it goes unnoticed.

## How to fix

Add an intent filter to the activity that hosts the `NavHost`:

```xml
<activity android:name=".MainActivity" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" android:host="www.example.com" />
    </intent-filter>
</activity>
```

For an XML navigation graph, `<nav-graph android:value="@navigation/nav_graph" />` inside the activity generates the filters at build time; droidmap counts it, along with the graphs it includes.
