# Changelog

## 0.4.0 (2026-11-18)

- New rules for code that compiles but fails at runtime: `component-not-in-manifest` (an activity, service or receiver the code sends an explicit Intent to that no manifest declares: `ActivityNotFoundException`), `hilt-viewmodel-factory` (a `@HiltViewModel` got with `viewModel()` in a navigation destination, `by viewModels()` outside an `@AndroidEntryPoint`, or `by navGraphViewModels()`: "Cannot create an instance of class"), `datastore-duplicate-file` (two DataStores on one file, or one built in a provider that is not a singleton), `room-database-not-singleton`, `nav-route-type-unsupported` (a type-safe route property with no NavType and no `typeMap`), `notification-channel-missing` (a notification for a channel the app never creates: not shown from Android 8), and `intent-extra-missing` and `worker-input-missing` (an extra or a worker input read under a key no sender puts: always null).
- Rules that compare with `--base` (as the GitHub Action does on pull requests), for what users of the last release have saved: `room-enum-constant-removed` (an enum constant removed while Room stores the enum by name: those rows crash when read) and `workmanager-worker-renamed` (a Worker renamed while users have its periodic work saved: it fails every time). `diff` now compares with its base too, so its findings include these and `room-schema-changed`.
- The Claude Code plugin has hooks: when Claude is about to finish, droidmap checks the project, and if the session's changes added an error, Claude is told which and fixes it before finishing. Findings from before the session are left alone.
- An eval suite for the Claude Code plugin (`plugin/evals`, run with `claude plugin eval`): adding a Room column was fully correct in 3 of 3 runs with the plugin and 0 of 3 without it. The launcher runs a local build with `DROIDMAP_JAR`.
- `droidmap mcp --http PORT` serves MCP over HTTP on 127.0.0.1, for Gemini in Android Studio, which supports only HTTP servers. Requests from a web page of another origin are refused.
- New rule `room-migration-sql` (error): each manual migration's SQL is replayed on the schema files of the version it starts from and compared with the version it ends at, the way Room checks after migrating (columns, `NOT NULL`, primary keys, defaults, foreign keys, indices, FTS tables, views). It reports statements SQLite refuses (a `NOT NULL` column without a default, a missing table), tables that do not match (Room's "Migration didn't properly handle" crash), several statements in one `execSQL` (Android runs only the first), and `RENAME COLUMN`/`DROP COLUMN` below Android 11/14 when `minSdk` is lower. SQL in `"""...""".trimIndent()` and `.trimMargin()` is read as a constant now.
- New rule `nav-argument-missing` (warning): a ViewModel that reads a `SavedStateHandle` key (`savedStateHandle["topicId"]`) that no destination showing it has as an argument (`topic/{id}`), so the value is always null. Destinations now list their arguments in the graph: string routes, type-safe route classes and XML `<argument>`s.
- New rule `moshi-model-not-kept` (warning): like `gson-model-not-kept`, for Moshi without code generation (`moshi.adapter(X::class.java)`, Retrofit with `MoshiConverterFactory`).
- Baseline: `droidmap check --update-baseline` writes today's findings to `droidmap-baseline.json`; from then on `check`, `diff`, the GitHub Action, the Gradle plugin and the MCP server report only new findings, and say when known ones are fixed. Findings are matched by rule, file and subject, not by line. SARIF results carry a stable `partialFingerprints`.
- A manual migration declared inside its database class (`UsersDatabase.MIGRATION_1_4`), or in the database's package, belongs to that database even when the module is unknown. On Google's architecture-components-samples this removes a false `room-migration-path` and finds that `MIGRATION_1_4` has no test: the test named `migrationFrom1To4` runs 1->2->3->4, while the app's users on version 1 get `MIGRATION_1_4`.
- `diff` says when no test class reaches the changed code ("Tests that cover it: none found"), so an empty list is not read as "not checked".
- The GitHub Action checks the SHA-256 of the droidmap release it downloads, as the installers do.
- The Windows installer (`install.ps1`) refused every release since 0.2.0 with "the download does not match its checksum". Fixed (on main, which the install command downloads from).
- The Gradle plugin is on the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.hussainshirazi.droidmap).

## 0.3.0 (2026-11-12)

- The Claude Code plugin needs nothing installed but Java, and finds Android Studio's: the first time, it downloads the droidmap release that matches the plugin and checks its SHA-256.
- Tests that reach a change: droidmap follows calls through what the code declares (a property's or parameter's type, a constructor, a function's return type, `by viewModels<X>()`), and a call on an interface reaches its implementations. `diff` and `impact` list the test classes whose calls reach the changed code, and `diff --format tests` prints them as Gradle arguments (`:core:data:testDebugUnitTest --tests com.example.RepoTest`) to run only those.
- New rules: `hilt-fragment-host` (an `@AndroidEntryPoint` fragment in an activity that is not one, through its navigation graph, layout or a fragment transaction: a crash when the fragment is attached) and `gson-model-not-kept` (a class Gson reads by reflection, with fields that R8 renames in a minified release build: debug works, release reads empty fields). On Google's architecture-components-samples it finds four such classes in PagingWithNetworkSample, whose release build is minified.
- A Gradle plugin, `io.github.hussainshirazi.droidmap`: `./gradlew droidmapCheck`, with `failOn` and `mergedManifest` (run each release manifest merge first, so library permissions are checked too). It runs on Gradle 8 and 9.
- Layout XML files and ProGuard rules are read.

## 0.2.1 (2026-11-12)

- The GitHub Action's description is short enough for the GitHub Marketplace, so the Action can be listed there.
- Release notes come from this changelog.

## 0.2.0 (2026-11-12)

- `diff`: what a change touches. It maps the changed lines (since `--base`, by default the uncommitted changes) onto the innermost declarations around them and lists what depends on those: screens, destinations, ViewModels, Room, bindings, modules and tests, with the findings in the changed files. The GitHub Action posts it on each pull request and keeps one comment updated; the MCP server has `droidmap_diff`.
- `diff` lists the declarations a change removes (from the base commit's code, read only for the changed files) and what still depended on them.
- `diff --format tasks` (or `modules`): the test tasks of the modules a change can break, for running only those in CI, without running Gradle.
- Navigation: Safe Args directions (`HomeFragmentDirections.actionHomeToDetail(id)`, also through a local and in Java) are matched to their XML actions; Navigation 3 `NavEntry(key)` built in a `when` over the keys; helpers followed up to five levels deep; an action with only `popUpTo` goes back to that destination.
- Koin Annotations: an annotated class counts as a definition only in a package a module's `@ComponentScan` covers (unless the build sets `KOIN_DEFAULT_MODULE`), and `koin-missing-definition` says when a class is missing only because no scan covers it.
- The GitHub Action's `merge-manifest` input runs `processReleaseMainManifest` (or a task you name) first, so the permissions libraries add are checked too.
- A page per rule (`docs/rules/`), shown by `droidmap explain <rule>` and the MCP tool `droidmap_explain`, linked from code scanning alerts (SARIF help) and the pull request comment.
- New rules: `hilt-app-not-registered` (`@AndroidEntryPoint` classes, but the manifest's Application is not the `@HiltAndroidApp` one: a crash when they open), `workmanager-setup` (a `@HiltWorker` without `HiltWorkerFactory`, or a `Configuration.Provider` the default initializer bypasses) and `nav-deeplink-not-in-manifest` (a deep link no intent filter accepts).
- A Claude Code plugin (`/plugin install droidmap --marketplace hussainshirazi/droidmap`) with the MCP server and a skill; a Docker image on ghcr.io for other CI systems and Docker-based MCP clients; an entry in the MCP Registry. Releases publish all three.
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
- Fixed: a name without an import could resolve to a project class of another package with the same simple name (your own `Result` for Kotlin's), which could report a missing binding that was not missing; names now resolve by Kotlin's and Java's rules. A Koin definition's constructor parameters were resolved in the module's file instead of the class's.
- Fixed: the start destination of a root `<navigation>` without an id, and `<deepLink android:id>`, showed as unreachable; a `@ColumnInfo` name or `@Embedded` prefix that cannot be read (such as `BaseColumns._ID`) made a schema look stale; Hilt rules ran on projects that only have Dagger leftovers in tests.

## 0.1.0 (2026-11-11)

First release. One runnable jar (`java -jar droidmap.jar`, Java 17+); Kotlin is read with the Kotlin compiler's parser.

- Graph of Gradle modules (settings, `project()`, type-safe accessors, Groovy, version-catalog plugins, convention plugins in included builds), Room (databases, entities, DAOs including inherited ones, the tables their queries use, migrations, schema JSON, migration tests with the classic and the driver `MigrationTestHelper`), navigation (Compose string and type-safe routes, Navigation 3 `entry<Key>`, XML graphs across files, deep links, menu items, navigate helpers, route constants used in tab lists), Hilt (ViewModels, `@Inject`, `@Provides`/`@Binds`, qualifiers, entry points) and source manifests.
- Room schema checks: entities compared with the schema JSON of the current version (`room-schema-stale`), and, with `--base REF`, schema files of existing versions that changed since that commit (`room-schema-changed`).
- `check` with 16 rules, text/JSON/SARIF output (`--sarif FILE` alongside the text), `.droidmap.toml` configuration that rejects unknown rules and severities, and exit codes for CI (1 findings, 2 usage, 3 internal error).
- `impact`, `show`, `find`, `report` and `build` (deterministic `graph.json`, optional networkx node-link JSON).
- `mcp`: a stdio MCP server that rebuilds when files change.
- A GitHub Action (`uses: hussainshirazi/droidmap@v0`) that runs `check` and shows the findings on pull requests.
