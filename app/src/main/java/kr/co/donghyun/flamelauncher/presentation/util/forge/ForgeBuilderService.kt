package kr.co.donghyun.flamelauncher.presentation.util.forge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import kr.co.donghyun.flamelauncher.presentation.util.jni.JavaNativeLauncher
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Forge/NeoForge patched jar 빌더 전용 서비스. (ZL2 JvmService 포팅)
 *
 * AndroidManifest 에서 android:process=":forgebuilder" 로 선언되어, 게임(:minecraft)/메인 프로세스와
 * 분리된 별도 프로세스에서 JNI_CreateJavaVM 을 호출한다. 진입점은 ProcessorLauncher 이며,
 * processor 가 jar 를 만들고 halt(0) 로 종료하면 그 코드를 UDP 로 메인 프로세스에 보낸 뒤
 * 자기 프로세스를 죽인다(다음 JNI_CreateJavaVM 이 깨끗한 프로세스를 쓰도록).
 */
class ForgeBuilderService : Service() {

    companion object {
        private const val TAG = "ForgeBuilderService"
        private const val CHANNEL_ID = "forge_builder"
        private const val NOTIFICATION_ID = 73151
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 시작 타임아웃 방지: 즉시 포그라운드 진입
        postNotification(null)

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val libJvmPath = intent.getStringExtra(SERVICE_LIB_JVM_PATH)
            ?: run { finishWithCode(1); return START_NOT_STICKY }
        val jvmArgs = decodeArgs(intent.getStringExtra(SERVICE_JVM_ARGS))
        val userDir = intent.getStringExtra(SERVICE_USER_DIR)
        val rendererEnv = decodeEnv(intent.getStringExtra(SERVICE_RENDERER_ENV))
        val postSummary = intent.getStringExtra(SERVICE_POST_SUMMARY)
        postNotification(postSummary)

        // 게임 JVM 과 동일한 네이티브 환경 구성 후 빌더 JVM 기동.
        Thread {
            val code = runCatching {
                // AWT 스텁 선로딩 (게임 경로와 동일) — 일부 processor 가 AWT 심볼을 참조할 수 있음
                runCatching {
                    JavaNativeLauncher.preloadAwtStubs(applicationInfo.nativeLibraryDir)
                }
                val launcher = JavaNativeLauncher()
                if (rendererEnv.isNotEmpty()) launcher.applyEnv(rendererEnv)

                // user.dir 보정: flamejvm.cpp 가 -Duser.dir 로 chdir 하므로, 인자에 이미 포함돼 있으면 그대로 사용.
                // 누락 시에만 보강.
                val finalArgs = if (userDir != null && jvmArgs.none { it.startsWith("-Duser.dir=") }) {
                    jvmArgs + "-Duser.dir=$userDir"
                } else jvmArgs

                Log.i(TAG, "빌더 JVM 기동: libjvm=$libJvmPath, args=${finalArgs.size}개")
                // ProcessorLauncher 가 모든 processor 실행 후 halt(0) 하므로 이 호출은 그 시점에 반환되지 않고
                // 프로세스가 종료된다. 반환된다면 비정상(예: 진입 클래스 로드 실패) → 코드 사용.
                launcher.bootMinecraftJVM(libJvmPath, finalArgs, emptyArray())
            }.getOrElse { e ->
                Log.e(TAG, "빌더 JVM 예외", e)
                1
            }
            // bootMinecraftJVM 이 반환됐다면(=halt 미발생) 그 코드로 종료 처리
            finishWithCode(code)
        }.apply { isDaemon = false; start() }

        return START_NOT_STICKY
    }

    private fun finishWithCode(code: Int) {
        Log.i(TAG, "빌더 종료 처리 (code=$code)")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        sendCode(code)
        stopSelf()
    }

    private fun sendCode(code: Int) {
        try {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", PROCESS_SERVICE_PORT))
                val data = code.toString().toByteArray()
                socket.send(DatagramPacket(data, data.size))
                Log.i(TAG, "종료코드 $code 전송 → 127.0.0.1:${PROCESS_SERVICE_PORT}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "종료코드 전송 실패", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 프로세스를 완전히 죽여 다음 JNI_CreateJavaVM 이 깨끗한 프로세스를 쓰게 한다. (ZL2 와 동일)
        Process.killProcess(Process.myPid())
    }

    private fun postNotification(summary: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Forge 설치",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Forge 구성 요소 준비 중")
            .setContentText(summary ?: "patched client jar 생성 중...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(applicationInfo.icon)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}