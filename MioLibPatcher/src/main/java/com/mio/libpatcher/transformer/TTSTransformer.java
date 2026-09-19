package com.mio.libpatcher.transformer;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtMethod;

public class TTSTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "com.mojang.text2speech.Narrator";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("getNarrator");
        try {
            method.setBody("{ return new com.mojang.text2speech.NarratorDummy(); }");
        } catch (CannotCompileException e) {
            method.setBody("{ return EMPTY; }");
        }
    }
}