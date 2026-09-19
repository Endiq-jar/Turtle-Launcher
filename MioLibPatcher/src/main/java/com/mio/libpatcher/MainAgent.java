package com.mio.libpatcher;

import com.mio.libpatcher.transformer.*;
import com.mio.libpatcher.transformer.oshi.CentralProcessor;
import com.mio.libpatcher.transformer.oshi.ProcessorIdentifierTransformer;
import com.mio.libpatcher.util.LogUtil;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.List;

public class MainAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        LogUtil.info("MioPatcher is running!");
        addTransformer(inst, false);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        addTransformer(inst, true);
    }

    private static void addTransformer(Instrumentation inst, boolean isAgentmain) {
        List<BaseTransformer> transformers = createTransformers();
        List<String> targetClasses = new ArrayList<>();
        transformers.forEach(baseTransformer -> {
            inst.addTransformer(baseTransformer, true);
            if (isAgentmain) {
                targetClasses.addAll(baseTransformer.getTargetClassNames());
            }
        });
        if (isAgentmain) {
            retransformLoadedClasses(inst, targetClasses);
        }
    }

    private static void retransformLoadedClasses(Instrumentation inst, List<String> targetClasses) {
        for (Class<?> aClass : inst.getAllLoadedClasses()) {
            if (!targetClasses.contains(aClass.getName())) {
                continue;
            }
            LogUtil.info("Transform class:" + aClass.getName());
            try {
                inst.retransformClasses(aClass);
            } catch (UnmodifiableClassException e) {
                LogUtil.error("Failed to retransform class: " + aClass.getName(), e);
            }
        }
    }

    private static List<BaseTransformer> createTransformers() {
        List<BaseTransformer> transformers = new ArrayList<>();
        transformers.add(new TTSTransformer());
        transformers.add(new LibraryTransformer());
        transformers.add(new SystemInfoTransformer());
        transformers.add(new RandomPatchesTransformer());
        transformers.add(new ProcessorIdentifierTransformer());
        transformers.add(new CentralProcessor());
        transformers.add(new SodiumLikeModTransformer());
        transformers.add(new SQLTransformer());
        transformers.add(new FabricLoaderTransformer());
        transformers.add(new ForgeModDirTransformer());
        transformers.add(new CreateTransformer());
        transformers.add(new SableRapierLibTransformer());
        transformers.add(new VeilImGuiTransformer());
        transformers.add(new AxiomTransformer());
        transformers.add(new ALC10Transformer());
        transformers.add(new ASMTransformer());
        transformers.add(new SmoothFontTransformer());
        return transformers;
    }

}
