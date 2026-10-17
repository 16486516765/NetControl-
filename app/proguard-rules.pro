# NetControl ProGuard rules (release 启用 minify；Shizuku 类必须 keep，反射调用)
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
