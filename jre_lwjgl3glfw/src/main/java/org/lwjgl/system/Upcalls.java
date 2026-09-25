package org.lwjgl.system;

import java.lang.reflect.Method;

/**
 * Small ABI adapter for the Android LWJGL native library.  The bundled
 * liblwjgl exposes its callback-handler entry point under the newer Upcalls
 * JNI name, while Minecraft's version-specific callback classes still use the
 * older Callback implementation.
 */
public final class Upcalls {
    private Upcalls() {}

    public static native long getCallbackHandler(Method callback);
}
