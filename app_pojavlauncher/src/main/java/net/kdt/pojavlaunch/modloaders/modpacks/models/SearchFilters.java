package net.kdt.pojavlaunch.modloaders.modpacks.models;

import org.jetbrains.annotations.Nullable;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    public boolean isModpack;
    public String name;
    @Nullable public String mcVersion;
    /** Loader id as used by Modrinth categories: "forge", "fabric", "quilt", "neoforge".
     *  Null or empty means no loader filtering. Ignored when isModpack is true. */
    @Nullable public String modLoader;

}
