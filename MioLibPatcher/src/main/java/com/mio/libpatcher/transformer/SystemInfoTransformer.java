package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtConstructor;

public class SystemInfoTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "net.vulkanmod.vulkan.SystemInfo";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtConstructor constructor = clazz.getClassInitializer();
        constructor.setBody("{cpuInfo = System.getProperty(\"cpu.name\",\"\");}");
    }
}