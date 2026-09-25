/*
 * mic_bridge.c — AAudio 기반 마이크 캡처를 게임 JVM 에 노출하는 JNI 브릿지.
 *
 * 배경: 게임의 JVM(모드가 도는 곳)은 안드로이드 앱과 "같은 프로세스" 안에서
 * JNI_CreateJavaVM 으로 임베드되지만, 앱의 ART 런타임과는 "별개의 JVM 인스턴스"다.
 * 즉 게임 JVM 쪽 자바 코드(예: Simple Voice Chat 같은 모드가 쓰는
 * javax.sound.sampled)는 앱의 Kotlin/Java(AudioRecord 등)를 직접 호출할 수 없다.
 *
 * 그래서 마이크 캡처 자체를 "네이티브(C) 코드"로 직접 구현한다 — 안드로이드
 * NDK 의 AAudio API 는 앱의 자바 레이어를 거치지 않고 네이티브에서 곧바로
 * 마이크에 접근할 수 있어서, 이 프로세스 안 어디서든(게임 JVM 이 로드한
 * libglfw.so/libpojavexec.so 안에서도) 호출 가능하다.
 *
 * 자바 쪽(게임 JVM 안에서 도는, 클래스패스에 추가되는 순수 자바 브릿지 jar)은
 * kr.co.donghyun.turtlelauncher.audio.TurtleMicNative 클래스의 native 메서드를
 * 통해 이 함수들을 호출하고, javax.sound.sampled.TargetDataLine 구현체로
 * 감싸서 모드가 표준 자바 오디오 API 로 마이크를 쓸 수 있게 한다.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <aaudio/AAudio.h>

#define TAG __FILE_NAME__
#include "log.h"

// 여러 스레드(모드의 오디오 캡처 스레드)에서 open/read/close 를 부를 수 있어서 보호.
static pthread_mutex_t g_mic_lock = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream *g_mic_stream = NULL;

// ⚠️ 렉의 진짜 원인이었던 부분 — read() 를 호출할 때마다 malloc/free 를 반복하면
//   (음성 캡처는 초당 수십~수백 번 read() 가 호출됨) 힙 할당/해제 오버헤드가
//   누적돼서 눈에 띄는 렉을 유발한다. open() 시점에 한 번만 넉넉하게 잡아두고
//   read() 에서는 재사용만 한다.
static uint8_t *g_scratch_buffer = NULL;
static size_t g_scratch_buffer_size = 0;

JNIEXPORT jboolean JNICALL
Java_kr_co_donghyun_turtlelauncher_audio_TurtleMicNative_nativeOpen(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channelCount) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_mic_lock);

    if (g_mic_stream != NULL) {
        // 이미 열려있으면 성공으로 간주(중복 open 방어).
        pthread_mutex_unlock(&g_mic_lock);
        return JNI_TRUE;
    }

    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == NULL) {
        LOGE("mic_bridge: AAudio_createStreamBuilder 실패 (%d)", result);
        pthread_mutex_unlock(&g_mic_lock);
        return JNI_FALSE;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    // ⚠️ AAudioStreamBuilder_setInputPreset(VOICE_COMMUNICATION) 은 API 28+ 전용이라
    //   이 프로젝트의 minSdk(26)에서는 컴파일이 안 된다 — 없어도 기본 캡처 자체는
    //   정상 동작하니 생략(있으면 노이즈 억제 등이 더 좋아지는 정도의 부가 옵션이었음).

    result = AAudioStreamBuilder_openStream(builder, &g_mic_stream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || g_mic_stream == NULL) {
        LOGE("mic_bridge: openStream 실패 (%d) — RECORD_AUDIO 권한이 없을 수 있음", result);
        g_mic_stream = NULL;
        pthread_mutex_unlock(&g_mic_lock);
        return JNI_FALSE;
    }

    result = AAudioStream_requestStart(g_mic_stream);
    if (result != AAUDIO_OK) {
        LOGE("mic_bridge: requestStart 실패 (%d)", result);
        AAudioStream_close(g_mic_stream);
        g_mic_stream = NULL;
        pthread_mutex_unlock(&g_mic_lock);
        return JNI_FALSE;
    }

    LOGI("mic_bridge: 마이크 스트림 시작됨 (sampleRate=%d, channels=%d)", sampleRate, channelCount);

    // 스크래치 버퍼를 넉넉하게 한 번만 할당(예: 8192 프레임 × 최대 2채널 × 2바이트 = 32KB).
    //   read() 요청 길이가 이보다 크면 그때그때 잘라서 처리한다(아래 nativeRead 참고).
    g_scratch_buffer_size = 8192 * 2 * 2;
    g_scratch_buffer = malloc(g_scratch_buffer_size);
    if (g_scratch_buffer == NULL) {
        LOGE("mic_bridge: 스크래치 버퍼 할당 실패");
        AAudioStream_requestStop(g_mic_stream);
        AAudioStream_close(g_mic_stream);
        g_mic_stream = NULL;
        pthread_mutex_unlock(&g_mic_lock);
        return JNI_FALSE;
    }

    pthread_mutex_unlock(&g_mic_lock);
    return JNI_TRUE;
}

// TargetDataLine.read(byte[] b, int off, int len) 에서 호출 — 캡처된 PCM(16bit) 을 채워서
// 실제로 읽은 "바이트 수"를 돌려준다(실패/미오픈 시 -1).
JNIEXPORT jint JNICALL
Java_kr_co_donghyun_turtlelauncher_audio_TurtleMicNative_nativeRead(
        JNIEnv *env, jclass clazz, jbyteArray buffer, jint offset, jint length) {
    (void) clazz;
    pthread_mutex_lock(&g_mic_lock);
    if (g_mic_stream == NULL || g_scratch_buffer == NULL) {
        pthread_mutex_unlock(&g_mic_lock);
        return -1;
    }

    // AAudio 는 "프레임" 단위로 읽으므로, 16bit(2바이트) x 채널수로 나눠서 프레임 수 계산.
    int32_t channelCount = AAudioStream_getChannelCount(g_mic_stream);
    int32_t bytesPerFrame = 2 * (channelCount > 0 ? channelCount : 1);
    int32_t requestedLength = length;
    // ⚠️ 렉의 진짜 원인 1: 매 호출마다 malloc/free 하던 걸 없앰 — open() 시점에
    //   미리 잡아둔 스크래치 버퍼를 재사용한다. 요청 길이가 그 버퍼보다 크면
    //   (거의 없는 경우지만) 버퍼 크기만큼만 잘라서 읽는다 — 자바 쪽 TargetDataLine
    //   은 반환된 바이트 수만큼만 처리하므로 문제 없다.
    if ((size_t) requestedLength > g_scratch_buffer_size) {
        requestedLength = (jint) g_scratch_buffer_size;
    }
    int32_t numFrames = requestedLength / bytesPerFrame;
    if (numFrames <= 0) {
        pthread_mutex_unlock(&g_mic_lock);
        return 0;
    }

    // ⚠️ 렉의 진짜 원인 2: 200ms 타임아웃은 음성채팅처럼 20~40ms 단위로 프레임을
    //   주고받는 용도엔 너무 길다 — read() 를 부를 때마다 최악의 경우 200ms 씩
    //   블로킹되면서 체감 렉으로 이어졌다. 20ms 로 줄여서 데이터가 없을 때도
    //   금방 돌아오게 한다(그래도 매 프레임 바쁜 대기(spin)는 안 하도록 0 은 아님).
    aaudio_result_t framesRead = AAudioStream_read(g_mic_stream, g_scratch_buffer, numFrames, 20 * 1000000LL);
    if (framesRead < 0) {
        pthread_mutex_unlock(&g_mic_lock);
        LOGE("mic_bridge: AAudioStream_read 실패 (%d)", framesRead);
        return -1;
    }

    jsize bytesRead = (jsize) (framesRead * bytesPerFrame);
    if (bytesRead > 0) {
        (*env)->SetByteArrayRegion(env, buffer, offset, bytesRead, (const jbyte *) g_scratch_buffer);
    }
    pthread_mutex_unlock(&g_mic_lock);
    return bytesRead;
}

JNIEXPORT void JNICALL
Java_kr_co_donghyun_turtlelauncher_audio_TurtleMicNative_nativeClose(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    pthread_mutex_lock(&g_mic_lock);
    if (g_mic_stream != NULL) {
        AAudioStream_requestStop(g_mic_stream);
        AAudioStream_close(g_mic_stream);
        g_mic_stream = NULL;
        LOGI("mic_bridge: 마이크 스트림 종료됨");
    }
    if (g_scratch_buffer != NULL) {
        free(g_scratch_buffer);
        g_scratch_buffer = NULL;
        g_scratch_buffer_size = 0;
    }
    pthread_mutex_unlock(&g_mic_lock);
}

JNIEXPORT jboolean JNICALL
Java_kr_co_donghyun_turtlelauncher_audio_TurtleMicNative_nativeIsAvailable(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    // AAudio 자체는 API 26+ 에서 항상 존재(이 프로젝트 minSdk=26) — 실제 사용 가능
    //   여부(권한 등)는 nativeOpen() 이 성공하는지로 판단한다. 이 함수는 단순히
    //   "이 브릿지가 컴파일/링크돼 있다"는 걸 자바 쪽에서 확인하기 위한 용도.
    return JNI_TRUE;
}
