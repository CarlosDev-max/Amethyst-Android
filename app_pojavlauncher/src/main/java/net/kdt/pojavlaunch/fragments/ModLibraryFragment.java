package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ProfileModLibraryApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import java.io.File;

/**
 * Browse and install individual Modrinth mods straight into a specific profile's mods/ folder.
 * Unlike SearchModFragment (modpacks, with a manual filter dialog), the version and mod loader
 * filters here come pre-set from the profile being edited - there is nothing to configure.
 */
public class ModLibraryFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    public static final String TAG = "ModLibrary";

    /** String extra: the profile's Minecraft version (SearchFilters.mcVersion). */
    public static final String ARG_MC_VERSION = "mc_version";
    /** String extra: the profile's mod loader id, as used by Modrinth categories
     *  ("forge", "fabric", "quilt", "neoforge"). May be absent/empty if none. */
    public static final String ARG_MOD_LOADER = "mod_loader";
    /** String extra: absolute path to the profile's game directory (where mods/ lives). */
    public static final String ARG_GAME_DIR = "game_dir";
    /** String extra: profile name/title, only used to label the screen. */
    public static final String ARG_PROFILE_TITLE = "profile_title";

    private RecyclerView mRecyclerview;
    private ModItemAdapter mModItemAdapter;
    private ProgressBar mProgressBar;
    private TextView mStatusTextView;
    private TextView mContextTextView;
    private EditText mSearchEditText;
    private ColorStateList mDefaultTextColor;

    private View mOverlay;
    private float mOverlayTopCache;
    private final RecyclerView.OnScrollListener mOverlayPositionListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            mOverlay.setY(MathUtils.clamp(mOverlay.getY() - dy, -mOverlay.getHeight(), mOverlayTopCache));
        }
    };

    private ModpackApi mModpackApi;
    private final SearchFilters mSearchFilters = new SearchFilters();
    private java.util.Timer mSearchDebounceTimer;  // Track debounce timer for cleanup

    public ModLibraryFragment() {
        super(R.layout.fragment_mod_library);
        mSearchFilters.isModpack = false;
        mSearchFilters.name = "";
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        // Keep this browser on the Modrinth path used by individual mods. The mod-library flow
        // should not depend on the modpack mixed-source search, and this avoids empty results when
        // one of the upstream APIs is unavailable or filtered out for the profile.
        Bundle args = getArguments();
        if (args != null) {
            mSearchFilters.mcVersion = normalizeVersion(args.getString(ARG_MC_VERSION, ""));
            mSearchFilters.modLoader = normalizeLoader(args.getString(ARG_MOD_LOADER, ""));
            String gameDirPath = args.getString(ARG_GAME_DIR);

            if (gameDirPath != null && !gameDirPath.isEmpty()) {
                File gameDir = new File(gameDirPath);
                mModpackApi = new ProfileModLibraryApi(new ModrinthApi(), gameDir);
            } else {
                android.util.Log.w(TAG, "onAttach: Missing gameDir argument, using Modrinth fallback");
                mModpackApi = new ModrinthApi();
            }
        } else {
            android.util.Log.w(TAG, "onAttach: No arguments provided, using Modrinth fallback");
            mModpackApi = new ModrinthApi();
        }
    }

    private String normalizeVersion(String version) {
        if (version == null) return "";
        String trimmed = version.trim();
        if (trimmed.isEmpty()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?").matcher(trimmed);
        return matcher.find() ? matcher.group() : trimmed;
    }

    private String normalizeLoader(String loader) {
        if (loader == null) return "";
        String trimmed = loader.trim().toLowerCase();
        if (trimmed.isEmpty()) return "";
        switch (trimmed) {
            case "forge":
            case "fabric":
            case "quilt":
            case "neoforge":
                return trimmed;
            default:
                return "";
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mModItemAdapter = new ModItemAdapter(getResources(), mModpackApi, this);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);
        mOverlayTopCache = getResources().getDimension(R.dimen.fragment_padding_medium);

        mOverlay = view.findViewById(R.id.mod_library_overlay);
        mContextTextView = view.findViewById(R.id.mod_library_context_textview);
        mSearchEditText = view.findViewById(R.id.mod_library_edittext);
        mProgressBar = view.findViewById(R.id.mod_library_progressbar);
        mRecyclerview = view.findViewById(R.id.mod_library_list);
        mStatusTextView = view.findViewById(R.id.mod_library_status_text);

        mDefaultTextColor = mStatusTextView.getTextColors();

        // Update context label (profile name + loader)
        Bundle args = getArguments();
        String profileTitle = args != null ? args.getString(ARG_PROFILE_TITLE, "") : "";
        String loaderLabel = mSearchFilters.modLoader == null || mSearchFilters.modLoader.isEmpty()
                ? getString(R.string.pedit_modloader_vanilla) : mSearchFilters.modLoader;
        mContextTextView.setText(getString(R.string.mod_library_context_format, profileTitle, loaderLabel));

        mRecyclerview.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerview.setAdapter(mModItemAdapter);
        mRecyclerview.addOnScrollListener(mOverlayPositionListener);

        // Search on editor action (enter key)
        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return true;
        });
        
        // Live search on text change (debounced 500ms for API efficiency)
        mSearchEditText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mSearchDebounceTimer != null) mSearchDebounceTimer.cancel();
                
                // Debounce: wait 500ms after user stops typing before searching
                mSearchDebounceTimer = new java.util.Timer();
                mSearchDebounceTimer.schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        if (isAdded() && mSearchEditText != null && mModItemAdapter != null) {
                            requireActivity().runOnUiThread(() -> {
                                searchMods(mSearchEditText.getText().toString());
                            });
                        }
                    }
                }, 500);
            }
            
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });

        // Position overlay above list
        mOverlay.post(() -> {
            int overlayHeight = mOverlay.getHeight();
            mRecyclerview.setPadding(mRecyclerview.getPaddingLeft(),
                    mRecyclerview.getPaddingTop() + overlayHeight,
                    mRecyclerview.getPaddingRight(),
                    mRecyclerview.getPaddingBottom());
        });

        // Initial search (empty query)
        searchMods(null);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // Clean up timer
        if (mSearchDebounceTimer != null) {
            mSearchDebounceTimer.cancel();
            mSearchDebounceTimer = null;
        }
        
        ProgressKeeper.removeTaskCountListener(mModItemAdapter);
        mRecyclerview.removeOnScrollListener(mOverlayPositionListener);
    }

    @Override
    public void onSearchFinished() {
        mProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        mProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.VISIBLE);
        switch (error) {
            case ERROR_INTERNAL:
                mStatusTextView.setTextColor(Color.RED);
                mStatusTextView.setText(R.string.search_modpack_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_modpack_no_result);
                break;
        }
    }

    private void searchMods(String name) {
        // Safety checks - ensure fragment is ready
        if (mModItemAdapter == null || mSearchFilters == null || mProgressBar == null) {
            android.util.Log.e(TAG, "searchMods called before fragment is fully initialized");
            return;
        }
        
        // Show progress
        mProgressBar.setVisibility(View.VISIBLE);
        mStatusTextView.setVisibility(View.GONE);
        
        // Normalize search query (empty vs null both become "")
        String searchQuery = name == null ? "" : name.trim();

        // Store search query and keep loader/version filters consistent for offline profile browsing.
        mSearchFilters.name = searchQuery;
        mSearchFilters.isModpack = false;
        mSearchFilters.mcVersion = normalizeVersion(mSearchFilters.mcVersion);
        mSearchFilters.modLoader = normalizeLoader(mSearchFilters.modLoader);
        
        // Log search parameters for debugging
        android.util.Log.d(TAG, String.format(
            "searchMods: query='%s' mcVersion='%s' loader='%s' isModpack=false",
            searchQuery, 
            mSearchFilters.mcVersion == null ? "" : mSearchFilters.mcVersion,
            mSearchFilters.modLoader == null ? "" : mSearchFilters.modLoader
        ));
        
        // Perform search through adapter
        mModItemAdapter.performSearchQuery(mSearchFilters);
    }
}
