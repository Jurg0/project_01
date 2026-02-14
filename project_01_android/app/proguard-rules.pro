# kotlinx.serialization — keep @Serializable classes and their generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.project01.session.**$$serializer { *; }
-keepclassmembers class com.project01.session.** {
    *** Companion;
}
-keepclasseswithmembers class com.project01.session.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# @Parcelize classes
-keep class com.project01.session.Player { *; }
-keep class com.project01.session.Video { *; }

# Wi-Fi P2P classes accessed via Intent extras
-keep class android.net.wifi.p2p.** { *; }
