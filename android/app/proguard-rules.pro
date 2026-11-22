# The Rust library finds these by name over JNI, so R8 must not rename or remove them.

# Native methods on the engine facade (looked up as Java_..._nativeXxx).
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.whisperflow.floating.native.NativeDictationEngine { *; }

# Callbacks invoked from Rust by method name and signature.
-keep interface com.whisperflow.floating.native.DictationListener { *; }
-keep class * implements com.whisperflow.floating.native.DictationListener {
    public <methods>;
}
