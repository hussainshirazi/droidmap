# hilt-viewmodel-factory

Default: **error**. A `@HiltViewModel` whose constructor needs more than a `SavedStateHandle` is got without Hilt's ViewModel factory:

- `viewModel()` in the screen of a Compose navigation destination, where the owner is the back stack entry, whose factory is Navigation's;
- `by viewModels()` in an activity or fragment that is not `@AndroidEntryPoint` (nor a subclass of one);
- `by navGraphViewModels(...)` without a factory, which uses the graph's back stack entry.

A call given a factory or another owner (`viewModel(factory = ...)`, `by viewModels { ... }`) is not reported.

## Why it matters

Only Hilt's factory can call the ViewModel's `@Inject` constructor. Any other factory throws when the ViewModel is first created: "Cannot create an instance of class ...ViewModel". It compiles, and the crash comes when that screen opens.

## How to fix

```kotlin
composable("home") { HomeScreen(viewModel = hiltViewModel()) } // androidx.hilt:hilt-lifecycle-viewmodel-compose

@AndroidEntryPoint
class SettingsFragment : Fragment() { private val viewModel: SettingsViewModel by viewModels() }

private val shared: CartViewModel by hiltNavGraphViewModels(R.id.checkout_graph)
```
