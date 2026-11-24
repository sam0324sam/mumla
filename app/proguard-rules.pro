# R8 / ProGuard rules for Mumla Walkie-Talkie Edition
# Ensures JNI (JavaCPP), Protobuf, SpongyCastle, Android Fragments/Preferences, and reflection stay intact.

-dontobfuscate
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# 1. JavaCPP & Native Audio Codecs (Opus / Speex JNI bindings & Platform annotations)
-keep class com.googlecode.javacpp.** { *; }
-keep class se.lublin.humla.audio.javacpp.** { *; }
-keep @com.googlecode.javacpp.annotation.Platform class * { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*,RuntimeVisibleAnnotations,Signature,InnerClasses,EnclosingMethod
-dontwarn org.apache.maven.**

# 2. Protocol Buffers (Mumble protocol messages)
-keep class se.lublin.humla.protobuf.** { *; }
-keep class com.google.protobuf.** { *; }

# 3. SpongyCastle / BouncyCastle (TLS & PKCS12 certificates loaded via Security Provider reflection)
-keep class org.spongycastle.** { *; }
-dontwarn org.spongycastle.**

# 4. MiniDNS (SRV DNS resolution)
-keep class org.minidns.** { *; }
-dontwarn org.minidns.**

# 5. Android Fragments, Preferences, Parcelables, Views, Services, Receivers
-keep public class * extends androidx.fragment.app.Fragment { public <init>(); }
-keep public class * extends androidx.preference.Preference { public <init>(...); }
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 6. Keep Mumla fragments instantiated dynamically by class name (Fragment.instantiate)
-keep class se.lublin.mumla.channel.** { *; }
-keep class se.lublin.mumla.servers.** { *; }
-keep class se.lublin.mumla.preference.** { *; }

# 7. Silence harmless warnings for optional desktop/javax references in dependencies
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn javax.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn sun.misc.Unsafe
