package droidmap

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@OptIn(ExperimentalSerializationApi::class)
val prettyJson = Json { prettyPrint = true; prettyPrintIndent = " " }
val compactJson = Json

/** Plain Kotlin values (String, Number, Boolean, List, Map, null) to JSON. */
fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(LinkedHashMap<String, JsonElement>().apply { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } })
    is Iterable<*> -> JsonArray(value.map { toJson(it) })
    is Array<*> -> JsonArray(value.map { toJson(it) })
    else -> JsonPrimitive(value.toString())
}

/** JSON back to plain Kotlin values; whole numbers become Int when they fit. */
fun fromJson(e: JsonElement?): Any? = when (e) {
    null, JsonNull -> null
    is JsonObject -> LinkedHashMap<String, Any?>().apply { e.forEach { (k, v) -> put(k, fromJson(v)) } }
    is JsonArray -> e.map { fromJson(it) }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.longOrNull != null -> e.longOrNull!!.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        else -> e.doubleOrNull
    }
}

fun jsonText(value: Any?, pretty: Boolean = true): String =
    if (pretty) prettyJson.encodeToString(JsonElement.serializer(), toJson(value))
    else compactJson.encodeToString(JsonElement.serializer(), toJson(value))

/** Sorted-key JSON text, used to compare and order attribute maps. */
fun canonical(value: Any?): String = when (value) {
    is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
        .joinToString(",", "{", "}") { (k, v) -> JsonPrimitive(k.toString()).toString() + ":" + canonical(v) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { canonical(it) }
    else -> toJson(value).toString()
}

fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<String, Any?>.bool(key: String): Boolean = this[key] == true
fun Map<String, Any?>.strings(key: String): List<String> = (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
fun Map<String, Any?>.ints(key: String): List<Int> = (this[key] as? List<*>)?.filterIsInstance<Number>()?.map { it.toInt() } ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.maps(key: String): List<Map<String, Any?>> =
    (this[key] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
