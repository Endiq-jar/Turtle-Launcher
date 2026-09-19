cmake_minimum_required(VERSION 3.22.1)

project("androidnsbypass")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(ENABLE_TESTS "Enable tests at runtime. Exits the process if any of the functions are gonna be non-functional." OFF)

if(ENABLE_TESTS)
    add_compile_definitions(ENABLE_TESTS)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        jni.c
        elf_soname_patcher.c
        nsbypass.c
        nsbypass_dlfcn.c
        utils.c
        android_linker_ns.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME}
        log
)

target_include_directories(${CMAKE_PROJECT_NAME} PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)