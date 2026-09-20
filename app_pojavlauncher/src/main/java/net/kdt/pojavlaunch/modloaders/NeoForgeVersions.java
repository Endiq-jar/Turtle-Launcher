package net.kdt.pojavlaunch.modloaders;

import net.kdt.pojavlaunch.utils.DownloadUtils;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

/**
 * NeoForge version list helper.
 *
 * Extracted from the upstream Amethyst NeoForgeInstallFragment during the Turtle
 * Launcher migration (the fragment UI is replaced by the Turtle download center,
 * but the version list logic stays part of the modpack/install API).
 */
public final class NeoForgeVersions {
    private static final String NEOFORGE_METADATA_URL =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";

    private NeoForgeVersions() {}

    public static List<String> downloadNeoForgeVersions() {
        SAXParser saxParser;
        try {
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            saxParser = parserFactory.newSAXParser();
        } catch (SAXException | ParserConfigurationException e) {
            e.printStackTrace();
            // if we cant make a parser we might as well not even try to parse anything
            return null;
        }
        try {
            return DownloadUtils.downloadStringCached(NEOFORGE_METADATA_URL, "neoforge_versions", input -> {
                try {
                    ForgeVersionListHandler handler = new ForgeVersionListHandler();
                    saxParser.parse(new InputSource(new StringReader(input)), handler);
                    return handler.getVersions();
                    // IOException is present here StringReader throws it only if the parser called close()
                    // sooner than needed, which is a parser issue and not an I/O one
                } catch (SAXException | IOException e) {
                    throw new DownloadUtils.ParseException(e);
                }
            });
        } catch (DownloadUtils.ParseException | IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
