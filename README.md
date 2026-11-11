# droidmap

[![CI](https://github.com/hussainshirazi/droidmap/actions/workflows/ci.yml/badge.svg)](https://github.com/hussainshirazi/droidmap/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**An Android-aware map of your project, for you and your AI coding agent.**

Generic code graphs see classes and calls. They do not see that:

- version 4 of your Room database has no migration to version 5, so users on 4 crash on update;
- `navigate("settings")` points at a route no `NavHost` registers;
- the ViewModel your screen uses injects a repository that nothing binds;
- a feature module gets `:core:data` through a convention plugin in `build-logic`, which your module rules forbid.

droidmap reads the Android wiring directly (Gradle, Room, Navigation, Hilt, the manifest) and turns it into a graph. You can query it from the command line, run its rules in CI, and hand it to an AI agent over MCP so the agent knows what a change will break *before* it makes it.

It is a single jar with no setup: it reads source files only, never runs Gradle, never compiles your code and never touches the network.

## Quick start

You need Java 17 or newer. Android Studio already ships one: `<Android Studio>/jbr/bin/java`.

Download `droidmap.jar` from the [latest release](../../releases/latest), or build it yourself with `./gradlew fatJar` (it lands in `build/libs/droidmap.jar`). Then, from your project's root:

```sh
java -jar droidmap.jar check                  # run the rules; exit code 1 on errors
java -jar droidmap.jar impact TopicEntity     # what depends on it, transitively
```

Tip: `alias droidmap='java -jar ~/tools/droidmap.jar'`. The jar is about 65 MB because it carries the Kotlin compiler's parser.

## What it looks like

On the test suite's sample project (paths shortened):

```
$ droidmap check
error   room-migration-path          core/database/.../AppDatabase.kt:16
        AppDatabase is at version 5 but there is no migration path from versions 1, 2, 3, 4: users on
        those versions hit IllegalStateException (or lose data with a destructive fallback)
error   nav-unknown-target           app/.../AppNav.kt:36
        AppNav navigates to settings, but no destination registers it
warning hilt-missing-binding         feature/home/.../HomeViewModel.kt:13
        HomeViewModel injects TagRepository, but nothing binds it (no @Inject constructor, @Provides or @Binds in this project)
warning room-migration-untested      core/database/.../AppDatabase.kt:16
        AppDatabase: 2 of 3 migration steps have no MigrationTestHelper test (2->3, 3->4)
...

$ droidmap impact ItemDao
Impact of ItemDao [room_dao] (core/database/.../ItemDao.kt:9)

9 dependents, nearest first (depth, dependent, how it depends; depth <= 4):
  1  ItemRepository [class] bound_by DataModule.bindItems  core/data/.../Repositories.kt:6
  1  DataModule.bindItems [hilt_provider] requires OfflineItemRepository  core/data/.../Modules.kt:59
  1  OfflineItemRepository [injectable] injects ItemDao  core/data/.../Repositories.kt:9
  1  AppDatabase [room_database] contains_dao ItemDao  core/database/.../AppDatabase.kt:16
  2  MainActivity [component] injects ItemRepository  app/.../MainActivity.kt:11
  2  HomeViewModel [viewmodel] injects ItemRepository  feature/home/.../HomeViewModel.kt:11
  3  :app [module] declares MainActivity  app/build.gradle.kts:1
  3  HomeScreen [screen] uses_viewmodel HomeViewModel  feature/home/.../HomeScreen.kt:7
  4  home [nav_destination] shows HomeScreen  app/.../AppNav.kt:28

Modules with impacted code: :app, :core:data, :core:database, :feature:home
Modules that depend on :core:database: :app, :core:data, :feature:detail-screen, :feature:home
```

## With an AI agent (MCP)

`droidmap mcp` is a [Model Context Protocol](https://modelcontextprotocol.io) server over stdio. It rebuilds the graph whenever a source file changes, so its answers follow the code as the agent edits it.

Claude Code:

```sh
claude mcp add droidmap -- java -jar /path/to/droidmap.jar mcp .
```

Cursor, VS Code, Windsurf and other clients take the same command in their MCP config:

```json
{ "mcpServers": { "droidmap": { "command": "java", "args": ["-jar", "/path/to/droidmap.jar", "mcp", "."] } } }
```

| Tool | Use it to |
|---|---|
| `droidmap_impact` | see what depends on an entity, route, ViewModel, binding or module before changing it |
| `droidmap_check` | run the rules after a change |
| `droidmap_show` | look at one node: its attributes, dependencies and users |
| `droidmap_find` | search nodes by name |
| `droidmap_report` | get a Markdown overview of the project |

## What droidmap understands

| Area | What it reads |
|---|---|
| **Gradle** | Modules from `settings.gradle(.kts)`; `project(":x")` and type-safe `projects.x.y` dependencies in Kotlin DSL and Groovy; plugins from the version catalog; and the project dependencies that **convention plugins in an included build** (`build-logic`) add to every module that applies them. |
| **Room** | Databases, entities, DAOs (the tables each `@Query` uses and the entities each `@Insert`, `@Update`, `@Delete` or `@Upsert` writes), manual and auto migrations, the exported schema JSON files, and **which `MigrationTestHelper` test covers which migration step**. |
| **Navigation** | Compose Navigation with string routes (built from constants, templates and helper functions) or type-safe routes, **Navigation 3** (`entry<Key>`), and XML navigation graphs: destinations, the screen each one shows, start destinations, deep links, menu items that open a destination, and `navigate(...)` calls, including calls through your own helpers such as `navigateToTab(route)`. |
| **Hilt** | `@HiltViewModel`s, `@Inject` constructors and fields, `@Provides` and `@Binds` with qualifiers, entry points, and which screens and activities use which ViewModel. |
| **Manifest** | Permissions (including `tools:node="remove"`) and components. |

Kotlin is read with the Kotlin compiler's own parser (the one detekt and ktlint use).

## Commands

```sh
droidmap check  [path] [--format text|json|sarif] [--fail-on error|warning|info|never]
droidmap impact <node> [path] [--depth N] [--json]   # what depends on a node
droidmap show   <node> [path] [--json]               # a node, its dependencies and its users
droidmap find   <text> [path] [--limit N] [--json]   # search nodes
droidmap build  [path] [-o DIR] [--node-link]        # write droidmap-out/graph.json and REPORT.md
droidmap report [path] [-o FILE]                     # the Markdown report
droidmap rules                                       # list the rules and their default severities
droidmap mcp    [path]                               # run the MCP server over stdio
```

`<node>` can be an id, a name or part of a name: `TopicEntity`, `:core:data`, `"editor?id={id}"`, `HomeViewModel`. `check`, `impact`, `show`, `find` and `report` also take `--config FILE` and `--graph FILE` (use a saved `graph.json` instead of scanning); `build` and `mcp` take `--config FILE`.

Exit codes: 0 when done, 1 when `check` finds something at `--fail-on` or above (or `find` matches nothing), 2 for a usage or config error, 3 for an internal error.

In CI, `droidmap check --format sarif > droidmap.sarif` produces a file that GitHub code scanning can show on pull requests. Add `droidmap-out/` to your `.gitignore` if you use `build`.

## Rules

| Rule | Default | Finds |
|---|---|---|
| `room-migration-path` | error | a known schema version with no migration path to the current version |
| `room-schema-missing` | warning | no exported schema JSON for the current version or an earlier one |
| `room-migration-untested` | warning | migration steps no `MigrationTestHelper` test runs |
| `room-migration-unused` | warning | a manual `Migration` that app code never uses (not passed to `addMigrations`?), even if a test runs it |
| `room-destructive-fallback` | warning | `fallbackToDestructiveMigration()`, which deletes user data |
| `room-insert-replace` | off | `@Insert(onConflict = REPLACE)`, which deletes and re-inserts rows |
| `nav-unknown-target` | error | `navigate()` to a route or key no destination registers |
| `nav-unreachable` | warning | a destination nothing navigates to, starts at or refers to, and that no deep link or menu item opens |
| `hilt-missing-binding` | warning | an injected project type with no `@Inject` constructor, `@Provides` or `@Binds` |
| `hilt-duplicate-binding` | error | the same type and qualifier bound twice in one source set |
| `gradle-unknown-project` | error | a project dependency on a module settings does not include |
| `gradle-forbidden-dependency` | error | a dependency your `[[gradle.forbid]]` rules ban |
| `gradle-cycle` | error | modules that depend on each other in a cycle |
| `manifest-permission` | error | a permission not in your `allowed_permissions` |

## Configuration

Put a `.droidmap.toml` at the project root (all of it is optional):

```toml
[rules]
"room-migration-untested" = "error"   # error | warning | info | off
"room-insert-replace" = "error"

[manifest]
allowed_permissions = ["android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS"]

[[gradle.forbid]]
from = ":feature:*"
to = ":feature:*"
reason = "features talk to each other through :core modules"

[ignore]
paths = ["benchmarks/**"]
```

An unknown rule name or severity is an error, so a typo never leaves a rule silently unchanged. `from`, `to` and `ignore.paths` are glob patterns where `*` also matches `:` and `/`. A forbid rule skips test dependencies unless it says `tests = true`, and `allow_same_parent = true` lets siblings such as `:feature:a:impl` and `:feature:a:api` depend on each other.

## The graph

`droidmap build` writes `droidmap-out/graph.json` (schema `droidmap/1`) with three lists:

- `nodes`: id, kind, label, file, line, module and attrs;
- `edges`: source, target, kind, file, line and attrs;
- `unresolved`: what static analysis could not follow, with the reason.

An edge always means *source depends on target*, so impact is a walk backwards. Paths are relative to the project root and the output is deterministic, so it diffs cleanly. `--node-link` also writes the networkx node-link shape that tools such as graphify read.

Node kinds: `module`, `room_database`, `room_entity`, `room_dao`, `room_migration`, `room_schema`, `test`, `nav_destination`, `nav_route`, `screen`, `function`, `viewmodel`, `injectable`, `hilt_module`, `hilt_provider`, `entry_point`, `component`, `permission`, `class`.

Edge kinds: `depends_on`, `contains_entity`, `contains_dao`, `queries`, `writes`, `has_schema`, `migrates`, `tests`, `registers`, `shows`, `navigates_to`, `starts_at`, `uses_viewmodel`, `injects`, `bound_by`, `requires`, `declared_in`, `uses_permission`, `declares`.

## Limits

droidmap is static and deliberately conservative. When it cannot resolve something (a route held in a variable, a version that is not a constant), it records it under `unresolved` instead of guessing, and the rules do not fire on it.

- Kotlin sources only for now; Java classes are not read yet (Groovy build files are).
- It reads source manifests, not the merged manifest, so library permissions do not show up. Check the merged one with `aapt2 dump permissions`.
- Generated code (KSP, kapt, Safe Args directions) is not read.
- Names resolve through imports, the same package and unique simple names, without type inference: two project classes with the same simple name and no import can be confused.
- Navigation arguments built at runtime and `NavEntry` objects built by hand in Navigation 3 are not followed; helper functions are followed one level deep.

## Roadmap

- Java sources (through the same compiler's Java parser).
- The merged manifest, when a build has produced one.

Ideas and bug reports are welcome in the issues. A small snippet that reproduces the problem helps most.

## Contributing

```sh
./gradlew test fatJar        # run the tests and build build/libs/droidmap.jar
```

The code lives in `src/main/kotlin/droidmap`: one extractor per area in `extract/` (Gradle, Room, Navigation, Hilt, Manifest), the rules in `Checks.kt`, queries in `Query.kt` and the MCP server in `Mcp.kt`. The test suite runs every rule against a small synthetic project in `src/test/resources/sample`; a new rule or a fixed false positive should come with a case there.

## License

Apache-2.0. See [LICENSE](LICENSE).
