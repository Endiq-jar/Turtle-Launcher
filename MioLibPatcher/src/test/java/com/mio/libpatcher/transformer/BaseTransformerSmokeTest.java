package com.mio.libpatcher.transformer;

import com.mio.libpatcher.transformer.oshi.CentralProcessor;
import com.mio.libpatcher.transformer.oshi.ProcessorIdentifierTransformer;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 冒烟测试：用 javassist 在内存中构造模拟目标类，
 * 验证各 transformer 的 transform 不抛异常且能生成字节码。
 */
class BaseTransformerSmokeTest {

    private static CtClass makeClass(String name, String... methodSources) throws Exception {
        CtClass cc = ClassPool.getDefault().makeClass(name);
        for (String source : methodSources) {
            cc.addMethod(CtMethod.make(source, cc));
        }
        return cc;
    }

    private static void assertTransformSucceeds(BaseTransformer transformer, CtClass cc) {
        try {
            transformer.transform(cc);
            cc.toBytecode();
        } catch (Throwable e) {
            throw new AssertionError("transform 失败: " + cc.getName(), e);
        } finally {
            cc.detach();
        }
    }

    @Test
    void tts() throws Exception {
        ClassPool pool = ClassPool.getDefault();
        CtClass dummy = pool.makeClass("com.mojang.text2speech.NarratorDummy");
        dummy.addConstructor(CtNewConstructor.defaultConstructor(dummy));
        CtClass cc = pool.makeClass("com.mojang.text2speech.Narrator");
        cc.addField(CtField.make("public static Object EMPTY;", cc));
        cc.addMethod(CtMethod.make("public static Object getNarrator() { return null; }", cc));
        assertTransformSucceeds(new TTSTransformer(), cc);
    }

    @Test
    void library() throws Exception {
        CtClass cc = makeClass("org.lwjgl.system.Library",
                "public static void checkHash(String lib) { int x = 1; }");
        assertTransformSucceeds(new LibraryTransformer(), cc);
    }

    @Test
    void systemInfo() throws Exception {
        ClassPool pool = ClassPool.getDefault();
        CtClass cc = pool.makeClass("net.vulkanmod.vulkan.SystemInfo");
        cc.addField(CtField.make("static String cpuInfo;", cc));
        cc.makeClassInitializer();
        assertTransformSucceeds(new SystemInfoTransformer(), cc);
    }

    @Test
    void randomPatches() throws Exception {
        CtClass cc = makeClass("com.therandomlabs.randompatches.client.WindowIconHandler",
                "public void setWindowIcon() {}");
        assertTransformSucceeds(new RandomPatchesTransformer(), cc);
    }

    @Test
    void processorIdentifier() throws Exception {
        CtClass cc = makeClass("oshi.hardware.CentralProcessor$ProcessorIdentifier",
                "public String getName() { return null; }");
        assertTransformSucceeds(new ProcessorIdentifierTransformer(), cc);
    }

    @Test
    void centralProcessor() throws Exception {
        CtClass cc = makeClass("oshi.software.os.linux.proc.CentralProcessor",
                "public String getName() { return null; }");
        assertTransformSucceeds(new CentralProcessor(), cc);
    }

    @Test
    void halGetProcessors() throws Exception {
        // oshi 1.x：HAL.getProcessors() 读取 /proc/cpuinfo，失败时返回 null 导致调用方 NPE。
        // 验证替换后返回 availableProcessors 个 CentralProcessor 实例，且 getName 返回系统属性。
        ClassPool pool = new ClassPool(true);
        CtClass proc = pool.makeInterface("oshi.hardware.Processor");
        CtClass cpu = pool.makeClass("oshi.software.os.linux.proc.CentralProcessor");
        cpu.addInterface(proc);
        cpu.addConstructor(CtNewConstructor.make("public CentralProcessor(int procNo) {}", cpu));
        cpu.addMethod(CtMethod.make("public String getName() { return null; }", cpu));
        CtClass hal = pool.makeClass("oshi.software.os.linux.LinuxHardwareAbstractionLayer");
        hal.addMethod(CtMethod.make("public oshi.hardware.Processor[] getProcessors() { return null; }", hal));
        try {
            new CentralProcessor().transform(hal);
        } catch (Throwable e) {
            throw new AssertionError("transform 失败", e);
        }
        Class<?> procClazz = proc.toClass();
        cpu.toClass();
        Class<?> clazz = hal.toClass();
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Object[] arr = (Object[]) clazz.getMethod("getProcessors").invoke(instance);
        assertEquals(Runtime.getRuntime().availableProcessors(), arr.length);
        assertEquals("oshi.software.os.linux.proc.CentralProcessor", arr[0].getClass().getName());
    }

    @Test
    void sodiumLike() throws Exception {
        CtClass cc = makeClass("net.caffeinemc.mods.sodium.client.compatibility.checks.PostLaunchChecks",
                "public boolean isUsingPojavLauncher() { return true; }");
        assertTransformSucceeds(new SodiumLikeModTransformer(), cc);
    }

    @Test
    void sql() throws Exception {
        CtClass cc = makeClass("dh_sqlite.util.OSInfo",
                "public static boolean isAndroid() { return false; }");
        assertTransformSucceeds(new SQLTransformer(), cc);
    }

    @Test
    void fabricLoader() throws Exception {
        CtClass cc = makeClass("net.fabricmc.loader.impl.gui.FabricGuiEntry",
                "public static void displayError(String message, String details) {}");
        assertTransformSucceeds(new FabricLoaderTransformer(), cc);
    }

    @Test
    void forgeModDir() throws Exception {
        CtClass cc = makeClass("net.minecraftforge.fml.loading.ModDirTransformerDiscoverer",
                "public void visitFile(java.nio.file.Path file) {}");
        assertTransformSucceeds(new ForgeModDirTransformer(), cc);
    }

    @Test
    void create() throws Exception {
        CtClass cc = makeClass("com.simibubi.create.compat.pojav.PojavChecker",
                "public static void init() {}");
        assertTransformSucceeds(new CreateTransformer(), cc);
    }

    @Test
    void sableRapier() throws Exception {
        CtClass cc = makeClass("dev.ryanhcode.sable.physics.impl.rapier.Rapier3D",
                "public void loadLibrary() { java.lang.System.load(\"lib.so\"); }");
        assertTransformSucceeds(new SableRapierLibTransformer(), cc);
    }

    @Test
    void veilImGui() throws Exception {
        CtClass cc = makeClass("foundry.veil.impl.client.imgui.VeilImGuiImpl",
                "public void setImGuiPath(String path) {}");
        assertTransformSucceeds(new VeilImGuiTransformer(), cc);
    }

    @Test
    void axiom() throws Exception {
        ClassPool pool = ClassPool.getDefault();
        CtClass cc = pool.makeClass("imgui.moulberry92.ImGui");
        cc.addField(CtField.make("static int dummy;", cc));
        CtConstructor clinit = cc.makeClassInitializer();
        clinit.insertBefore("java.lang.System.load(\"abc\");");
        assertTransformSucceeds(new AxiomTransformer(), cc);
    }

    @Test
    void alc10() throws Exception {
        CtClass cc = makeClass("org.lwjgl.openal.ALC10",
                "public static void alcGetCurrentContext() {}");
        assertTransformSucceeds(new ALC10Transformer(), cc);
    }

    @Test
    void asm() throws Exception {
        // 补丁默认关闭，transform 应安全跳过
        CtClass cc = ClassPool.getDefault().makeClass("org.objectweb.asm.ClassVisitor");
        assertTransformSucceeds(new ASMTransformer(), cc);
    }

    @Test
    void asmWithPropertyOverride() throws Exception {
        // 启动器可通过系统属性显式启用补丁，此时应安全执行
        System.setProperty("miolibpatcher.asmBackport", "true");
        try {
            CtClass cc = ClassPool.getDefault().makeClass("org.objectweb.asm.ClassVisitor");
            assertTransformSucceeds(new ASMTransformer(), cc);
        } finally {
            System.clearProperty("miolibpatcher.asmBackport");
        }
    }
}
