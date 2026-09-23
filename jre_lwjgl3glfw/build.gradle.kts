plugins {
    java
}

group = "org.lwjgl.glfw"

// Keep the checked-in Vulkan bridge classes as an input while the full jar task
// refreshes its output. The local legacy LWJGL dependency set does not contain
// Vulkan, but the Android bridge asset does and SDL launches must retain it.
val bridgePayloadSnapshot = tasks.register<Copy>("snapshotBridgePayload") {
    from(file("../TurtleLauncher/src/main/assets/components/lwjgl3/lwjgl-glfw-bridge.jar"))
    into(file("$buildDir/bridge-input"))
}

// The current arm64 renderer native exports GL32C.glDeleteSync, while the
// exact Minecraft 26.3 Java payload calls the older nglDeleteSync wrapper.
// Patch only that wrapper in the matching 3.3.6-snapshot class; do not replace
// the newer native renderer or the rest of the version-specific OpenGL API.
val patchedGL32C = file("$buildDir/generated/gl32c/org/lwjgl/opengl/GL32C.class")
val patchGL32C = tasks.register<Exec>("patchGL32C") {
    commandLine(
        "python3",
        file("patch_gl32c_delete_sync.py").absolutePath,
        file("libs/lwjgl-opengl.jar").absolutePath,
        patchedGL32C.absolutePath
    )
    outputs.file(patchedGL32C)
}

tasks.jar {
    dependsOn(bridgePayloadSnapshot)
    dependsOn(patchGL32C)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("lwjgl-glfw-classes")
    destinationDirectory.set(file("../TurtleLauncher/src/main/assets/components/lwjgl3/"))
    // Auto update the version with a timestamp so the project jar gets updated by Turtle
    doLast {
        val versionFile = file("../TurtleLauncher/src/main/assets/components/lwjgl3/version")
        versionFile.writeText(System.currentTimeMillis().toString())
    }
    from(patchedGL32C) {
        into("org/lwjgl/opengl")
    }
    from({
        configurations.getByName("runtimeClasspath").map {
            println(it.name)
            if (it.isDirectory) it else zipTree(it).matching {
                exclude("org/lwjgl/opengl/GL32C.class")
            }
        }
    })
    // The checked-in bridge carries the released Vulkan/support payload. Include
    // it in the full legacy jar while keeping the SDL bridge isolated from the
    // legacy LWJGL core.
    from(zipTree(file("$buildDir/bridge-input/lwjgl-glfw-bridge.jar")))
    exclude("net/java/openjdk/cacio/ctc/**")
    manifest {
        attributes("Manifest-Version" to "3.3.6")
        attributes("Automatic-Module-Name" to "org.lwjgl")
    }
}

// Minecraft 26.x supplies its own version-specific LWJGL core, GLFW and SDL
// jars. The bridge contains only Android GLFW/Vulkan support classes; it must
// not contribute org.lwjgl.system callback classes to the SDL class path.
tasks.register<Jar>("bridgeJar") {
    dependsOn(tasks.classes)
    dependsOn(bridgePayloadSnapshot)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("lwjgl-glfw-bridge")
    destinationDirectory.set(file("../TurtleLauncher/src/main/assets/components/lwjgl3/"))

    from(sourceSets.main.get().output) {
        include("android/**")
        include("net/java/openjdk/**")
        include("org/lwjgl/glfw/CallbackBridge.class")
        include("org/lwjgl/glfw/Callbacks.class")
        include("org/lwjgl/glfw/GLFW.class")
        include("org/lwjgl/glfw/GLFW\$Functions.class")
        include("org/lwjgl/glfw/GLFWWindowProperties.class")
        include("org/lwjgl/input/InfdevMouse.class")
        include("org/lwjgl/opengl/PojavRendererInit.class")
        // Keep only the Android bridge classes. The exact SDL Minecraft
        // version supplies its own GLFW callback/core classes after this jar;
        // copying the legacy callback implementation here would mix LWJGL
        // 3.3.x classes with the 3.4.x SDL ABI.
        include("com/endiq/turtlelauncher/**")
    }
    from({
        configurations.getByName("runtimeClasspath").map {
            zipTree(it).matching {
                // Keep support libraries and the generated GLFW/Vulkan bridge
                // payload, but never a second LWJGL core/system implementation.
                exclude("org/lwjgl/**")
                exclude("net/java/openjdk/cacio/ctc/**")
                include("**/*")
            }
        }
    })
    // Keep only bridge Vulkan classes that are absent from the legacy build
    // dependencies. The exact SDL version's lwjgl-glfw jar is added after
    // this bridge by Tools.getLwjglAbiOverrideClasspath; the bridge's custom
    // GLFW class wins while its matching callback classes remain intact.
    from({
        configurations.getByName("runtimeClasspath").map {
            zipTree(it).matching {
                include("org/lwjgl/vulkan/**")
            }
        }
    })
    // Preserve bridge modules that are not present in the legacy build-time
    // dependency set (notably Vulkan) without copying the full old core.
    from(zipTree(file("$buildDir/bridge-input/lwjgl-glfw-bridge.jar")).matching {
        include("org/lwjgl/vulkan/**")
    })
    manifest {
        attributes("Manifest-Version" to "3.3.6")
        attributes("Automatic-Module-Name" to "org.lwjgl.bridge")
    }
}

tasks.named("assemble") {
    dependsOn("bridgeJar")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
