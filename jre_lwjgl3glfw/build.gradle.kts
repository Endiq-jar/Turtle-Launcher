plugins {
    java
}

group = "org.lwjgl.glfw"

// Gradle 9 hard-locks the built-in "default" configuration as Consumable-only at
// creation - you can no longer flip isCanBeResolved on it (this used to work as a
// soft-enforced warning in older Gradle, now it's a hard failure). runtimeClasspath
// is the proper resolvable configuration that already contains everything pulled in
// via `implementation` below, so we walk that instead; behavior is identical.

tasks.jar {
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
    exclude("net/java/openjdk/cacio/ctc/**")
    manifest {
        attributes("Manifest-Version" to "3.3.6")
        attributes("Automatic-Module-Name" to "org.lwjgl")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
