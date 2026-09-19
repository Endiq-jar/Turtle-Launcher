package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;

public class VeilImGuiTransformer implements BaseTransformer {
    @Override
    public String getTargetClassName() {
        return "foundry.veil.impl.client.imgui.VeilImGuiImpl";
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        // 部分版本没有 setImGuiPath 方法，找不到时保持原样即可（多版本兼容）
        try {
            CtMethod loadLibraryMethod = clazz.getDeclaredMethod("setImGuiPath");
            loadLibraryMethod.setBody("{}");
        } catch (NotFoundException ignored) {
        }
    }
}
