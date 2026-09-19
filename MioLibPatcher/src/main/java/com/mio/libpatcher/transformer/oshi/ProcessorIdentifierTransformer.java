package com.mio.libpatcher.transformer.oshi;

import com.mio.libpatcher.transformer.BaseTransformer;
import javassist.CtClass;
import javassist.CtMethod;

public class ProcessorIdentifierTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "oshi.hardware.CentralProcessor$ProcessorIdentifier";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("getName");
        method.setBody("{return System.getProperty(\"cpu.name\",\"\");}");
    }
}