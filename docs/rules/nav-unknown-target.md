# nav-unknown-target

Default: **error**. A `navigate(...)` call, start destination or XML action that points at a route, key or id that no destination registers.

## Why it matters

Navigation looks the target up when the call runs. A route no `NavHost` registers throws `IllegalArgumentException: Navigation destination that matches request ... cannot be found in the navigation graph`, which crashes the app when the user taps whatever triggers it. Renaming a route in one place and not the other is the usual cause.

## Example

```kotlin
NavHost(navController, startDestination = "home") {
    composable("home") { HomeScreen(onSettings = { navController.navigate("settings") }) }
    composable("setting") { SettingsScreen() } // registered as "setting", not "settings"
}
```

## How to fix

Register the destination, or correct the route, key or action. Keeping routes in constants or type-safe route classes avoids the mismatch.
