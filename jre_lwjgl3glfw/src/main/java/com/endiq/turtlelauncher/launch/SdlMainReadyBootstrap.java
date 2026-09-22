package com.endiq.turtlelauncher.launch;

import java.lang.reflect.Method;

/**
 * TurtleLauncher CRASH FIX (MC 26.3+): Minecraft's own bootstrap calls
 * org.lwjgl.sdl.SDL's SDL_Init() directly as a library call from inside a
 * normal JVM - never through SDL's own hijacked main() entry point. SDL's
 * init path specifically checks for this and refuses to initialize
 * otherwise, which surfaced as:
 *   IllegalStateException: Unable to initialize SDL: Application didn't
 *   initialize properly, did you include SDL_main.h in the file containing
 *   your main() function?
 *
 * SDL_SetMainReady() is SDL's own documented function for exactly this case
 * (embedding SDL as a library instead of owning main()). We can't call it
 * from inside Minecraft's own main() - that's Mojang's compiled code, not
 * ours - so this class becomes the actual JVM entry point instead for SDL
 * versions only (see LaunchArgs.kt): it calls SDL_SetMainReady() first, then
 * reflectively invokes the real main class Minecraft was supposed to run.
 *
 * IMPORTANT: this class must be plain Java, compiled into the launcher
 * LWJGL bridge payload (lwjgl-glfw-bridge.jar; the full legacy payload is
 * kept for pre-SDL versions; see getLWJGL3ClassPath(versionInfo) in Tools.java), NOT part
 * of the Android app's own Kotlin/dex source. Minecraft runs in a separate,
 * real embedded-JRE subprocess with its own classpath - the Android app's
 * own compiled classes are never on that classpath and can't be referenced
 * from it. An earlier attempt at this fix put it in the app's own Kotlin
 * source instead, which produced:
 *   Error: Could not find or load main class
 *     com.endiq.turtlelauncher.launch.SdlMainReadyBootstrap
 * because that class simply didn't exist anywhere on the game process's
 * classpath.
 *
 * Reflection throughout because org.lwjgl.sdl.SDLMain isn't a compile-time
 * dependency available here - it's one of Mojang's own downloaded
 * libraries, resolved only at runtime from the version-specific classpath.
 *
 * ── Sept 2026 verification note ──
 * The source and compiled class in the bridge payload are kept together:
 * assets/components/lwjgl3/lwjgl-glfw-bridge.jar contains
 * com/endiq/turtlelauncher/launch/SdlMainReadyBootstrap.class (Java 8
 * bytecode), Components.kt unpacks the lwjgl3 component into
 * <gameHome>/lwjgl3/, Tools.getLWJGL3ClassPath(versionInfo) puts the bridge
 * jar first on the SDL game -cp, and LaunchArgs routes every NEW_SDL launch's
 * main class through here. The bridge's Callback/Descriptor ABI shim is also
 * selected before Minecraft's version-specific core, so a hook linkage failure
 * now aborts before Minecraft can load SDL3 through an uninitialized guest-VM
 * instance.
 *
 * DELIBERATELY does NOT load SDL3 itself, even though that would seem to
 * "pre-initialize" it: loading libSDL3.so from THIS (guest) JVM - by
 * System.loadLibrary("SDL3") or by touching org.lwjgl.sdl.SDL early - runs
 * SDL's JNI_OnLoad, which binds SDL's mJavaVM to the GUEST VM and overwrites
 * the ART-side binding SdlAndroidJniPrep carefully set up beforehand. SDL's
 * Android backend would then call Java through the guest JNIEnv using
 * jclass/jmethodID globals created against the ART VM - cross-VM jobject
 * misuse, i.e. a different, less predictable crash than the one it tries to
 * prevent. The launcher-side fix for the SDL3 two-instance crash is
 * therefore done entirely on the ART side + JVM args instead:
 * -Dorg.lwjgl.sdl.libname pinning and the natives-cache purge (see
 * SdlAndroidJniPrep.ensureSingleSdl3Source and its class doc). This class's
 * job stays exactly one thing: call SDL_SetMainReady() before Mojang's main.
 */
public class SdlMainReadyBootstrap {
    public static void main(String[] args) throws Exception {
        String realMainClass = System.getProperty("turtlelauncher.realMainClass");
        if (realMainClass == null) {
            throw new IllegalStateException(
                "turtlelauncher.realMainClass system property was not set - " +
                "LaunchArgs should always set this alongside routing through this bootstrap class"
            );
        }

        // DroidBridge's GLFW bridge installs the LWJGL ndlopen hook from its
        // static initializer. SDL 3.2 moved Minecraft's window creation from
        // GLFW into SDL, so SDL launches otherwise never load libpojavexec in
        // the guest JVM and the game can receive a second, uninitialized SDL
        // linker instance. This only touches GLFW; it deliberately does not
        // load SDL3 from the guest VM.
        try {
            Class.forName("org.lwjgl.glfw.GLFW", true, SdlMainReadyBootstrap.class.getClassLoader());
            System.out.println("TurtleSDL3: guest libpojavexec loaded, LWJGL dlopen hook installed");
        } catch (Throwable t) {
            // Continuing here lets LWJGL load SDL3 through a second guest-VM
            // linker path. That instance has no ART-side SDL JNI state and is
            // exactly the failure this bootstrap is meant to prevent. Fail
            // before Minecraft's main class can create SDL instead of turning
            // the mismatch into a native crash later.
            String detail = t.getClass().getName() +
                (t.getMessage() == null ? "" : ": " + t.getMessage());
            throw new IllegalStateException(
                "TurtleSDL3: refusing launch: hook installation failed: " + detail, t);
        }

        try {
            Class<?> sdlInit = Class.forName("org.lwjgl.sdl.SDLMain");
            Method setMainReady = sdlInit.getMethod("SDL_SetMainReady");
            setMainReady.invoke(null);
        } catch (Throwable t) {
            // Best effort. If the class/method genuinely isn't present, fall
            // through and let the real main class's own SDL_Init call fail
            // with its own error instead of masking it with a reflection
            // failure here - that keeps the crash log meaningful either way.
        }

        Class<?> realMain = Class.forName(realMainClass);
        Method mainMethod = realMain.getMethod("main", String[].class);
        mainMethod.invoke(null, (Object) args);
    }
}
