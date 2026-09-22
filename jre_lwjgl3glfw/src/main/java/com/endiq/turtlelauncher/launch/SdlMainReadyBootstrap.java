package com.endiq.turtlelauncher.launch;

import java.lang.reflect.Method;

/**
 * Calls SDL_SetMainReady before handing control to Minecraft's real main
 * method. Minecraft 26.x invokes SDL as a library from a normal JVM rather
 * than through SDL_main.
 */
public final class SdlMainReadyBootstrap {
    private SdlMainReadyBootstrap() {}

    public static void main(String[] args) throws Exception {
        String realMainClass = System.getProperty("turtlelauncher.realMainClass");
        if (realMainClass == null || realMainClass.isEmpty()) {
            throw new IllegalStateException("turtlelauncher.realMainClass was not set");
        }

        // Loading GLFW first loads libpojavexec in the guest JVM and installs
        // the dlopen hook. It must not touch SDL3 here: SDL3 was initialized
        // on the Android/ART side and must remain bound to that VM.
        try {
            Class.forName("org.lwjgl.glfw.GLFW", true,
                    SdlMainReadyBootstrap.class.getClassLoader());
            System.out.println("TurtleSDL3: LWJGL dlopen hook installed");
        } catch (Throwable t) {
            System.out.println("TurtleSDL3: LWJGL dlopen hook unavailable: " + t);
        }

        try {
            Class<?> sdlMain = Class.forName("org.lwjgl.sdl.SDLMain");
            Method setMainReady = sdlMain.getMethod("SDL_SetMainReady");
            setMainReady.invoke(null);
        } catch (Throwable t) {
            // Keep Minecraft's own SDL error visible if this optional entry
            // point is absent in a future LWJGL build.
        }

        Class<?> realMain = Class.forName(realMainClass);
        Method main = realMain.getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }
}
