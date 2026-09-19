package dev.alexytomi.androidnsbypass;


/***
 * Extremely unsafe and thin JNI bridge in case you need it.
 ***/
@SuppressWarnings("unused")
public class NativeLib {

    static {
        System.loadLibrary("androidnsbypass");
    }

    public enum Namespace {
        DEFAULT(0),
        RETURN_ADDRESS(-1),
        CLASSLOADER(-2);
        private final long value;
        Namespace(long value) {
            this.value = value;
        }
        public long getValue() {
            return value;
        }
    }

    public static native long createNamespace(
            String name,
            String ldLibraryPath,
            String defaultLibraryPath,
            long type,
            String permittedWhenIsolatedPath,
            long parentNamespace,
            long callerAddr);

    public static native boolean linkNamespaces(
            long from,
            long to,
            String sharedLibsSonames);

    public static native boolean linkNamespacesAllLibs(
            long from,
            long to);

    public static native long getExportedNamespace(
            String name);

    public static native int dlclose(long handle);

    public static native long dlopen(
            String filename,
            int flags,
            long callerAddr);

    public static native long dlopenExt(
            String filename,
            int flags,
            long extInfo,
            long callerAddr);

    public static native long dlsym(
            long handle,
            String symbol,
            long callerAddr);
}
