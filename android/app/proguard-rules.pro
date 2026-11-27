# Native JNI entry points and SDK classes must retain their Java names.
# Room/Hilt/WorkManager/ML Kit ship consumer rules; do not keep the entire app.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.paddle.ocr.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
