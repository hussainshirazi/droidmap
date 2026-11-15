# nav-route-type-unsupported

Default: **error**. A type-safe route class (`@Serializable data class Profile(val user: User)`) has a property of a type Navigation has no NavType for, and the destination is registered without a `typeMap` (`composable<Profile> { }`), or a ViewModel reads it with `savedStateHandle.toRoute<Profile>()` without one.

Navigation has NavTypes for `String`, `Int`, `Long`, `Float`, `Double`, `Boolean` (also nullable), enums, `IntArray` and the other primitive arrays, `Array<String>`, and lists of those and of enums. Anything else needs a custom NavType: a project class, `Set` or `Map`, `Char`, `Short`, unsigned numbers, dates, `Uuid`. Value classes and types droidmap cannot resolve are not reported.

## Why it matters

kotlinx.serialization can serialize the class, so it compiles, but Navigation throws `IllegalArgumentException` when it builds the destination ("Route Profile could not find any NavType for argument user of type User - typeMap received was {}"): the whole NavHost fails, or the screen crashes when its ViewModel reads the route.

## How to fix

Pass the id instead of the object (`data class Profile(val userId: String)`) and load the object on the other side, which also keeps the back stack small. Or give each custom type a NavType:

```kotlin
val UserType = object : NavType<User>(isNullableAllowed = false) {
    override fun get(bundle: Bundle, key: String) = bundle.getString(key)?.let { Json.decodeFromString<User>(it) }
    override fun parseValue(value: String) = Json.decodeFromString<User>(value)
    override fun serializeAsValue(value: User) = Json.encodeToString(value)
    override fun put(bundle: Bundle, key: String, value: User) = bundle.putString(key, Json.encodeToString(value))
}

composable<Profile>(typeMap = mapOf(typeOf<User>() to UserType)) { }
savedStateHandle.toRoute<Profile>(typeMap = mapOf(typeOf<User>() to UserType))
```
