package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

import java.util.ArrayList;
import java.util.List;

public class SodiumLikeModTransformer implements BaseTransformer {
    @Override
    public List<String> getTargetClassNames() {
        List<String> nameList = new ArrayList<>();
        nameList.add("net.caffeinemc.mods.sodium.client.compatibility.checks.PostLaunchChecks");
        nameList.add("me.jellysquid.mods.sodium.client.compatibility.checks.PostLaunchChecks");
        nameList.add("org.embeddedt.embeddium.impl.compatibility.checks.LateDriverScanner");
        nameList.add("me.jellysquid.mods.sodium.client.compatibility.checks.LateDriverScanner");
        return nameList;
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("isUsingPojavLauncher");
        method.setBody("{return false;}");
    }
}
