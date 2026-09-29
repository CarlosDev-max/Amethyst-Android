package net.kdt.pojavlaunch.servers;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kdt.LoggerView;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.services.ServerService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Console for a server running on this device. Hosts the server JVM in its own process
 * (the JVM only exists once per process, hence the dedicated :server process) and shows
 * its output through the same native log pipe the game uses.
 */
public class ServerActivity extends AppCompatActivity {
    public static final String EXTRA_BUNDLE_DIR = "bundle_dir";

    private TextView mStatusText;
    private EditText mCommandInput;
    private Button mSendButton;
    private Button mStopButton;

    private File mBundleDir;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_console);

        String bundlePath = getIntent().getStringExtra(EXTRA_BUNDLE_DIR);
        mBundleDir = bundlePath == null ? null : new File(bundlePath);
        if (mBundleDir == null || !new File(mBundleDir, ServerBundleGenerator.SERVER_JAR_NAME).isFile()) {
            Toast.makeText(this, R.string.local_server_error_missing_bundle, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mStatusText = findViewById(R.id.server_console_status);
        mCommandInput = findViewById(R.id.server_console_command_input);
        mSendButton = findViewById(R.id.server_console_send_button);
        mStopButton = findViewById(R.id.server_console_stop_button);
        LoggerView loggerView = findViewById(R.id.server_console_logger);

        String serverName = mBundleDir.getName();
        setTitle(serverName);
        mStatusText.setText(getString(R.string.local_server_status_starting, serverName, readPort()));

        mSendButton.setOnClickListener(v -> sendCommand());
        mCommandInput.setOnEditorActionListener((v, actionId, event) -> {
            sendCommand();
            return true;
        });
        mStopButton.setOnClickListener(v -> confirmStop());

        loggerView.setVisibility(View.VISIBLE);

        Intent serviceIntent = new Intent(this, ServerService.class)
                .putExtra(ServerService.EXTRA_SERVER_NAME, serverName)
                .putExtra(ServerService.EXTRA_BUNDLE_DIR, mBundleDir.getAbsolutePath());
        ContextCompat.startForegroundService(this, serviceIntent);

        if (ServerRunner.getState() == ServerRunner.STATE_IDLE) {
            startServer();
        } else if (ServerRunner.getState() == ServerRunner.STATE_STOPPED) {
            showStopped();
        }
    }

    private void sendCommand() {
        String command = mCommandInput.getText().toString().trim();
        if (command.isEmpty()) return;
        if (ServerRunner.getState() != ServerRunner.STATE_RUNNING) {
            Toast.makeText(this, R.string.local_server_error_not_running, Toast.LENGTH_SHORT).show();
            return;
        }
        Logger.appendToLog("> " + command);
        mCommandInput.setText("");
        PojavApplication.sExecutorService.execute(() -> {
            String reply = ServerRunner.sendCommand(mBundleDir, command);
            if (reply == null) {
                runOnUiThread(() -> Toast.makeText(ServerActivity.this,
                        R.string.local_server_error_rcon, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmStop() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.local_server_stop_title)
                .setMessage(R.string.local_server_stop_message)
                .setPositiveButton(R.string.local_server_stop, (dialog, which) -> stopServer(false))
                .setNeutralButton(R.string.local_server_force_stop, (dialog, which) -> stopServer(true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void stopServer(boolean force) {
        if (force || ServerRunner.getState() != ServerRunner.STATE_RUNNING) {
            killThisProcess();
            return;
        }
        mStopButton.setEnabled(false);
        mStatusText.setText(R.string.local_server_status_stopping);
        ServerRunner.requestStop(mBundleDir);
        // If the JVM does not come down in time, take the process with it
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (ServerRunner.getState() == ServerRunner.STATE_RUNNING) {
                killThisProcess();
            }
        }, 20000);
    }

    private void startServer() {
        Properties metadata = readMetadata();
        int memoryMb = parseInt(metadata.getProperty("memory-mb"), 2048);
        int javaVersion = parseInt(metadata.getProperty("java-version"), 17);

        File logFile = new File(mBundleDir, "latestlog.txt");
        try {
            // Logger.begin opens with O_TRUNC and no O_CREAT, so the file must exist first
            if (!logFile.exists() && !logFile.createNewFile()) {
                throw new IOException("Failed to create " + logFile);
            }
            Logger.begin(logFile.getAbsolutePath());
        } catch (IOException e) {
            Logger.appendToLog(e);
        }

        PojavApplication.sExecutorService.execute(() -> {
            // Downloads the matching JRE if the device does not have it yet
            String runtimeName = NewJREUtil.ensureRuntimeForVersion(ServerActivity.this, javaVersion);
            if (runtimeName == null) {
                Logger.appendToLog("No Java " + javaVersion + " runtime could be prepared");
                runOnUiThread(() -> new AlertDialog.Builder(ServerActivity.this)
                        .setMessage(getString(R.string.local_server_error_no_runtime, javaVersion))
                        .setOnDismissListener(dialog -> finishAndRemoveTask())
                        .show());
                return;
            }
            new Thread(() -> ServerRunner.launchServer(ServerActivity.this, mBundleDir, memoryMb, javaVersion,
                    () -> new AlertDialog.Builder(ServerActivity.this)
                            .setMessage(R.string.local_server_error_launch_failed)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> showStopped())),
                    "ServerJVM").start();
        });
    }

    private void showStopped() {
        mStatusText.setText(getString(R.string.local_server_status_stopped, ServerRunner.getExitCode()));
        mSendButton.setEnabled(false);
        mCommandInput.setEnabled(false);
        mStopButton.setText(R.string.local_server_close);
        mStopButton.setEnabled(true);
        mStopButton.setOnClickListener(v -> finishAndRemoveProcess());
    }

    private void finishAndRemoveProcess() {
        finish();
        killThisProcess();
    }

    /** The server JVM lives in this process; killing it is the only way to fully stop. */
    private static void killThisProcess() {
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    public void onBackPressed() {
        if (ServerRunner.getState() == ServerRunner.STATE_RUNNING) {
            Toast.makeText(this, R.string.local_server_back_background, Toast.LENGTH_LONG).show();
        }
        super.onBackPressed();
    }

    private String readPort() {
        File propertiesFile = new File(mBundleDir, "server.properties");
        Properties properties = new Properties();
        try (InputStream inputStream = new FileInputStream(propertiesFile)) {
            properties.load(inputStream);
        } catch (IOException ignored) {}
        return properties.getProperty("server-port", "25565");
    }

    private Properties readMetadata() {
        Properties metadata = new Properties();
        File metadataFile = new File(mBundleDir, ServerBundleGenerator.METADATA_FILE_NAME);
        try (InputStream inputStream = new FileInputStream(metadataFile)) {
            metadata.load(inputStream);
        } catch (IOException ignored) {}
        return metadata;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
