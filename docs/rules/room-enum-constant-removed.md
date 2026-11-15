# room-enum-constant-removed

Default: **error**. With `--base` (and on pull requests in the GitHub Action): an enum the change removed (or renamed) a constant of, while a Room entity stores that enum in a column. Room stores enums by name, through its own converter or a `@TypeConverter` that parses with `valueOf`.

Not reported when the enum has a `@TypeConverter` that does not parse by name without a fallback, or when a migration's SQL names the constant (`UPDATE subscriptions SET status = 'ACTIVE' WHERE status = 'PAUSED'`), which is taken to update those rows.

## Why it matters

Rows that users saved with the old constant are still in their database after the update. Reading one fails: Room's converter throws `IllegalArgumentException` ("Can't convert value to enum, unknown value: PAUSED"), and so does `valueOf`. The query, and often the screen, crashes for those users only: a fresh install, as in testing, has no such row.

## How to fix

Keep the constant (mark it `@Deprecated` and stop writing it), or migrate the rows in the same release:

```kotlin
val MIGRATION_4_5 = Migration(4, 5) { db ->
    db.execSQL("UPDATE subscriptions SET status = 'ACTIVE' WHERE status = 'PAUSED'")
}
```

Or read through a converter with a fallback: `@TypeConverter fun toStatus(s: String) = Status.entries.firstOrNull { it.name == s } ?: Status.ACTIVE`.
