# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# We use Reflection on the builder to avoid creating too many objects
 -keep class net.objecthunter.exp4j.ExpressionBuilder**
 -keepclassmembers class net.objecthunter.exp4j.ExpressionBuilder** {
    *;
 }

-keep class com.movtery.turtlelauncher.ui.activity.ErrorActivity {
    *;
}


# ===================== Terracotta (Friends/LAN play) =====================
# Ported from the Terracotta module's own proguard-rules.pro (Zalith Launcher 2,
# github.com/ZalithLauncher/ZalithLauncher2/Terracotta). libterracotta.so resolves its
# JNI entry points by exact name and calls back into onVpnServiceStateChanged by exact
# signature; without these keeps R8 renames/strips them and the native library fails at
# runtime - only in minified release builds, which makes it especially nasty.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class net.burningtnt.terracotta.TerracottaAndroidAPI {
    native <methods>;
    private static int onVpnServiceStateChanged(...);
}

-keep class net.burningtnt.terracotta.TerracottaAndroidAPI$Metadata {
    *;
}
-keep interface net.burningtnt.terracotta.TerracottaAndroidAPI$VpnServiceCallback {
    *;
}
-keep interface net.burningtnt.terracotta.TerracottaAndroidAPI$VpnServiceRequest {
    *;
}

-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ===================== SDL3 (Minecraft 26.3+) =====================
# Ported from Zalith Launcher 2 (ZalithLauncher2/ZalithLauncher/proguard-rules.pro).
# SDL's native code resolves these Java entry points by exact name/signature via
# JNI reflection (nativeSetupJNI registration, message-box bridge, IME callbacks);
# R8 must not rename or strip them.
-keep class org.libsdl.app.** { *; }

# Turtle's SDL bridge: libsdlhook.so resolves its JNI entry points by exact name,
# and SDLActivity/SdlImeController call the wrappers from Java.
-keep class com.endiq.turtlelauncher.game.sdl.** { *; }
-keep class com.endiq.turtlelauncher.launch.SdlHook { *; }
-keep class com.endiq.turtlelauncher.launch.SdlAndroidJniPrep { *; }

# SDL native resolves this instance method on the host Activity by exact
# signature (see MainActivity.messageboxShowMessageBox).
-keepclassmembers class net.endiq.launcher.MainActivity {
    public int messageboxShowMessageBox(...);
}
