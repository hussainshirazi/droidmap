# room-schema-stale

Default: **warning**. The entity classes no longer match the schema JSON of the database's current version: a column or table was added, removed or renamed, but the version was not bumped.

## Why it matters

Room stores a hash of the schema in the database. When the app opens a database whose version matches but whose schema does not, it throws `IllegalStateException: Room cannot verify the data integrity. Looks like you've changed schema but forgot to update the version number.` Everyone who has the previous build of that version installed crashes on launch.

## Example

```kotlin
@Entity data class Item(@PrimaryKey val id: Long, val title: String, val note: String) // note is new
@Database(entities = [Item::class], version = 4) // still 4, and 4.json has no note column
```

## How to fix

If version 4 has shipped: bump the version to 5 and add a migration from 4 to 5 that adds the column. If version 4 has never been released, rebuild so Room regenerates `4.json`, and commit it.
