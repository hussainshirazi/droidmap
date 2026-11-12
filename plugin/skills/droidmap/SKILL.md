---
name: droidmap
description: Use in an Android project (Kotlin or Java, Gradle) before and after changing Room entities, DAOs, databases or migrations, navigation routes or destinations, ViewModels, Hilt or Koin bindings, WorkManager workers, Gradle modules or their dependencies, or the manifest. droidmap says what depends on the code you are about to change and checks the Android wiring (missing Room migrations and stale schemas, routes no destination registers, missing DI bindings, the Hilt Application, module rules, permissions) that compiles fine but crashes at runtime.
---

# droidmap

droidmap reads the project's Android wiring (Gradle modules, Room, navigation, Hilt, Koin, WorkManager, the manifest) from source into a graph. Use its MCP tools (`droidmap_*`) when the server is connected; otherwise run the same commands in a shell with `droidmap` (from anywhere inside the project).

## Before changing code

Call `droidmap_impact` (or `droidmap impact <name>`) on what you are about to change: an entity, DAO, database, route or destination, ViewModel, injected class, binding or module. It lists everything that depends on it, nearest first, and the tests that cover it. Read the nearest dependents before editing, and change or check them together.

## After changing code

1. `droidmap_check` (or `droidmap check`): fix every error before you finish. For a finding you do not understand, `droidmap_explain` with its rule (or `droidmap explain <rule>`) says why it matters and how to fix it.
2. `droidmap_diff` (or `droidmap diff`): what your changes touch, and the tests that cover it ("Tests that cover it": the test classes whose calls reach the changed code). Run those tests; in a shell, `./gradlew $(droidmap diff --format tests --task testDebugUnitTest)` runs exactly them. When you removed something, it lists what still depended on it: update those too.

## Rules that need care

- **A Room entity changed** (a field added, removed, renamed or retyped): bump the database `version`, add a `Migration` (or an `AutoMigration`) from the old version, pass it to `addMigrations(...)`, add a `MigrationTestHelper` test for that step, and rebuild so the new schema JSON is exported and committed. Never edit the schema JSON of a version that has shipped, and never add `fallbackToDestructiveMigration()` to make the error go away: it deletes users' data.
- **A route or navigation key changed**: change every `navigate(...)` to it; `droidmap_impact` on the destination lists them.
- **A class is injected**: something must bind it (`@Inject` constructor, `@Binds` or `@Provides` in Hilt; a definition in a Koin module). `@AndroidEntryPoint` classes need the `@HiltAndroidApp` Application named in the manifest.
- **A module dependency changed**: run `droidmap_check` for the project's module rules and cycles.

droidmap is static: it does not run Gradle, read code a build generates (Safe Args calls are matched by name), trace calls on variables, or follow values built at runtime. What it cannot resolve is listed as unresolved, never guessed.
