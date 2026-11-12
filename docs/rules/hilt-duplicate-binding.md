# hilt-duplicate-binding

Default: **error**. The same type, with the same qualifier, is bound by two `@Provides` or `@Binds` methods in one source set.

## Why it matters

Dagger cannot choose between them and stops the build with `[Dagger/DuplicateBindings]`. Two modules installed in the same component that each provide a `Clock` or an `OkHttpClient` are the usual cause, often after code is moved between modules.

A `debug` binding and a `release` binding of the same type never meet in one build, so they are not reported; either one does clash with a binding in `main`. Multibindings (`@IntoSet`, `@IntoMap`) and `@TestInstallIn` replacements are skipped too.

## How to fix

Remove one of them, or give them different qualifiers (`@Named("io")`, or your own `@Qualifier` annotation) if both are needed.
