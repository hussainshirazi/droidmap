# room-migration-sql

Default: **error**. A manual `Migration`'s SQL, replayed on the schema files, fails or does not produce the tables Room expects for the new version.

droidmap takes the tables of the version the migration starts from (`<from>.json`), runs the migration's `execSQL` statements on them, in order, and compares the result with the version it ends at (`<to>.json`), the way Room compares them after migrating: each table's columns (type affinity, `NOT NULL`, primary key position, default value), foreign keys and indices, then FTS tables and views. It reports:

- a statement SQLite refuses, such as `ALTER TABLE ... ADD COLUMN x TEXT NOT NULL` without a default, a table or column that is not there, or one that already is;
- a table, column, index or foreign key that is missing, different or left over after the migration;
- an `execSQL` with several statements: Android runs only the first, and ignores the rest;
- `ALTER TABLE ... RENAME COLUMN` (SQLite 3.25, Android 11) or `DROP COLUMN` (SQLite 3.35, Android 14) when the project's `minSdk` is lower and the app does not bring its own SQLite.

It follows SQL written as constants (string templates of constants and `trimIndent()` included). A migration whose SQL is built at runtime, runs only in some cases (in a condition, loop, `catch` or lambda), or calls other code that gets the database is not checked; `droidmap build` lists why under unresolved items (`room_migration_sql`). Statements that only change rows (`INSERT`, `UPDATE`, `DELETE`) may be anywhere.

## Why it matters

Room checks the tables after a migration. When they differ from what the new version expects, it throws `IllegalStateException: Migration didn't properly handle: ...` and the app crashes on start, for every user who updates from that version, until a fixed release ships. A statement SQLite refuses crashes the same way. Tests that would catch this (`MigrationTestHelper.runMigrationsAndValidate`) are rare (see [room-migration-untested](room-migration-untested.md)); this rule needs none.

## How to fix

Make the migration produce exactly the schema of the new version. The schema file's `createSql` and each index's `createSql` are the statements Room itself would run; copy them, with the table name in place of `${TABLE_NAME}`:

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A NOT NULL column needs a default for the rows that are already there.
        db.execSQL("ALTER TABLE `user` ADD COLUMN `email` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_email` ON `user` (`email`)")
    }
}
```

Use one `execSQL` per statement. To change a column's type, nullability or constraints, create the new table, copy the rows, drop the old table and rename the new one. Then add a `MigrationTestHelper` test, which runs the migration on a real SQLite.
