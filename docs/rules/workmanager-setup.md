# workmanager-setup

Default: **warning**. WorkManager is not set up the way the code needs:

- a `@HiltWorker` class, but nothing in the app uses `HiltWorkerFactory`; or
- a class implements `Configuration.Provider`, but the manifest keeps WorkManager's default initializer.

## Why it matters

WorkManager creates workers with its default factory, which only knows the `(Context, WorkerParameters)` constructor. A `@HiltWorker` has an `@AssistedInject` constructor that only `HiltWorkerFactory` can call. Without it, each run logs `Could not instantiate` and the work fails, silently, in the background.

WorkManager also starts itself at app launch, through `androidx.startup`, with the default configuration. It asks the Application for its own configuration (`Configuration.Provider`) only when that default initializer is removed from the manifest. Otherwise the custom configuration, and the worker factory in it, is never used.

## How to fix

```kotlin
@HiltAndroidApp
class App : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="merge">
    <meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup" tools:node="remove" />
</provider>
```

Getting the factory another way, as Now in Android does with a delegating worker and an entry point, also counts: the rule only fires when nothing uses `HiltWorkerFactory`.
