# gson-model-not-kept

Default: **warning**. A class that Gson reads or writes by reflection has fields that R8 may rename in a release build: the project shrinks release builds (`isMinifyEnabled = true`), and the fields have no `@SerializedName`, the class has no `@Keep`, and no keep rule keeps its members.

droidmap looks for the classes Gson reflects on: `fromJson(json, Model::class.java)`, `TypeToken<List<Model>>`, the return and `@Body` types of a Retrofit API when the project uses `GsonConverterFactory`, and the project classes that their fields hold.

## Why it matters

Gson matches JSON keys to field names at runtime. R8 renames fields in release builds, so `name` becomes `a`: reading JSON leaves the field empty (null, 0, false), and writing JSON uses the wrong keys. Debug builds are not shrunk, so everything works until the release build, often in production. Kotlin non-null fields left null then crash with a NullPointerException far from the cause.

## How to fix

Name each field explicitly; Gson's own R8 rules (Gson 2.11 and later) keep fields that have `@SerializedName`:

```kotlin
data class Repo(
    @SerializedName("name") val name: String,
    @SerializedName("stargazers_count") val stars: Int,
)
```

Or keep the class with `@Keep` (androidx.annotation), or add a rule to `proguard-rules.pro`:

```
-keep class com.example.model.** { *; }
```

Or switch to a library that generates its adapters at compile time (kotlinx.serialization, or Moshi with `@JsonClass(generateAdapter = true)`).
