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

tasks.jar {
    dependsOn(bridgePayloadSnapshot)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("lwjgl-glfw-classes")
    destinationDirectory.set(file("../TurtleLauncher/src/main/assets/components/lwjgl3/"))
    // Auto update the version with a timestamp so the project jar gets updated by Turtle
    doLast {
        val versionFile = file("../TurtleLauncher/src/main/assets/components/lwjgl3/version")
        versionFile.writeText(System.currentTimeMillis().toString())
    }
    from({
        configurations.getByName("runtimeClasspath").map {
            println(it.name)
            if (it.isDirectory) it else zipTree(it)
        }
    })
    // The checked-in bridge carries the released Vulkan/support payload and
    // Callback.Descriptor ABI shim. Include it in the full legacy jar before
    // patchCallbackDescriptor runs; otherwise the patch task has no descriptor
    // class to update and bridgeJar cannot be regenerated from source.
    from(zipTree(file("$buildDir/bridge-input/lwjgl-glfw-bridge.jar")))
    exclude("net/java/openjdk/cacio/ctc/**")
    manifest {
        attributes("Manifest-Version" to "3.3.6")
        attributes("Automatic-Module-Name" to "org.lwjgl")
    }
}

// Minecraft 26.x supplies its own version-specific LWJGL core and SDL jars.  A
// second payload keeps the Android GLFW/Vulkan bridge classes without copying
// org.lwjgl.system (or another LWJGL core) into the SDL class path.  Legacy
// versions continue to use lwjgl-glfw-classes.jar above.
val patchCallbackDescriptor = tasks.register<Exec>("patchCallbackDescriptor") {
    dependsOn(tasks.jar)
    commandLine(
        "python3",
        file("patch_callback_descriptor.py").absolutePath,
        file("../TurtleLauncher/src/main/assets/components/lwjgl3/lwjgl-glfw-classes.jar").absolutePath
    )
}

tasks.register<Jar>("bridgeJar") {
    dependsOn(tasks.classes)
    dependsOn(patchCallbackDescriptor)
    dependsOn(bridgePayloadSnapshot)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("lwjgl-glfw-bridge")
    destinationDirectory.set(file("../TurtleLauncher/src/main/assets/components/lwjgl3/"))

    from(sourceSets.main.get().output) {
        include("android/**")
        include("net/java/openjdk/**")
        include("org/lwjgl/glfw/**")
        include("org/lwjgl/input/InfdevMouse.class")
        include("org/lwjgl/opengl/PojavRendererInit.class")
        include("org/lwjgl/system/Callback\$Descriptor.class")
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
    from({
        configurations.getByName("runtimeClasspath").map {
            zipTree(it).matching {
                // The source set above contains the Android GLFW replacement.
                // Do not let the desktop LWJGL GLFW classes overwrite it: the
                // desktop class tries to load libglfw.so, while Android exposes
                // the hook through libpojavexec.so.
                include("org/lwjgl/glfw/**")
                exclude("org/lwjgl/glfw/CallbackBridge.class")
                exclude("org/lwjgl/glfw/Callbacks.class")
                exclude("org/lwjgl/glfw/GLFW.class")
                exclude("org/lwjgl/glfw/GLFWImage.class")
                exclude("org/lwjgl/glfw/GLFWNativeCocoa.class")
                exclude("org/lwjgl/glfw/GLFWNativeEGL.class")
                exclude("org/lwjgl/glfw/GLFWNativeNSGL.class")
                exclude("org/lwjgl/glfw/GLFWNativeOSMesa.class")
                exclude("org/lwjgl/glfw/GLFWNativeWGL.class")
                exclude("org/lwjgl/glfw/GLFWNativeWayland.class")
                exclude("org/lwjgl/glfw/GLFWNativeWin32.class")
                exclude("org/lwjgl/glfw/GLFWNativeX11.class")
                exclude("org/lwjgl/glfw/GLFWWindowProperties.class")
                include("org/lwjgl/vulkan/**")
            }
        }
    })
    // The checked-in full payload is patched before this task runs. Keep only
    // its nested Descriptor ABI shim; never copy Callback.class or the core.
    from(zipTree(file("../TurtleLauncher/src/main/assets/components/lwjgl3/lwjgl-glfw-classes.jar")).matching {
        include("org/lwjgl/system/Callback\$Descriptor.class")
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
