import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.gradle.tasks.MergeSourceSetFolders

plugins {
    id("com.android.application")
    // No explicit version here: AGP 9's built-in-Kotlin machinery already puts a Kotlin
    // Gradle Plugin version on the buildscript classpath internally. Pinning a version
    // here collides with that ("already on the classpath with an unknown version") -
    // Gradle can't verify the two match, so it refuses. Letting this resolve to
    // whatever AGP 9.2.0 already loaded is also the combination Google actually tests.
    id("org.jetbrains.kotlin.android")
    // Room's annotation processor. Using kapt instead of KSP deliberately: KSP releases are
    // versioned as "<kotlin-version>-<ksp-version>" and must match the Kotlin compiler
    // exactly, but that Kotlin version isn't pinned anywhere in this project (see comment
    // above) - it resolves implicitly from AGP 9.2.0's bundled Kotlin Gradle Plugin, whatever
    // that happens to be. Guessing a KSP version here risks a hard "Kotlin version X but KSP
    // expects Y" build failure. kapt rides the same implicit Kotlin Gradle Plugin version as
    // id("org.jetbrains.kotlin.android") above, so it can't drift out of sync the same way.
    id("org.jetbrains.kotlin.kapt")
}

// TurtleLauncher: AI Chat GitHub sync token, env-var-first with a local file
// fallback, but that fallback is now an AES-256 ENCRYPTED file (github_chat_token.txt.enc)
// instead of plaintext - matching how a .jks
// keystore needs a password to actually be read, not just OS file permissions.
// Decrypted at build time via openssl using GITHUB_CHAT_TOKEN_PASSWORD, which you
// set yourself (export it in your shell profile, or Termux's ~/.bashrc) - that
// password is never written to any file in this repo, encrypted or not.
val getGithubChatSyncToken = {
    System.getenv("GITHUB_CHAT_SYNC_TOKEN") ?: run {
        val encryptedFile = File(rootDir, "github_chat_token.txt.enc")
        val password = System.getenv("GITHUB_CHAT_TOKEN_PASSWORD")
        if (encryptedFile.canRead() && encryptedFile.isFile && !password.isNullOrBlank()) {
            val process = ProcessBuilder(
                "openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "100000", "-d",
                "-in", encryptedFile.absolutePath,
                "-pass", "env:GITHUB_CHAT_TOKEN_PASSWORD"
            ).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                output
            } else {
                logger.warn("BUILD: Failed to decrypt github_chat_token.txt.enc (wrong password?), AI Chat saving will get disabled !")
                ""
            }
        } else {
            logger.warn("BUILD: You have no GitHub chat-sync token (or no GITHUB_CHAT_TOKEN_PASSWORD set), AI Chat saving will get disabled !")
            ""
        }
    }
}

val getBuildType = {
    val buildType = System.getenv("ZL_BUILD_TYPE") ?: "DEBUG"
    logger.warn("BUILD: Build Type --> $buildType")
    buildType
}

val nameId = "com.endiq.turtlelauncher"
// namespace controls where the generated R/BuildConfig classes land, and must match
// the Kotlin/Java source package (com.endiq.turtlelauncher.*) or every implicit "R"
// reference and "import ...BuildConfig" across the codebase breaks. Since the full
// Turtle rebrand renamed the source tree to com.endiq.turtlelauncher.*, namespace and
// applicationId now coincide.
val namespaceId = "com.endiq.turtlelauncher"
val generatedTurtleDir = file("$buildDir/generated/source/turtle/java")
val launcherAPPName = project.findProperty("launcher_app_name") as? String ?: error("The \"launcher_app_name\" property is not set in gradle.properties.")
val launcherName = project.findProperty("launcher_name") as? String ?: error("The \"launcher_name\" property is not set in gradle.properties.")
val launcherVersionCode = (project.findProperty("launcher_version_code") as? String)?.toIntOrNull() ?: error("The \"launcher_version_code\" property is not set as an integer in gradle.properties.")
val launcherVersionName = project.findProperty("launcher_version_name") as? String ?: error("The \"launcher_version_name\" property is not set in gradle.properties.")

configurations {
    create("instrumentedClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
}

android {
    namespace = namespaceId
    // TurtleLauncher: bumped so Android 15 (API 35) APIs - WindowInsetsControllerCompat edge-
    // to-edge, predictive back, etc. (see Tools#setFullscreen and the manifest's
    // enableOnBackInvokedCallback) - are actually on the compile classpath. Deliberately NOT
    // bumping targetSdk past 34 in the same change: targeting 35 makes edge-to-edge mandatory
    // (Window#setDecorFitsSystemWindows(true) becomes a no-op) and predictive back default-on
    // app-wide, both instant behavior changes across every one of this app's ~20 activities/
    // fragments with zero opportunity to test each one on real Android 15 hardware first. The
    // code above already opts in to both explicitly and per-activity where needed, so once
    // that's been verified on-device, bumping targetSdk to 35 here should be close to a no-op.
    // Separately: apps targeting API 35 must also ship 16 KB-page-aligned native libraries for
    // Play Store submission - this project's numerous prebuilt jniLibs .so files (renderers,
    // LWJGL natives, libpojavexec.so, etc.) would need to be confirmed/rebuilt 16 KB-aligned
    // before that targetSdk bump, independent of anything fixable from Kotlin/Java source here.
    // Bumped again to 36: androidx.media3:*:1.11.0's AAR metadata requires compileSdk 36+
    // (checkDebugAarMetadata fails otherwise). Same reasoning as above applies unchanged - this
    // is still just the compile-time API surface, targetSdk stays at 34 below.
    compileSdk = 36

    signingConfigs {
        create("releaseBuild") {
            val pwd = System.getenv("ENDIQ_KEYSTORE_PASSWORD") ?: ""
            storeFile = file("endiq-key.jks")
            storePassword = pwd
            keyAlias = "mtp"
            keyPassword = pwd
        }
        create("customDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = nameId
        minSdk = 26
        targetSdk = 34
        versionCode = launcherVersionCode
        versionName = launcherVersionName
        multiDexEnabled = true //important
        manifestPlaceholders["launcher_name"] = launcherAPPName
    }

    buildTypes {
        val storageProviderId = "$nameId.storage_provider"

        getByName("debug") {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("customDebug")
            resValue("string", "storageProviderAuthorities", "$storageProviderId.debug")
        }
        create("proguard") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isShrinkResources = true
        }
        create("proguardNoDebug") {
            initWith(getByName("proguard"))
            isDebuggable = false
        }
        getByName("release") {
            // Don't set to true or java.awt will be a.a or something similar.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "storageProviderAuthorities", storageProviderId)
            signingConfig = signingConfigs.getByName("releaseBuild")
        }
    }

    sourceSets["main"].java.srcDirs(generatedTurtleDir)

    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    val variantName = variant.name.replaceFirstChar { it.uppercaseChar() }
                    afterEvaluate {
                        val task = tasks.named("merge${variantName}Assets").get() as MergeSourceSetFolders
                        task.doLast {
                            val arch = System.getProperty("arch", "all")
                            val assetsDir = task.outputDir.get().asFile
                            val jreList = listOf("jre-8", "jre-17", "jre-21", "jre-25")
                            println("arch:$arch")
                            jreList.forEach { jreVersion ->
                                val runtimeDir = File("$assetsDir/components/$jreVersion")
                                println("runtimeDir:${runtimeDir.absolutePath}")
                                runtimeDir.listFiles()?.forEach {
                                    if (arch != "all" && it.name != "version" && !it.name.contains("universal") && it.name != "bin-${arch}.tar.xz") {
                                        println("delete:${it} : ${it.delete()}")
                                    }
                                }
                            }
                        }
                    }

                    (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                        val baseName = "$launcherName-${if (variant.buildType == "release") defaultConfig.versionName else "Debug-${defaultConfig.versionName}"}"
                        output.outputFileName = if (abi == "all") "$baseName.apk" else "$baseName-$abi.apk"
                    }
                }
            }
        }
    }

    splits {
        val arch = System.getProperty("arch", "all")
        if (arch != "all") {
            abi {
                isEnable = true
                reset()
                when (arch) {
                    "arm" -> include("armeabi-v7a")
                    "arm64" -> include("arm64-v8a")
                    "x86" -> include("x86")
                    "x86_64" -> include("x86_64")
                }
            }
        }
    }

    // ndkVersion = "25.2.9519653"

    // externalNativeBuild {
    //     ndkBuild {
    //         path = file("src/main/jni/Android.mk")
    //     }
    // }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    buildFeatures {
        prefab = true
        buildConfig = true
        viewBinding = true
        resValues = true
    }

    // Kept in step with compileSdk = 36 above - AGP 9.2.0 already forces build-tools 36.0.0 at
    // build time regardless (see the "specified Android SDK Build Tools version is ignored"
    // warning this was silently hitting before), so pinning it explicitly here just removes
    // that warning instead of relying on the fallback.
    buildToolsVersion = "36.0.0"
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// TurtleLauncher: this must be an object, not a plain top-level fun. A `fun` declared
// directly in a .kts script compiles as a member of the script's own generated class,
// so calling it from inside doLast (execution time) still captures a reference to that
// script/Project instance to dispatch the call - which is exactly what the configuration
// cache's "cannot serialize Gradle script object references" error is about, even though
// the function body itself never touches Project. A top-level object is a genuinely
// independent singleton with no implicit outer/script reference, so calling a method on
// it doesn't drag the script instance into the task's serialized state.
object InfoDistributorGenerator {
    fun generate(sourceOutputDir: File, packageName: String, className: String, constantMap: Map<String, String>) {
        val outputDir = File(sourceOutputDir, packageName.replace(".", "/"))
        outputDir.mkdirs()
        val javaFile = File(outputDir, "$className.java")
        val constants = constantMap.entries.joinToString("\n") { (key, value) ->
            "\tpublic static final String $key = \"$value\";"
        }
        javaFile.writeText(
            """
            |/**
            | * Automatically generated file. DO NOT MODIFY
            | */
            |package $packageName;
            |
            |public class $className {
            |$constants
            |}
            """.trimMargin()
        )
        println("Generated Java file: ${javaFile.absolutePath}")
    }
}

tasks.register("generateInfoDistributor") {
    // Gradle's configuration cache forbids touching Project (via project.property(...),
    // or the getBuildType() lambda above, which closes over this script's implicit
    // Project receiver) from inside doLast - that's execution time, after the cache
    // would have already been written. Resolve every Project-dependent value here in
    // the task's configuration block instead, into plain local vals - doLast below
    // then only touches those (already just Strings/a File) and the independent
    // InfoDistributorGenerator object above, neither of which reference Project or the
    // script instance at all.
    val githubChatSyncToken = getGithubChatSyncToken()
    val launcherName = project.property("launcher_name").toString()
    val appName = project.property("launcher_app_name").toString()
    val buildType = getBuildType()
    val outputDir = generatedTurtleDir

    doLast {
        val constantMap = mapOf(
            "GITHUB_CHAT_SYNC_TOKEN" to githubChatSyncToken,
            "LAUNCHER_NAME" to launcherName,
            "APP_NAME" to appName,
            "BUILD_TYPE" to buildType
        )
        InfoDistributorGenerator.generate(outputDir, "com.endiq.turtlelauncher", "InfoDistributor", constantMap)
    }
}

tasks.named("preBuild") {
    dependsOn("generateInfoDistributor")
}

dependencies {
    // TurtleLauncher CRASH FIX (MC 26.3+ SDL): org.libsdl.app.* classes now live as real
    // source under src/main/java/org/libsdl/app (adapted from Turtle Launcher's public
    // source, itself based on SDL's official Android glue) instead of the precompiled
    // libs/sdl3-android-classes.jar this used to be - that jar's SDLActivity.nativeSetupJNI()
    // was compiled ()V but the bundled libSDL3.so's JNI_OnLoad expects ()I, a version
    // mismatch that hard-aborted the process. See SdlAndroidJniPrep for the rest of the story.
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("commons-codec:commons-codec:1.17.1")
    // implementation("com.wu-man:android-bsf-api:3.1.3")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0-beta01")
    implementation("androidx.annotation:annotation:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.0")
    // TurtleLauncher: declarative, dependency-ordered app startup for the pieces of
    // TurtleApplication.onCreate() that don't need to block everything else (ANR watchdog,
    // dynamic color theming) - see TurtleStartupInitializer.
    implementation("androidx.startup:startup-runtime:1.1.1")
    // TurtleLauncher: android.os.Trace wrapper that safely no-ops below API 18 instead of
    // crashing - used to mark hot sections (asset prefetch, launch) so they show up when
    // profiling with Perfetto/Android Studio Profiler, without adding a hard dependency on
    // any particular profiler.
    implementation("androidx.tracing:tracing:1.2.0")
    // TurtleLauncher: installs the Baseline Profile below at app-install/first-run time so
    // ART can AOT-compile the app's known hot paths up front instead of interpreting/JIT-ing
    // them cold - biggest relative win on exactly the low-end devices where JIT warm-up is
    // most expensive. See src/main/baseline-prof.txt.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("com.github.duanhong169:checkerboarddrawable:1.0.2")
    implementation("com.github.PojavLauncherTeam:portrait-sdp:ed33e89cbc")
    implementation("com.github.PojavLauncherTeam:portrait-ssp:6c02fd739b")
    implementation("com.github.Mathias-Boulay:ExtendedView:1.0.0")
    implementation("com.github.Mathias-Boulay:android_gamepad_remapper:2.0.3")
    implementation("com.github.Mathias-Boulay:virtual-joystick-android:1.14")
    implementation("com.github.skydoves:powerspinner:1.2.7")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
    implementation("com.github.angcyo.DslTablayout:TabLayout:3.6.5")

    implementation("top.fifthlight.touchcontroller:proxy-client-android:0.0.2")

    // Shizuku / Sui - lets the launcher do a small number of things that genuinely need
    // ADB-shell or root privilege, without the launcher itself having to be rooted:
    // granting all-files access + battery-optimization exemption, raising Android 14+'s
    // phantom-process limit (a real cause of "the game just closes"), and reading the full
    // system logcat for crash diagnosis instead of only this app's own lines.
    // 13.1.5 is the current release (confirmed against Maven Central); both artifacts must
    // move together. `provider` is what actually acquires the binder (ShizukuProvider,
    // declared in the manifest) and is what makes Shizuku usable at all - `api` alone only
    // gives you the classes. Entirely optional at runtime: the app works with neither
    // installed, and ShizukuManager reports NOT_INSTALLED if that is the case.
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // implementation("com.intuit.sdp:sdp-android:1.0.5")
    // implementation("com.intuit.ssp:ssp-android:1.0.5")

    implementation("org.tukaani:xz:1.9")

    // Apache Commons Compress / IO - real, versioned Maven Central deps replacing
    // libs/ExagearApacheCommons.jar, a hand-vendored, unversioned fat jar (dated Feb 2020,
    // confirmed via its class list to contain ONLY re-packaged commons-io/commons-compress/
    // commons-lang3/commons-collections4 classes - nothing Exagear-specific despite the name)
    // that every existing FileUtils call in this codebase (39 files) has quietly been
    // resolving against for years, plus one commons-compress call site. The old jar is
    // excluded from the libs/ fileTree below so its classes can't collide with these; the
    // file itself has been deleted. commons-lang3/commons-collections4 were also bundled in
    // that same jar but have zero real usages anywhere in the codebase (checked directly),
    // so they're left out rather than added back for nothing.
    implementation("commons-io:commons-io:2.20.0")
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Real per-release version-string comparison (used by the launcher self-updater, see
    // UpdateUtils.compareDottedVersions) - added so that hand-rolled comparator can eventually
    // be replaced by ComparableVersion's battle-tested Maven-style parsing instead. Not yet
    // wired in this round; declared and ready.
    implementation("org.apache.maven:maven-artifact:3.9.16")

    // kotlinx.coroutines - not previously a dependency anywhere in this project; everything
    // async currently goes through TaskExecutors' own hand-rolled ThreadPoolExecutor instead
    // (see feature/turtle/*). Added as a real, standard dependency for future Kotlin-idiomatic
    // async code, but nothing in the codebase has been switched over to it this round - that's
    // a real migration, not a drop-in, and risks fighting the existing executor-based model if
    // done half-way.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // MMKV - fast mmap-backed key-value store from Tencent. Declared and ready; AllSettings'
    // existing SettingUnit classes (BooleanSettingUnit/StringSettingUnit/etc.) are still on
    // SharedPreferences underneath - migrating them to MMKV is a real, separate change (their
    // read/write internals, not just the dependency) that hasn't been done this round.
    // CAVEAT (real, unresolved): MMKV dropped 32-bit arch + API<23 support as of v2.0.0 per its
    // own docs - this project still ships armeabi-v7a/x86 (32-bit) builds per the jniLibs ABI
    // set. Using this version as-is on a 32-bit device would be a real crash risk once anything
    // actually calls into it. The 1.3.x LTS line keeps 32-bit support if that matters more than
    // being on latest - flagging rather than picking silently.
    implementation("com.tencent:mmkv:2.4.1")

    // Okio - modern I/O library from Square. Already present transitively via OkHttp 4.12.0
    // (which depends on an older 3.x Okio internally), but relying on a transitive version
    // means it can shift under this project any time the OkHttp version changes, with no
    // guarantee the app's own code (if any starts using Okio directly) stays compatible.
    // Declaring it explicitly pins a known-good version and makes it a real, intentional
    // dependency rather than an implicit side effect of OkHttp. Nothing in the codebase uses
    // Okio's own API directly yet (all current I/O goes through java.io/commons-io) - this is
    // available for future use, most naturally alongside Room/DataStore below since both can
    // use Okio-backed storage.
    implementation("com.squareup.okio:okio:3.17.0")

    // AndroidX DataStore (Preferences variant) - SharedPreferences replacement with a
    // coroutines/Flow-based API and no synchronous-disk-I/O-on-main-thread footgun (which
    // SharedPreferences' commit()/apply() edge cases have). AllSettings' SettingUnit classes
    // are still on SharedPreferences underneath, same as the MMKV note above - migrating them
    // is a real, separate change to SettingUnit's internals, not done this round. Requires
    // kotlinx-coroutines (already present above) to actually use its Flow-based read API.
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Room - SQLite ORM with compile-time query verification. No entities/DAOs/database class
    // exist yet - there's no current SQLite usage anywhere in this codebase to migrate (crash
    // logs, mod lists, etc. are all flat files/JSON today), so this is declared and ready for
    // whichever future feature actually needs relational storage, rather than force-fitting an
    // existing flat-file feature into tables it doesn't need. See the kotlin-kapt plugin note
    // at the top of this file for why kapt is used here instead of KSP.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    // Process Phoenix - clean full-process restart (vs. just finishing the current Activity).
    // Declared and ready; no call site uses it yet - see notes on the wider dependency list.
    implementation("com.jakewharton:process-phoenix:3.0.0")

    // SkinViewAndroid (JitPack). Coordinate corrected: the previous
    // com.github.storeforminecraft.SkinViewAndroid:library:master-SNAPSHOT form doesn't resolve -
    // confirmed against the repo's own README/JitPack badge, which publishes the root project as
    // a single artifact (group = com.github.storeforminecraft, artifact = SkinViewAndroid), not
    // the per-module com.github.USER.REPO:MODULE form. No GitHub Releases exist, so master-SNAPSHOT
    // is still the right version. Real Android-native 2D/3D Minecraft skin renderer (OpenGL ES 3.0,
    // SkinView3DSurfaceView.render(bitmap)) - the actual skinview3d project is Three.js/web-only
    // and has no Android build, so this is the closest real equivalent. Wired into the account
    // detail panel - see SkinLoader.getSkinBitmap() and view_account_detail.xml.
    implementation("com.github.storeforminecraft:SkinViewAndroid:master-SNAPSHOT")

    // NBT (Querz/NBT) - standalone Java NBT reader/writer, real tagged release. Declared and
    // ready; no current call site reads a .dat/level file anywhere in this codebase, so nothing
    // wired yet - tell me what should use it (world preview, player data, etc.) and I'll build it.
    implementation("com.github.Querz:NBT:6.1")

    // Media3 - real AndroidX artifacts. Declared and ready; no audio/video playback feature
    // exists anywhere in this codebase currently, so nothing wired - same as NBT above.
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    // Faster launcher-cache I/O (Zstandard compression, see CacheCompression.kt). The @aar
    // classifier is required on Android per the library's own docs (implementation("...@aar"),
    // testImplementation("...") without it) - it's what bundles the actual Android .so files
    // (jni/<abi>/libzstd-jni-*.so) via AGP's native-lib merging. The previous declaration here
    // was missing @aar (would only have carried desktop/server natives) and the comment above
    // it claimed a native lib was manually vendored into jniLibs/arm64-v8a as a workaround -
    // no such file exists anywhere in the project, so this was silently broken from the start.
    // Never caught because nothing had actually called into it until now.
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    // Fast/lightweight compression for small, frequently-rewritten launcher temp files (see
    // CacheCompression.kt). io.maryk.lz4:lz4-android is a real Android port with its own
    // bundled native libs (confirmed via its GitHub releases/Maven Central listing before
    // adding this) - the original lz4-java only ships desktop/server natives, no Android
    // build exists for it (checked and stayed blocked on that one).
    implementation("io.maryk.lz4:lz4-android:1.10.0")
    // Our version of exp4j can be built from source at
    // https://github.com/PojavLauncherTeam/exp4j
    implementation("net.sourceforge.htmlcleaner:htmlcleaner:2.6.1")
    implementation("com.bytedance:bytehook:1.0.10")

    // implementation("net.sourceforge.streamsupport:streamsupport-cfuture:1.7.0")

    // libs/gson-2.8.6.jar used to sit in this fileTree too. It was a hand-vendored copy of
    // Gson (180 classes, all under com/google/gson/**) that duplicated the real
    // com.google.code.gson:gson dependency declared below, which fails
    // :TurtleLauncher:checkDebugDuplicateClasses with "Duplicate class com.google.gson.Gson
    // found in modules gson-2.8.6 ... and gson-2.14.0". Deleted rather than excluded: nothing
    // here needs the older 2.8.6 API, and all 48 Gson call sites compile against the Maven one.
    //
    // libs/shaderc.aar + libs/spvc.aar (both from git.artdeell.mesa_libs) were also vendored
    // in, and also deleted. They broke :TurtleLauncher:processDebugMainManifest outright:
    //
    //   Error: Namespace 'git.artdeell.mesa_libs' is used in multiple modules and/or
    //   libraries: shaderc.aar, spvc.aar. Please ensure that all modules and libraries have
    //   a unique namespace.
    //
    // Two prebuilt AARs sharing one manifest namespace is a hard AGP failure, and neither of
    // them has anything a name change would rescue - no classes.jar, no res/, no assets, just
    // jni/<abi>/libshaderc.so and libspirv-cross-c-shared.so. Those exact libraries are
    // already shipped by this module itself under src/main/jniLibs/<abi>/ (all four ABIs for
    // libshaderc.so), and an app module's own jniLibs outrank a dependency's jni/ folder in
    // mergeDebugNativeLibs, so the AAR copies were being shadowed anyway. Re-adding them as
    // AARs would reintroduce the duplicate-namespace failure for zero payload difference; if
    // these natives need refreshing, update the .so files in jniLibs directly.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"), "exclude" to listOf("ExagearApacheCommons.jar"))))

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.commonmark:commonmark:0.19.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    implementation("com.getkeepsafe.taptargetview:taptargetview:1.14.0")
    implementation("io.github.petterpx:floatingx:2.3.3")
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("com.moandjiezana.toml:toml4j:0.7.2") {
        exclude(group = "com.google.code.gson", module = "gson")
    }
}
