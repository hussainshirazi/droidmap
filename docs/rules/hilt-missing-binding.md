# hilt-missing-binding

Default: **warning**. A class in the project is injected (a constructor parameter, an `@Inject` field or an entry point method), but nothing binds it: it has no `@Inject` constructor, and no `@Provides` or `@Binds` returns it.

## Why it matters

Dagger stops the build with `[Dagger/MissingBinding]`, after the full compile. droidmap finds it in seconds from the source. That helps most when an AI agent or a refactor adds a dependency, because the agent can check the wiring before building. It is reported for project types only: a library type is assumed to be bound by the library.

## How to fix

Give Hilt a way to make it:

```kotlin
class OfflineRepository @Inject constructor(private val dao: ItemDao) : Repository // an @Inject constructor

@Module @InstallIn(SingletonComponent::class)
interface DataModule {
    @Binds fun bindRepository(impl: OfflineRepository): Repository // an interface bound to it
}
```
