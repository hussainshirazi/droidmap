# gradle-cycle

Default: **error**. Modules that depend on each other in a cycle, directly (`:a` on `:b` and `:b` on `:a`) or through others. Test dependencies are not counted.

## Why it matters

Gradle cannot order the compilation of modules in a cycle and fails with `Circular dependency between the following tasks`. It often appears after a convention plugin starts adding a dependency to modules that the dependency itself uses.

## How to fix

Move the code both modules need into a new, lower module that both depend on, or invert one dependency with an interface.
