# Changelog

## 0.1.0 (2026-11-11)

First release. One runnable jar (`java -jar droidmap.jar`, Java 17+); Kotlin is read with the Kotlin compiler's parser.

- Graph of Gradle modules (settings, `project()`, type-safe accessors, Groovy, version-catalog plugins, convention plugins in included builds), Room (databases, entities, DAOs including inherited ones, the tables their queries use, migrations, schema JSON, migration tests with the classic and the driver `MigrationTestHelper`), navigation (Compose string and type-safe routes, Navigation 3 `entry<Key>`, XML graphs across files, deep links, menu items, navigate helpers, route constants used in tab lists), Hilt (ViewModels, `@Inject`, `@Provides`/`@Binds`, qualifiers, entry points) and source manifests.
- `check` with 14 rules, text/JSON/SARIF output (`--sarif FILE` alongside the text), `.droidmap.toml` configuration that rejects unknown rules and severities, and exit codes for CI (1 findings, 2 usage, 3 internal error).
- `impact`, `show`, `find`, `report` and `build` (deterministic `graph.json`, optional networkx node-link JSON).
- `mcp`: a stdio MCP server that rebuilds when files change.
- A GitHub Action (`uses: hussainshirazi/droidmap@v0`) that runs `check` and shows the findings on pull requests.
