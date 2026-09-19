package com.mio.libpatcher.transformer;

import java.util.ArrayList;
import java.util.List;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.Opcode;

/**
 * For ASM 4.1 and above, it properly checks if proper Opcode is passed, but Applied Energistics 1
 * passes something completely invalid while using earlier versions. So backport the bug in case
 * some other smart guy mod also does something silly.
 */
public class ASMTransformer implements BaseTransformer {

    private final boolean asm504Enabled;

    /**
     * 补丁默认关闭，仅当启动器显式指定 -Dmiolibpatcher.asmBackport=true 时启用。
     * 在构造器（premain 阶段）读取系统属性并缓存结果；transform 回调路径禁止任何
     * 类加载/探测操作（回调发生在 JVM 定义类的过程中，曾因 Class.forName 重入触发
     * ClassCircularityError，见 49e4a6e 回归）。
     */
    public ASMTransformer() {
        asm504Enabled = Boolean.parseBoolean(System.getProperty("miolibpatcher.asmBackport", "false"));
    }

    /**
     * @return Exhaustive list of all 5 visitor classes in ASM 5.0.4
     */
    @Override
    public List<String> getTargetClassNames() {
        List<String> list = new ArrayList<>();
        /*
        可选补丁：ASM 5.0.4 覆盖版（forge 未自带）中 visitor 构造器会拒绝旧版本传入的非法
        Opcode，回退兼容旧版本模组的错误用法（如 Applied Energistics 1）。
        默认关闭，仅当启动器显式指定 -Dmiolibpatcher.asmBackport=true 时启用。
         */
        if (!asm504Enabled) return list;
        list.add("org.objectweb.asm.ClassVisitor");
        list.add("org.objectweb.asm.MethodVisitor");
        list.add("org.objectweb.asm.FieldVisitor");
        list.add("org.objectweb.asm.AnnotationVisitor");
        list.add("org.objectweb.asm.signature.SignatureVisitor");
        return list;
    }

    /**
     * WARNING: Should only be used on ASM 5.0.4
     * Enable it via -Dmiolibpatcher.asmBackport=true (disabled by default).
     * @throws CannotCompileException If used on the wrong class.
     */
    @Override
    public void transform(CtClass clazz) throws CannotCompileException {
        if (!asm504Enabled) return;
        for (CtConstructor ctor : clazz.getDeclaredConstructors()) {
            if (!ctor.isClassInitializer()) {
                CodeIterator it = ctor.getMethodInfo().getCodeAttribute().iterator();
                // This is a bit janky, but it works for all five classes without manually
                // setting their Java source bodies.
                /*
                   What this does:
                     public ClassVisitor(final int api, final ClassVisitor cv) {
                        if (api != Opcodes.ASM4) {
                            throw new IllegalArgumentException(); // NOPs this part
                        }
                        this.api = api;
                        this.cv = cv; // This is unique to ClassVisitor
                     }
                   "throw new IllegalArgumentException()" compiles to this bytecode:
                     new
                     dup
                     invokespecial
                     athrow
                 */
                while (it.hasNext()) {
                    try {
                        int pos = it.next();

                        if (it.byteAt(pos) != Opcode.NEW) continue;

                        int dup = it.next();
                        if (it.byteAt(dup) != Opcode.DUP) continue;

                        int invokespecial = it.next();
                        if (it.byteAt(invokespecial) != Opcode.INVOKESPECIAL) continue;

                        int athrow = it.next();
                        if (it.byteAt(athrow) != Opcode.ATHROW) continue;


                        // NOP the entire four instructions.
                        // I checked, we can assume at least this much of all five classes.
                        for (int i = pos; i < athrow + 1; ++i) {
                            it.writeByte(Opcode.NOP, i);
                        }
                        break;
                    } catch (BadBytecode e) {
                        throw new CannotCompileException(
                                "Failed to parse bytecode while searching for the" +
                                        "IllegalArgumentException pattern, is this ASM 5.0.4?", e
                        );
                    }
                }
            }
        }
    }
}
