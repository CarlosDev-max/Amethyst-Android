package net.kdt.pojavlaunch.servers;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a self-contained Minecraft server folder from the official server jar.
 *
 * The bundle runs on the device itself through {@link ServerRunner} (the same in-process
 * JVM mechanism the game uses, since Android 10+ forbids executing files from app storage),
 * and can equally be copied to another machine via the generated start scripts.
 */
public class ServerBundleGenerator {
    public static final String SERVER_JAR_NAME = "server.jar";
    public static final String METADATA_FILE_NAME = "amethyst-server.properties";

    private ServerBundleGenerator() {}

    /** Everything the generated server.properties and start scripts need. */
    public static class Config {
        public String sanitizedName;
        public String motd;
        public int port = 25565;
        public int maxPlayers = 20;
        public boolean onlineMode = true;
        public String gamemode = "survival";
        public String difficulty = "normal";
        public int memoryMb = 2048;
        public int javaMajorVersion = 17;
        public int rconPort = 25575;
        public String rconPassword;
    }

    public static File getServersRoot() {
        return new File(Tools.DIR_GAME_HOME, "servers");
    }

    public static File getBundleDirectory(String sanitizedName) {
        return new File(getServersRoot(), sanitizedName);
    }

    /** @return a filesystem-safe folder name, or null if nothing usable was left */
    public static String sanitizeName(String rawName) {
        if (rawName == null) return null;
        String sanitized = rawName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) return null;
        return sanitized;
    }

    /**
     * Server jars are only published from 1.7 onwards, and loader versions such as Forge or
     * Fabric profiles inherit them instead of shipping their own.
     */
    public static MinecraftClientInfo getServerDownload(JMinecraftVersionList.Version fullVersion) {
        if (fullVersion == null || fullVersion.downloads == null) return null;
        return fullVersion.downloads.get("server");
    }

    /**
     * Downloads the server jar (skipping it when an identical copy is already present) and
     * writes the configuration and start scripts next to it.
     * @return the bundle directory
     */
    public static File generate(Config config, MinecraftClientInfo serverDownload) throws IOException {
        File bundleDir = getBundleDirectory(config.sanitizedName);
        net.kdt.pojavlaunch.utils.FileUtils.ensureDirectory(bundleDir);

        if (config.rconPassword == null || config.rconPassword.isEmpty()) {
            config.rconPassword = newRconPassword();
        }
        if (config.rconPort < 1 || config.rconPort > 65535 || config.rconPort == config.port) {
            config.rconPort = config.port == 25575 ? 25576 : 25575;
        }

        File serverJar = new File(bundleDir, SERVER_JAR_NAME);
        DownloadUtils.ensureSha1(serverJar, serverDownload.sha1, () -> {
            DownloadUtils.downloadFileMonitored(serverDownload.url, serverJar, null, (current, total) -> {
                int usedTotal = total > 0 ? total : serverDownload.size;
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_SERVER,
                        usedTotal > 0 ? (int) (current * 100L / usedTotal) : 0,
                        R.string.local_server_dl_progress,
                        current / 1048576f, usedTotal / 1048576f);
            });
            return null;
        });
        ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_SERVER);

        writeText(new File(bundleDir, "eula.txt"),
                "#By changing the setting below to TRUE you are indicating your agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA).\n"
                        + "eula=true\n");

        writeText(new File(bundleDir, "server.properties"), buildServerProperties(config));
        writeText(new File(bundleDir, METADATA_FILE_NAME), buildMetadata(config));
        writeText(new File(bundleDir, "start.sh"), buildStartSh(config));
        writeText(new File(bundleDir, "start.bat"), buildStartBat(config));
        writeText(new File(bundleDir, "README.txt"), buildReadme(config));

        File startScript = new File(bundleDir, "start.sh");
        startScript.setExecutable(true, false);
        return bundleDir;
    }

    private static String buildServerProperties(Config config) {
        StringBuilder properties = new StringBuilder();
        properties.append("#Generated by Amethyst\n");
        appendProperty(properties, "motd", singleLine(config.motd));
        appendProperty(properties, "server-port", config.port);
        appendProperty(properties, "max-players", config.maxPlayers);
        appendProperty(properties, "online-mode", config.onlineMode);
        appendProperty(properties, "gamemode", config.gamemode);
        appendProperty(properties, "difficulty", config.difficulty);
        appendProperty(properties, "level-name", "world");
        appendProperty(properties, "view-distance", 10);
        appendProperty(properties, "simulation-distance", 8);
        appendProperty(properties, "spawn-protection", 16);
        appendProperty(properties, "white-list", false);
        appendProperty(properties, "sync-chunk-writes", false);
        // Required for offline-mode servers on 1.19+, otherwise clients are kicked.
        appendProperty(properties, "enforce-secure-profile", false);
        // RCON is how Amethyst's console sends commands to a server running on-device.
        appendProperty(properties, "enable-rcon", true);
        appendProperty(properties, "rcon.port", config.rconPort);
        appendProperty(properties, "rcon.password", config.rconPassword);
        return properties.toString();
    }

    private static String newRconPassword() {
        byte[] bytes = new byte[12];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder password = new StringBuilder();
        for (byte b : bytes) password.append(String.format("%02x", b));
        return password.toString();
    }

    private static void appendProperty(StringBuilder properties, String key, Object value) {
        properties.append(key).append('=').append(value).append('\n');
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\r\n]+", " ").trim();
    }

    private static String javaCommand(Config config) {
        return "java -Xms512M -Xmx" + config.memoryMb + "M -jar " + SERVER_JAR_NAME + " nogui";
    }

    private static String buildStartSh(Config config) {
        return "#!/bin/sh\n"
                + "#Generated by Amethyst\n"
                + "cd \"$(dirname \"$0\")\"\n"
                + javaCommand(config) + "\n";
    }

    private static String buildStartBat(Config config) {
        return "@echo off\r\n"
                + "rem Generated by Amethyst\r\n"
                + "cd /d \"%~dp0\"\r\n"
                + javaCommand(config) + "\r\n"
                + "pause\r\n";
    }

    private static String buildMetadata(Config config) {
        StringBuilder metadata = new StringBuilder();
        metadata.append("#Used by Amethyst to run this server on the device\n");
        appendProperty(metadata, "java-version", config.javaMajorVersion);
        appendProperty(metadata, "memory-mb", config.memoryMb);
        return metadata.toString();
    }

    private static String buildReadme(Config config) {
        return "Amethyst server bundle: " + config.sanitizedName + "\n"
                + "\n"
                + "Run it on this device from Amethyst (Local server -> Run existing server),\n"
                + "or copy the folder to a computer:\n"
                + "\n"
                + "  Windows:        double-click start.bat\n"
                + "  Linux / macOS:  ./start.sh\n"
                + "\n"
                + "Java " + config.javaMajorVersion + " or newer must be installed on that computer.\n"
                + "Players connect to port " + config.port + " of the machine running the server.\n"
                + "For players outside your local network, forward that port on your router.\n"
                + "\n"
                + "Settings live in server.properties; stop the server before editing it.\n";
    }

    private static void writeText(File file, String content) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Packs the bundle's files (not any world it has since created) into a sibling archive.
     * @return the created zip file
     */
    public static File createZip(File bundleDir) throws IOException {
        File zipFile = new File(bundleDir.getParentFile(), bundleDir.getName() + ".zip");
        File[] files = bundleDir.listFiles();
        if (files == null) throw new IOException("Cannot read " + bundleDir.getAbsolutePath());
        Arrays.sort(files);

        try (ZipOutputStream zipStream = new ZipOutputStream(new FileOutputStream(zipFile))) {
            String prefix = bundleDir.getName() + "/";
            for (File file : files) {
                if (!file.isFile() || file.equals(zipFile)) continue;
                zipStream.putNextEntry(new ZipEntry(prefix + file.getName()));
                try (FileInputStream inputStream = new FileInputStream(file)) {
                    IOUtils.copy(inputStream, zipStream);
                }
                zipStream.closeEntry();
            }
        }
        return zipFile;
    }
}
