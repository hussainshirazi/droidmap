# room-migration-untested

Default: **warning**. A migration step that no `MigrationTestHelper` test runs.

## Why it matters

A migration runs once, on each user's phone, against data you have never seen, and it cannot be undone. A typo in its SQL either crashes the update or corrupts or loses data. A test that creates the old schema, runs the migration and validates the result (`runMigrationsAndValidate`) is the only way to know it works before users do.

droidmap counts the steps each test actually runs. A test named `migrate1To4` that passes `MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4` does not test `MIGRATION_1_4`, which is the one Room uses for users on version 1, because Room takes the shortest path.

## How to fix

Add a test per step:

```kotlin
@Test fun migrate2To3() {
    helper.createDatabase(DB, 2).close()
    helper.runMigrationsAndValidate(DB, 3, true, MIGRATION_2_3)
}
```

Both the classic `MigrationTestHelper` and the driver-based one count.
