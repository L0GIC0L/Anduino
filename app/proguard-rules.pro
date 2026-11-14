# Shrinking mainly removes the unused part of material-icons-extended. Libraries that
# rely on reflection are kept whole, and nothing is renamed, to keep the risk low.
-dontobfuscate

# sora-editor + TextMate (tm4e, joni/jcodings regex, gson, snakeyaml)
-keep class io.github.rosemoe.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-keep class com.google.gson.** { *; }
-keep class org.snakeyaml.** { *; }
-dontwarn org.eclipse.jdt.annotation.**
-dontwarn org.snakeyaml.**

# usb-serial-for-android instantiates drivers via reflection
-keep class com.hoho.android.usbserial.** { *; }

# commons-compress optional codecs
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn org.objectweb.asm.**

# kotlinx.serialization models
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class com.l0gic0l.anduino.cli.**$$serializer { *; }
-keepclassmembers class com.l0gic0l.anduino.cli.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# sora-editor references a Kotlin compiler-generated helper that does not exist at runtime
-dontwarn kotlin.Cloneable$DefaultImpls
