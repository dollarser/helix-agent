# sherpa JNI resolves configuration fields and constructors by their original names.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# sherpa JNI resolves the typed invoke(float[]) -> Integer callback by name.
-keep class com.helix.app.voice.MeloSpeechEngine$* { *; }
