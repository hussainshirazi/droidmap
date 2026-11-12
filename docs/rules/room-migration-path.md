# room-migration-path

Default: **error**. A schema version the project knows about (from an exported schema file or a migration) has no chain of migrations to the database's current version.

## Why it matters

When the app updates and opens a database at an older version, Room looks for migrations that lead to the new version. If there is no path, Room throws `IllegalStateException: A migration from 2 to 3 was required but not found`, so every user still on version 2 crashes on launch. With `fallbackToDestructiveMigration()` they don't crash: their data is deleted instead.

## Example

```kotlin
@Database(entities = [Item::class], version = 3)
abstract class AppDatabase : RoomDatabase()

val MIGRATION_1_2 = object : Migration(1, 2) { /* ... */ }
// Nothing goes from 2 to 3: users on version 2 crash after the update.
```

## How to fix

Add the missing step, as a `Migration(2, 3)` passed to `addMigrations(...)` or as `AutoMigration(from = 2, to = 3)` in `@Database(autoMigrations = ...)`. Then test it with `MigrationTestHelper` (see [room-migration-untested](room-migration-untested.md)).
