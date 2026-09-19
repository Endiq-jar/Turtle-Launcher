package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

public class RandomPatchesTransformer implements BaseTransformer {

    @Override
    public String getTargetClassName() {
        return "com.therandomlabs.randompatches.client.WindowIconHandler";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("setWindowIcon", new CtClass[]{});
        method.setBody("{}");
    }
}