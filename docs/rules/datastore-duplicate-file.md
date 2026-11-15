# datastore-duplicate-file

Default: **error**. Two DataStores open the same file in one app:

- two delegates with one name (`by preferencesDataStore(name = "settings")` in two files, or `dataStore(fileName = ...)`), or factories whose `produceFile` names the same file (`preferencesDataStoreFile("settings")`), in modules and source sets that are built into the same app;
- a DataStore built in a dependency injection provider that is not a singleton: a `@Provides` without `@Singleton` (or with a shorter scope), or a Koin `factory`. It builds another DataStore for each class it is injected into.

## Why it matters

A DataStore owns its file. When a second one on the same file is used while the first is active, DataStore throws `IllegalStateException`: "There are multiple DataStores active for the same file ... You should either maintain your DataStore as a singleton or confirm that there is no two DataStore's active on the same file". It works until the second screen or class that injects it is created.

## How to fix

Keep one DataStore per file: a single top-level delegate, or a singleton provider.

```kotlin
val Context.settings: DataStore<Preferences> by preferencesDataStore(name = "settings") // one, top level

@Provides @Singleton
fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("settings") })
```
