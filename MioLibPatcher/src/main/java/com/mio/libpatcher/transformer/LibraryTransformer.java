package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

public class LibraryTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "org.lwjgl.system.Library";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("checkHash");
        method.setBody("{}");
    }
}