# Another Linker Namespace Bypass Library
This library serves as an alternative to [bylaws/liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) because I hate
C++ and I wanted to have more platform support. 

This is built to be an easy replacement so there is a
provided [liblinkernsbypass_compat/android_linker_ns.h](./src/main/cpp/include/liblinkernsbypass_compat/android_linker_ns.h)
header that provides the same API.  

[`elf_soname_patcher.h`](src/main/cpp/elf_soname_patcher.h) is NOT exposed.   

## Implementation
The implementation was derived from [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) and [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher/blob/v3_openjdk/app_pojavlauncher/src/main/jni/driver_helper/nsbypass.c)
as well as my own research into the subject.  
Everything is fully commented, if anything is unclear,
raise an issue and I'll improve the comments however I can.

## Features
[`NativeLib.java`](src/main/java/dev/alexytomi/androidnsbypass/NativeLib.java) provides a simple JNI layer for [`nsbypass.h`](./src/main/cpp/include/androidnsbypass/nsbypass.h)  
[`androidnsbypass/nsbypass.h`](./src/main/cpp/include/androidnsbypass/nsbypass.h) provides the convenience functions you were looking for    
[`androidnsbypass/nsbypass_t.h`](./src/main/cpp/include/androidnsbypass/nsbypass_t.h) provides typedefs for the private API functions being used    
[`fasthook/nsbypass_dlfcn.h`](./src/main/cpp/include/fasthook/nsbypass_dlfcn.h) provides memory scanning functions for ELF files (this is a fork of [turing-technician/Enhanced_dlfunctions](https://github.com/turing-technician/Enhanced_dlfunctions))    
[`liblinkernsbypass_compat/android_linker_ns.h`](./src/main/cpp/include/liblinkernsbypass_compat/android_linker_ns.h) is exactly the same as the one in [bylaws/liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass)


## How to include

Either use the AAR you can build by running `./gradlew assembleRelease` or include this repository 
as a submodule and add it as a dependency in your build.gradle.

As for using the C API, prefabs have integration with CMake and Android.mk (ndk-build).  
See https://google.github.io/prefab/build-systems.html  


## Support
Android 7+  

## Licensing
[MIT License](./LICENSE) except for one file.

[`android_linker_ns.h`](./src/main/cpp/include/liblinkernsbypass_compat/android_linker_ns.h)
is taken directly from [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass) and as such
is licensed under [BSD-2-Clause](./LICENSE-BSD-2-Clause)

