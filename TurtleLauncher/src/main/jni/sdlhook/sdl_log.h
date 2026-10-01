#ifndef TURTLE_SDL_LOG_H
#define TURTLE_SDL_LOG_H

// Logging for the sdlhook module. Unlike upstream (which routes everything
// through Zalith's file logger), this standalone module logs to logcat only.

#include <android/log.h>

#define SDLHOOK_LOG_TAG "TurtleSdlHook"

#define LOG_TO_E(...) __android_log_print(ANDROID_LOG_ERROR, SDLHOOK_LOG_TAG, __VA_ARGS__)
#define LOG_TO_W(...) __android_log_print(ANDROID_LOG_WARN, SDLHOOK_LOG_TAG, __VA_ARGS__)
#define LOG_TO_I(...) __android_log_print(ANDROID_LOG_INFO, SDLHOOK_LOG_TAG, __VA_ARGS__)
#define LOG_TO_D(...) __android_log_print(ANDROID_LOG_DEBUG, SDLHOOK_LOG_TAG, __VA_ARGS__)

#endif // TURTLE_SDL_LOG_H
