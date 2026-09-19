package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

public class ForgeModDirTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "net.minecraftforge.fml.loading.ModDirTransformerDiscoverer";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("visitFile");
        method.insertBefore("{" +
                "    String name = $1.getFileName().toString();\n" +
                "    if (name.endsWith(\".jar\")) {\n" +
                "        System.out.println(\"Loading mod: \" + name);\n" +
                "    }" +
                "}");
    }
}
