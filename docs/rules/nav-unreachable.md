# nav-unreachable

Default: **warning**. A registered destination that nothing navigates to: it is not a start destination, a deep link or a menu item, and no code refers to its route.

## Why it matters

Usually it is a screen whose entry point was removed, so it is dead code that still has to be built and maintained. Sometimes it is the other half of a bug: a `navigate(...)` that was meant to reach it points somewhere else.

## How to fix

Remove the destination, or add the navigation that should reach it. If the finding says some navigate calls could not be resolved, the destination may be reached through a route droidmap cannot follow (one built at runtime). Then it is a false positive; you can turn the rule down in `.droidmap.toml`.
