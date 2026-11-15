# moshi-model-not-kept

Default: **warning**. A class that Moshi reads or writes by reflection has fields that R8 may rename in a release build: the project shrinks release builds (`isMinifyEnabled = true`), the class has no generated adapter (`@JsonClass(generateAdapter = true)`), its fields have no `@Json(name = ...)`, the class has no `@Keep`, and no keep rule keeps its members.

droidmap looks for the classes Moshi reads: `moshi.adapter(Model::class.java)` and `moshi.adapter<Model>()`, the return and `@Body` types of a Retrofit API when the project uses `MoshiConverterFactory`, and the project classes their fields hold. A class with a generated adapter is fine, but the classes its fields hold are checked too: each needs its own.

## Why it matters

Without code generation, Moshi finds a class's properties at runtime, by name (with `KotlinJsonAdapterFactory` and kotlin-reflect, or by Java reflection), and matches them to JSON keys. R8 renames fields and properties in release builds, so `title` becomes `a`: reading JSON leaves it empty, writing JSON uses the wrong keys, and a non-null Kotlin property can fail with `JsonDataException`. Debug builds are not shrunk, so everything works until the release build.

## How to fix

Use Moshi's code generation, which writes an adapter for each class at compile time; Moshi's own R8 rules keep the generated adapters:

```kotlin
@JsonClass(generateAdapter = true)
data class Item(val title: String, val url: String)
```

(with the `com.squareup.moshi:moshi-kotlin-codegen` processor through KSP). Or name each field (`@Json(name = "title") val title: String`), keep the class with `@Keep` (androidx.annotation), or add a rule to `proguard-rules.pro`:

```
-keep class com.example.model.** { *; }
```
