package com.mio.libpatcher.transformer;

import javassist.CtClass;
import javassist.CtMethod;

import java.util.ArrayList;
import java.util.List;

public class SQLTransformer implements BaseTransformer {
    @Override
    public List<String> getTargetClassNames() {
        List<String> list = new ArrayList<>();
        list.add("dh_sqlite.util.OSInfo");
        list.add("org.rfresh.sqlite.util.OSInfo");
        list.add("org.sqlite.util.OSInfo");
        //e4mc
        list.add("io.netty.util.internal.PlatformDependent");
        return list;
    }

    @Override
    public void transform(CtClass clazz) throws Throwable {
        CtMethod method = clazz.getDeclaredMethod("isAndroid");
        method.setBody("{return true;}");
    }
}