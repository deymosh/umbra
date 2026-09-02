-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# Keep metadata/annotations used by Hilt, Room and serialization-generated code.
# AGP 9.2+ tightened R8's -keepattributes wildcard matching: `*Annotation*` no longer implicitly
# covers the RuntimeInvisible* attributes, so they're listed explicitly rather than relying on
# the wildcard alone.
-keepattributes Signature,*Annotation*,RuntimeInvisibleAnnotations,RuntimeInvisibleParameterAnnotations,RuntimeInvisibleTypeAnnotations,InnerClasses,EnclosingMethod

# Keep kotlinx.serialization generated serializer classes and hooks.
-keep class **$$serializer { *; }
-keepclassmembers class ** {
	kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx.coroutines ships its own consumer proguard rules, but those only cover the library's
# own classes -- they can't know about the synthetic Continuation/Flow-operator classes R8
# generates from app code that merely *uses* coroutines (e.g. a ViewModel's flow.map{}.collect{}
# chain). A benchmark-build (minifyEnabled) crash was traced via mapping.txt to exactly one of
# these: a NullPointerException deep in kotlinx.coroutines' own dispatch machinery
# (DispatchedTask/EventLoopImplPlatform) while resuming a generated
# "...$$inlined$map$1$2$1.invokeSuspend()" class, only under R8 -- never reproduced on a plain
# (unminified) debug build. Matches a known class of coroutines+R8 interaction issues
# (Kotlin/kotlinx.coroutines#3400 and similar). These rules keep suspend-function state-machine
# metadata and the internal dispatch/queue classes intact rather than leaving it to inference.
-keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    private java.lang.Object result;
    private int label;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keep class kotlinx.coroutines.internal.** { *; }
-keep class kotlinx.coroutines.android.** { *; }
-dontwarn kotlinx.coroutines.**

# Third-party crypto/sqlcipher warnings should not break shrinking.
-dontwarn org.bouncycastle.**
-dontwarn net.zetetic.database.sqlcipher.**

# SQLCipher's native (JNI) layer binds to Java fields/methods by fixed name (e.g.
# mConnectionPtr on SQLiteDatabase/SQLiteConnection) — -dontwarn only silences build-time
# warnings, it does not stop R8 from renaming/stripping those members, which crashes at
# first native call with NoSuchFieldError. This was never caught before because this
# project's CI only ever builds assembleDebug, never a minified build type. The library
# moved from net.sqlcipher.* (android-database-sqlcipher) to net.zetetic.database.sqlcipher.*
# (sqlcipher-android, adopted for 16 KB page-size support) — the keep rules must track
# whichever package the native layer actually binds against.
-keep class net.zetetic.database.sqlcipher.** { *; }
-keepclassmembers class net.zetetic.database.sqlcipher.** { *; }