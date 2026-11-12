# room-insert-replace

Default: **off**. `@Insert(onConflict = OnConflictStrategy.REPLACE)`.

## Why it matters

`REPLACE` does not update the conflicting row: SQLite deletes it and inserts a new one. Rows in other tables that reference it with `ON DELETE CASCADE` are deleted too, delete triggers fire, and columns the new object does not set lose their values. Using it to "save" an edited object can delete data that hangs off it.

The rule is off by default because `REPLACE` is fine for tables without dependents, such as a cache refreshed from the network.

## How to fix

Use `@Upsert` (Room 2.5 and later), which updates the existing row, or `@Update`. Turn the rule on to keep `REPLACE` out of the project:

```toml
[rules]
"room-insert-replace" = "error"
```
