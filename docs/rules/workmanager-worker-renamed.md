# workmanager-worker-renamed

Default: **warning**. With `--base` (and on pull requests in the GitHub Action): a Worker class the change renamed, moved to another package, or deleted, while the code at the base enqueued it as periodic work (`PeriodicWorkRequestBuilder<SyncWorker>(...)`).

Not reported when the code now cancels that unique work (`cancelUniqueWork("sync")`), enqueues it with `ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE` (or `REPLACE`), or still names the old class as a string (a `WorkerFactory` that maps it).

## Why it matters

WorkManager saves periodic work in its database with the worker's class name, and keeps it across app updates. After the update that class no longer exists: each time the work is due, WorkManager cannot create it ("Could not instantiate ..."), and the work fails, forever, on every phone that had it. Enqueueing again under the same unique name with `KEEP` keeps the broken work, and `UPDATE` cannot change a worker's class. A fresh install, as in testing, works.

## How to fix

Replace the saved work once, in the release that renames the class:

```kotlin
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "sync", ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
)
```

Or keep a class with the old name, or cancel the old work (`cancelUniqueWork`) when the feature is removed.
