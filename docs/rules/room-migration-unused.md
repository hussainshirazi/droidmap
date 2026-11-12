# room-migration-unused

Default: **warning**. A manual `Migration` that only tests use: no app code refers to it.

## Why it matters

A migration Room is never given never runs. Its test passes, because the test hands it to `MigrationTestHelper` directly, while users on the old version crash on update (see [room-migration-path](room-migration-path.md)) or lose their data with a destructive fallback.

## How to fix

Pass it to the database builder:

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .addMigrations(MIGRATION_3_4)
    .build()
```

A list such as `val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_3_4)` that the builder uses counts as a use.
