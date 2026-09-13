package net.kdt.pojavlaunch.multirt;

import static org.apache.commons.io.FileUtils.listFiles;

import android.system.Os;

import com.kdt.mcgui.ProgressLayout;
import com.endiq.turtlelauncher.R;
import com.endiq.turtlelauncher.feature.log.Logging;
import com.endiq.turtlelauncher.feature.unpack.Jre;
import com.endiq.turtlelauncher.utils.path.PathManager;
import com.endiq.turtlelauncher.utils.stringutils.SortStrings;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public class MultiRTUtils {

    private static final HashMap<String,Runtime> sCache = new HashMap<>();

    private static final File RUNTIME_FOLDER = new File(PathManager.DIR_MULTIRT_HOME);
    private static final String JAVA_VERSION_STR = "JAVA_VERSION=\"";
    private static final String OS_ARCH_STR = "OS_ARCH=\"";

    public static List<Runtime> getRuntimes() {
        if (!RUNTIME_FOLDER.exists() && !RUNTIME_FOLDER.mkdirs()) {
            throw new RuntimeException("Failed to create runtime directory");
        }

        ArrayList<Runtime> runtimes = new ArrayList<>();
        File[] files = RUNTIME_FOLDER.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) continue;

                String fileName = file.getName();
                Runtime runtime = read(fileName);

                for (Jre jre : Jre.getEntries()) {
                    if (jre.getJreName().equals(fileName)) {
                        runtime.isProvidedByLauncher = true;
                        break;
                    }
                }

                runtimes.add(runtime);
            }
        } else {
            throw new RuntimeException("The runtime directory does not exist");
        }

        runtimes.sort((o1, o2) -> {
            String thisRuntime = o1.versionString != null ? o1.versionString : o1.name;
            String otherRuntime = o2.versionString != null ? o2.versionString : o2.name;
            return -SortStrings.compareClassVersions(thisRuntime, otherRuntime);
        });

        return runtimes;
    }

    public static String getExactJreName(int majorVersion) {
        List<Runtime> runtimes = getRuntimes();
        for(Runtime r : runtimes)
            if(r.javaVersion == majorVersion)return r.name;

        return null;
    }

    public static String getNearestJreName(int majorVersion) {
        List<Runtime> runtimes = getRuntimes();
        MathUtils.RankedValue<Runtime> nearestRankedRuntime = MathUtils.findNearestPositive(majorVersion, runtimes, (runtime)->runtime.javaVersion);
        if(nearestRankedRuntime == null) return null;
        Runtime nearestRuntime = nearestRankedRuntime.value;
        if(nearestRuntime == null) return null;
        return nearestRuntime.name;
    }

    public static void installRuntimeNamed(String nativeLibDir, InputStream runtimeInputStream, String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/"+name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
        unpack200(nativeLibDir,RUNTIME_FOLDER + "/" + name);
        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);
        read(name);
    }

    public static void postPrepare(String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/" + name);
        if(!dest.exists()) return;
        Runtime runtime = read(name);
        String libFolder = "lib";
        if(new File(dest,libFolder + "/" + runtime.arch).exists()) libFolder = libFolder + "/" + runtime.arch;
        File ftIn = new File(dest, libFolder + "/libfreetype.so.6");
        File ftOut = new File(dest, libFolder + "/libfreetype.so");
        if (ftIn.exists() && (!ftOut.exists() || ftIn.length() != ftOut.length())) {
            if(!ftIn.renameTo(ftOut)) throw new IOException("Failed to rename freetype");
        }

        // Refresh libraries
        copyDummyNativeLib("libawt_xawt.so", dest, libFolder);
    }

    public static void installRuntimeNamedBinpack(InputStream universalFileInputStream, InputStream platformBinsInputStream, String name, String binpackVersion) throws IOException {
        File dest = new File(RUNTIME_FOLDER,"/"+name);
        if(dest.exists()) FileUtils.deleteDirectory(dest);
        installRuntimeNamedNoRemove(universalFileInputStream,dest);
        installRuntimeNamedNoRemove(platformBinsInputStream,dest);

        unpack200(PathManager.DIR_NATIVE_LIB,RUNTIME_FOLDER + "/" + name);

        File binpack_verfile = new File(RUNTIME_FOLDER,"/"+name+"/turtle_version");
        FileOutputStream fos = new FileOutputStream(binpack_verfile);
        fos.write(binpackVersion.getBytes());
        fos.close();

        ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);

        forceReread(name);
    }


    public static String readInternalRuntimeVersion(String name) {
        File versionFile = new File(RUNTIME_FOLDER,"/" + name + "/turtle_version");
        try {
            if (versionFile.exists()) {
                return Tools.read(versionFile.getAbsolutePath());
            }else{
                return null;
            }
        }catch (IOException e) {
            Logging.e("ReadInternalRuntimeVersion", Tools.printToString(e));
            return null;
        }
    }

    public static long readLastUpdateTime(String name) {
        File lastUpdateTimeFile = new File(RUNTIME_FOLDER, name+"/last_check_time");
        if(!lastUpdateTimeFile.exists()) return -1;
        try {
            return Long.parseLong(net.kdt.pojavlaunch.Tools.read(lastUpdateTimeFile.getAbsolutePath()).trim());
        }catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    public static void writeLastUpdateTime(String name, long time) {
        File lastUpdateTimeFile = new File(RUNTIME_FOLDER, name+"/last_check_time");
        try {
            java.nio.file.Files.write(lastUpdateTimeFile.toPath(), Long.toString(time).getBytes());
        }catch (IOException ignored) {}
    }

    public static void removeRuntimeNamed(String name) throws IOException {
        File dest = new File(RUNTIME_FOLDER, name);
        if(dest.exists()) {
            FileUtils.deleteDirectory(dest);
            sCache.remove(name);
        }
    }

    public static File getRuntimeHome(String name) {
        File dest = new File(RUNTIME_FOLDER, name);
        Logging.i("MiltiRTUitls", "Dest exists? "+dest.exists());
        if((!dest.exists()) || MultiRTUtils.forceReread(name).versionString == null) throw new RuntimeException("Selected runtime is broken!");
        return dest;
    }

    public static Runtime forceReread(String name) {
        sCache.remove(name);
        return read(name);
    }

    public static Runtime read(String name) {
        Runtime returnRuntime = sCache.get(name);
        if(returnRuntime != null) return returnRuntime;
        File release = new File(RUNTIME_FOLDER,name+"/release");
        if(!release.exists()) {
            return new Runtime(name);
        }
        try {
            String content = Tools.read(release.getAbsolutePath());
            String javaVersion = Tools.extractUntilCharacter(content, JAVA_VERSION_STR, '"');
            String osArch = Tools.extractUntilCharacter(content, OS_ARCH_STR, '"');
            if(javaVersion != null && osArch != null) {
                // JAVA_VERSION from a runtime's release file is free-form text: "1" alone
                // crashed the old code with ArrayIndexOutOfBounds ([1] missing), and
                // early-access builds ("21-ea", "25+36") crashed it with
                // NumberFormatException. Fall back to an unknown-version Runtime so a
                // weird release file can't kill the launcher.
                int javaVersionInt = parseJavaMajorVersion(javaVersion);
                if (javaVersionInt > 0) {
                    returnRuntime = new Runtime(name, javaVersion, osArch, javaVersionInt);
                } else {
                    Logging.w("ReadRuntimeVersion", "Unparseable JAVA_VERSION \"" + javaVersion + "\" in runtime \"" + name + "\"");
                    returnRuntime = new Runtime(name);
                }
            }else{
                returnRuntime =  new Runtime(name);
            }
        }catch(IOException e) {
            returnRuntime =  new Runtime(name);
        }
        sCache.put(name, returnRuntime);
        return returnRuntime;
    }

    /**
     * Extracts the major Java version from a free-form JAVA_VERSION string:
     * "1.8.0_392" -&gt; 8, "17.0.1" -&gt; 17, "21-ea"/"25+36" -&gt; 21/25.
     * Returns -1 when no leading number can be found.
     */
    private static int parseJavaMajorVersion(String javaVersion) {
        if (javaVersion == null) return -1;
        String[] javaVersionSplit = javaVersion.split("\\.");
        String majorPart;
        if (javaVersionSplit.length > 0 && javaVersionSplit[0].equals("1")) {
            // Old 1.x scheme: the major version is the second segment ("1.8" -> 8).
            if (javaVersionSplit.length < 2) return -1;
            majorPart = javaVersionSplit[1];
        } else if (javaVersionSplit.length > 0) {
            majorPart = javaVersionSplit[0];
        } else {
            return -1;
        }
        int end = 0;
        while (end < majorPart.length() && Character.isDigit(majorPart.charAt(end))) end++;
        if (end == 0) return -1;
        try {
            return Integer.parseInt(majorPart.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Unpacks all .pack files into .jar Serves only for java 8, as java 9 brought project jigsaw
     * @param nativeLibraryDir The native lib path, required to execute the unpack200 binary
     * @param runtimePath The path to the runtime to walk into
     */
    private static void unpack200(String nativeLibraryDir, String runtimePath) {

        File basePath = new File(runtimePath);
        Collection<File> files = listFiles(basePath, new String[]{"pack"}, true);

        File workdir = new File(nativeLibraryDir);

        ProcessBuilder processBuilder = new ProcessBuilder().directory(workdir);
        for(File jarFile : files){
            try{
                Process process = processBuilder.command("./libunpack200.so", "-r", jarFile.getAbsolutePath(), jarFile.getAbsolutePath().replace(".pack", "")).start();
                process.waitFor();
            }catch (InterruptedException | IOException e) {
                Logging.e("MULTIRT", "Failed to unpack the runtime !");
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void copyDummyNativeLib(String name, File dest, String libFolder) throws IOException {
        File fileLib = new File(dest, "/"+libFolder + "/" + name);
        FileInputStream is = new FileInputStream(new File(PathManager.DIR_NATIVE_LIB, name));
        FileOutputStream os = new FileOutputStream(fileLib);
        IOUtils.copy(is, os);
        is.close();
        os.close();
    }

    private static void installRuntimeNamedNoRemove(InputStream runtimeInputStream, File dest) throws IOException {
        uncompressTarXZ(runtimeInputStream,dest);
        runtimeInputStream.close();
    }

    private static void uncompressTarXZ(final InputStream tarFileInputStream, final File dest) throws IOException {
        net.kdt.pojavlaunch.utils.FileUtils.ensureDirectory(dest);

        byte[] buffer = new byte[8192];
        TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new XZCompressorInputStream(tarFileInputStream)
        );
        TarArchiveEntry tarEntry = tarIn.getNextTarEntry();
        // tarIn is a TarArchiveInputStream
        while (tarEntry != null) {

            final String tarEntryName = tarEntry.getName();
            // publishProgress(null, "Unpacking " + tarEntry.getName());
            ProgressLayout.setProgress(ProgressLayout.UNPACK_RUNTIME, 100, R.string.generic_unpacking, tarEntryName);

            File destPath = new File(dest, tarEntry.getName());
            net.kdt.pojavlaunch.utils.FileUtils.ensureParentDirectory(destPath);
            if (tarEntry.isSymbolicLink()) {
                try {
                    File linkFile = new File(dest, tarEntry.getName());
                    net.kdt.pojavlaunch.utils.FileUtils.ensureParentDirectory(linkFile);
                    if (linkFile.exists()) linkFile.delete();
                    String linkTarget = tarEntry.getLinkName();
                    if (!linkTarget.startsWith("/")) {
                        linkTarget = new File(linkFile.getParentFile(), linkTarget).getCanonicalPath();
                    }
                    Os.symlink(linkTarget, linkFile.getAbsolutePath());
                } catch (Throwable e) {
                    Logging.e("MultiRT", Tools.printToString(e));
                }

            } else if (tarEntry.isDirectory()) {
                net.kdt.pojavlaunch.utils.FileUtils.ensureDirectory(destPath);
            } else if (!destPath.exists() || destPath.length() != tarEntry.getSize()) {
                FileOutputStream os = new FileOutputStream(destPath);
                IOUtils.copyLarge(tarIn, os, buffer);
                os.close();
                // Runtime tarballs mark bin/java and friends executable (unix mode bits),
                // but this extraction never propagated that - every extracted file landed
                // non-executable. Invisible until something actually tries to *exec* one of
                // these as a subprocess (the embedded-JVM launch path only dlopen()s the
                // .so libs in-process, so it never hit this). Fabric Loader's splash screen
                // spawning bin/java directly is what surfaced it: "Permission denied".
                int mode = tarEntry.getMode();
                if ((mode & 0111) != 0) {
                    destPath.setExecutable(true, false);
                }
            }
            tarEntry = tarIn.getNextTarEntry();
        }
        tarIn.close();
    }
}
