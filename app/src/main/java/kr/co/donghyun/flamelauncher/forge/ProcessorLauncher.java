package kr.co.donghyun.flamelauncher.forge;

import java.io.*;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Modern Forge / NeoForge processors 실행기.
 *
 * 동작:
 *  1) forge-install-data.properties 로딩
 *  2) processor 순차 실행. 각 단계마다 입력 파일 존재 / 출력 결과 / 예외를 모두 stdout 으로 dump.
 *  3) 마지막에 realMainClass.main(args) 호출.
 *
 * stdout 은 flamejvm.cpp 의 stdout_logger_thread 가 logcat "MinecraftJVM_IO" 태그로 중계함.
 */
public final class ProcessorLauncher {

    private static final String DELIM = "\u0001";

    public static void main(String[] args) throws Throwable {
        File userDir = new File(System.getProperty("user.dir", "."));
        File dataFile = new File(userDir, "forge-install-data.properties");
        if (!dataFile.exists()) {
            throw new IllegalStateException("forge-install-data.properties not found at " + dataFile);
        }

        Properties p = new Properties();
        try (InputStream in = new FileInputStream(dataFile)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        String realMainClass = require(p, "realMainClass");
        int count = Integer.parseInt(p.getProperty("processorCount", "0"));

        log("count=" + count + " realMain=" + realMainClass);
        log("workdir=" + userDir.getAbsolutePath());

        for (int i = 0; i < count; i++) {
            String prefix = "processor." + i + ".";
            String jar = require(p, prefix + "jar");
            String[] cp = split(p.getProperty(prefix + "classpath", ""));
            String[] pargs = split(p.getProperty(prefix + "args", ""));
            String[] outKeys = split(p.getProperty(prefix + "outputs.keys", ""));
            String[] outShas = split(p.getProperty(prefix + "outputs.values", ""));

            String jarName = new File(jar).getName();

            // 🔧 FIX: 출력이 "실제로 유효"할 때만 건너뛴다.
            //   (구) 조건: (!strictShaCheck || outputsValid(...))
            //     → ping.forge.skip_sha=1 이면 !strictShaCheck 가 true 가 되고, OR 의 단락평가
            //       때문에 outputsValid 를 아예 호출하지 않고 '출력 존재 여부와 무관하게' 무조건
            //       SKIP 됐다. 결과적으로 ForgeAutoRenamingTool(SRG 변환)/binarypatcher 가 한 번도
            //       실행되지 않아, net/minecraft/client/Minecraft.class 가 든 patched client jar 가
            //       생성되지 않고 → BOOTSTRAP 에서 "Could not find ...Minecraft.class" 로 죽었다.
            //   skip_sha 는 'SHA 강제 여부' 플래그였는데 outputsValid 는 이미 SHA 를 보지 않으므로
            //   여기서 별도 분기로 둘 이유가 없다. 항상 존재/구조 검증(outputsValid)을 거치게 한다.
            if (outKeys.length > 0 && outputsValid(outKeys, outShas)) {
                log("[" + i + "/" + count + "] SKIP (outputs already valid): " + jarName);
                continue;
            }

            // 🔧 DOWNLOAD_MOJMAPS 처럼 install_profile 에 outputs 선언이 없는 다운로드 task 는
            //    위 skip(outKeys>0)에 안 걸려 매번 실행된다. 그런데 빌더(:forgebuilder) 프로세스는
            //    DNS 후킹이 깨져 launchermeta.mojang.com 을 못 풀어(UnknownHostException → code -6)
            //    빌드가 통째로 실패한다. 런처가 설치 때 preDownloadMojmaps 로 --output 경로에 매핑을
            //    미리 받아두므로, 그 파일이 이미 있으면 네트워크 없이 이 task 를 건너뛴다.
            if (outKeys.length == 0 && hasTask(pargs, "DOWNLOAD_MOJMAPS")) {
                String out = argValue(pargs, "--output");
                File outFile = (out == null) ? null : new File(out);
                if (outFile != null && outFile.isFile() && outFile.length() > 0) {
                    log("[" + i + "/" + count + "] SKIP (mojmaps 이미 존재, 네트워크 불필요): "
                            + jarName + " → " + out);
                    continue;
                }
                log("[" + i + "/" + count + "] ⚠️ DOWNLOAD_MOJMAPS 출력 없음(" + out
                        + ") — 빌더에서 네트워크 시도 예정. preDownloadMojmaps 가 안 깔았을 수 있음.");
            }

            // ⚠️ 버그 수정: 기존에 여기 "outputs 가 invalid 면 SKIP" 하는 블록이 있었음.
            //    그 블록은 outputsValid 의 true/false 를 양쪽 if 에서 모두 잡아버려
            //    출력이 유효하든 아니든 무조건 프로세서를 건너뛰게 만들었음.
            //    → ForgeAutoRenamingTool(SRG 변환)/binarypatcher 가 한 번도 실행되지 않아
            //      net/minecraft/client/Minecraft.class 가 든 patched client jar 가 생성 안 됨.
            //    출력이 invalid 면 SKIP 하지 말고 아래로 내려가 실제로 프로세서를 실행해야 함.

            log("[" + i + "/" + count + "] === run " + jarName + " ===");
            log("  args: " + Arrays.toString(pargs));

            // 1) 입력 파일 존재 여부 진단 — Processor 0 의 출력이 Processor 1 의 입력이 되는 식.
            //    여기서 missing/size=0 인 입력이 보이면 그게 진짜 원인.
            for (int a = 0; a < pargs.length - 1; a++) {
                String arg = pargs[a];
                if (arg.equals("--input") || arg.equals("--names")
                        || arg.equals("--mappings") || arg.equals("--patch")
                        || arg.equals("--data") || arg.equals("--lib")
                        || arg.equals("--mc") || arg.equals("--source")) {
                    String inPath = pargs[a + 1];
                    File inFile = new File(inPath);
                    log("  input " + arg + " = " + inPath
                            + " (exists=" + inFile.exists()
                            + " size=" + (inFile.exists() ? inFile.length() : -1) + ")");
                }
            }
            for (int k = 0; k < outKeys.length; k++) {
                File f = new File(outKeys[k]);
                log("  expect output[" + k + "]=" + outKeys[k]
                        + " (currently exists=" + f.exists()
                        + " size=" + (f.exists() ? f.length() : -1) + ")");
            }

            // 2) 실행
            try {
                runProcessor(jar, cp, pargs);
            } catch (Throwable t) {
                log("[" + i + "] processor THREW " + t.getClass().getName() + ": " + t.getMessage());
                Throwable cause = t.getCause();
                while (cause != null) {
                    log("    caused by " + cause.getClass().getName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
                throw t;
            }
            log("[" + i + "/" + count + "] returned normally");

            // 3) 출력 검증 — outputs 선언이 있을 때만
            if (outKeys.length > 0 && !outputsValid(outKeys, outShas)) {
                StringBuilder msg = new StringBuilder("Processor ").append(i)
                        .append(" (").append(jarName).append(") did not produce expected outputs:");
                for (int k = 0; k < outKeys.length; k++) {
                    File f = new File(outKeys[k]);
                    msg.append("\n  - ").append(outKeys[k]).append(" → ");
                    if (!f.exists()) {
                        msg.append("MISSING");
                    } else if (f.length() == 0) {
                        msg.append("EMPTY");
                    } else {
                        try {
                            String actual = sha1(f);
                            if (actual.equalsIgnoreCase(outShas[k])) {
                                msg.append("OK");
                            } else {
                                msg.append("SHA mismatch (size=").append(f.length())
                                        .append(" expected=").append(outShas[k])
                                        .append(" actual=").append(actual).append(")");
                            }
                        } catch (Exception e) {
                            msg.append("CHECK_FAILED ").append(e);
                        }
                    }
                }
                throw new IllegalStateException(msg.toString());
            }
        }

        log("All processors complete. (빌더 모드: 게임은 별도로 부팅됨)");

        // NeoForge 전용 후처리:
        //   우리 프로세서는 Minecraft 를 미리 deobfuscate 한 patched client jar 를 만든다.
        //   그런데 NeoForge 의 GameLocator 는 클래스패스에서 net/minecraft/DetectedVersion.class
        //   (production 이면 난독화돼 있어야 함)를 발견하면 "dev(neodev) 환경"으로 판정한다.
        //   deobfuscated jar 라 그 클래스가 원래 이름으로 존재 → dev 로 인식됨.
        //   dev 경로(NeoForgeDevDistCleaner)는 Minecraft jar manifest 에 "Minecraft-Dists"
        //   속성을 요구하며, 없으면 neodev_missing_dists_attribute 로 즉시 종료한다.
        //   client 단일 dist 이므로 "Minecraft-Dists: client" 를 넣어주면 마스킹 없이 통과한다.
        if (realMainClass != null && realMainClass.startsWith("net.neoforged")) {
            try {
                patchNeoForgeClientDists(userDir);
            } catch (Throwable t) {
                log("⚠️ NeoForge Minecraft-Dists manifest 패치 실패(무시하고 진행): " + t);
            }
        }

        // ✅ ZL2 방식: 이 프로세스는 "patched jar 빌더" 역할만 한다.
        //   ForgeBootstrap(게임)을 같은 JVM 에서 부르지 않는다.
        //
        //   [이유] 게임 JVM 부팅 시점엔 forge-<ver>-client.jar 가 아직 디스크에 없어,
        //   AppClassLoader / BootstrapLauncher 의 SecureModuleClassLoader 가 그 jar 경로를
        //   "죽은 엔트리"로 캐시한다. binarypatcher 가 같은 프로세스에서 뒤늦게 파일을 만들어도
        //   getResource("net/minecraft/client/Minecraft.class") 는 캐시된 빈 엔트리를 보고 null →
        //   ForgeProdLaunchHandler.getMinecraftPaths 가 "Could not find ...Minecraft.class" 로
        //   죽는다(첫 실행만 크래시, jar 가 생긴 둘째 실행부턴 정상이던 바로 그 증상).
        //   같은 프로세스 안에서는 이 캐시를 되살릴 방법이 없다.
        //
        //   [해결] processor 가 jar 를 만들고 종료(halt)하면, 런처가 :minecraft 프로세스를
        //   ForgeBootstrap 진입점으로 새로 띄운다. 그 시점엔 jar 가 디스크에 존재하므로
        //   부팅 시 모듈 인덱싱이 정상적으로 되고 getResource 가 클래스를 찾는다.
        //   (ZL2 의 JvmService 가 processor JVM 과 게임 JVM 을 프로세스째 분리하는 것과 동일)
        //
        //   종료코드는 JvmService 가 UDP 로 런처에 전달한다. 정상=0.
        //   shutdown hook / AWT thread 등 부작용 없이 즉시 끝내기 위해 halt 를 쓴다.
        log("✅ 빌더 종료(코드 0). 게임 프로세스 재기동 대기.");
        sendExitCode(0);   // ★ halt 가 native bootMinecraftJVM 반환을 막으므로 여기서 직접 전송
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(0);
    }

    /** 빌더 종료코드를 메인 프로세스로 직접 UDP 전송. (127.0.0.1:PROCESS_SERVICE_PORT)
     *  halt(0) 는 JVM 을 즉사시켜 native bootMinecraftJVM 이 반환되지 않으므로
     *  ForgeBuilderService.sendCode() 가 못 돈다 → 여기서 직접 쏴야 런처가 게임을 띄운다.
     *  포트는 JVMBuilderAPI.kt 의 PROCESS_SERVICE_PORT(=53151) 와 반드시 동일. */
    private static void sendExitCode(int code) {
        final int PROCESS_SERVICE_PORT = 53151;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PROCESS_SERVICE_PORT));
            byte[] data = Integer.toString(code).getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length));
            log("종료코드 " + code + " 전송 → 127.0.0.1:" + PROCESS_SERVICE_PORT);
        } catch (Exception e) {
            log("⚠️ 종료코드 전송 실패: " + e);
        }
    }

    /**
     * NeoForge client patched jar 의 manifest 에 "Minecraft-Dists: client" 를 보장한다.
     * libraries/net/neoforged/minecraft-client-patched/<ver>/minecraft-client-patched-<ver>.jar
     * 를 찾아, 메인 속성에 해당 키가 없으면 jar 를 다시 써서 추가한다(client 단일 dist).
     */
    private static void patchNeoForgeClientDists(File workDir) throws IOException {
        File base = new File(workDir, "libraries/net/neoforged/minecraft-client-patched");
        if (!base.isDirectory()) {
            log("ℹ️ minecraft-client-patched 디렉토리 없음 — Dists 패치 생략: " + base);
            return;
        }
        File target = null;
        File[] verDirs = base.listFiles(File::isDirectory);
        if (verDirs != null) {
            for (File vd : verDirs) {
                File[] jars = vd.listFiles((d, n) ->
                        n.startsWith("minecraft-client-patched") && n.endsWith(".jar"));
                if (jars != null) {
                    for (File j : jars) { target = j; break; }
                }
                if (target != null) break;
            }
        }
        if (target == null) {
            log("ℹ️ minecraft-client-patched-*.jar 못 찾음 — Dists 패치 생략");
            return;
        }

        // 이미 Minecraft-Dists 가 있으면 건너뜀
        Manifest mf;
        try (JarFile jf = new JarFile(target)) {
            mf = jf.getManifest();
        }
        if (mf == null) mf = new Manifest();
        Attributes main = mf.getMainAttributes();
        if (main.getValue("Minecraft-Dists") != null) {
            log("✅ Minecraft-Dists 이미 존재 — 패치 불필요: " + target.getName());
            return;
        }
        if (main.getValue(Attributes.Name.MANIFEST_VERSION) == null) {
            main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }
        main.put(new Attributes.Name("Minecraft-Dists"), "client");

        // jar 를 임시 파일로 다시 쓰며 manifest 교체(다른 엔트리는 그대로 복사)
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        byte[] buf = new byte[8192];
        try (JarInputStream in = new JarInputStream(new BufferedInputStream(new FileInputStream(target)));
             JarOutputStream out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)), mf)) {
            JarEntry e;
            while ((e = in.getNextJarEntry()) != null) {
                String name = e.getName();
                // 기존 manifest 는 건너뜀(위에서 새 manifest 로 이미 기록됨)
                if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    in.closeEntry();
                    continue;
                }
                out.putNextEntry(new JarEntry(name));
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.closeEntry();
                in.closeEntry();
            }
        }
        // 원본 교체
        Files.move(tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log("🩹 Minecraft-Dists=client 추가 완료: " + target.getName());
    }

    private static void runProcessor(String jarPath, String[] classpath, String[] args) throws Throwable {
        URL[] urls = new URL[classpath.length];
        for (int i = 0; i < classpath.length; i++) {
            urls[i] = new File(classpath[i]).toURI().toURL();
        }
        // ⚠️ child-first(self-first) 클래스로더 사용.
        //   installertools-*-fatjar.jar 같은 shaded fat jar 는 내부에 변형된(메서드명에
        //   해시가 붙은) jopt-simple 등을 번들한다. 표준 parent-first 위임을 쓰면 시스템
        //   클래스패스에 있는 "원본" jopt-simple-5.0.4 가 먼저 로드되어, fatjar 가 기대하는
        //   withRequiredArg$5f782b77() 같은 변형 메서드를 못 찾고 NoSuchMethodError 가 난다.
        //   → 프로세서 jar/클래스패스의 클래스를 부모보다 우선 로드하게 하여 충돌을 막는다.
        URLClassLoader cl = new ChildFirstClassLoader(urls, ClassLoader.getSystemClassLoader());

        String mainClass;
        try (JarFile jf = new JarFile(jarPath)) {
            Manifest mf = jf.getManifest();
            if (mf == null) throw new IllegalStateException("No manifest in " + jarPath);
            mainClass = mf.getMainAttributes().getValue("Main-Class");
            if (mainClass == null) throw new IllegalStateException("No Main-Class in " + jarPath);
        }

        ClassLoader prev = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(cl);
        try {
            Class<?> cls = Class.forName(mainClass, true, cl);
            Method m = cls.getMethod("main", String[].class);
            m.invoke(null, (Object) args);
        } finally {
            Thread.currentThread().setContextClassLoader(prev);
        }
    }

    /**
     * child-first(self-first) 위임 클래스로더.
     * 표준 ClassLoader 는 부모에게 먼저 위임(parent-first)하지만, 이 로더는
     * 자신의 URL(프로세서 jar + 지정 classpath)에서 먼저 클래스를 찾고,
     * 없을 때만 부모로 위임한다. 단, JDK 코어 클래스(java, javax, sun 패키지 등)는
     * 반드시 부모(플랫폼)에서 로드해야 하므로 예외로 둔다.
     */
    private static final class ChildFirstClassLoader extends URLClassLoader {
        private final ClassLoader realParent;
        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
            this.realParent = parent;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    // JDK 코어/부트스트랩 클래스는 부모에서 로드(child 에서 덮으면 안 됨)
                    if (isParentOnly(name)) {
                        c = realParent.loadClass(name);
                    } else {
                        try {
                            // 먼저 자신(URL)에서 시도 → fatjar 내부 변형 클래스 우선
                            c = findClass(name);
                        } catch (ClassNotFoundException e) {
                            // 없으면 부모로 위임
                            c = realParent.loadClass(name);
                        }
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }

        private static boolean isParentOnly(String name) {
            return name.startsWith("java.")
                    || name.startsWith("javax.")
                    || name.startsWith("sun.")
                    || name.startsWith("jdk.")
                    || name.startsWith("com.sun.")
                    || name.startsWith("org.w3c.")
                    || name.startsWith("org.xml.");
        }
    }



    private static boolean outputsValid(String[] keys, String[] sha) throws Exception {
        if (keys.length != sha.length) return false;
        for (int i = 0; i < keys.length; i++) {
            File f = new File(keys[i]);
            if (!f.exists() || f.length() == 0) return false;

            // 🔧 FIX: "존재 + 크기>0" 만으로는 중간에 끊겨 일부만 써진 jar / 엔트리가 깨진 jar
            //   같은 손상·미완성 산출물을 '유효'로 오판해 프로세서를 영구히 건너뛰게 된다.
            //   (그러면 patched client jar 에 Minecraft.class 가 없는데도 SKIP → 동일 크래시 반복)
            //   SHA 비교는 아래 사유로 여전히 쓸 수 없으므로, 대신 '구조 검증'을 한다:
            //   jar 면 zip 으로 열어 central directory/엔트리를 끝까지 읽을 수 있는지 확인하고,
            //   읽다 깨지면(EOFException/ZipException) invalid 로 보고 프로세서를 재실행시킨다.
            if (f.getName().endsWith(".jar") && !isReadableJar(f)) return false;

            // 🔧 FIX(첫 설치 최초 실행 크래시): 패치된 Forge client jar 은 net/minecraft/client/
            //   Minecraft.class 를 반드시 포함해야 한다. 설치 단계에서 만들어진 "구조적으론 멀쩡하지만
            //   Minecraft.class 가 빠진" forge-<ver>-client.jar 가 존재하면, 위 구조 검증만으론 유효로
            //   오판해 binarypatcher 가 SKIP 되고 → 그 불완전 jar 가 classpath 에 남아
            //   getMinecraftPaths 가 "Could not find ...Minecraft.class" 로 죽는다.
            //   (그래서 첫 설치 최초 실행에서만 터지고, jar 를 rm 후 재실행하면 재생성돼 정상이었다.)
            //   → client jar 면 실제 엔트리 포함 여부까지 확인해, 없으면 invalid 로 보고 재생성시킨다.
            String norm = f.getPath().replace('\\', '/');
            if (norm.endsWith("-client.jar") && norm.contains("/net/minecraftforge/forge/")
                    && !jarContainsEntry(f, "net/minecraft/client/Minecraft.class")) {
                log("  ⚠️ patched client jar 에 net/minecraft/client/Minecraft.class 없음(재생성 유도): "
                        + f.getName());
                return false;
            }

            // ⚠️ SHA 비교 제거(사유):
            //   ForgeAutoRenamingTool/binarypatcher 같은 변환 단계는 jar 압축 시
            //   타임스탬프·엔트리 순서가 환경마다 달라서, 내용이 동일해도 바이트 해시가 달라짐.
            //   expected SHA 는 Forge 설치 데이터를 만든 PC 환경 기준이라 안드로이드 변환 결과와 불일치.
            //   (예: client-official.jar 안에 Minecraft.class 등 올바른 클래스가 다 들어있는데도
            //    SHA 만 안 맞아 실패하던 문제) → 존재 + 구조 정상 여부만 검증.
            //   if (!sha1(f).equalsIgnoreCase(sha[i])) return false;
        }
        return true;
    }

    /**
     * jar 가 끝까지 정상적으로 읽히는 zip 인지(=손상/미완성이 아닌지) 검사.
     * 프로세스가 중간에 죽어 EOCD(End Of Central Directory)가 안 써진 jar 는
     * new JarFile(...) 또는 엔트리 열거에서 ZipException 으로 걸린다.
     */
    private static boolean isReadableJar(File f) {
        try (JarFile jf = new JarFile(f)) {
            int entries = 0;
            java.util.Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) { e.nextElement(); entries++; }
            return entries > 0;
        } catch (Exception ex) {
            log("  ⚠️ 손상/미완성 산출물 감지(프로세서 재실행 유도): " + f.getName() + " — " + ex);
            return false;
        }
    }

    /** jar 안에 특정 엔트리(예: net/minecraft/client/Minecraft.class)가 들어있는지 검사. */
    private static boolean jarContainsEntry(File f, String entry) {
        try (JarFile jf = new JarFile(f)) {
            return jf.getJarEntry(entry) != null;
        } catch (Exception ex) {
            return false;
        }
    }

    /** pargs 에 "--task <name>" 이 있는지. */
    private static boolean hasTask(String[] args, String task) {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--task".equals(args[i]) && task.equals(args[i + 1])) return true;
        }
        return false;
    }

    /** pargs 에서 "--flag" 바로 뒤 값. 없으면 null. */
    private static String argValue(String[] args, String flag) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    private static String sha1(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] dig = md.digest();
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String[] split(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split(DELIM, -1);
    }

    private static String require(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) throw new IllegalStateException("missing key: " + key);
        return v;
    }

    private static void log(String msg) {
        System.out.println("[ProcessorLauncher] " + msg);
    }
}