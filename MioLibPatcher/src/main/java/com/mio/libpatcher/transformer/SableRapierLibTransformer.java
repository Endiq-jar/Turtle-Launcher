package com.mio.libpatcher.transformer;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public class SableRapierLibTransformer implements BaseTransformer {

    @Override
    public String getTargetClassName() {
        return "dev.ryanhcode.sable.physics.impl.rapier.Rapier3D";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        // User override
        String override = System.getProperty("miolibpatcher.sablerapier");
        if (override != null && !Boolean.parseBoolean(override)) return;

        // If there is no set path, this transform is useless, assume there is another method mixin
        // and disable this transform so we don't break mods that provide rapier natives.
        if (System.getProperty("sable_rapier_path") == null) return;
        CtMethod loadLibraryMethod = clazz.getDeclaredMethod("loadLibrary");

        loadLibraryMethod.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                if (m.getClassName().equals("java.lang.System")
                        && m.getMethodName().equals("load")) {

                    m.replace(
                            "{ " +
                                    "   String libPath = java.lang.System.getProperty(\"sable_rapier_path\");" +
                                    "   if (libPath != null) {" +
                                    "       java.lang.System.load(new java.io.File(libPath).getAbsolutePath());" +
                                    "   } else {" +
                                    "       $_ = $proceed($$);" +
                                    "   }" +
                                    "}"
                    );
                }
            }
        });
    }
}