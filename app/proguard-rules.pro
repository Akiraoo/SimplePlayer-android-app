# Keep the app's own code (small project; avoids surprises from R8 full mode).
-keep class com.akira.simpleplayer.** { *; }
-dontwarn org.jetbrains.annotations.**
