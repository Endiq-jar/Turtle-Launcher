package com.mio.libpatcher.transformer;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.io.File;

public class AxiomTransformer implements BaseTransformer {

    @Override
    public String getTargetClassName() {
        return "imgui.moulberry92.ImGui";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtConstructor constructor = clazz.getClassInitializer();
        constructor.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall m) throws CannotCompileException {
                if (m.getClassName().equals("java.lang.System")
                        && m.getMethodName().equals("load")) {
                    m.replace(
                            "{ " +
                                    "   String path = System.getProperty(\"imgui.library.path\");" +
                                    "   String name = System.getProperty(\"imgui.library.name\");" +
                                    "   if (path != null && name != null) {" +
                                    "          System.load(new java.io.File(path, name).getAbsolutePath());" +
                                    "   } else {" +
                                    "          $_ = $proceed($$);" +
                                    "   }" +
                                    "}"
                    );
                }
            }
        });
    }
}