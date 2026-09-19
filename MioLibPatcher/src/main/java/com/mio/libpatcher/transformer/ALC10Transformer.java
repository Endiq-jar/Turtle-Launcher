package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

public class ALC10Transformer implements BaseTransformer {

    @Override
    public String getTargetClassName() {
        return "org.lwjgl.openal.ALC10";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        if (Boolean.parseBoolean(System.getProperty("miolibpatcher.alc10", "false"))) {
            CtMethod oldMethod = clazz.getDeclaredMethod("alcGetCurrentContext");
            CtMethod newMethod = CtMethod.make(
                    "public static org.lwjgl.openal.ALCcontext alcGetCurrentContext() {" +
                            "   return alcContext;" +
                            "}",
                    clazz);
            clazz.removeMethod(oldMethod);
            clazz.addMethod(newMethod);
        }
    }
}
