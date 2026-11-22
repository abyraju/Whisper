# sherpa-onnx's native library looks up these classes, fields and constructors by name over JNI,
# so R8 must not rename, remove or shrink them.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Callbacks into the app.
-keep interface com.whisperflow.floating.speech.DictationListener { *; }
