package net.kdt.pojavlaunch.servers;

import static net.kdt.pojavlaunch.Tools.NATIVE_LIB_DIR;

import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.oracle.dalvik.VMLauncher;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.utils.JREUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Boots a generated server bundle on the device itself.
 *
 * Android 10+ forbids executing files from app storage, but that only applies to native
 * binaries: a Minecraft server is pure Java bytecode, and this app already knows how to
 * start a JVM inside its own process through {@link VMLauncher#launchJVM(String[])}.
 * This is the same mechanism the game and the JAR installer use, minus the graphics stack.
 */
public class ServerRunner {
    private static final String TAG = "ServerRunner";

    public static final int STATE_IDLE = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_STOPPED = 2;

    private static volatile int sState = STATE_IDLE;
    private static volatile int sExitCode;

    private ServerRunner() {}

    public static int getState() {
        return sState;
    }

    public static int getExitCode() {
        return sExitCode;
    }

    /** Reads the RCON endpoint the server was configured with, or null if RCON is off. */
    public static RconEndpoint getRconEndpoint(File bundleDir) {
        Properties properties = new Properties();
        try (InputStream inputStream = new FileInputStream(new File(bundleDir, "server.properties"))) {
            properties.load(inputStream);
        } catch (IOException e) {
            return null;
        }
        if (!Boolean.parseBoolean(properties.getProperty("enable-rcon", "false"))) return null;
        String password = properties.getProperty("rcon.password");
        try {
            int port = Integer.parseInt(properties.getProperty("rcon.port", "").trim());
            if (password == null || password.isEmpty()) return null;
            return new RconEndpoint(port, password);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static class RconEndpoint {
        public final int port;
        public final String password;
        RconEndpoint(int port, String password) {
            this.port = port;
            this.password = password;
        }
    }

    /**
     * Sends a console command over RCON. The in-process server does not read the host
     * process' stdin, so RCON is the only channel that reliably reaches a running server.
     *
     * @return the server's reply, or null if RCON is unavailable / the send failed
     */
    public static String sendCommand(File bundleDir, String command) {
        RconEndpoint endpoint = getRconEndpoint(bundleDir);
        if (endpoint == null) return null;
        try (RconClient client = new RconClient("127.0.0.1", endpoint.port, endpoint.password)) {
            return client.command(command);
        } catch (IOException e) {
            Log.w(TAG, "RCON command failed: " + command, e);
            return null;
        }
    }

    /** Asks a running server to shut down gracefully (saves worlds, disconnects players). */
    public static void requestStop(File bundleDir) {
        new Thread(() -> sendCommand(bundleDir, "stop"), "ServerRconStop").start();
    }

    /**
     * Launches the server JVM on the calling (background) thread and blocks until it exits.
     * Must run in the dedicated :server process, after {@code Logger.begin(...)}.
     *
     * @param onLaunchFailed invoked on the UI thread when the JVM could not be started at all
     */
    public static void launchServer(AppCompatActivity activity, File bundleDir, int memoryMb, int javaVersion, Runnable onLaunchFailed) {
        if (sState == STATE_RUNNING) return;
        sState = STATE_RUNNING;
        try {
            File serverJar = new File(bundleDir, ServerBundleGenerator.SERVER_JAR_NAME);
            if (!serverJar.isFile()) throw new IOException("Missing " + serverJar.getAbsolutePath());

            String runtimeName = MultiRTUtils.getNearestJreName(javaVersion);
            if (runtimeName == null) throw new IOException("No Java runtime available for Java " + javaVersion);
            Runtime runtime = MultiRTUtils.forceReread(runtimeName);
            if (runtime.javaVersion < javaVersion) {
                throw new IOException("Installed Java " + runtime.javaVersion + " is older than the required Java " + javaVersion);
            }
            String runtimeHome = MultiRTUtils.getRuntimeHome(runtimeName).getAbsolutePath();
            Logger.appendToLog("Server runtime: " + runtimeName + " (" + runtime.versionString + ")");

            JREUtils.relocateLibPath(runtime, runtimeHome);
            JREUtils.setJavaEnvironment(activity, runtimeHome);
            try {
                Os.setenv("HOME", bundleDir.getAbsolutePath(), true);
                Os.setenv("TMPDIR", Tools.DIR_CACHE.getAbsolutePath(), true);
            } catch (ErrnoException e) {
                Log.w(TAG, "Failed to set server env", e);
            }

            List<String> args = new ArrayList<>();
            args.add("-Xms" + Math.min(memoryMb, 512) + "M");
            args.add("-Xmx" + memoryMb + "M");
            args.add("-Djava.home=" + runtimeHome);
            args.add("-Djava.io.tmpdir=" + Tools.DIR_CACHE.getAbsolutePath());
            args.add("-Duser.home=" + bundleDir.getAbsolutePath());
            args.add("-Dos.name=Linux");
            args.add("-Djava.awt.headless=true");
            // The server resolves its own natives; the launcher's LWJGL paths would only confuse it
            args.add("-Djdk.lang.Process.launchMechanism=FORK");
            args.add("-XX:ActiveProcessorCount=" + java.lang.Runtime.getRuntime().availableProcessors());
            args.add("-jar");
            args.add(serverJar.getAbsolutePath());
            args.add("nogui");

            JREUtils.initJavaRuntime(runtimeHome);
            JREUtils.setupExitMethod(activity.getApplication());
            JREUtils.initializeHooks();
            JREUtils.chdir(bundleDir.getAbsolutePath());
            args.add(0, "java"); // argv[0] is the program name according to the C standard

            Logger.appendToLog("Starting server: " + android.text.TextUtils.join(" ", args));
            int exitCode = VMLauncher.launchJVM(args.toArray(new String[0]));
            sExitCode = exitCode;
            Logger.appendToLog("Server exit code: " + exitCode);
            // The JVM is gone; take the process (foreground service and console UI) with it
            // so Android shows the app as no longer running and frees the memory.
            Process.killProcess(Process.myPid());
        } catch (Throwable throwable) {
            sExitCode = -1;
            Logger.appendToLog(throwable);
            Log.e(TAG, "Server failed to start", throwable);
            if (onLaunchFailed != null) activity.runOnUiThread(onLaunchFailed);
        } finally {
            sState = STATE_STOPPED;
        }
    }
}
