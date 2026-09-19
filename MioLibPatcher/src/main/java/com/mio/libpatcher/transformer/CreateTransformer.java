package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

public class CreateTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "com.simibubi.create.compat.pojav.PojavChecker";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("init");
        method.setBody("{}");
    }
}
