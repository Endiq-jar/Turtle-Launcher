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

# TurtleLauncher: the prebuilt libpojavexec.so (all ABIs) hardcodes the JNI class
# path com/movtery/turtlelauncher/ui/activity/ErrorActivity and calls its static
# showExitMessage(Context, int, boolean) from the game-exit hook. Nothing in Java
# references that trampoline class, so without this R8 strips/renames it in the
# minified build types and every game launch SIGABRTs on the FindClass. See the
# trampoline's own doc comment for the full story.
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

# Gson: TerracottaState.TerracottaProfile (and every other Gson model in the app) is
# instantiated reflectively and its @SerializedName fields are read/written by name.
# Zalith Launcher 2 protects the same classes with @Keep; this is the matching global
# rule so the minified "proguard" build types can't strip or rename serialized fields.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
