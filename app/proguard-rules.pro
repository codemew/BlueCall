# JNI bridge: native methods are looked up by name from libbluecall.so.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.codemew.bluecall.audio.NativeAudio { *; }
