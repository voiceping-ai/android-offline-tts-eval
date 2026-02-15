# Keep sherpa-onnx JNI bindings and Kotlin metadata.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Keep ONNX Runtime Java API (used by NeMo ONNX pipeline).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
