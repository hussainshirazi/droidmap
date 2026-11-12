# koin-missing-definition

Default: **warning**. Code asks Koin for a project type (`get()`, `inject()`, `koinInject()`, or a constructor parameter of a definition such as `singleOf(::X)`) that no Koin definition provides.

## Why it matters

Koin resolves dependencies when the code runs, not when it compiles. A missing definition throws `NoDefinitionFoundException` the first time that code runs, often when a screen opens, so the build and most tests pass. droidmap reads the modules (`single`, `factory`, `viewModel`, `singleOf(::X) bind Y`) and Koin Annotations, and checks every request against them.

## How to fix

Add a definition to a module the app loads:

```kotlin
val dataModule = module {
    singleOf(::OfflineRepository) bind Repository::class
}
```

With Koin Annotations, annotate the class (`@Single`, `@Factory`, `@KoinViewModel`). Koin's own `verify()` in a unit test catches the same mistakes at test time.
