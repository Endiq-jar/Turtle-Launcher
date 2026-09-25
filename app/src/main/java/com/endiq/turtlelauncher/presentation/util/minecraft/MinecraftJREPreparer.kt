package com.endiq.turtlelauncher.presentation.util.minecraft

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MinecraftJREPreparer {
    companion object {
        private const val TAG = "TURTLE_LAUNCHER"

        /**
         * MC 버전 → 필요한 Java major
         *  1.0  ~ 1.16.x  → 8
         *  1.17 ~ 1.17.x  → 16
         *  1.18 ~ 1.20.4  → 17
         *  1.20.5+        → 21
         */
        fun pickJavaMajor(mcVersion: String): Int {
            val id = mcVersion.trim().lowercase()

            // 베타/알파/클래식/인데브/rd- 등 아주 옛 버전은 전부 Java 8.
            //   (b1.x, a1.x 는 우연히 아래 split 파서에서도 맞게 나오지만, "inf-20100618"
            //    이나 "rd-132211" 처럼 점이 없거나 위치가 다른 표기는 그렇지 않아 명시적으로 처리)
            if (id.startsWith("b1.") || id.startsWith("a1.") || id.startsWith("a0.") ||
                id.startsWith("c0.") || id.startsWith("inf-") || id.startsWith("rd-")) {
                return 8
            }

            // "1.16.5", "1.20.4", "1.21" ... 스냅샷("23w14a")은 21로 처리
            val snapshotMatch = Regex("^(\\d{2})w\\d+[a-z]$").matchEntire(id)
            if (snapshotMatch != null) {
                val year = snapshotMatch.groupValues[1].toInt()
                return when {
                    year >= 26 -> 25
                    year >= 24 -> 21
                    else       -> 17
                }
            }
            val parts = id.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
            if (major >= 26) return 25
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return 21
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

            return when {
                minor <= 16 -> 8
                minor == 17 -> 16
                minor in 18..19 -> 17
                minor == 20 && patch <= 4 -> 17
                else -> 21
            }
        }

        private fun assetExists(context: Context, name: String): Boolean = try {
            context.assets.open(name).close(); true
        } catch (_: Exception) { false }

        /**
         * 요청한 major의 jre{N}.zip이 없으면 상위 LTS로 폴백한다.
         * 8 → 17 → 21 순으로 시도.
         */
        private fun resolveAvailableMajor(context: Context, requested: Int): Int {
            val order = linkedSetOf(requested, 25, 21, 17).toList()
            return order.firstOrNull { assetExists(context, "jre/jre${it}.zip") }
                ?: throw Exception("assets/jre/에 jre{8,17,21,25}.zip이 하나도 없습니다.")
        }


        fun prepareJreAndGetPath(context: Context, mcVersion: String): String {
            val requestedMajor = pickJavaMajor(mcVersion)
            return prepareJreMajorAndGetPath(context, requestedMajor, "mcVersion=$mcVersion")
        }

        /**
         * 지정한 JRE 메이저를 압축 해제(최초 1회)하고 libjvm.so 절대경로를 반환한다.
         * 요청 메이저의 jre/jre{major}.zip 이 없으면 resolveAvailableMajor 로 폴백.
         *
         * Forge/NeoForge patched jar 빌더가 JRE 8 을 직접 요청할 때(ZL2 와 동일)와,
         * 게임 실행이 mcVersion 으로 고른 메이저를 요청할 때 모두 이 함수를 쓴다.
         */
        fun prepareJreMajorAndGetPath(
            context: Context,
            requestedMajor: Int,
            reasonForLog: String = ""
        ): String {
            val major = resolveAvailableMajor(context, requestedMajor)
            if (major != requestedMajor) {
                Log.w(TAG, "jre/jre${requestedMajor}.zip 없음 → jre/jre${major}.zip 로 폴백 ($reasonForLog)")
            }
            val targetDir = File(context.filesDir, "jre${major}_runtime")
            if (!targetDir.exists() || targetDir.listFiles()?.isEmpty() == true) {
                targetDir.mkdirs()
                Log.i(TAG, "📦 JRE $major 최초 압축 해제 시작...")
                context.assets.open("jre/jre${major}.zip").use { input ->
                    ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val outFile = File(targetDir, entry.name)
                            if (entry.isDirectory) outFile.mkdirs()
                            else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { zip.copyTo(it) }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                Log.i(TAG, "✅ JRE $major 압축 해제 완료!")
            }
            if (major == 8) {
                val cacioDir = File(context.filesDir, "caciocavallo")
                cacioDir.mkdirs()
                val cacioJars = listOf(
                    "cacio-shared-1.10-SNAPSHOT.jar",
                    "cacio-androidnw-1.10-SNAPSHOT.jar",
                    "ResConfHack.jar"
                )
                val needsCopy = cacioJars.any { !File(cacioDir, it).exists() }
                if (needsCopy) {
                    Log.i(TAG, "📦 Caciocavallo 복사 시작...")
                    for (jar in cacioJars) {
                        copyAsset(context, "caciocavallo/$jar", File(cacioDir, jar))
                    }
                    Log.i(TAG, "✅ Caciocavallo 복사 완료 (${cacioJars.size}개)")
                }
            } else {
                // ── JRE9+ (17/21/25): modern 버전 AWT 지원 ────────────────────────────
                // (1) cacio17 jar 복사 (JVMSettings 의 JRE9+ 분기가 -Xbootclasspath/a: 와
                //     -javaagent: 로 참조)
                //   ⚠️ cacio-agent.jar 는 toolkit jar(shared/tta)와 별개의 파일이며
                //      Premain-Class 를 갖는다. premain 이 Unsafe 로 Toolkit 을 설치하므로
                //      이 3개가 모두 있어야 JRE9+ 에서 AWT(cacio)가 동작한다.
                val cacio17Dir = File(context.filesDir, "caciocavallo17")
                cacio17Dir.mkdirs()

                // 구버전(1.18) 잔재 제거: 파일명이 바뀌어도 옛 jar 가 남아 있으면
                //   -Xbootclasspath/a: 에 두 버전이 섞여 클래스 충돌이 날 수 있어 먼저 지운다.
                listOf(
                    "cacio-shared-1.18-SNAPSHOT.jar",
                    "cacio-tta-1.18-SNAPSHOT.jar"
                ).forEach { old ->
                    val f = File(cacio17Dir, old)
                    if (f.exists() && f.delete()) {
                        Log.i(TAG, "🧹 구버전 cacio jar 제거: $old")
                    }
                }

                val cacio17Jars = listOf(
                    "cacio-shared-1.19.1-SNAPSHOT.jar",
                    "cacio-tta-1.19.1-SNAPSHOT.jar",
                    "cacio-agent.jar"
                )
                val needsCopy17 = cacio17Jars.any { !File(cacio17Dir, it).exists() }
                if (needsCopy17) {
                    Log.i(TAG, "📦 Caciocavallo17 복사 시작...")
                    for (jar in cacio17Jars) {
                        copyAsset(context, "caciocavallo17/$jar", File(cacio17Dir, jar))
                    }
                    Log.i(TAG, "✅ Caciocavallo17 복사 완료 (${cacio17Jars.size}개)")
                }

                // (2) JRE 의 libawt_xawt.so 를 APK 의 X11-free 스텁으로 교체
                //
                // 왜 필요한가:
                //   FancyMenu/Dice 등이 java.awt.Color 를 호출하면 Toolkit.loadLibraries 가
                //   System.loadLibrary("awt_xawt") 를 실행하는데, 이건 SONAME 점유와 무관하게
                //   JRE 의 절대경로(jre17/lib/libawt_xawt.so)에서 파일을 다시 찾아 로드한다.
                //   JRE 본 libawt_xawt.so 는 X11(libXrender 등) 의존이라 Android 에서 로드 실패 →
                //   UnsatisfiedLinkError 로 게임이 죽는다.
                //   cacio 가 toolkit 을 가로채도 이 "로드 시도" 자체는 일어나므로,
                //   JRE 의 libawt_xawt.so 를 APK 의 의존성 없는 스텁으로 덮어써서 로드를 성공시킨다.
                //   (cacio 가 그 위에서 toolkit 을 점유하므로 실제 X11 호출은 발생하지 않는다.)
                replaceJreXawtWithStub(context, targetDir)
            }
            val libJvm = targetDir.walkTopDown().firstOrNull { it.name == "libjvm.so" }
                ?: throw Exception("❌ jre${major}_runtime 안에 libjvm.so 없음")
            libJvm.setExecutable(true, false)
            Log.i(TAG, "🎯 libjvm.so: ${libJvm.absolutePath}")
            return libJvm.absolutePath
        }

        /**
         * JRE 의 libawt_xawt.so 를 APK 에 들어있는 X11-free 스텁으로 덮어쓴다.
         * modern(JRE9+) 에서 AWT 의존 모드(FancyMenu/Dice 등)가 Toolkit.loadLibraries 로
         * libawt_xawt.so 를 절대경로 로드할 때 UnsatisfiedLinkError 가 나지 않게 한다.
         *
         * 멱등(idempotent): 이미 스텁으로 바뀐 경우 파일 크기로 감지해 재복사를 건너뛴다.
         */
        private fun replaceJreXawtWithStub(context: Context, jreRuntimeDir: File) {
            // APK 의 네이티브 라이브러리 디렉토리. 추출된 .so 들이 위치한다.
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val stub = File(nativeDir, "libawt_xawt.so")
            if (!stub.exists()) {
                Log.w(TAG, "⚠️ APK 스텁 libawt_xawt.so 없음 (${stub.absolutePath}) — xawt 교체 건너뜀")
                return
            }

            // JRE lib 디렉토리는 libawt.so 가 있는 곳(libawt_xawt.so 와 같은 폴더).
            val jreLibDir = jreRuntimeDir.walkTopDown()
                .firstOrNull { it.name == "libawt.so" }
                ?.parentFile
            if (jreLibDir == null) {
                Log.w(TAG, "⚠️ JRE lib 디렉토리(libawt.so 위치)를 못 찾음 — xawt 교체 건너뜀")
                return
            }

            val target = File(jreLibDir, "libawt_xawt.so")

            // 이미 스텁과 동일 크기면(=교체 완료) 건너뛴다.
            if (target.exists() && target.length() == stub.length()) {
                Log.i(TAG, "ℹ️ JRE libawt_xawt.so 이미 스텁으로 교체됨 — 건너뜀")
                return
            }

            try {
                stub.copyTo(target, overwrite = true)
                target.setExecutable(true, false)
                Log.i(TAG, "✅ JRE libawt_xawt.so 를 APK 스텁으로 교체: ${target.absolutePath} (${target.length()}B)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ JRE libawt_xawt.so 교체 실패: ${e.message}")
            }
        }


        fun findJreLibDir(context: Context, mcVersion: String): File? {
            val major = pickJavaMajor(mcVersion)
            val candidates = listOf(major, 17, 21).distinct()
            for (m in candidates) {
                val root = File(context.filesDir, "jre${m}_runtime")
                if (!root.exists()) continue
                // libjava.so가 있는 폴더 = JRE21은 lib/, JRE8은 lib/aarch64/
                val libJava = root.walkTopDown().firstOrNull { it.name == "libjava.so" }
                if (libJava != null) return libJava.parentFile
            }
            return null
        }

        private fun copyAsset(context: Context, assetPath: String, outFile: File) {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}