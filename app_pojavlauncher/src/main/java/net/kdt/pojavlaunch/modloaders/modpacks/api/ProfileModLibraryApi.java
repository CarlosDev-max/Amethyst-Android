package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;

import java.io.File;

/**
 * Decorates a ModpackApi so that, when used from the mod library screen (individual mods for
 * an already-set-up profile), the "install" action downloads the mod jar straight into the
 * profile's mods/ folder instead of going through the usual modpack/mod-loader install flow.
 * All other calls (search, get details, etc.) are simply forwarded to the wrapped API.
 */
public class ProfileModLibraryApi implements ModpackApi {
    private static final String TAG = "ProfileModLibrary";
    private final ModpackApi mDelegate;
    private final File mProfileGameDir;

    public ProfileModLibraryApi(ModpackApi delegate, File profileGameDir) {
        mDelegate = delegate;
        mProfileGameDir = profileGameDir;
        Log.d(TAG, "Initialized: gameDir=" + profileGameDir.getAbsolutePath());
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        Log.d(TAG, "searchMod: query=" + (searchFilters.name != null ? searchFilters.name : "") + 
                   " mcVersion=" + searchFilters.mcVersion + 
                   " loader=" + searchFilters.modLoader);
        return mDelegate.searchMod(searchFilters, previousPageResult);
    }

    @Override
    public ModDetail getModDetails(net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem item) {
        return mDelegate.getModDetails(item);
    }

    @Override
    public void handleInstallation(Context context, ModDetail modDetail, int selectedVersion) {
        // The mod library only ever deals with individual mods, never modpacks - always
        // install straight into the target profile's mods/ folder.
        downloadModToProfile(context, modDetail, selectedVersion, mProfileGameDir);
    }

    @Override
    public ModLoader installMod(ModDetail modDetail, int selectedVersion) {
        throw new UnsupportedOperationException("Not used by the mod library - use handleInstallation");
    }

    @Override
    public ModLoader importModpack(File modpackFile) {
        throw new UnsupportedOperationException("Not supported from the mod library");
    }
}
