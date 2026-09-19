package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

public class FabricLoaderTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "net.fabricmc.loader.impl.gui.FabricGuiEntry";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod[] methods = clazz.getDeclaredMethods("displayError");
        if (methods != null) {
            for (CtMethod method : methods) {
                method.setBody("{" +
                        "System.out.println(\"start net.fabricmc.loader.impl.gui.FabricGuiEntry.displayError\");" +
                        "System.out.println($1);" +
                        "System.out.println($2);" +
                        "System.out.println(\"end net.fabricmc.loader.impl.gui.FabricGuiEntry.displayError\");" +
                        " System.exit(1);" +
                        "}");
            }
        }
    }
}