package net.kdt.pojavlaunch.modloaders.modpacks.api;


import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;

import java.io.IOException;
import java.io.File;
import java.security.NoSuchAlgorithmException;


/**
 *
 */
public interface ModpackApi {

    /**
     * @param searchFilters Filters
     * @param previousPageResult The result from the previous page
     * @return the list of mod items from specified offset
     */
    SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult);

    /**
     * @param searchFilters Filters
     * @return A list of mod items
     */
    default SearchResult searchMod(SearchFilters searchFilters) {
        return searchMod(searchFilters, null);
    }

    /**
     * Fetch the mod details
     * @param item The moditem that was selected
     * @return Detailed data about a mod(pack)
     */
    ModDetail getModDetails(ModItem item);

    /**
     * Download and install the mod(pack)
     * @param modDetail The mod detail data
     * @param selectedVersion The selected version
     */
    default void handleInstallation(Context context, ModDetail modDetail, int selectedVersion) {
        // Doing this here since when starting installation, the progress does not start immediately
        // which may lead to two concurrent installations (very bad)
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                ModLoader loaderInfo = installMod(modDetail, selectedVersion);
                if (loaderInfo == null) return;
                loaderInfo.getDownloadTask(new NotificationDownloadListener(context, loaderInfo)).run();
            }catch (IOException e) {
                Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
            }
        });
    }

    /**
     * Install the mod(pack).
     * May require the download of additional files.
     * May requires launching the installation of a modloader
     * @param modDetail The mod detail data
     * @param selectedVersion The selected version
     */
    ModLoader installMod(ModDetail modDetail, int selectedVersion) throws IOException;

    /**
     * Downloads a single mod's jar (NOT a modpack, NOT a mod loader) straight into an existing
     * profile's mods/ folder. Used by the mod library screen, where the user is browsing
     * individual mods to add to a profile that's already set up with a compatible loader.
     * @param modDetail The mod detail data (isModpack must be false)
     * @param selectedVersion The selected version index, as in installMod
     * @param profileGameDir The profile's .minecraft-style game directory (mods/ is created under it)
     */
    default void downloadModToProfile(Context context, ModDetail modDetail, int selectedVersion, File profileGameDir) {
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String url = modDetail.versionUrls[selectedVersion];
                String sha1 = modDetail.versionHashes[selectedVersion];
                int fileSize = modDetail.versionFileSizes != null ? modDetail.versionFileSizes[selectedVersion] : 0;
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                File modsDir = new File(profileGameDir, "mods");
                if (!modsDir.exists() && !modsDir.mkdirs())
                    throw new IOException("Could not create mods directory: " + modsDir);

                ModDownloader downloader = new ModDownloader(modsDir, fileSize <= 0);
                downloader.submitDownload(fileSize, fileName, sha1, url);
                downloader.awaitFinish(new DownloaderProgressWrapper(
                        R.string.modpack_download_downloading_mods, ProgressLayout.INSTALL_MODPACK));
                Tools.runOnUiThread(() -> ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK));
            } catch (IOException e) {
                Tools.runOnUiThread(() -> ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK));
                Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
            }
        });
    }

    /**
     * Imports the mod(pack) from a file.
     * May require the download of additional files.
     * May requires launching the installation of a modloader
     * @param modpackFile Zip file to mrpack or cf zip pack
     */
    ModLoader importModpack(File modpackFile) throws IOException, NoSuchAlgorithmException;
}
