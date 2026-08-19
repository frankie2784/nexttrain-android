# Gson deserializes these classes by reading field names via reflection —
# none of them use @SerializedName, so the JSON key *is* the field name.
# R8 renaming/removing a field here wouldn't fail the build, it would just
# silently deserialize to null/default at runtime. Scoped to the packages
# that hold Gson request/response DTOs and the data models they map to,
# rather than the whole app, so unrelated code still gets shrunk/obfuscated.
#
# -keep (not just -keepclassmembers) is required on the classes themselves:
# these DTOs are only ever instantiated reflectively by Gson, never via a
# `new X(...)` R8 can see in source, so without a class-level -keep R8's
# optimizer can merge/abstract them away as "never concretely allocated" —
# Gson then throws "Abstract classes can't be instantiated!" at runtime in
# release builds only (debug skips R8 entirely).
-keep class com.nexttrain.data.** { <fields>; }
-keep class com.nexttrain.api.** { <fields>; }
-keep class com.nexttrain.prefs.** { <fields>; }
-keep class com.nexttrain.config.** { <fields>; }

# Gson's own recommended rules (see google/gson#1470): generic signatures
# and TypeToken must survive for List<T>/Map<K,V> deserialization
# (WidgetPrefs uses TypeToken<List<...>>() for every cached collection).
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn sun.misc.**
