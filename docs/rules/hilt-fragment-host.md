# hilt-fragment-host

Default: **error**. An `@AndroidEntryPoint` fragment is hosted by an activity that is not `@AndroidEntryPoint`: through the navigation graph of a `NavHostFragment` in the activity's layout (or set in its code), a `<fragment>` or `FragmentContainerView` in the layout, or a fragment transaction in the activity's code.

## Why it matters

A Hilt fragment gets its dependencies from its activity's Hilt component. When the activity has none, Hilt throws as the fragment is attached:

```
java.lang.IllegalStateException: Hilt Fragments must be attached to an @AndroidEntryPoint Activity. Found: class com.example.MainActivity
```

It compiles, and the app runs until someone opens that screen. It happens after moving a fragment into another activity's graph, or after adding `@AndroidEntryPoint` to a fragment to inject something into it.

## How to fix

Annotate the activity (a subclass of an `@AndroidEntryPoint` activity counts as one):

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity()
```

The Application must be a `@HiltAndroidApp` named in the manifest too (see [hilt-app-not-registered](hilt-app-not-registered.md)).
