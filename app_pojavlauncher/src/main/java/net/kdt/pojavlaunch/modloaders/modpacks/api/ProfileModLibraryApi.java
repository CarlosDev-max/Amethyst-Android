package net.kdt.pojavlaunch.modloaders.modpacks.api;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
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
    private final String mProjectType;

    public ProfileModLibraryApi(ModpackApi delegate, File profileGameDir) {
        this(delegate, profileGameDir, "mod");
    }

    public ProfileModLibraryApi(ModpackApi delegate, File profileGameDir, String projectType) {
        mDelegate = delegate;
        mProfileGameDir = profileGameDir;
        mProjectType = projectType == null ? "mod" : projectType;
        Log.d(TAG, "Initialized: gameDir=" + profileGameDir.getAbsolutePath() + " type=" + mProjectType);
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
    public ModDetail getModDetails(net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem item, SearchFilters searchFilters) {
        return mDelegate.getModDetails(item, searchFilters);
    }

    @Override
    public void handleInstallation(Context context, ModDetail modDetail, int selectedVersion) {
        switch (mProjectType) {
            case "shader":
                downloadModToFolder(context, modDetail, selectedVersion, new File(mProfileGameDir, "shaderpacks"));
                break;
            case "resourcepack":
                downloadModToFolder(context, modDetail, selectedVersion, new File(mProfileGameDir, "resourcepacks"));
                break;
            case "datapack":
                installDatapack(context, modDetail, selectedVersion);
                break;
            default:
                // The mod library only ever deals with individual mods, never modpacks - always
                // install straight into the target profile's mods/ folder.
                downloadModToProfile(context, modDetail, selectedVersion, mProfileGameDir);
                break;
        }
    }

    /**
     * Data packs live inside a world folder, so the user picks which world receives the pack:
     * any world of this instance plus the worlds of the local server bundles.
     */
    private void installDatapack(Context context, ModDetail modDetail, int selectedVersion) {
        final java.util.List<File> targets = listDatapackTargets();
        Tools.runOnUiThread(() -> {
            if (targets.isEmpty()) {
                Tools.dialog(context, context.getString(R.string.global_error),
                        context.getString(R.string.content_datapack_no_targets));
                return;
            }
            String[] names = new String[targets.size()];
            for (int i = 0; i < targets.size(); i++) {
                File target = targets.get(i);
                if (new File(target, net.kdt.pojavlaunch.servers.ServerBundleGenerator.SERVER_JAR_NAME).isFile()) {
                    names[i] = context.getString(R.string.content_datapack_server_prefix, target.getName());
                } else if ("saves".equals(target.getParentFile().getName())) {
                    names[i] = target.getName();
                } else {
                    names[i] = target.getParentFile().getName() + " / " + target.getName();
                }
            }
            new androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.content_datapack_choose_title)
                    .setItems(names, (dialog, which) ->
                            downloadModToFolder(context, modDetail, selectedVersion,
                                    new File(targets.get(which), "datapacks")))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private java.util.List<File> listDatapackTargets() {
        java.util.List<File> targets = new java.util.ArrayList<>();
        collectWorlds(new File(mProfileGameDir, "saves"), targets);
        for (File bundle : net.kdt.pojavlaunch.servers.ServerBundleGenerator.listBundles()) {
            // A server bundle is itself the world folder (level.dat at its root).
            if (new File(bundle, "level.dat").isFile()) {
                targets.add(bundle);
            } else {
                collectWorlds(bundle, targets);
            }
        }
        return targets;
    }

    private static void collectWorlds(File root, java.util.List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;
        java.util.Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory() && new File(child, "level.dat").isFile()) out.add(child);
        }
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
