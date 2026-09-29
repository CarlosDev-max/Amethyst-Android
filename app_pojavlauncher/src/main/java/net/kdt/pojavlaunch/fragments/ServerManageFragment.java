package net.kdt.pojavlaunch.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.servers.ServerActivity;
import net.kdt.pojavlaunch.servers.ServerBundleGenerator;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Properties;

/**
 * Per-server management screen: edits the bundle's server.properties through a form,
 * exposes the bundle folder and tails the last log, without leaving the launcher process
 * (the live console lives in :server while the JVM runs).
 */
public class ServerManageFragment extends Fragment {
    public static final String TAG = "ServerManageFragment";
    public static final String ARG_BUNDLE_DIR = "bundle_dir";

    private static final int LOG_TAIL_BYTES = 65536;
    private static final int LOG_TAIL_LINES = 120;

    private File mBundleDir;
    private final Properties mProperties = new Properties();
    private final Properties mMetadata = new Properties();

    private TextInputEditText mMotdInput;
    private TextInputEditText mPortInput;
    private TextInputEditText mPlayersInput;
    private TextInputEditText mViewDistanceInput;
    private TextInputEditText mRconPortInput;
    private TextInputEditText mRconPasswordInput;
    private Spinner mGamemodeSpinner;
    private Spinner mDifficultySpinner;
    private Spinner mMemorySpinner;
    private MaterialSwitch mOnlineModeSwitch;
    private MaterialSwitch mWhitelistSwitch;
    private MaterialSwitch mRconSwitch;
    private TextView mLogTail;

    private View mSettingsSection;
    private View mFilesSection;
    private View mConsoleSection;

    private String[] mGamemodeValues;
    private String[] mDifficultyValues;
    private String[] mMemoryValues;

    public ServerManageFragment() {
        super(R.layout.fragment_server_manage);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        if (args == null || args.getString(ARG_BUNDLE_DIR) == null) {
            requireActivity().getSupportFragmentManager().popBackStack();
            return;
        }
        mBundleDir = new File(args.getString(ARG_BUNDLE_DIR));
        requireActivity().setTitle(mBundleDir.getName());

        mMotdInput = view.findViewById(R.id.server_manage_motd_input);
        mPortInput = view.findViewById(R.id.server_manage_port_input);
        mPlayersInput = view.findViewById(R.id.server_manage_players_input);
        mViewDistanceInput = view.findViewById(R.id.server_manage_view_distance_input);
        mRconPortInput = view.findViewById(R.id.server_manage_rcon_port_input);
        mRconPasswordInput = view.findViewById(R.id.server_manage_rcon_password_input);
        mGamemodeSpinner = view.findViewById(R.id.server_manage_gamemode_spinner);
        mDifficultySpinner = view.findViewById(R.id.server_manage_difficulty_spinner);
        mMemorySpinner = view.findViewById(R.id.server_manage_memory_spinner);
        mOnlineModeSwitch = view.findViewById(R.id.server_manage_online_mode_switch);
        mWhitelistSwitch = view.findViewById(R.id.server_manage_whitelist_switch);
        mRconSwitch = view.findViewById(R.id.server_manage_rcon_switch);
        mLogTail = view.findViewById(R.id.server_manage_log_tail);

        mSettingsSection = view.findViewById(R.id.server_manage_settings_section);
        mFilesSection = view.findViewById(R.id.server_manage_files_section);
        mConsoleSection = view.findViewById(R.id.server_manage_console_section);

        mGamemodeValues = getResources().getStringArray(R.array.local_server_gamemode_values);
        mDifficultyValues = getResources().getStringArray(R.array.local_server_difficulty_values);
        mMemoryValues = getResources().getStringArray(R.array.local_server_memory_values);

        TabLayout tabs = view.findViewById(R.id.server_manage_tabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showSection(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        view.findViewById(R.id.server_manage_save_button).setOnClickListener(v -> save());
        view.findViewById(R.id.server_manage_regen_password_button)
                .setOnClickListener(v -> mRconPasswordInput.setText(ServerBundleGenerator.newRconPassword()));
        view.findViewById(R.id.server_manage_open_folder_button)
                .setOnClickListener(v -> Tools.openPath(requireContext(), mBundleDir, false));
        view.findViewById(R.id.server_manage_share_zip_button).setOnClickListener(this::onShareZip);
        view.findViewById(R.id.server_manage_delete_button).setOnClickListener(v -> confirmDelete());
        view.findViewById(R.id.server_manage_open_console_button).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ServerActivity.class)
                        .putExtra(ServerActivity.EXTRA_BUNDLE_DIR, mBundleDir.getAbsolutePath())));

        load();
        showSection(0);
    }

    private void showSection(int position) {
        mSettingsSection.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        mFilesSection.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        mConsoleSection.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        if (position == 2) mLogTail.setText(readLogTail());
    }

    private void load() {
        readInto(mProperties, new File(mBundleDir, "server.properties"));
        readInto(mMetadata, new File(mBundleDir, ServerBundleGenerator.METADATA_FILE_NAME));

        mMotdInput.setText(mProperties.getProperty("motd", ""));
        mPortInput.setText(mProperties.getProperty("server-port", "25565"));
        mPlayersInput.setText(mProperties.getProperty("max-players", "20"));
        mViewDistanceInput.setText(mProperties.getProperty("view-distance", "10"));
        mRconPortInput.setText(mProperties.getProperty("rcon.port", "25575"));
        mRconPasswordInput.setText(mProperties.getProperty("rcon.password", ""));
        mOnlineModeSwitch.setChecked(Boolean.parseBoolean(mProperties.getProperty("online-mode", "true")));
        mWhitelistSwitch.setChecked(Boolean.parseBoolean(mProperties.getProperty("white-list", "false")));
        mRconSwitch.setChecked(Boolean.parseBoolean(mProperties.getProperty("enable-rcon", "false")));
        mGamemodeSpinner.setSelection(indexOf(mGamemodeValues, mProperties.getProperty("gamemode", "survival")));
        mDifficultySpinner.setSelection(indexOf(mDifficultyValues, mProperties.getProperty("difficulty", "normal")));
        mMemorySpinner.setSelection(indexOf(mMemoryValues, mMetadata.getProperty("memory-mb", "2048")));
    }

    private void readInto(Properties properties, File file) {
        properties.clear();
        try (InputStream inputStream = new FileInputStream(file)) {
            properties.load(inputStream);
        } catch (IOException ignored) {
            // Missing file: the form falls back to defaults and Save recreates it.
        }
    }

    private void save() {
        mProperties.setProperty("motd", text(mMotdInput).replaceAll("[\r\n]+", " ").trim());
        mProperties.setProperty("server-port", text(mPortInput));
        mProperties.setProperty("max-players", text(mPlayersInput));
        mProperties.setProperty("view-distance", text(mViewDistanceInput));
        mProperties.setProperty("online-mode", String.valueOf(mOnlineModeSwitch.isChecked()));
        mProperties.setProperty("white-list", String.valueOf(mWhitelistSwitch.isChecked()));
        mProperties.setProperty("enable-rcon", String.valueOf(mRconSwitch.isChecked()));
        mProperties.setProperty("rcon.port", text(mRconPortInput));
        mProperties.setProperty("rcon.password", text(mRconPasswordInput));
        mProperties.setProperty("gamemode", valueAt(mGamemodeValues, mGamemodeSpinner.getSelectedItemPosition()));
        mProperties.setProperty("difficulty", valueAt(mDifficultyValues, mDifficultySpinner.getSelectedItemPosition()));
        mMetadata.setProperty("memory-mb", valueAt(mMemoryValues, mMemorySpinner.getSelectedItemPosition()));
        mMetadata.setProperty("java-version", mMetadata.getProperty("java-version", "17"));

        try (FileOutputStream propertiesOut = new FileOutputStream(new File(mBundleDir, "server.properties"));
             FileOutputStream metadataOut = new FileOutputStream(new File(mBundleDir, ServerBundleGenerator.METADATA_FILE_NAME))) {
            mProperties.store(propertiesOut, "Edited by Amethyst");
            mMetadata.store(metadataOut, "Edited by Amethyst");
            Toast.makeText(requireContext(), R.string.server_manage_saved, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Tools.showError(requireContext(), e);
        }
    }

    private void onShareZip(View view) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File zipFile = ServerBundleGenerator.createZip(mBundleDir);
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Tools.openPath(requireContext(), zipFile, true);
                });
            } catch (IOException e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Tools.showError(requireContext(), e);
                });
            }
        });
    }

    private void confirmDelete() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.server_manage_delete_title)
                .setMessage(getString(R.string.server_manage_delete_message, mBundleDir.getName()))
                .setPositiveButton(R.string.server_manage_delete, (dialog, which) -> deleteServer())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteServer() {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                FileUtils.deleteDirectory(mBundleDir);
            } catch (IOException e) {
                Tools.runOnUiThread(() -> {
                    if (isAdded()) Tools.showError(requireContext(), e);
                });
                return;
            }
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                Toast.makeText(requireContext(), R.string.server_manage_deleted, Toast.LENGTH_SHORT).show();
                requireActivity().getSupportFragmentManager().popBackStack();
            });
        });
    }

    private String readLogTail() {
        File logFile = new File(mBundleDir, "latestlog.txt");
        if (!logFile.isFile()) return getString(R.string.server_manage_no_log);
        try (RandomAccessFile accessFile = new RandomAccessFile(logFile, "r")) {
            long length = accessFile.length();
            if (length == 0) return getString(R.string.server_manage_no_log);
            accessFile.seek(Math.max(0, length - LOG_TAIL_BYTES));
            byte[] buffer = new byte[(int) Math.min(length, LOG_TAIL_BYTES)];
            accessFile.readFully(buffer);
            String[] lines = new String(buffer, java.nio.charset.StandardCharsets.UTF_8).split("\n");
            int start = Math.max(0, lines.length - LOG_TAIL_LINES);
            StringBuilder tail = new StringBuilder();
            for (int i = start; i < lines.length; i++) tail.append(lines[i]).append('\n');
            return tail.toString();
        } catch (IOException e) {
            return getString(R.string.server_manage_no_log);
        }
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return i;
        }
        return 0;
    }

    private static String valueAt(String[] values, int position) {
        return position >= 0 && position < values.length ? values[position] : values[0];
    }

    private static String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}
