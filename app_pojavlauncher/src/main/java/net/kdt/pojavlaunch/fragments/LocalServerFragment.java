package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.servers.ServerActivity;
import net.kdt.pojavlaunch.servers.ServerBundleGenerator;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Creates a Minecraft server folder and can run it on this device. The generated bundle
 * (official server.jar + configuration) can also be shared to run on another machine.
 */
public class LocalServerFragment extends Fragment {
    public static final String TAG = "LocalServerFragment";

    private static final String STATE_SELECTED_VERSION = "selected_version";
    private static final int DEFAULT_PORT = 25565;
    private static final int DEFAULT_MAX_PLAYERS = 20;

    private Button mVersionButton;
    private TextInputEditText mNameInput;
    private TextInputEditText mPortInput;
    private TextInputEditText mPlayersInput;
    private Spinner mGamemodeSpinner;
    private Spinner mDifficultySpinner;
    private Spinner mMemorySpinner;
    private MaterialSwitch mOnlineModeSwitch;

    private String[] mGamemodeValues;
    private String[] mDifficultyValues;
    private String[] mMemoryValues;

    private String mSelectedVersion;
    private Future<?> mRunningTask;

    public LocalServerFragment() {
        super(R.layout.fragment_local_server);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        if (savedInstanceState != null) mSelectedVersion = savedInstanceState.getString(STATE_SELECTED_VERSION);

        mVersionButton = view.findViewById(R.id.local_server_version_button);
        mNameInput = view.findViewById(R.id.local_server_name_input);
        mPortInput = view.findViewById(R.id.local_server_port_input);
        mPlayersInput = view.findViewById(R.id.local_server_players_input);
        mGamemodeSpinner = view.findViewById(R.id.local_server_gamemode_spinner);
        mDifficultySpinner = view.findViewById(R.id.local_server_difficulty_spinner);
        mMemorySpinner = view.findViewById(R.id.local_server_memory_spinner);
        mOnlineModeSwitch = view.findViewById(R.id.local_server_online_mode_switch);
        Button createButton = view.findViewById(R.id.local_server_create_button);
        Button runExistingButton = view.findViewById(R.id.local_server_run_existing_button);

        mGamemodeValues = getResources().getStringArray(R.array.local_server_gamemode_values);
        mDifficultyValues = getResources().getStringArray(R.array.local_server_difficulty_values);
        mMemoryValues = getResources().getStringArray(R.array.local_server_memory_values);

        mMemorySpinner.setSelection(1); // 2 GB
        updateVersionButton();

        mVersionButton.setOnClickListener(v -> VersionSelectorDialog.open(v.getContext(), true,
                (versionId, isSnapshot) -> {
                    mSelectedVersion = versionId;
                    updateVersionButton();
                }));
        createButton.setOnClickListener(this::onCreateClicked);
        runExistingButton.setOnClickListener(this::onRunExistingClicked);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SELECTED_VERSION, mSelectedVersion);
    }

    @Override
    public void onDestroyView() {
        if (mRunningTask != null) mRunningTask.cancel(true);
        super.onDestroyView();
    }

    private void updateVersionButton() {
        mVersionButton.setText(mSelectedVersion == null
                ? getString(R.string.local_server_pick_version)
                : getString(R.string.local_server_version_picked, mSelectedVersion));
    }

    private void onCreateClicked(View view) {
        if (ProgressKeeper.hasOngoingTasks()) {
            Toast.makeText(view.getContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return;
        }
        if (mSelectedVersion == null) {
            showError(view.getContext(), getString(R.string.local_server_error_no_version));
            return;
        }
        String sanitizedName = ServerBundleGenerator.sanitizeName(getText(mNameInput));
        if (sanitizedName == null) {
            showError(view.getContext(), getString(R.string.local_server_error_bad_name));
            return;
        }

        int port = parseIntOrDefault(getText(mPortInput), DEFAULT_PORT);
        int maxPlayers = parseIntOrDefault(getText(mPlayersInput), DEFAULT_MAX_PLAYERS);
        if (port < 1 || port > 65535 || maxPlayers < 1 || maxPlayers > 1000) {
            showError(view.getContext(), getString(R.string.local_server_error_bad_number));
            return;
        }

        JMinecraftVersionList releaseTable = (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        JMinecraftVersionList.Version manifestVersion = findVersion(releaseTable, mSelectedVersion);
        if (manifestVersion == null || manifestVersion.url == null) {
            showError(view.getContext(), getString(R.string.local_server_error_no_release_table));
            return;
        }

        ServerBundleGenerator.Config config = new ServerBundleGenerator.Config();
        config.sanitizedName = sanitizedName;
        config.motd = sanitizedName;
        config.port = port;
        config.maxPlayers = maxPlayers;
        config.onlineMode = mOnlineModeSwitch.isChecked();
        config.gamemode = valueAt(mGamemodeValues, mGamemodeSpinner.getSelectedItemPosition(), "survival");
        config.difficulty = valueAt(mDifficultyValues, mDifficultySpinner.getSelectedItemPosition(), "normal");
        config.memoryMb = parseIntOrDefault(valueAt(mMemoryValues, mMemorySpinner.getSelectedItemPosition(), "2048"), 2048);

        File bundleDir = ServerBundleGenerator.getBundleDirectory(sanitizedName);
        if (bundleDir.exists()) {
            new AlertDialog.Builder(view.getContext())
                    .setTitle(R.string.local_server_overwrite_title)
                    .setMessage(getString(R.string.local_server_overwrite_message, sanitizedName))
                    .setPositiveButton(R.string.global_yes, (dialog, which) -> startGeneration(config, manifestVersion))
                    .setNegativeButton(R.string.global_no, null)
                    .show();
            return;
        }
        startGeneration(config, manifestVersion);
    }

    private void startGeneration(ServerBundleGenerator.Config config, JMinecraftVersionList.Version manifestVersion) {
        mRunningTask = PojavApplication.sExecutorService.submit(() -> {
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_SERVER, 0,
                    R.string.local_server_fetching_metadata, manifestVersion.id);
            try {
                String versionJson = DownloadUtils.downloadString(manifestVersion.url);
                JMinecraftVersionList.Version fullVersion =
                        Tools.GLOBAL_GSON.fromJson(versionJson, JMinecraftVersionList.Version.class);
                MinecraftClientInfo serverDownload = ServerBundleGenerator.getServerDownload(fullVersion);
                if (serverDownload == null) {
                    String message = getString(R.string.local_server_error_no_server_jar, manifestVersion.id);
                    Tools.runOnUiThread(() -> showErrorRemote(new IOException(message)));
                    return;
                }
                if (fullVersion.javaVersion != null) config.javaMajorVersion = fullVersion.javaVersion.majorVersion;

                File bundleDir = ServerBundleGenerator.generate(config, serverDownload);
                showSuccessDialog(bundleDir, config.port);
            } catch (Exception e) {
                Tools.runOnUiThread(() -> showErrorRemote(e));
            } finally {
                ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_SERVER);
            }
        });
    }

    private void showErrorRemote(Throwable throwable) {
        if (!isAdded()) {
            Tools.showErrorRemote(throwable);
            return;
        }
        Tools.showError(requireContext(), throwable);
    }

    private void showSuccessDialog(File bundleDir, int port) {
        Tools.runOnUiThread(() -> {
            if (!isAdded()) return;
            String[] actions = {
                    getString(R.string.local_server_run_on_device),
                    getString(R.string.local_server_open_folder),
                    getString(R.string.local_server_share_zip)
            };
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.local_server_done_title)
                    .setMessage(getString(R.string.local_server_done_message, bundleDir.getName(), String.valueOf(port)))
                    .setItems(actions, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                runOnDevice(bundleDir);
                                break;
                            case 1:
                                Tools.openPath(requireContext(), bundleDir, false);
                                break;
                            case 2:
                                shareAsZip(bundleDir);
                                break;
                        }
                    })
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        });
    }

    private void onRunExistingClicked(View view) {
        File serversRoot = ServerBundleGenerator.getServersRoot();
        File[] children = serversRoot.listFiles();
        List<File> bundles = new ArrayList<>();
        if (children != null) {
            Arrays.sort(children);
            for (File child : children) {
                if (child.isDirectory() && new File(child, ServerBundleGenerator.SERVER_JAR_NAME).isFile()) {
                    bundles.add(child);
                }
            }
        }
        if (bundles.isEmpty()) {
            Toast.makeText(view.getContext(), R.string.local_server_no_bundles, Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[bundles.size()];
        for (int i = 0; i < bundles.size(); i++) names[i] = bundles.get(i).getName();
        new AlertDialog.Builder(view.getContext())
                .setTitle(R.string.local_server_run_existing)
                .setItems(names, (dialog, which) -> runOnDevice(bundles.get(which)))
                .show();
    }

    private void runOnDevice(File bundleDir) {
        startActivity(new Intent(requireContext(), ServerActivity.class)
                .putExtra(ServerActivity.EXTRA_BUNDLE_DIR, bundleDir.getAbsolutePath()));
    }

    private void shareAsZip(File bundleDir) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File zipFile = ServerBundleGenerator.createZip(bundleDir);
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Tools.openPath(requireContext(), zipFile, true);
                });
            } catch (IOException e) {
                Tools.runOnUiThread(() -> showErrorRemote(e));
            }
        });
    }

    private void showError(Context context, String message) {
        Tools.dialog(context, context.getString(R.string.global_error), message);
    }

    private static JMinecraftVersionList.Version findVersion(JMinecraftVersionList releaseTable, String versionId) {
        if (releaseTable == null || releaseTable.versions == null) return null;
        for (JMinecraftVersionList.Version version : releaseTable.versions) {
            if (versionId.equals(version.id)) return version;
        }
        return null;
    }

    private static String valueAt(String[] values, int position, String fallback) {
        return position >= 0 && position < values.length ? values[position] : fallback;
    }

    private static String getText(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }

    private static int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
