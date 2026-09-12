# Release shrinking for Walcott. The aim is size, not secrecy: the source is public.

# Nothing is renamed. The debug log travels to the parent as a remote diagnostic, and a stack
# trace of a.b.c() in a family's report is a trace nobody can read — there is no mapping upload.
-dontobfuscate

# Our own code is kept whole. It is serialised by kotlinx.serialization (the wire protocol, the
# stored policy), instantiated by name (receivers, services, workers, the accessibility service,
# the device admin), and read back by class name in a couple of places. Keeping it costs a few
# megabytes; getting one of those wrong costs a phone that silently stops enforcing.
-keep class dev.walcott.** { *; }

# kotlinx.serialization, belt and braces over the rules the library ships: generated serializers
# are looked up through companion objects.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }

# osmdroid reaches optional dependencies it does not declare.
-dontwarn org.osmdroid.**
-dontwarn org.apache.http.**
