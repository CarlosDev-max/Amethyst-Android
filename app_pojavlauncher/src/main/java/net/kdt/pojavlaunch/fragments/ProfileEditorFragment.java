package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.RTSpinnerAdapter;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.utils.CropperUtils;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfileEditorFragment extends Fragment implements CropperUtils.CropperListener{
    public static final String TAG = "ProfileEditorFragment";
    public static final String DELETED_PROFILE = "deleted_profile";

    private String mProfileKey;
    private MinecraftProfile mTempProfile = null;
    private String mValueToConsume = "";
    private Button mSaveButton, mDeleteButton, mControlSelectButton, mGameDirButton, mVersionSelectButton;
    private Spinner mDefaultRuntime, mDefaultRenderer;
    private Spinner mModloaderSpinner;
    private Button mModloaderActionButton;
    private Button mModLibraryButton;
    private EditText mDefaultName, mDefaultJvmArgument;
    private TextView mDefaultPath, mDefaultVersion, mDefaultControl;
    private ImageView mProfileIcon;
    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);

    private List<String> mRenderNames;

    private static final String[] MODLOADER_KEYS = {"vanilla", "forge", "neoforge", "fabric", "quilt"};

    public ProfileEditorFragment(){
        super(R.layout.fragment_profile_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Paths, which can be changed
        String value = (String) ExtraCore.consumeValue(ExtraConstants.FILE_SELECTOR);
        if(value != null){
            if(mValueToConsume.equals(FileSelectorFragment.BUNDLE_SELECT_FOLDER)){
                mTempProfile.gameDir = value;
            }else{
                mTempProfile.controlFile = value;
            }
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);

        Tools.RenderersList renderersList = Tools.getCompatibleRenderers(view.getContext());
        mRenderNames = renderersList.rendererIds;
        List<String> renderList = new ArrayList<>(renderersList.rendererDisplayNames.length + 1);
        renderList.addAll(Arrays.asList(renderersList.rendererDisplayNames));
        renderList.add(view.getContext().getString(R.string.global_default));
        mDefaultRenderer.setAdapter(new ArrayAdapter<>(getContext(), R.layout.item_simple_list_1, renderList));

        List<String> modloaderDisplayNames = Arrays.asList(
                view.getContext().getString(R.string.pedit_modloader_vanilla),
                "Forge", "NeoForge", "Fabric", "Quilt");
        mModloaderSpinner.setAdapter(new ArrayAdapter<>(getContext(), R.layout.item_simple_list_1, modloaderDisplayNames));
        mModloaderActionButton.setOnClickListener(v -> onModloaderActionClicked());

        // Set up behaviors
        mSaveButton.setOnClickListener(v -> {
            ProfileIconCache.dropIcon(mProfileKey);
            save();
            Tools.backToMainMenu(requireActivity());
        });

        mDeleteButton.setOnClickListener(v -> {
            if(LauncherProfiles.mainProfileJson.profiles.size() > 1){
                ProfileIconCache.dropIcon(mProfileKey);
                LauncherProfiles.mainProfileJson.profiles.remove(mProfileKey);
                LauncherProfiles.write();
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, DELETED_PROFILE);
            }

            Tools.removeCurrentFragment(requireActivity());
        });


        View.OnClickListener gameDirListener = getGameDirListener();
        mGameDirButton.setOnClickListener(gameDirListener);
        mDefaultPath.setOnClickListener(gameDirListener);

        View.OnClickListener controlSelectListener = getControlSelectListener();
        mControlSelectButton.setOnClickListener(controlSelectListener);
        mDefaultControl.setOnClickListener(controlSelectListener);

        // Setup the expendable list behavior
        View.OnClickListener versionSelectListener = getVersionSelectListener();
        mVersionSelectButton.setOnClickListener(versionSelectListener);
        mDefaultVersion.setOnClickListener(versionSelectListener);

        // Set up the icon change click listener
        mProfileIcon.setOnClickListener(v -> CropperUtils.startCropper(mCropperLauncher));

        // Set up the mod library button
        mModLibraryButton.setOnClickListener(v -> onModLibraryClicked());

        loadValues(LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""), view.getContext());
    }

    private View.OnClickListener getGameDirListener() {
        return v -> {
            Bundle bundle = new Bundle(2);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, true);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.DIR_GAME_HOME);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SHOW_FILE, false);
            mValueToConsume = FileSelectorFragment.BUNDLE_SELECT_FOLDER;

            Tools.swapFragment(requireActivity(),
                    FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getControlSelectListener() {
        return v -> {
            Bundle bundle = new Bundle(3);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, false);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.CTRLMAP_PATH);
            mValueToConsume = FileSelectorFragment.BUNDLE_SELECT_FILE;

            Tools.swapFragment(requireActivity(),
                    FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getVersionSelectListener() {
        return v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot)-> {
            mTempProfile.lastVersionId = id;
            mDefaultVersion.setText(id);
            autoSelectRuntimeForVersion(id);
        });
    }

    /**
     * Looks up the chosen Minecraft version's required Java version (fetching its version.json
     * if needed) and, if a materially better runtime is installed, pre-selects it in the runtime
     * spinner - so the user doesn't have to realize on their own that e.g. mod loader installers
     * for older Minecraft versions need Java 8. Best-effort: silently does nothing on failure,
     * since the user can always still pick a runtime manually.
     */
    private void autoSelectRuntimeForVersion(String versionId) {
        JMinecraftVersionList releaseTable = (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (releaseTable == null || releaseTable.versions == null) return;
        JMinecraftVersionList.Version matchedVersion = null;
        for (JMinecraftVersionList.Version version : releaseTable.versions) {
            if (versionId.equals(version.id)) {
                matchedVersion = version;
                break;
            }
        }
        if (matchedVersion == null || matchedVersion.url == null) return;

        final JMinecraftVersionList.Version versionToFetch = matchedVersion;
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String versionJson = DownloadUtils.downloadString(versionToFetch.url);
                JMinecraftVersionList.Version fullVersion = Tools.GLOBAL_GSON.fromJson(versionJson, JMinecraftVersionList.Version.class);
                if (fullVersion.javaVersion == null) return;
                int requiredJavaVersion = fullVersion.javaVersion.majorVersion;
                String nearestRuntimeName = MultiRTUtils.getNearestJreName(requiredJavaVersion);
                if (nearestRuntimeName == null) return;
                Tools.runOnUiThread(() -> {
                    if (!isAdded() || mDefaultRuntime.getAdapter() == null) return;
                    int count = mDefaultRuntime.getAdapter().getCount();
                    for (int i = 0; i < count; i++) {
                        Runtime candidate = (Runtime) mDefaultRuntime.getAdapter().getItem(i);
                        if (candidate != null && nearestRuntimeName.equals(candidate.name)) {
                            mDefaultRuntime.setSelection(i);
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Could not auto-select a runtime for version " + versionId, e);
            }
        });
    }


    private void loadValues(@NonNull String profile, @NonNull Context context){
        if(mTempProfile == null){
            mTempProfile = getProfile(profile);
        }
        // TODO: Remove this jank when it's not relevant anymore
        // Shitty hack to make OSMZink smoothly transition into kopper
        if ("vulkan_zink".equals(mTempProfile.pojavRendererName)) mTempProfile.pojavRendererName = "opengles3_desktopgl_zink_kopper";
        mProfileIcon.setImageDrawable(
                ProfileIconCache.fetchIcon(getResources(), mProfileKey, mTempProfile.icon)
        );

        // Runtime spinner
        List<Runtime> runtimes = MultiRTUtils.getInstalledRuntimes();
        int jvmIndex = runtimes.indexOf(new Runtime("<Default>"));
        if (mTempProfile.javaDir != null) {
            String selectedRuntime = mTempProfile.javaDir.substring(Tools.LAUNCHERPROFILES_RTPREFIX.length());
            int nindex = runtimes.indexOf(new Runtime(selectedRuntime));
            if (nindex != -1) jvmIndex = nindex;
        }
        mDefaultRuntime.setAdapter(new RTSpinnerAdapter(context, runtimes));
        if(jvmIndex == -1) jvmIndex = runtimes.size() - 1;
        mDefaultRuntime.setSelection(jvmIndex);

        // Renderer spinner
        int rendererIndex = mDefaultRenderer.getAdapter().getCount() - 1;
        if(mTempProfile.pojavRendererName != null) {
            int nindex = mRenderNames.indexOf(mTempProfile.pojavRendererName);
            if(nindex != -1) rendererIndex = nindex;
        }
        mDefaultRenderer.setSelection(rendererIndex);

        mDefaultVersion.setText(mTempProfile.lastVersionId);
        mDefaultJvmArgument.setText(mTempProfile.javaArgs == null ? "" : mTempProfile.javaArgs);
        mDefaultName.setText(mTempProfile.name);
        mDefaultPath.setText(mTempProfile.gameDir == null ? "" : mTempProfile.gameDir);
        mDefaultControl.setText(mTempProfile.controlFile == null ? "" : mTempProfile.controlFile);
    }

    private MinecraftProfile getProfile(@NonNull String profile){
        MinecraftProfile minecraftProfile;
        if(getArguments() == null) {
            // EDGE CASE: User leaves Pojav in background. Pojav gets terminated in the background.
            // Current selected fragment and its arguments are saved.
            // User returns to Pojav. Android restarts process and reinitializes fragment without
            // going to the main screen. mainProfileJson and profiles left uninitialized, which
            // results in a crash.
            // Reload the profiles to avoid this edge case.
            LauncherProfiles.load();
            MinecraftProfile originalProfile = LauncherProfiles.mainProfileJson.profiles.get(profile);
            // EDGE CASE: User edits the JSON, so the profile that was edited no longer exists.
            // Create a brand new profile as a fallback for this case.
            if(originalProfile != null) minecraftProfile = new MinecraftProfile(originalProfile);
            else minecraftProfile = MinecraftProfile.createTemplate();
            mProfileKey = profile;
        }else{
            minecraftProfile = MinecraftProfile.createTemplate();
            mProfileKey = LauncherProfiles.getFreeProfileKey();
        }
        return minecraftProfile;
    }


    private void onModloaderActionClicked() {
        if (mDefaultVersion.getText().toString().trim().isEmpty()) {
            Tools.dialog(requireContext(), getString(R.string.global_error), getString(R.string.version_select_hint));
            return;
        }
        // Persist the profile first (with its current, vanilla lastVersionId) so the
        // install flow can reliably find and update this exact profile afterward.
        ProfileIconCache.dropIcon(mProfileKey);
        save();

        int position = mModloaderSpinner.getSelectedItemPosition();
        String loaderKey = MODLOADER_KEYS[position];

        Bundle bundle = new Bundle(1);
        // Both ModVersionListFragment.ARG_TARGET_PROFILE_KEY and
        // FabriclikeInstallFragment.ARG_TARGET_PROFILE_KEY use this same key name.
        bundle.putString(ModVersionListFragment.ARG_TARGET_PROFILE_KEY, mProfileKey);

        switch (loaderKey) {
            case "forge":
                Tools.swapFragment(requireActivity(), ForgeInstallFragment.class, ForgeInstallFragment.TAG, bundle);
                break;
            case "neoforge":
                Tools.swapFragment(requireActivity(), NeoForgeInstallFragment.class, NeoForgeInstallFragment.TAG, bundle);
                break;
            case "fabric":
                Tools.swapFragment(requireActivity(), FabricInstallFragment.class, FabricInstallFragment.TAG, bundle);
                break;
            case "quilt":
                Tools.swapFragment(requireActivity(), QuiltInstallFragment.class, QuiltInstallFragment.TAG, bundle);
                break;
            default:
                // "vanilla" - nothing to install, this option is just informational.
                break;
        }
    }

    private void onModLibraryClicked() {
        if (mDefaultVersion.getText().toString().trim().isEmpty()) {
            Tools.dialog(requireContext(), getString(R.string.global_error), getString(R.string.version_select_hint));
            return;
        }
        String selectedMcVersion = normalizeMinecraftVersion(mDefaultVersion.getText().toString().trim(), mTempProfile.lastVersionId);
        String loader = detectLoaderFromVersionId(mTempProfile.lastVersionId);
        String gameDir = resolveGameDir();
        String profileTitle = mTempProfile.name;

        Bundle bundle = new Bundle(4);
        bundle.putString(ModLibraryFragment.ARG_MC_VERSION, selectedMcVersion);
        bundle.putString(ModLibraryFragment.ARG_MOD_LOADER, loader);
        bundle.putString(ModLibraryFragment.ARG_GAME_DIR, gameDir);
        bundle.putString(ModLibraryFragment.ARG_PROFILE_TITLE, profileTitle);

        Tools.swapFragment(requireActivity(), ModLibraryFragment.class, ModLibraryFragment.TAG, bundle);
    }

    /**
     * Resolves the real Minecraft game version for a profile's lastVersionId.
     * For a loader version id (e.g. "neoforge-21.1.233", "1.20.1-forge-47.2.0"), this is NOT
     * derivable by regex from the id alone - loader ids don't reliably embed the MC version
     * (NeoForge's don't at all). Instead, read the locally installed version.json, whose
     * "inheritsFrom" field points at the actual base game version for any loader profile.
     * Falls back to the id itself (best-effort) if the version isn't installed/readable yet.
     */
    private String normalizeMinecraftVersion(String selectedVersion, String versionId) {
        if (versionId != null && !versionId.isEmpty()) {
            try {
                JMinecraftVersionList.Version info = Tools.getVersionInfo(versionId, true);
                if (info != null) {
                    if (info.inheritsFrom != null && !info.inheritsFrom.isEmpty()) return info.inheritsFrom;
                    if (info.type != null && (info.type.equals("release") || info.type.equals("snapshot"))) return versionId;
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not read version.json for " + versionId + " to resolve its MC version", e);
            }
        }
        // Fallback: best-effort guess from whichever string looks like a plain MC version.
        Matcher matcher = Pattern.compile("^\\d+\\.\\d+(?:\\.\\d+)?$").matcher(
                selectedVersion != null ? selectedVersion.trim() : "");
        if (matcher.matches()) return matcher.group();
        return versionId != null ? versionId : "";
    }

    /**
     * Detects the mod loader from the profile's lastVersionId by looking for loader keywords.
     * @param versionId The lastVersionId string (e.g., "1.20.1-forge-47.2.0" or "fabric-1.20.1")
     * @return The loader id as used by Modrinth ("forge", "fabric", "quilt", "neoforge"), or
     *         empty string if vanilla/unknown.
     */
    private String detectLoaderFromVersionId(String versionId) {
        if (versionId == null || versionId.isEmpty()) {
            return "";
        }
        String lowerCase = versionId.toLowerCase();
        if (lowerCase.contains("neoforge")) return "neoforge";
        if (lowerCase.contains("forge")) return "forge";
        if (lowerCase.contains("fabric")) return "fabric";
        if (lowerCase.contains("quilt")) return "quilt";
        return "";
    }

    /**
     * Resolves the game directory for the current profile.
     * Delegates to Tools.getGameDirPath, the same resolution logic used everywhere else
     * a profile's actual .minecraft-style directory is needed (handles the custom gameDir
     * field, its "amethyst://" prefix for paths relative to the storage root, and the default
     * shared game directory when no custom one is set).
     */
    private String resolveGameDir() {
        return Tools.getGameDirPath(mTempProfile).getAbsolutePath();
    }

    private void bindViews(@NonNull View view){
        mDefaultControl = view.findViewById(R.id.vprof_editor_ctrl_spinner);
        mDefaultRuntime = view.findViewById(R.id.vprof_editor_spinner_runtime);
        mDefaultRenderer = view.findViewById(R.id.vprof_editor_profile_renderer);
        mDefaultVersion = view.findViewById(R.id.vprof_editor_version_spinner);

        mModloaderSpinner = view.findViewById(R.id.vprof_editor_modloader_spinner);
        mModloaderActionButton = view.findViewById(R.id.vprof_editor_modloader_button);
        mModLibraryButton = view.findViewById(R.id.vprof_editor_mod_library_button);

        mDefaultPath = view.findViewById(R.id.vprof_editor_path);
        mDefaultName = view.findViewById(R.id.vprof_editor_profile_name);
        mDefaultJvmArgument = view.findViewById(R.id.vprof_editor_jre_args);

        mSaveButton = view.findViewById(R.id.vprof_editor_save_button);
        mDeleteButton = view.findViewById(R.id.vprof_editor_delete_button);
        mControlSelectButton = view.findViewById(R.id.vprof_editor_ctrl_button);
        mVersionSelectButton = view.findViewById(R.id.vprof_editor_version_button);
        mGameDirButton = view.findViewById(R.id.vprof_editor_path_button);
        mProfileIcon = view.findViewById(R.id.vprof_editor_profile_icon);
    }

    private void save(){
        //First, check for potential issues in the inputs
        mTempProfile.lastVersionId = mDefaultVersion.getText().toString();
        mTempProfile.controlFile = mDefaultControl.getText().toString();
        mTempProfile.name = mDefaultName.getText().toString();
        mTempProfile.javaArgs = mDefaultJvmArgument.getText().toString()
                .replaceAll("[\r\n]+", " ")
                .trim();
        mTempProfile.gameDir = mDefaultPath.getText().toString();

        if(mTempProfile.controlFile.isEmpty()) mTempProfile.controlFile = null;
        if(mTempProfile.javaArgs.isEmpty()) mTempProfile.javaArgs = null;
        if(mTempProfile.gameDir.isEmpty()) mTempProfile.gameDir = null;

        Runtime selectedRuntime = (Runtime) mDefaultRuntime.getSelectedItem();
        mTempProfile.javaDir = (selectedRuntime.name.equals("<Default>") || selectedRuntime.versionString == null)
                ? null : Tools.LAUNCHERPROFILES_RTPREFIX + selectedRuntime.name;

        if(mDefaultRenderer.getSelectedItemPosition() == mRenderNames.size()) mTempProfile.pojavRendererName = null;
        else mTempProfile.pojavRendererName = mRenderNames.get(mDefaultRenderer.getSelectedItemPosition());


        LauncherProfiles.mainProfileJson.profiles.put(mProfileKey, mTempProfile);
        LauncherProfiles.write();
        ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, mProfileKey);
    }

    @Override
    public void onCropped(Bitmap contentBitmap) {
        mProfileIcon.setImageBitmap(contentBitmap);
        Log.i("bitmap", "w="+contentBitmap.getWidth() +" h="+contentBitmap.getHeight());
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (Base64OutputStream base64OutputStream = new Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP)) {
            contentBitmap.compress(
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ?
                    // On Android < 30, there was no distinction between "lossy" and "lossless",
                    // and the type is picked by the quality parameter. We set the quality to 60.
                    // so it should be lossy,
                    Bitmap.CompressFormat.WEBP:
                    // On Android >= 30, we can explicitly specify that we want lossy compression
                    // with the visual quality of 60.
                    Bitmap.CompressFormat.WEBP_LOSSY,
                60,
                base64OutputStream
            );
            base64OutputStream.flush();
            byteArrayOutputStream.flush();
        }catch (IOException e) {
            Tools.showErrorRemote(e);
            return;
        }
        String iconLine = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
        mTempProfile.icon = "data:image/webp;base64," + iconLine;
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }
}
