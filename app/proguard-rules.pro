# kotlinx.serialization
-keepattributes Signature, *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kinonn.ocrmobile.**$$serializer { *; }
-keepclassmembers class com.kinonn.ocrmobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.kinonn.ocrmobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Paddle Lite JNI — keep native method bindings and the engine class
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.kinonn.ocrmobile.ocr.PaddleLiteOcrEngine { *; }

# Hilt
-keep class dagger.hilt.** { *; }
