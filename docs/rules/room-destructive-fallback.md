# room-destructive-fallback

Default: **warning**. A call to `fallbackToDestructiveMigration()` (or one of its variants).

## Why it matters

With a destructive fallback, a missing migration does not crash: Room deletes the database and creates an empty one. The app keeps working and the user's data is silently gone. A missing migration is a bug you want to find in testing, not one that erases what users saved.

## How to fix

Remove the call and provide the migrations (see [room-migration-path](room-migration-path.md)). If the database only caches data that can be downloaded again, a destructive fallback is reasonable: turn the rule off in `.droidmap.toml`:

```toml
[rules]
"room-destructive-fallback" = "off"
```
