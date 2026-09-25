cmake_minimum_required(VERSION 3.22.1)
project("turtlejvm")

# ── bytehook (AAR에서 제공하는 CMake config) ───────────────────
find_package(bytehook REQUIRED CONFIG)

# ── libadrenotools (사용자가 제공한 커스텀 Vulkan 드라이버(Turnip 등)를
#    루트 없이 로드하기 위한 서드파티 라이브러리, BSD-2-Clause) ──────
# The upstream helper is arm64-only. Keep the target name on legacy ABIs as
# an empty interface so the rest of the launcher JNI still builds and the
# bundled legacy renderer/native matrix can be packaged for those devices.
if (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    add_subdirectory(libadrenotools)
else()
    add_library(adrenotools INTERFACE)
endif()

# ── 시스템 라이브러리 ─────────────────────────────────────────
find_library(log-lib log)
find_library(EGL-lib EGL)
find_library(GLESv2-lib GLESv2)
find_library(android-lib android)
find_library(dl-lib dl)
find_library(aaudio-lib aaudio)

# ── turtlejvm (JVM 부팅) ────────────────────────────────────────
add_library(turtlejvm SHARED turtlejvm.cpp)
target_include_directories(turtlejvm PRIVATE
        pojav_jni
        pojav_jni/environ
        pojav_jni/egl_bridge
)
target_link_libraries(turtlejvm ${log-lib} vulkan)

# ── turtlesdl (26.3 · SDL3 입력 주입) ───────────────────────────
# 화면 버튼·게임패드를 SDL 이벤트로 넣는다. dlopen/dlsym 으로만 부르므로
# libSDL3.so 를 링크하지도, SDL3 헤더를 들여오지도 않는다.
add_library(turtlesdl SHARED turtle_sdl.c)
target_link_libraries(turtlesdl ${log-lib} ${dl-lib})


# ── PojavLauncher core 소스 (awt_bridge.c 제외) ───────────────
set(POJAV_SOURCES
        pojav_jni/egl_bridge.c
        pojav_jni/utils.c
        pojav_jni/stdio_is.c
        pojav_jni/bigcoreaffinity.c
        pojav_jni/input_bridge_v3.c
        pojav_jni/environ/environ.c
        pojav_jni/ctxbridges/gl_bridge.c
        pojav_jni/ctxbridges/egl_loader.c
        pojav_jni/ctxbridges/osm_bridge.c
        pojav_jni/ctxbridges/osmesa_loader.c
        pojav_jni/ctxbridges/loader_dlopen.c
        pojav_jni/ctxbridges/swap_interval_no_egl.c
        pojav_jni/driver_helper/nsbypass.c
        pojav_jni/driver_helper/hook.c
        pojav_jni/jvm_hooks/lwjgl_dlopen_hook.c
        pojav_jni/jvm_hooks/java_exec_hooks.c
        pojav_jni/jvm_hooks/emui_iterator_fix_hook.c
        pojav_jni/native_hooks/exit_hook.c
        pojav_jni/native_hooks/chmod_hook.c
        pojav_jni/native_hooks/resolvconf_hook.c
        pojav_jni/native_hooks/resolver_hook.c
        pojav_jni/jre_launcher.c
        pojav_jni/mic_bridge.c
)

set(POJAV_INCLUDES
        pojav_jni
        pojav_jni/GL
        pojav_jni/ctxbridges
        pojav_jni/environ
        pojav_jni/driver_helper
        pojav_jni/jvm_hooks
        pojav_jni/native_hooks
        pojav_jni/awt_xawt
)

set(POJAV_WARN_FLAGS
        -Wno-implicit-function-declaration
        -Wno-incompatible-pointer-types
        -Wno-int-conversion
        -Wno-deprecated-declarations
        -Wno-unused-parameter
)

# ── libglfw.so ────────────────────────────────────────────────
add_library(glfw SHARED ${POJAV_SOURCES})
target_include_directories(glfw PRIVATE ${POJAV_INCLUDES})
target_compile_options(glfw PRIVATE ${POJAV_WARN_FLAGS})
target_link_libraries(glfw
        ${log-lib}
        ${EGL-lib}
        ${GLESv2-lib}
        ${android-lib}
        ${dl-lib}
        ${aaudio-lib}
        adrenotools
        bytehook::bytehook
)

# ── libpojavexec.so ──────────────────────────────────────────
add_library(pojavexec SHARED ${POJAV_SOURCES})
target_include_directories(pojavexec PRIVATE ${POJAV_INCLUDES})
target_compile_options(pojavexec PRIVATE ${POJAV_WARN_FLAGS})
target_link_libraries(pojavexec
        ${log-lib}
        ${EGL-lib}
        ${GLESv2-lib}
        ${android-lib}
        ${dl-lib}
        ${aaudio-lib}
        adrenotools
        vulkan
        bytehook::bytehook
)

# ── libawt_xawt.so ────────────────────────────────────────────
# JRE 의 libfontmanager.so 가 SONAME 으로 libawt_xawt.so 를 찾고
# 그 안에서 X11FontScaler 심볼 (AWTFreeChar 등 17개) 을 resolve 하려고 한다.
# JRE 자체의 libawt_xawt.so 에는 이 심볼이 없으므로,
# xawt_fake.c 의 노옵 스텁을 담은 우리 버전을 APK 에 같이 패키징해서
# JRE 버전보다 먼저 로드하면 SONAME 중복으로 우리 것이 우선된다.
add_library(awt_xawt SHARED
        pojav_jni/awt_xawt/xawt_fake.c
)
target_include_directories(awt_xawt PRIVATE ${POJAV_INCLUDES})
target_compile_options(awt_xawt PRIVATE ${POJAV_WARN_FLAGS})
target_link_libraries(awt_xawt
        ${log-lib}
        ${android-lib}
        ${dl-lib}
)

# ── libpojavexec_awt.so (AWT/Cacio 브릿지 분리) ───────────────
# awt_bridge.c 의 JNI_OnLoad 가 input_bridge_v3.c 의 것과 충돌하므로
# PojavLauncher 본가처럼 별도 .so 로 분리한다.
# MinecraftActivity 가 jre lib 디렉토리에 이 이름으로 복사하고 있음.
add_library(pojavexec_awt SHARED
        pojav_jni/awt_bridge.c
)
target_include_directories(pojavexec_awt PRIVATE ${POJAV_INCLUDES})
target_compile_options(pojavexec_awt PRIVATE ${POJAV_WARN_FLAGS})
target_link_libraries(pojavexec_awt
        ${log-lib}
        ${android-lib}
        ${dl-lib}
)