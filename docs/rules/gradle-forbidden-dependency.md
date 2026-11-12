# gradle-forbidden-dependency

Default: **error**. A module dependency that one of your `[[gradle.forbid]]` rules bans.

## Why it matters

Module boundaries keep build times down and features independent, but nothing in Gradle enforces them. One `implementation(project(":feature:b"))` in `:feature:a` couples two features. Once one exception is in, more follow. droidmap also sees dependencies that a convention plugin in `build-logic` adds to every module that applies it, which a module's own build file does not show.

## Configuration

```toml
[[gradle.forbid]]
from = ":feature:*"
to = ":feature:*"
reason = "features talk to each other through :core modules"
allow_same_parent = true   # :feature:a:impl may use :feature:a:api

[[gradle.forbid]]
from = ":core:*"
to = ":feature:*"
tests = true               # also for test dependencies
```

`*` matches anything, `:` included. Test dependencies are skipped unless the rule says `tests = true`.

## How to fix

Move what both modules need into a lower module (a `:core` module or an `api` module), or depend on an interface the app wires together.
