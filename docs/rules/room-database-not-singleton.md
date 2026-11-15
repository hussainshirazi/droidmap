# room-database-not-singleton

Default: **warning**. A Room database (`Room.databaseBuilder(...).build()`) is built in a dependency injection provider that is not a singleton: a `@Provides` without `@Singleton` (or with a shorter scope), or a Koin `factory`. Each class it is injected into gets another instance.

## Why it matters

Each instance has its own invalidation tracker: a `Flow` or `LiveData` from one instance does not update when another instance writes, so screens show stale data until they reload. Each instance also holds its own connections and caches.

## How to fix

```kotlin
@Provides @Singleton
fun database(@ApplicationContext context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()
```

With Koin, use `single { ... }`.
