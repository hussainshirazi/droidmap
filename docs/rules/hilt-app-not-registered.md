# hilt-app-not-registered

Default: **error**. The project has `@AndroidEntryPoint` classes (activities, fragments, services, receivers, views), but the Application the manifest names is not the `@HiltAndroidApp` class or a subclass of it, or no Application is annotated `@HiltAndroidApp` at all.

## Why it matters

Hilt builds its components from the `@HiltAndroidApp` Application. An `@AndroidEntryPoint` class asks the running Application for them when Android creates it, and throws `IllegalStateException: Hilt Activity must be attached to an @HiltAndroidApp Application` if it is the wrong one. The build succeeds; the crash comes when the screen opens or the service starts. A missing or mistyped `android:name`, an Application renamed without updating the manifest, or a project that moved from Hilt to another library while one class kept `@AndroidEntryPoint` are the usual causes.

## How to fix

```kotlin
@HiltAndroidApp
class App : Application()
```

```xml
<application android:name=".App">
```

A subclass of the `@HiltAndroidApp` class (a debug Application, for example) is fine. If the project no longer uses Hilt, remove `@AndroidEntryPoint` and inject the class another way.
