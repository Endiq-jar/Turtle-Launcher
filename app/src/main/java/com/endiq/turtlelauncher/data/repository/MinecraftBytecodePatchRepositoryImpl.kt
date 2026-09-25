package com.endiq.turtlelauncher.data.repository

import android.util.Log
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MinecraftActivity 의 바이트코드 패칭 유틸 함수들(ASM 기반) 이관.
 *
 * ⚠️ MinecraftActivity 는 JNI 네이티브 바인딩(external fun)이 클래스 이름에
 * 하드코딩되어 있어(Java_com_endiq_turtlelauncher_presentation_MinecraftActivity_...)
 * 그 부분과 입력 처리·게임 실행 오케스트레이션(startMinecraft 등)은 이관하지 않았다 —
 * 컴파일은 되지만 런타임에 UnsatisfiedLinkError 로 조용히 깨질 수 있는 리스크.
 * 이 클래스는 Activity 상태 필드(versionId 등)에 전혀 의존하지 않는, 순수하게
 * "jar 파일을 받아서 바이트코드를 패치해 반환/저장"하는 함수들만 담았다.
 */
@Singleton
class MinecraftBytecodePatchRepositoryImpl @Inject constructor() {

    fun patchLwjglGlfwIfNeeded(lwjgl3Dir: File) {
        if (!lwjgl3Dir.exists()) return
        val candidates = lwjgl3Dir.listFiles()
            ?.filter { it.name.startsWith("lwjgl-glfw-classes") && it.extension == "jar" }
            ?: return

        // 26.1.x 부팅에 필요한 GLFW 3.4 API 들
        val required = setOf(
            "glfwPlatformSupported(I)Z",
            "glfwGetPlatform()I",
            "glfwFocusWindow(J)V",
            "glfwHideWindow(J)V",
            "glfwMaximizeWindow(J)V",
            "glfwRestoreWindow(J)V",
            "glfwRequestWindowAttention(J)V",
        )

        for (jar in candidates) {
            val missing = findMissingMethods(jar, required)
            if (missing.isEmpty()) {
                Log.d("TURTLE_LAUNCHER", "✅ GLFW 3.4 stubs 이미 있음: ${jar.name}")
                // 옛 마커 파일 청소 (있을 수도 없을 수도)
                File(jar.parent, "${jar.name}.patched_glfw34").delete()
                continue
            }
            Log.w("TURTLE_LAUNCHER", "🩹 GLFW 패치 필요: ${jar.name} — 누락 메서드 $missing")
            try {
                patchGlfwJar(jar)
                Log.d("TURTLE_LAUNCHER", "✅ 패치 완료: ${jar.name}")
                // 검증
                val stillMissing = findMissingMethods(jar, required)
                if (stillMissing.isNotEmpty()) {
                    Log.e("TURTLE_LAUNCHER", "❌ 패치 후에도 여전히 누락: $stillMissing — patcher 버그 의심")
                }
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "❌ GLFW 패치 실패: ${e.message}", e)
            }
        }
    }

    /**
     * jar 안의 org/lwjgl/glfw/GLFW.class 를 열어서 [required] 중 빠진 메서드 시그니처 목록 반환.
     * jar 가 깨졌거나 GLFW.class 가 없으면 required 전체를 반환 (= 무조건 패치 시도).
     */
    fun findMissingMethods(jar: File, required: Set<String>): Set<String> {
        return try {
            ZipFile(jar).use { zip ->
                val entry = zip.getEntry("org/lwjgl/glfw/GLFW.class")
                    ?: return required
                val bytes = zip.getInputStream(entry).readBytes()
                val found = HashSet<String>()
                ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int, name: String, descriptor: String,
                        signature: String?, exceptions: Array<out String>?
                    ): org.objectweb.asm.MethodVisitor? {
                        found.add("$name$descriptor")
                        return null
                    }
                }, ClassReader.SKIP_CODE)
                required - found
            }
        } catch (e: Exception) {
            Log.w("TURTLE_LAUNCHER", "jar 메서드 스캔 실패 (${jar.name}): ${e.message}")
            required
        }
    }

    fun patchGlfwJar(jar: File) {
        val tmp = File(jar.parent, jar.name + ".tmp")
        ZipFile(jar).use { zin ->
            ZipOutputStream(tmp.outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val bytes = zin.getInputStream(entry).readBytes()
                    val finalBytes = if (entry.name == "org/lwjgl/glfw/GLFW.class") {
                        patchGlfwClass(bytes)
                    } else bytes

                    // 새 ZipEntry 로 만들어야 CRC/size 자동 계산. DEFLATED 로 통일.
                    val newEntry = ZipEntry(entry.name).apply {
                        method = ZipEntry.DEFLATED
                    }
                    zout.putNextEntry(newEntry)
                    zout.write(finalBytes)
                    zout.closeEntry()
                }
            }
        }
        if (!jar.delete()) throw IOException("기존 jar 삭제 실패: ${jar.absolutePath}")
        if (!tmp.renameTo(jar)) throw IOException("임시 jar rename 실패")
    }

    fun patchGlfwClass(bytes: ByteArray): ByteArray {
        val ASM = Opcodes.ASM9

        // 이미 같은 시그니처 메서드가 있으면 덮어쓰지 않도록 1차 스캔
        val existing = HashSet<String>()
        ClassReader(bytes).accept(object : ClassVisitor(ASM) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor? {
                existing.add("$name$descriptor")
                return null
            }
        }, ClassReader.SKIP_CODE)

        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)

        val visitor = object : ClassVisitor(ASM, writer) {
            override fun visitEnd() {
                // GLFW_PLATFORM_X11 = 0x60004
                if ("glfwPlatformSupported(I)Z" !in existing) {
                    emitPlatformSupported(); Log.d("TURTLE_LAUNCHER", "  + glfwPlatformSupported(I)Z")
                }
                if ("glfwGetPlatform()I" !in existing) {
                    emitGetPlatform(); Log.d("TURTLE_LAUNCHER", "  + glfwGetPlatform()I")
                }
                listOf(
                    "glfwFocusWindow", "glfwHideWindow",
                    "glfwMaximizeWindow", "glfwRestoreWindow",
                    "glfwRequestWindowAttention"
                ).forEach { n ->
                    if ("$n(J)V" !in existing) {
                        emitNoopJV(n); Log.d("TURTLE_LAUNCHER", "  + $n(J)V")
                    }
                }
                super.visitEnd()
            }

            private fun emitPlatformSupported() {
                val mv = cv.visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                    "glfwPlatformSupported", "(I)Z", null, null
                )
                mv.visitCode()
                mv.visitVarInsn(Opcodes.ILOAD, 0)
                mv.visitLdcInsn(0x60004)
                val notEqual = org.objectweb.asm.Label()
                mv.visitJumpInsn(Opcodes.IF_ICMPNE, notEqual)
                mv.visitInsn(Opcodes.ICONST_1)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitLabel(notEqual)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitMaxs(2, 1)
                mv.visitEnd()
            }

            private fun emitGetPlatform() {
                val mv = cv.visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                    "glfwGetPlatform", "()I", null, null
                )
                mv.visitCode()
                mv.visitLdcInsn(0x60004)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitMaxs(1, 0)
                mv.visitEnd()
            }

            private fun emitNoopJV(name: String) {
                val mv = cv.visitMethod(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                    name, "(J)V", null, null
                )
                mv.visitCode()
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(0, 2)   // long = 2 slot
                mv.visitEnd()
            }
        }
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }

    /**
     * Sodium 의 "전체화면 해상도" 슬라이더(SodiumGameOptionPages.general 라인 98)는
     *   new SliderControl(option, 0, monitor.getModeCount(), 1, ...)
     * 로 만들어진다. Android(가상 디스플레이)에서는 monitor 의 video mode 리스트가 비어
     * getModeCount()==0 이 되고, SliderControl 생성자의
     *   Validate.isTrue(max > min)   // 0 > 0  → false
     * 에서 IllegalArgumentException 으로 죽는다(ESC → 비디오 설정 진입 시 크래시).
     *
     * 이 옵션은 어차피 Windows 전용(setEnabled OS==WIN)이라 Android 에선 의미가 없으므로,
     * SliderControl.class 생성자 맨 앞에서 max 를 보정한다:
     *   if (max <= min) max = min + interval;
     * → max > min, (max-min)%interval==0 둘 다 만족 → 어떤 슬라이더든(향후 0-범위 케이스 포함)
     *   터지지 않는다. 람다 구조/난독화/Sodium 버전과 무관한 가장 견고한 지점.
     *
     * sodium / podium 두 jar 모두에 SliderControl 이 들어올 수 있으므로 mods 전체를 훑는다.
     */
    fun patchSodiumSliderIfNeeded(modsDir: File) {
        if (!modsDir.isDirectory) return
        val SLIDER_ENTRY =
            "net/caffeinemc/mods/sodium/client/gui/options/control/SliderControl.class"
        modsDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (!f.name.endsWith(".jar", ignoreCase = true)) return@forEach
            val lower = f.name.lowercase()
            // sodium 본체 + podium(sodium 재패키징) 만 대상. 불필요한 jar 스캔 회피.
            if (!(lower.startsWith("sodium") || lower.startsWith("podium"))) return@forEach

            val marker = File(f.parentFile, "${f.name}.patched_slider")
            if (marker.exists()) return@forEach

            // 이 jar 가 SliderControl 을 품고 있는지 먼저 확인(없으면 스킵)
            val hasSlider = try {
                ZipFile(f).use { it.getEntry(SLIDER_ENTRY) != null }
            } catch (e: Exception) {
                Log.w("TURTLE_LAUNCHER", "slider 스캔 실패 (${f.name}): ${e.message}"); false
            }
            if (!hasSlider) { marker.createNewFile(); return@forEach }

            Log.w("TURTLE_LAUNCHER", "🩹 Sodium SliderControl 패치: ${f.name}")
            try {
                patchSodiumSliderJar(f, SLIDER_ENTRY)
                marker.createNewFile()
                Log.d("TURTLE_LAUNCHER", "✅ SliderControl 패치 완료: ${f.name}")
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "❌ SliderControl 패치 실패: ${e.message}", e)
            }
        }
    }

    fun patchSodiumSliderJar(jar: File, sliderEntry: String) {
        val tmp = File(jar.parent, jar.name + ".tmp")
        ZipFile(jar).use { zin ->
            ZipOutputStream(tmp.outputStream()).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val bytes = zin.getInputStream(entry).readBytes()
                    val finalBytes = if (entry.name == sliderEntry)
                        patchSliderControlClass(bytes) else bytes
                    val newEntry = ZipEntry(entry.name).apply { method = ZipEntry.DEFLATED }
                    zout.putNextEntry(newEntry)
                    zout.write(finalBytes)
                    zout.closeEntry()
                }
            }
        }
        if (!jar.delete()) throw IOException("기존 jar 삭제 실패: ${jar.absolutePath}")
        if (!tmp.renameTo(jar)) throw IOException("임시 jar rename 실패")
    }

    /**
     * SliderControl 생성자
     *   (Lnet/caffeinemc/mods/sodium/client/gui/options/Option;IILnet/caffeinemc/mods/sodium/client/gui/options/control/ControlValueFormatter;)V
     * 의 맨 앞(첫 명령 이전)에 다음을 prepend:
     *   if (max <= min) max = min + interval;
     * 슬롯: this=0, option=1, min=2, max=3, interval=4, mode=5.
     */
    fun patchSliderControlClass(bytes: ByteArray): ByteArray {
        val ASM = Opcodes.ASM9
        // (Option option, int min, int max, int interval, ControlValueFormatter mode)
        //  → int 가 3개(min,max,interval)이므로 III. (이전에 II 로 잘못 적어 매칭 실패했었음)
        val ctorDesc =
            "(Lnet/caffeinemc/mods/sodium/client/gui/options/Option;IIIL" +
                    "net/caffeinemc/mods/sodium/client/gui/options/control/ControlValueFormatter;)V"

        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
        val visitor = object : ClassVisitor(ASM, writer) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor? {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name == "<init>" && descriptor == ctorDesc) {
                    Log.d("TURTLE_LAUNCHER", "  ~ SliderControl.<init> max 보정 prepend")
                    return object : org.objectweb.asm.MethodVisitor(ASM, mv) {
                        override fun visitCode() {
                            super.visitCode()
                            // max = Math.max(max, min + interval);
                            // 분기(if)를 쓰지 않아 새 stack-map frame 이 생기지 않으므로
                            // <init> + COMPUTE_FRAMES 조합에서도 안전하다.
                            // 슬롯: this=0, option=1, min=2, max=3, interval=4, mode=5
                            visitVarInsn(Opcodes.ILOAD, 3)          // max
                            visitVarInsn(Opcodes.ILOAD, 2)          // min
                            visitVarInsn(Opcodes.ILOAD, 4)          // interval
                            visitInsn(Opcodes.IADD)                 // (min + interval)
                            visitMethodInsn(
                                Opcodes.INVOKESTATIC, "java/lang/Math",
                                "max", "(II)I", false
                            )                                       // Math.max(max, min+interval)
                            visitVarInsn(Opcodes.ISTORE, 3)         // max =
                            // 이후 원본 생성자 바디(aload_0; super(); Validate.isTrue ...)가 그대로 실행됨
                        }
                    }
                }
                return mv
            }
        }
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }

    fun patchLaunchwrapperIfNeeded(searchDirs: List<File>) {
        searchDirs.forEach { dir ->
            dir.walkTopDown()
                .filter { it.name.startsWith("launchwrapper") && it.extension == "jar" }
                .forEach { lwJar ->
                    // 이미 패치됐는지 확인 (패치 마커 파일)
                    val markerFile = File(lwJar.parent, "${lwJar.name}.patched")
                    if (markerFile.exists()) return@forEach

                    Log.d("TURTLE_LAUNCHER", "launchwrapper 패치 중: ${lwJar.absolutePath}")
                    try {
                        patchLaunchJar(lwJar)
                        markerFile.createNewFile() // 패치 완료 마커
                        Log.d("TURTLE_LAUNCHER", "✅ launchwrapper 패치 완료")
                    } catch (e: Exception) {
                        Log.e("TURTLE_LAUNCHER", "launchwrapper 패치 실패: ${e.message}")
                    }
                }
        }
    }

    /**
     * pre-1.6 (map_to_resources / virtual) 에셋 펼치기.
     *
     * 1.6 이전 마인크래프트(1.2.5, 1.5.2 등)는 assets/objects/<hash> 해시 저장소를 직접
     * 읽지 못한다. 대신 평문 경로(lang/en_US.lang, sound/... 등)로 리소스를 찾는다.
     * 에셋 인덱스에 "map_to_resources": true(또는 "virtual": true)가 있으면,
     * objects/<hash[0:2]>/<hash> 를 원래 파일명으로 펼쳐서 그 디렉터리를 게임에 넘겨야 한다.
     *
     * 펼친 위치를 반환한다(= ${game_assets}/${assets_root} 로 넘길 경로).
     * pre-1.6 이 아니면 null 반환(기존 동작 유지).
     *
     * 용량 절약을 위해 하드링크를 우선 시도하고, 실패(EXDEV/EPERM, sdcardfs 등)하면 복사로 폴백한다.
     */
    fun prepareLegacyResources(assetsDir: File, mcDir: File, assetIndexName: String): File? {
        val indexFile = File(assetsDir, "indexes/$assetIndexName.json")
        if (!indexFile.exists()) {
            Log.w("TURTLE_LAUNCHER", "legacy resources: index 없음 ($assetIndexName.json)")
            return null
        }

        val root = try {
            com.google.gson.JsonParser.parseString(indexFile.readText()).asJsonObject
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "legacy resources: index 파싱 실패: ${e.message}")
            return null
        }

        val mapToResources = root.has("map_to_resources") && root["map_to_resources"].asBoolean
        val isVirtual = root.has("virtual") && root["virtual"].asBoolean
        if (!mapToResources && !isVirtual) return null   // 1.7+ 표준 에셋 → 펼칠 필요 없음

        // map_to_resources(1.5 이하) → <gameDir>/resources/ , virtual(1.6) → assets/virtual/legacy/
        val targetRoot = if (mapToResources) File(mcDir, "resources")
        else File(assetsDir, "virtual/legacy")
        targetRoot.mkdirs()

        val objectsDir = File(assetsDir, "objects")
        val objects = root["objects"].asJsonObject
        var linked = 0; var copied = 0; var missing = 0; var skipped = 0

        for ((path, v) in objects.entrySet()) {
            val hash = v.asJsonObject["hash"].asString
            val src = File(objectsDir, "${hash.substring(0, 2)}/$hash")
            val dst = File(targetRoot, path)

            if (dst.exists() && dst.length() > 0) { skipped++; continue }
            if (!src.exists()) { missing++; continue }
            dst.parentFile?.mkdirs()

            // 1) 하드링크 시도 (용량 0, 가장 빠름)
            try {
                android.system.Os.link(src.absolutePath, dst.absolutePath)
                linked++
                continue
            } catch (_: Throwable) {
                // EXDEV(다른 파일시스템)/EPERM(sdcardfs) 등 → 복사로 폴백
            }
            // 2) 복사 폴백
            try {
                src.copyTo(dst, overwrite = true)
                copied++
            } catch (e: Exception) {
                Log.w("TURTLE_LAUNCHER", "legacy resources: 복사 실패 $path: ${e.message}")
            }
        }

        Log.d("TURTLE_LAUNCHER",
            "✅ legacy resources 펼침 → ${targetRoot.absolutePath} " +
                    "(link=$linked copy=$copied skip=$skipped missing=$missing, mapToResources=$mapToResources)")
        return targetRoot
    }

    fun patchLaunchJar(lwJar: File) {
        val zipIn = ZipFile(lwJar)
        val patchedJar = File(lwJar.parent, lwJar.name + ".tmp")
        val zipOut = ZipOutputStream(patchedJar.outputStream())

        zipIn.entries().asSequence().forEach { entry ->
            val bytes = zipIn.getInputStream(entry).readBytes()
            val patched = if (entry.name == "net/minecraft/launchwrapper/Launch.class") {
                patchLaunchClass(bytes)
            } else bytes
            zipOut.putNextEntry(ZipEntry(entry.name))
            zipOut.write(patched)
            zipOut.closeEntry()
        }

        zipIn.close()
        zipOut.close()
        lwJar.delete()
        patchedJar.renameTo(lwJar)
    }

    fun patchLaunchClass(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)

        val visitor = object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int, name: String, descriptor: String,
                signature: String?, exceptions: Array<out String>?
            ): org.objectweb.asm.MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name == "<init>" && descriptor == "()V") {
                    return object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9, mv) {
                        override fun visitTypeInsn(opcode: Int, type: String) {
                            if (opcode == Opcodes.CHECKCAST && type == "java/net/URLClassLoader") {
                                visitInsn(Opcodes.POP)
                                visitLdcInsn("java.class.path")
                                visitLdcInsn("")
                                visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false)
                                visitLdcInsn(File.pathSeparator)
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", false)
                                visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/launchwrapper/Launch", "pingStringsToUrls", "([Ljava/lang/String;)[Ljava/net/URL;", false)
                                return
                            }
                            super.visitTypeInsn(opcode, type)
                        }
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                            if (owner == "java/net/URLClassLoader" && name == "getURLs") return
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
                return mv
            }

            override fun visitEnd() {
                // 헬퍼 메서드 추가
                val mv = cv.visitMethod(
                    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
                    "pingStringsToUrls", "([Ljava/lang/String;)[Ljava/net/URL;", null, null
                )
                mv.visitCode()
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/net/URL")
                mv.visitVarInsn(Opcodes.ASTORE, 1)
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitVarInsn(Opcodes.ISTORE, 2)
                val loopStart = org.objectweb.asm.Label()
                val loopEnd = org.objectweb.asm.Label()
                mv.visitLabel(loopStart)
                mv.visitVarInsn(Opcodes.ILOAD, 2)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitInsn(Opcodes.ARRAYLENGTH)
                mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd)
                val tryStart = org.objectweb.asm.Label()
                val tryEnd = org.objectweb.asm.Label()
                val catchBlock = org.objectweb.asm.Label()
                mv.visitTryCatchBlock(tryStart, tryEnd, catchBlock, "java/lang/Exception")
                mv.visitLabel(tryStart)
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitVarInsn(Opcodes.ILOAD, 2)
                mv.visitTypeInsn(Opcodes.NEW, "java/io/File")
                mv.visitInsn(Opcodes.DUP)
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitVarInsn(Opcodes.ILOAD, 2)
                mv.visitInsn(Opcodes.AALOAD)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "toURI", "()Ljava/net/URI;", false)
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URI", "toURL", "()Ljava/net/URL;", false)
                mv.visitInsn(Opcodes.AASTORE)
                mv.visitLabel(tryEnd)
                mv.visitIincInsn(2, 1)
                mv.visitJumpInsn(Opcodes.GOTO, loopStart)
                mv.visitLabel(catchBlock)
                mv.visitInsn(Opcodes.POP)
                mv.visitIincInsn(2, 1)
                mv.visitJumpInsn(Opcodes.GOTO, loopStart)
                mv.visitLabel(loopEnd)
                mv.visitVarInsn(Opcodes.ALOAD, 1)
                mv.visitInsn(Opcodes.ARETURN)
                mv.visitMaxs(5, 3)
                mv.visitEnd()
                super.visitEnd()
            }
        }
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }
}
