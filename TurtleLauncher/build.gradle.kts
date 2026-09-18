import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import com.android.build.gradle.tasks.MergeSourceSetFolders

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

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

    buildToolsVersion = "36.0.0"
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

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
    implementation("javax.annotation:javax.annotation-api:1.3.2")
    implementation("commons-codec:commons-codec:1.17.1")
    // implementation("com.wu-man:android-bsf-api:3.1.3")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0-beta01")
    implementation("androidx.annotation:annotation:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("androidx.tracing:tracing:1.2.0")
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

    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // implementation("com.intuit.sdp:sdp-android:1.0.5")
    // implementation("com.intuit.ssp:ssp-android:1.0.5")

    implementation("org.tukaani:xz:1.9")

    implementation("commons-io:commons-io:2.20.0")
    implementation("org.apache.commons:commons-compress:1.28.0")

    implementation("org.apache.maven:maven-artifact:3.9.16")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("com.tencent:mmkv:2.4.1")

    implementation("com.squareup.okio:okio:3.17.0")

    // AndroidX DataStore (Preferences variant) - SharedPreferences replacement with a
    // coroutines/Flow-based API and no synchronous-disk-I/O-on-main-thread footgun (which
    // SharedPreferences' commit()/apply() edge cases have). AllSettings' SettingUnit classes
    // are still on SharedPreferences underneath, same as the MMKV note above - migrating them
    // is a real, separate change to SettingUnit's internals, not done this round. Requires
    // kotlinx-coroutines (already present above) to actually use its Flow-based read API.
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    // Process Phoenix - clean full-process restart (vs. just finishing the current Activity).
    // Declared and ready; no call site uses it yet - see notes on the wider dependency list.
    implementation("com.jakewharton:process-phoenix:3.0.0")

    implementation("com.github.storeforminecraft:SkinViewAndroid:master-SNAPSHOT")

    // NBT (Querz/NBT) - standalone Java NBT reader/writer, real tagged release. Declared and
    // ready; no current call site reads a .dat/level file anywhere in this codebase, so nothing
    // wired yet - tell me what should use it (world preview, player data, etc.) and I'll build it.
    implementation("com.github.Querz:NBT:6.1")

    // Media3 - real AndroidX artifacts. Declared and ready; no audio/video playback feature
    // exists anywhere in this codebase currently, so nothing wired - same as NBT above.
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    implementation("io.maryk.lz4:lz4-android:1.10.0")
    implementation("net.sourceforge.htmlcleaner:htmlcleaner:2.6.1")
    implementation("com.bytedance:bytehook:1.0.10")

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
