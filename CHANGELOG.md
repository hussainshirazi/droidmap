# Changelog

## 0.2.0 (2026-11-12)

- `diff`: what a change touches. It maps the changed lines (since `--base`, by default the uncommitted changes) onto the innermost declarations around them and lists what depends on those: screens, destinations, ViewModels, Room, bindings, modules and tests, with the findings in the changed files. The GitHub Action posts it on each pull request and keeps one comment updated; the MCP server has `droidmap_diff`.
- `diff --format tasks` (or `modules`): the test tasks of the modules a change can break, for running only those in CI, without running Gradle.
- A page per rule (`docs/rules/`), shown by `droidmap explain <rule>` and the MCP tool `droidmap_explain`, linked from code scanning alerts (SARIF help) and the pull request comment.
- New rules: `hilt-app-not-registered` (`@AndroidEntryPoint` classes, but the manifest's Application is not the `@HiltAndroidApp` one: a crash when they open), `workmanager-setup` (a `@HiltWorker` without `HiltWorkerFactory`, or a `Configuration.Provider` the default initializer bypasses) and `nav-deeplink-not-in-manifest` (a deep link no intent filter accepts).
- CI runs droidmap on four open-source apps at fixed commits and fails when their findings change; Dependabot keeps the Kotlin parser current.
- Java: Java sources are read through the same model as Kotlin, so Room (entities, DAOs, migrations, migration tests, schema columns), Hilt, `navigate(R.id.x)` and `new ViewModelProvider(this).get(X.class)` work in Java code.
- Koin: DSL definitions (`single`, `factory`, `viewModel`, `singleOf(::X)`, `bind`), Koin Annotations (with JSR-330), `get()`/`inject()`/`koinInject()` uses, and the `koin-missing-definition` rule.
- Kotlin Multiplatform: dependencies in `commonTest`-style source sets are test dependencies; an `expect` class and its `actual`s are one type.
- Merged manifest: when a build has produced it, its permissions are checked against `allowed_permissions` too, with the library that added each.
- `report` draws the module graph and the screen flow (Mermaid, rendered on GitHub) and lists Koin definitions and permissions.
- Install with a script (`install.sh`, `install.ps1`), Homebrew or Scoop; releases publish a checksum. The `droidmap` command finds Android Studio's Java when there is no `JAVA_HOME` or `java` on the PATH.
- `init` writes the GitHub workflow and the MCP config (`.mcp.json`, and Cursor's or VS Code's when their folder exists), never changing a file that exists.
- Easier to use: droidmap finds the project from any folder inside it, and says so instead of reporting "No findings." outside a Gradle project. Terminal output is in colour (`NO_COLOR`, `FORCE_COLOR`), long messages wrap, `find` and `show` drop internal ids and attributes, and `impact` lines up its columns.
- Faster: files are parsed on several threads, the MCP server reparses only changed files, version catalogs are read without a TOML library, and the installers start the JVM for short runs.
- Fixed: the start destination of a root `<navigation>` without an id, and `<deepLink android:id>`, showed as unreachable; a `@ColumnInfo` name or `@Embedded` prefix that cannot be read (such as `BaseColumns._ID`) made a schema look stale; Hilt rules ran on projects that only have Dagger leftovers in tests.

## 0.1.0 (2026-11-11)

First release. One runnable jar (`java -jar droidmap.jar`, Java 17+); Kotlin is read with the Kotlin compiler's parser.

- Graph of Gradle modules (settings, `project()`, type-safe accessors, Groovy, version-catalog plugins, convention plugins in included builds), Room (databases, entities, DAOs including inherited ones, the tables their queries use, migrations, schema JSON, migration tests with the classic and the driver `MigrationTestHelper`), navigation (Compose string and type-safe routes, Navigation 3 `entry<Key>`, XML graphs across files, deep links, menu items, navigate helpers, route constants used in tab lists), Hilt (ViewModels, `@Inject`, `@Provides`/`@Binds`, qualifiers, entry points) and source manifests.
- Room schema checks: entities compared with the schema JSON of the current version (`room-schema-stale`), and, with `--base REF`, schema files of existing versions that changed since that commit (`room-schema-changed`).
- `check` with 16 rules, text/JSON/SARIF output (`--sarif FILE` alongside the text), `.droidmap.toml` configuration that rejects unknown rules and severities, and exit codes for CI (1 findings, 2 usage, 3 internal error).
- `impact`, `show`, `find`, `report` and `build` (deterministic `graph.json`, optional networkx node-link JSON).
- `mcp`: a stdio MCP server that rebuilds when files change.
- A GitHub Action (`uses: hussainshirazi/droidmap@v0`) that runs `check` and shows the findings on pull requests.
