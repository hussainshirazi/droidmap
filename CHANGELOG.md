# Changelog

## 0.1.0 (unreleased)

First release. One runnable jar (`java -jar droidmap.jar`, Java 17+); Kotlin is read with the Kotlin compiler's parser.

- Graph of Gradle modules (settings, `project()`, type-safe accessors, Groovy, version-catalog plugins, convention plugins in included builds), Room (databases, entities, DAOs and the tables their queries read, migrations, schema JSON, migration tests), navigation (Compose string and type-safe routes, Navigation 3 `entry<Key>`, XML graphs, navigate helpers, route constants used in tab lists), Hilt (ViewModels, `@Inject`, `@Provides`/`@Binds`, qualifiers, entry points) and source manifests.
- `check` with 14 rules, text/JSON/SARIF output and `.droidmap.toml` configuration.
- `impact`, `show`, `find`, `report` and `build` (deterministic `graph.json`, optional networkx node-link JSON).
- `mcp`: a stdio MCP server that rebuilds when files change.
