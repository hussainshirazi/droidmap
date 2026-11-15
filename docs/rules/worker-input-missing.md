# worker-input-missing

Default: **warning**. A WorkManager worker reads an input key (`inputData.getString("userId")`) that none of the work requests the app makes for it put (`setInputData(workDataOf("user_id" to id))`, or `Data.Builder().putString(...)`).

droidmap checks a worker only when it sees every request made for it (`OneTimeWorkRequestBuilder<W>()`, `PeriodicWorkRequestBuilder<W>(...)`, `OneTimeWorkRequest.Builder(W::class.java)`) and the constant keys of each one's input. A builder handed to other code before `build()`, or input it cannot read, means it does not check that worker. Keys a test puts (`TestListenableWorkerBuilder`) are not reported.

## Why it matters

The worker gets null (or the default of `getInt`) every time, and often fails or does nothing in the background, where nobody sees it.

## How to fix

Use one constant for the key on both sides:

```kotlin
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { const val USER_ID = "user_id" }
    override suspend fun doWork(): Result {
        val userId = inputData.getString(USER_ID) ?: return Result.failure()
        ...
    }
}

OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf(SyncWorker.USER_ID to id)).build()
```
