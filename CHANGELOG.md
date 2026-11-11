# Changelog

## 0.2.0 (2026-11-12)

- `diff`: what a change touches. It maps the changed lines (since `--base`, by default the uncommitted changes) onto the innermost declarations around them and lists what depends on those: screens, destinations, ViewModels, Room, bindings, modules and tests, with the findings in the changed files. The GitHub Action posts it on each pull request and keeps one comment updated; the MCP server has `droidmap_diff`.
- Java: Java sources are read through the same model as Kotlin, so Room (entities, DAOs, migrations, migration tests, schema columns), Hilt, `navigate(R.id.x)` and `new ViewModelProvider(this).get(X.class)` work in Java code.
- Koin: DSL definitions (`single`, `factory`, `viewModel`, `singleOf(::X)`, `bind`), Koin Annotations (with JSR-330), `get()`/`inject()`/`koinInject()` uses, and the `koin-missing-definition` rule.
- Kotlin Multiplatform: dependencies in `commonTest`-style source sets are test dependencies; an `expect` class and its `actual`s are one type.
- Merged manifest: when a build has produced it, its permissions are checked against `allowed_permissions` too, with the library that added each.
- `report` draws the module graph and the screen flow (Mermaid, rendered on GitHub) and lists Koin definitions and permissions.
- Install with a script (`install.sh`, `install.ps1`), Homebrew or Scoop; releases publish a checksum.
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
