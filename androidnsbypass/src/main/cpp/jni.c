// SPDX-License-Identifier: MIT
// Copyright (c) 2026 alexytomi

#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <dlfcn.h>

#include "androidnsbypass/nsbypass.h"

#define CALLER_DEFAULT_NAMESPACE (0)
#define CALLER_RETURN_ADDRESS (-1)
#define CALLER_CLASSLOADER_NAMESPACE (-2)

// TODO: Guard against random NULLs because GetStringUTFChars is nullable

const void *getCaller(jlong caller_addr);

JNIEXPORT const void *getCaller(jlong caller_addr) {
    const void *caller;
    if (caller_addr == CALLER_DEFAULT_NAMESPACE)
        caller = (const void *)&dlopen;
    else if (caller_addr == CALLER_RETURN_ADDRESS)
        caller = __builtin_return_address(0);
    else if (caller_addr == CALLER_CLASSLOADER_NAMESPACE)
        caller = private_create_namespace;
    else
        caller = (const void *)(uintptr_t)caller_addr;
    return caller;
}

jlong JNICALL
Java_dev_alexytomi_androidnsbypass_NativeLib_createNamespace(
        JNIEnv *env,
        jclass clazz,
        jstring name,
        jstring ld_library_path,
        jstring default_library_path,
        jlong type,
        jstring permitted_when_isolated_path,
        jlong parent_namespace,
        jlong caller_addr)
{
    const char *name_c = NULL;
    const char *ld_path_c = NULL;
    const char *default_path_c = NULL;
    const char *permitted_path_c = NULL;

    if (name)
        name_c = (*env)->GetStringUTFChars(env, name, NULL);

    if (ld_library_path)
        ld_path_c = (*env)->GetStringUTFChars(
                env, ld_library_path, NULL);

    if (default_library_path)
        default_path_c = (*env)->GetStringUTFChars(
                env, default_library_path, NULL);

    if (permitted_when_isolated_path)
        permitted_path_c = (*env)->GetStringUTFChars(
                env,
                permitted_when_isolated_path,
                NULL);

    const void *caller;

    caller = getCaller(caller_addr);

    struct android_namespace_t *result =
            private_create_namespace(
                    name_c,
                    ld_path_c,
                    default_path_c,
                    (uint64_t)type,
                    permitted_path_c,
                    (struct android_namespace_t *)(uintptr_t)
                            parent_namespace,
                    caller);

    if (name_c)
        (*env)->ReleaseStringUTFChars(env, name, name_c);

    if (ld_path_c)
        (*env)->ReleaseStringUTFChars(
                env,
                ld_library_path,
                ld_path_c);

    if (default_path_c)
        (*env)->ReleaseStringUTFChars(
                env,
                default_library_path,
                default_path_c);

    if (permitted_path_c)
        (*env)->ReleaseStringUTFChars(
                env,
                permitted_when_isolated_path,
                permitted_path_c);

    return (jlong)(uintptr_t)result;
}


JNIEXPORT jboolean JNICALL
Java_dev_alexytomi_androidnsbypass_NativeLib_linkNamespaces(
        JNIEnv *env,
        jclass clazz,
        jlong from,
        jlong to,
        jstring shared_libs_sonames)
{
    const char *libs = NULL;

    if (shared_libs_sonames)
        libs = (*env)->GetStringUTFChars(
                env,
                shared_libs_sonames,
                NULL);

    bool result = private_link_namespaces(
            (struct android_namespace_t *)(uintptr_t)from,
            (struct android_namespace_t *)(uintptr_t)to,
            libs);

    if (libs)
        (*env)->ReleaseStringUTFChars(
                env,
                shared_libs_sonames,
                libs);

    return result ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jboolean JNICALL
Java_dev_alexytomi_androidnsbypass_NativeLib_linkNamespacesAllLibs(
        JNIEnv *env,
        jclass clazz,
        jlong from,
        jlong to)
{
    bool result = private_link_namespaces_all_libs(
            (struct android_namespace_t *)(uintptr_t)from,
            (struct android_namespace_t *)(uintptr_t)to);

    return result ? JNI_TRUE : JNI_FALSE;
}


JNIEXPORT jlong JNICALL
Java_dev_alexytomi_androidnsbypass_NativeLib_getExportedNamespace(
        JNIEnv *env,
        jclass clazz,
        jstring name)
{
    const char *name_c = NULL;

    if (name)
        name_c = (*env)->GetStringUTFChars(
                env,
                name,
                NULL);

    struct android_namespace_t *result =
            private_get_exported_namespace(name_c);

    if (name_c)
        (*env)->ReleaseStringUTFChars(
                env,
                name,
                name_c);

    return (jlong)(uintptr_t)result;
}


JNIEXPORT jint JNICALL
Java_dev_alexytomi_androidnsbypass_NativeLib_dlclose(
        JNIEnv *env,
        jclass clazz,
        jlong handle)
{
    return private_dlclose(
            (void *)(uintptr_t)handle);
}


JNIEXPORT jlong JNICALL
Java_dev_alexytomi_androidnsbypass_NativeLib_dlopen(
        JNIEnv *env,
        jclass clazz,
        jstring filename,
        jint flags,
        jlong caller_addr)
{
    const char *filename_c = NULL;

    if (filename)
        filename_c = (*env)->GetStringUTFChars(
                env,
                filename,
                NULL);

    const void *caller;

    caller = getCaller(caller_addr);

    void *result = private_dlopen(
            filename_c,
            flags,
            caller);

    if (filename_c)
        (*env)->ReleaseStringUTFChars(
                env,
                filename,
                filename_c);

    return (jlong)(uintptr_t)result;
}


JNIEXPORT jlong JNICALL
Java_dev_alexytomi_androidnsbypass_NativeLib_dlopenExt(
        JNIEnv *env,
        jclass clazz,
        jstring filename,
        jint flags,
        jlong ext_info,
        jlong caller_addr)
{
    const char *filename_c = NULL;

    if (filename)
        filename_c = (*env)->GetStringUTFChars(
                env,
                filename,
                NULL);

    const void *caller;

    caller = getCaller(caller_addr);

    void *result = private_dlopen_ext(
            filename_c,
            flags,
            (const android_dlextinfo *)(uintptr_t)ext_info,
            caller);

    if (filename_c)
        (*env)->ReleaseStringUTFChars(
                env,
                filename,
                filename_c);

    return (jlong)(uintptr_t)result;
}


JNIEXPORT jlong JNICALL
Java_dev_alexytomi_androidnsbypass_NativeLib_dlsym(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jstring symbol,
        jlong caller_addr)
{
    const char *symbol_c = NULL;

    if (symbol)
        symbol_c = (*env)->GetStringUTFChars(
                env,
                symbol,
                NULL);

    const void *caller;

    caller = getCaller(caller_addr);

    void *result = private_dlsym(
            (void *)(uintptr_t)handle,
            symbol_c,
            caller);

    if (symbol_c)
        (*env)->ReleaseStringUTFChars(
                env,
                symbol,
                symbol_c);

    return (jlong)(uintptr_t)result;
}