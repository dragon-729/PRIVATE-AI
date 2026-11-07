# Used only when the release build is made with ENABLE_R8=true.

# JNI: native code calls these by name.
-keep class com.privateai.ai.llama.LlamaBridge { *; }
-keep class com.whisper.** { *; }
-keep class com.privateai.whisper.WhisperBridge { *; }
-keep interface com.privateai.ai.llama.TokenCallback { *; }
-keepclassmembers class * implements com.privateai.ai.llama.TokenCallback { public void onPiece(byte[]); }

# ONNX Runtime (JNI)
-keep class ai.onnxruntime.** { *; }

# PdfBox-Android and its optional image codecs
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# Room entities
-keep class com.privateai.data.** { *; }
