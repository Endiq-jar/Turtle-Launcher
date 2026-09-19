package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

public class SmoothFontTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "bre.smoothfont.FontUtils";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("getMaxFontSizeIndex");
        method.setBody("{ return 7; }");
    }
}