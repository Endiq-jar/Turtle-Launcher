/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.system;

import org.lwjgl.*;
import org.lwjgl.system.libffi.*;

import javax.annotation.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

import static org.lwjgl.system.APIUtil.*;
import static org.lwjgl.system.Checks.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.jni.JNINativeInterface.*;
import static org.lwjgl.system.libffi.LibFFI.*;

/**
 * LWJGL callback base with an ABI-compatible Android handler lookup.
 *
 * The Minecraft 26.3 Java libraries use the pre-Upcalls Callback JNI name,
 * while the arm64 Android liblwjgl exposes the same handler as
 * org.lwjgl.system.Upcalls.getCallbackHandler. Try the newer entry point
 * first, then retain the legacy entry point used by the other native ABIs and
 * the legacy launcher payload.
 */
public abstract class Callback implements Pointer, NativeResource {

    private static final boolean DEBUG_ALLOCATOR = Configuration.DEBUG_MEMORY_ALLOCATOR.get(false);

    private static final ClosureRegistry CLOSURE_REGISTRY;

    private interface ClosureRegistry {
        void put(long executableAddress, FFIClosure closure);
        FFIClosure get(long executableAddress);
        FFIClosure remove(long executableAddress);
    }

    static {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer code = stack.mallocPointer(1);

            FFIClosure closure = ffi_closure_alloc(FFIClosure.SIZEOF, code);
            if (closure == null) {
                throw new OutOfMemoryError();
            }

            if (code.get(0) == closure.address()) {
                apiLog("Closure Registry: simple");
                CLOSURE_REGISTRY = new ClosureRegistry() {
                    @Override
                    public void put(long executableAddress, FFIClosure closure) {
                    }

                    @Override
                    public FFIClosure get(long executableAddress) {
                        return FFIClosure.create(executableAddress);
                    }

                    @Override
                    public FFIClosure remove(long executableAddress) {
                        return get(executableAddress);
                    }
                };
            } else {
                apiLog("Closure Registry: ConcurrentHashMap");
                CLOSURE_REGISTRY = new ClosureRegistry() {
                    private final ConcurrentHashMap<Long, FFIClosure> map = new ConcurrentHashMap<>();

                    @Override
                    public void put(long executableAddress, FFIClosure closure) {
                        map.put(executableAddress, closure);
                    }

                    @Override
                    public FFIClosure get(long executableAddress) {
                        return map.get(executableAddress);
                    }

                    @Override
                    public FFIClosure remove(long executableAddress) {
                        return map.remove(executableAddress);
                    }
                };
            }
            ffi_closure_free(closure);
        }
    }

    private static final long CALLBACK_HANDLER;

    /** Kept for the native libraries that still export the original JNI name. */
    private static native long getCallbackHandler(Method callback);

    static {
        try {
            Method callback = CallbackI.class.getDeclaredMethod("callback", long.class, long.class);
            long handler;
            try {
                // The current arm64 payload exports this name.
                handler = Upcalls.getCallbackHandler(callback);
            } catch (UnsatisfiedLinkError newerAbiUnavailable) {
                // armeabi-v7a, x86 and x86_64 retain the released LWJGL name.
                handler = getCallbackHandler(callback);
            }
            CALLBACK_HANDLER = handler;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize the native callback handler.", e);
        }

        MemoryUtil.getAllocator();
    }

    private long address;

    protected Callback(FFICIF cif) {
        this.address = create(cif, this);
    }

    protected Callback(long address) {
        if (CHECKS) {
            check(address);
        }
        this.address = address;
    }

    @Override
    public long address() {
        return address;
    }

    @Override
    public void free() {
        free(address());
    }

    static long create(FFICIF cif, Object instance) {
        FFIClosure closure;
        long executableAddress;
        try (MemoryStack stack = stackPush()) {
            PointerBuffer code = stack.mallocPointer(1);

            closure = ffi_closure_alloc(FFIClosure.SIZEOF, code);
            if (closure == null) {
                throw new OutOfMemoryError();
            }
            executableAddress = code.get(0);
            if (DEBUG_ALLOCATOR) {
                MemoryManage.DebugAllocator.track(executableAddress, FFIClosure.SIZEOF);
            }
        }

        long user_data = NewGlobalRef(instance);
        int errcode = ffi_prep_closure_loc(
            closure, cif, CALLBACK_HANDLER, user_data, executableAddress);
        if (errcode != FFI_OK) {
            DeleteGlobalRef(user_data);
            ffi_closure_free(closure);
            throw new RuntimeException("Failed to prepare the libffi closure");
        }

        CLOSURE_REGISTRY.put(executableAddress, closure);
        return executableAddress;
    }

    public static <T extends CallbackI> T get(long functionPointer) {
        return memGlobalRefToObject(CLOSURE_REGISTRY.get(functionPointer).user_data());
    }

    @Nullable
    public static <T extends CallbackI> T getSafe(long functionPointer) {
        return functionPointer == NULL ? null : get(functionPointer);
    }

    public static void free(long functionPointer) {
        if (DEBUG_ALLOCATOR) {
            MemoryManage.DebugAllocator.untrack(functionPointer);
        }

        FFIClosure closure = CLOSURE_REGISTRY.get(functionPointer);
        DeleteGlobalRef(closure.user_data());
        ffi_closure_free(closure);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Callback)) {
            return false;
        }
        Callback that = (Callback)o;
        return address == that.address();
    }

    @Override
    public int hashCode() {
        return (int)(address ^ (address >>> 32));
    }

    @Override
    public String toString() {
        return String.format("%s pointer [0x%X]", getClass().getSimpleName(), address);
    }
}
