package com.mio.libpatcher.util;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class LogUtil {
    private static final PrintStream out = System.out;

    public static void info(String str) {
        out.println("[MioLibPatcher/INFO]: " + str);
    }

    public static void error(String str) {
        out.println("[MioLibPatcher/ERROR]: " + str);
    }

    public static void error(String str, Throwable e) {
        out.println("[MioLibPatcher/ERROR]: " + str);
        out.println(getStackTrace(e));
    }

    private static String getStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
