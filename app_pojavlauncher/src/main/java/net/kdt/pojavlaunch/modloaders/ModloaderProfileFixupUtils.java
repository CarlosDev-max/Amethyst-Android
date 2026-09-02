package net.kdt.pojavlaunch.modloaders;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Lets the profile editor's "Install/Change loader" flow update an EXISTING profile's
 * lastVersionId in-place, instead of the loader installer creating a brand new separate
 * profile (which is what most of the *InstallFragment flows do by default when launched
 * from ProfileTypeSelectFragment).
 *
 * Usage: snapshot the versions/ directory before running an installer (jar-based, like
 * Forge/NeoForge, or direct, like Fabric/Quilt), then call {@link #applyNewlyCreatedVersion}
 * after it finishes to detect what got added and point the target profile at it.
 */
public class ModloaderProfileFixupUtils {
    private static final String TAG = "ModloaderProfileFixup";

    /** Snapshot of version folder names currently present, to diff against after install. */
    public static Set<String> snapshotVersions() {
        Set<String> names = new HashSet<>();
        File[] children = new File(Tools.DIR_HOME_VERSION).listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) names.add(child.getName());
            }
        }
        return names;
    }

    /**
     * Compares the current versions/ directory against a prior snapshot, and if exactly one
     * new version folder appeared, points {@code targetProfileKey}'s lastVersionId at it.
     *
     * Safe to call with a null targetProfileKey (no-op) so callers don't need to branch.
     *
     * @return the newly detected version id, or null if none could be determined.
     */
    public static String applyNewlyCreatedVersion(String targetProfileKey, Set<String> versionsBeforeInstall) {
        if (targetProfileKey == null) return null;
        Set<String> after = snapshotVersions();
        after.removeAll(versionsBeforeInstall);
        if (after.size() != 1) {
            // Either nothing new appeared (install failed/was cancelled) or more than one
            // version folder appeared at once (ambiguous - don't guess which one to use).
            Log.w(TAG, "Could not determine the installed version unambiguously, found: " + after);
            return null;
        }
        String newVersionId = after.iterator().next();
        MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(targetProfileKey);
        if (profile == null) {
            Log.w(TAG, "Target profile " + targetProfileKey + " no longer exists, skipping fixup");
            return null;
        }
        profile.lastVersionId = newVersionId;
        LauncherProfiles.write();
        return newVersionId;
    }
}
