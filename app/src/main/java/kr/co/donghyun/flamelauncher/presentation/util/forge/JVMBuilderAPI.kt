package kr.co.donghyun.flamelauncher.presentation.util.forge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

/**
 * Forge/NeoForge patched jar 빌더 프로세스 제어 API (ZL2 jvm_server 포팅).
 *
 * 배경: flamejvm.cpp 의 JNI_CreateJavaVM 은 "프로세스당 1회"만 가능하다. 그래서 게임 JVM 안에서
 * ProcessorLauncher 로 jar 를 만들고 곧바로 ForgeBootstrap 을 부르면, 게임 JVM 부팅 시점에
 * forge-<ver>-client.jar 가 없어 모듈 인덱스가 죽고 getResource 가 Minecraft.class 를 못 찾는다.
 *
 * 해결(ZL2 방식): processor 실행을 ":forgebuilder" 별도 프로세스의 [ForgeBuilderService] 에서
 * 돌려 jar 를 완성하고, 그 프로세스를 통째로 종료한다. 이후 게임은 ":minecraft" 프로세스에서
 * ForgeBootstrap 진입점으로 깨끗하게 부팅된다. 빌더 JVM 종료코드는 UDP(127.0.0.1)로 전달받는다.
 */

const val PROCESS_SERVICE_PORT = 53151 // ZL2 와 동일(임의 포트)

/**
 * 빌더 종료 신호(UDP) 대기 최대 시간. 이게 없으면 :forgebuilder 프로세스가 크래시/OOM-kill
 * 등으로 신호를 못 보내는 경우 socket.receive() 가 무한정 블록해서 "Launching minecraft"
 * 에서 영원히 멈춘 것처럼 보이는 버그가 있었다(실제 보고됨: 1.21.4 순정 Forge 첫 실행).
 * 모던/대형 Forge(디옵퓨스케이션+바이너리 패치)는 저사양 기기에서 정상적으로도 수 분씩
 * 걸릴 수 있어 넉넉하게 잡는다 — 그래도 무한대보다는 낫다(타임아웃 시 명확한 에러로 전환).
 */
private const val BUILDER_TIMEOUT_MS = 5 * 60_000L   // 5분

/** [startBuilderAndWaitExit] 이 타임아웃으로 포기했을 때 반환하는 특수 코드(정상 종료코드와 구분). */
const val BUILDER_TIMEOUT_EXIT_CODE = -999

// ForgeBuilderService 로 넘길 Intent extra 키
const val SERVICE_LIB_JVM_PATH = "service.libjvm.path"
const val SERVICE_JVM_ARGS     = "service.jvm.args"       // \u0001 로 join 된 JVM 인자
const val SERVICE_USER_DIR     = "service.user.dir"
const val SERVICE_RENDERER_ENV = "service.renderer.env"   // "k=v\u0001k=v" (없으면 빈 문자열)
const val SERVICE_POST_SUMMARY = "service.post.summary"

private const val TAG = "JvmBuilder"
private const val DELIM = "\u0001"

fun encodeArgs(args: Array<String>): String = args.joinToString(DELIM)
fun decodeArgs(s: String?): Array<String> =
    if (s.isNullOrEmpty()) emptyArray() else s.split(DELIM).toTypedArray()

fun encodeEnv(env: Map<String, String>): String =
    env.entries.joinToString(DELIM) { "${it.key}=${it.value}" }
fun decodeEnv(s: String?): Map<String, String> {
    if (s.isNullOrEmpty()) return emptyMap()
    return s.split(DELIM).mapNotNull { entry ->
        val i = entry.indexOf('=')
        if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 1)
    }.toMap()
}

/** 현재 메인 프로세스만 살아있는지(=빌더 프로세스가 없는지) 확인. (ZL2 isOnlyMainProcessesRunning) */
fun isOnlyMainProcessRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val builderProc = "${context.packageName}:forgebuilder"
    val procs = am.runningAppProcesses ?: return true
    return procs.none { it.processName == builderProc }
}

/** 메인 외 모든 자식 프로세스 강제 종료. 빌더 JVM 기동 전 호출해 프로세스를 깨끗이 비운다. */
fun stopAllNonMainProcesses(context: Context) {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val builderProc = "${context.packageName}:forgebuilder"
    val myPid = Process.myPid()
    am.runningAppProcesses
        ?.filter { it.processName == builderProc && it.pid != myPid }
        ?.forEach { runCatching { Process.killProcess(it.pid) } }
}

/**
 * 빌더 서비스를 띄우고 JVM 종료코드를 UDP 로 수신해 반환한다. (ZL2 startJvmServiceAndWaitExit)
 * 정상 종료(halt 0) = 0. 그 외는 크래시.
 */
suspend fun startBuilderAndWaitExit(
    context: Context,
    libJvmPath: String,
    jvmArgs: Array<String>,
    userDir: String,
    rendererEnv: Map<String, String>,
    postSummary: String? = null,
): Int = withContext(Dispatchers.IO) {
    // 이전 빌더 잔여 프로세스가 있으면 정리될 때까지 대기
    var waited = 0
    while (!isOnlyMainProcessRunning(context)) {
        if (waited == 0) Log.i(TAG, "다른 프로세스 종료 대기 중...")
        delay(100)
        waited += 100
        if (waited > 10_000) { // 10s 넘으면 강제 정리
            stopAllNonMainProcesses(context)
            delay(300)
            break
        }
    }

    val doneSignal = CompletableDeferred<Int>()

    // UDP 수신 서버: 빌더 프로세스가 보낸 종료코드를 받는다.
    val socket = DatagramSocket(PROCESS_SERVICE_PORT)
    val serverThread = Thread {
        try {
            val buf = ByteArray(64)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet) // 블록
            val msg = String(packet.data, 0, packet.length).trim()
            val code = msg.toIntOrNull() ?: 0
            Log.i(TAG, "빌더 종료코드 수신: $code")
            if (!doneSignal.isCompleted) doneSignal.complete(code)
        } catch (e: SocketException) {
            // stop() 으로 닫힌 정상 케이스
            if (!doneSignal.isCompleted) doneSignal.complete(0)
        } catch (e: Exception) {
            Log.e(TAG, "UDP 수신 실패", e)
            if (!doneSignal.isCompleted) doneSignal.complete(0)
        }
    }.apply { isDaemon = true; start() }

    // 빌더 서비스 시작 (:forgebuilder 프로세스)
    val intent = Intent(context, ForgeBuilderService::class.java).apply {
        putExtra(SERVICE_LIB_JVM_PATH, libJvmPath)
        putExtra(SERVICE_JVM_ARGS, encodeArgs(jvmArgs))
        putExtra(SERVICE_USER_DIR, userDir)
        putExtra(SERVICE_RENDERER_ENV, encodeEnv(rendererEnv))
        putExtra(SERVICE_POST_SUMMARY, postSummary)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }

    val code = withTimeoutOrNull(BUILDER_TIMEOUT_MS) { doneSignal.await() }
        ?: run {
            Log.e(TAG, "⏱️ 빌더 종료 신호 대기 타임아웃(${BUILDER_TIMEOUT_MS}ms) — :forgebuilder 가 응답 없음(크래시/OOM-kill 추정)")
            BUILDER_TIMEOUT_EXIT_CODE
        }
    runCatching { socket.close() }
    runCatching { serverThread.interrupt() }

    // ⚠️ UDP 신호는 빌더 JVM 이 halt(0) 직전에 보내는 거라, 신호를 받았다고 해서
    //   안드로이드가 :forgebuilder 프로세스 자체를 이미 회수했다는 보장은 없다.
    //   바로 이어서 게임 프로세스가 큰 힙(수 GB)을 요청하면 아직 안 죽은 빌더 프로세스와
    //   메모리가 경합해서 게임 JVM 이 극도로 느려질 수 있어, 짧게 폴링해서 실제로
    //   프로세스가 사라질 때까지(최대 3초) 기다린다.
    var waitedAfter = 0
    while (!isOnlyMainProcessRunning(context) && waitedAfter < 3000) {
        delay(100)
        waitedAfter += 100
    }
    if (waitedAfter >= 3000) {
        Log.w(TAG, "⚠️ :forgebuilder 프로세스가 3초 내로 정리 안 됨 — 강제 종료")
        stopAllNonMainProcesses(context)
    }

    code
}

/**
 * [startBuilderAndWaitExit] 의 동기(blocking) 버전.
 * 코루틴 스코프가 없는 일반 Thread(예: MinecraftActivity 의 게임 부팅 Thread) 안에서
 * 빌더를 띄우고 종료코드를 받을 때 사용한다. 호출 스레드를 빌더 종료까지 블록한다.
 */
fun startBuilderAndWaitExitBlocking(
    context: Context,
    libJvmPath: String,
    jvmArgs: Array<String>,
    userDir: String,
    rendererEnv: Map<String, String>,
    postSummary: String? = null,
): Int = runBlocking {
    startBuilderAndWaitExit(
        context = context,
        libJvmPath = libJvmPath,
        jvmArgs = jvmArgs,
        userDir = userDir,
        rendererEnv = rendererEnv,
        postSummary = postSummary,
    )
}