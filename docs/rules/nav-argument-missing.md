# nav-argument-missing

Default: **warning**. A ViewModel reads a key from its `SavedStateHandle` (`savedStateHandle["topicId"]`, `get<String>("topicId")`, `getStateFlow("topicId", ...)`) that no navigation destination showing it has as an argument.

With Navigation, the `SavedStateHandle` of a ViewModel made for a destination (with `hiltViewModel()`, `viewModel()`, `koinViewModel()` or a fragment's `by viewModels()`) holds that destination's arguments, by name: the `{placeholders}` and `navArgument(...)` entries of a string route, the properties of a type-safe route class (or their `@SerialName`), or the `<argument>` elements of an XML destination.

droidmap checks a ViewModel when every screen or fragment that uses it is shown by a destination whose arguments it knows, and none gets it for an activity, a navigation graph or another back stack entry (`activityViewModels()`, `hiltViewModel(parentEntry)`). Keys the ViewModel also writes (`savedStateHandle["query"] = ...`) are its own state, not arguments, and are not reported.

## Why it matters

A key no argument has is never set: the value is always null, or the default of `getStateFlow`. `checkNotNull(savedStateHandle["topicId"])` crashes as soon as the screen opens; other code silently shows an empty screen or the wrong data. The two names live in different files (the route, the ViewModel), and nothing at compile time compares them.

## How to fix

Use the same name in both places, ideally one constant:

```kotlin
const val TOPIC_ID = "topicId"

composable("topic/{$TOPIC_ID}") { TopicScreen() }

class TopicViewModel @Inject constructor(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val topicId: String = checkNotNull(savedStateHandle[TOPIC_ID])
}
```

With type-safe routes, read the whole route instead of single keys: `savedStateHandle.toRoute<Topic>().id`.
