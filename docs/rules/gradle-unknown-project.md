# gradle-unknown-project

Default: **error**. A dependency on a project (`project(":core:x")` or `projects.core.x`) that `settings.gradle(.kts)` does not include.

## Why it matters

Gradle fails while configuring the build (`Project with path ':core:x' could not be found`). A module renamed or removed in settings while another module still depends on it is the usual cause.

## How to fix

Include the module in settings (`include(":core:x")`), or correct or remove the dependency.
