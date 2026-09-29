package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.servers.ServerBundleGenerator;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;

    private View mSectionContent;
    private View mSectionTools;
    private View mSectionCommunity;
    private View mSectionServers;
    private LinearLayout mServerListContainer;
    private TextView mServerEmptyText;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);

        Button mModsButton = view.findViewById(R.id.content_mods_button);
        Button mShadersButton = view.findViewById(R.id.content_shaders_button);
        Button mResourcePacksButton = view.findViewById(R.id.content_resourcepacks_button);
        Button mDataPacksButton = view.findViewById(R.id.content_datapacks_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mSectionContent = view.findViewById(R.id.section_content);
        mSectionTools = view.findViewById(R.id.section_tools);
        mSectionCommunity = view.findViewById(R.id.section_community);
        mSectionServers = view.findViewById(R.id.section_servers);
        mServerListContainer = view.findViewById(R.id.server_list_container);
        mServerEmptyText = view.findViewById(R.id.server_empty_text);

        TabLayout tabs = view.findViewById(R.id.main_tabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showSection(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) { showSection(tab.getPosition()); }
        });

        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        // Removed account requirement for JAR execution - now works with offline accounts too
        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        mInstallJarButton.setOnLongClickListener(v -> {
            runInstallerWithConfirmation(true);
            return true;
        });
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        mModsButton.setOnClickListener(v -> openContentBrowser("mod"));
        mShadersButton.setOnClickListener(v -> openContentBrowser("shader"));
        mResourcePacksButton.setOnClickListener(v -> openContentBrowser("resourcepack"));
        mDataPacksButton.setOnClickListener(v -> openContentBrowser("datapack"));

        view.findViewById(R.id.server_create_button).setOnClickListener(v -> Tools.swapFragment(requireActivity(),
                LocalServerFragment.class, LocalServerFragment.TAG, null));

        mPlayButton.setOnClickListener(v -> {
            if (Tools.hasRenderingMods() && !(LauncherPreferences.DEFAULT_PREF.getBoolean("sodium_override", false))) {
                AlertDialog sodiumWarningDialog = new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.sodium_warning_title)
                        .setMessage(R.string.sodium_optimized_message)
                        .setPositiveButton(R.string.sodium_continue, (d,w)-> {
                            Tools.configureSodiumOptimizations();
                            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                sodiumWarningDialog.show();
            } else ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });

        mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));

        mOpenDirectoryButton.setOnClickListener((v)-> {
            if (Tools.isDemoProfile(v.getContext())){ // Say a different message when on demo profile since they might see the hidden demo folder
                hasNoOnlineProfileDialog(getActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
            } else if (!hasOnlineProfile()) { // Otherwise display the generic pop-up to log in
                hasNoOnlineProfileDialog(requireActivity());
            } else openPath(v.getContext(), getCurrentProfileDirectory(), false);

        });


        mNewsButton.setOnLongClickListener((v)->{
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });

        showSection(tabs.getSelectedTabPosition());
    }

    /** Toggles which main-menu section is visible for the selected tab index. */
    private void showSection(int index) {
        mSectionContent.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        mSectionTools.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        mSectionCommunity.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        mSectionServers.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        if (index == 3) populateServerList();
    }

    /** Rebuilds the Servers tab list from the on-disk server bundles. */
    private void populateServerList() {
        mServerListContainer.removeAllViews();
        List<File> bundles = ServerBundleGenerator.listBundles();
        mServerEmptyText.setVisibility(bundles.isEmpty() ? View.VISIBLE : View.GONE);

        int margin = getResources().getDimensionPixelSize(R.dimen.padding_medium);
        for (File bundle : bundles) {
            MaterialButton button = new MaterialButton(requireContext());
            button.setText(bundle.getName());
            button.setAllCaps(false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = margin;
            button.setLayoutParams(params);
            button.setOnClickListener(v -> {
                Bundle args = new Bundle(1);
                args.putString(ServerManageFragment.ARG_BUNDLE_DIR, bundle.getAbsolutePath());
                Tools.swapFragment(requireActivity(), ServerManageFragment.class, ServerManageFragment.TAG, args);
            });
            mServerListContainer.addView(button);
        }
    }

    /**
     * Opens the Modrinth content browser for the current profile, filtered to the given project
     * type ("mod", "shader", "resourcepack", "datapack"). Mirrors the profile editor's mod-library
     * wiring so the version/loader filters match the profile being played.
     */
    private void openContentBrowser(String projectType) {
        MinecraftProfile profile = getCurrentProfile();
        if (profile == null) {
            Tools.dialog(requireContext(), getString(R.string.global_error), getString(R.string.version_select_hint));
            return;
        }
        String versionId = profile.lastVersionId;
        if (versionId == null || versionId.trim().isEmpty()) {
            Tools.dialog(requireContext(), getString(R.string.global_error), getString(R.string.version_select_hint));
            return;
        }

        Bundle bundle = new Bundle(5);
        bundle.putString(ModLibraryFragment.ARG_MC_VERSION, normalizeMinecraftVersion(versionId));
        bundle.putString(ModLibraryFragment.ARG_MOD_LOADER, detectLoaderFromVersionId(versionId));
        bundle.putString(ModLibraryFragment.ARG_GAME_DIR, Tools.getGameDirPath(profile).getAbsolutePath());
        bundle.putString(ModLibraryFragment.ARG_PROFILE_TITLE, profile.name);
        bundle.putString(ModLibraryFragment.ARG_PROJECT_TYPE, projectType);

        Tools.swapFragment(requireActivity(), ModLibraryFragment.class, ModLibraryFragment.TAG, bundle);
    }

    private MinecraftProfile getCurrentProfile() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return null;
        LauncherProfiles.load();
        return LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
    }

    private File getCurrentProfileDirectory() {
        MinecraftProfile profile = getCurrentProfile();
        if(profile == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profile);
    }

    /** Resolves a profile's real Minecraft game version from its lastVersionId (loader ids don't embed it). */
    private String normalizeMinecraftVersion(String versionId) {
        try {
            JMinecraftVersionList.Version info = Tools.getVersionInfo(versionId, true);
            if (info != null) {
                if (info.inheritsFrom != null && !info.inheritsFrom.isEmpty()) return info.inheritsFrom;
                if (info.type != null && (info.type.equals("release") || info.type.equals("snapshot"))) return versionId;
            }
        } catch (Exception ignored) {}
        Matcher matcher = Pattern.compile("^\\d+\\.\\d+(?:\\.\\d+)?$").matcher(versionId.trim());
        if (matcher.matches()) return matcher.group();
        return versionId;
    }

    /** Detects the mod loader from a version id, matching Modrinth's loader category names. */
    private String detectLoaderFromVersionId(String versionId) {
        if (versionId == null || versionId.isEmpty()) return "";
        String lowerCase = versionId.toLowerCase();
        if (lowerCase.contains("neoforge")) return "neoforge";
        if (lowerCase.contains("forge")) return "forge";
        if (lowerCase.contains("fabric")) return "fabric";
        if (lowerCase.contains("quilt")) return "quilt";
        return "";
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
        if (mSectionServers != null && mSectionServers.getVisibility() == View.VISIBLE) populateServerList();
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
