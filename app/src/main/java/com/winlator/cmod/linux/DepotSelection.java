package com.winlator.cmod.linux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Picks the depots to download for one platform, language and version of a game. */
public final class DepotSelection {
    /** One {@code download_depot} to run. */
    public static final class Pick {
        public final long depot;
        public final String manifest;
        /** Installed size in bytes, 0 when unknown. */
        public final long size;
        public final long download;

        Pick(long depot, String manifest, long size, long download) {
            this.depot = depot;
            this.manifest = manifest;
            this.size = size;
            this.download = download;
        }
    }

    private DepotSelection() {
    }

    /**
     * @param platform  {@code windows} or {@code linux}
     * @param language  Steam language name such as {@code english}; depots for other languages are left out
     * @param branch    the branch whose manifests are used, or null when {@code manifests} names them all
     * @param manifests depot id to manifest id: replaces the branch's manifest, and supplies depots the branch does not list
     *                  (a version the client no longer offers as a branch); may be null
     * @param owned     apps the account owns: depots of a DLC are only taken when the DLC is owned; null means do not check
     */
    public static List<Pick> select(SteamAppInfo info, String platform, String language, String branch, Map<Long, String> manifests, Set<Long> owned) {
        List<Pick> picks = new ArrayList<>();
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        for (SteamAppInfo.Depot depot : info.depots) {
            if (depot.fromApp != 0 || !depot.forPlatform(platform)) continue;
            if (!depot.language.isEmpty() && !depot.language.equals(lang)) continue;
            if (depot.dlcAppId != 0 && owned != null && !owned.contains(depot.dlcAppId)) continue;
            SteamAppInfo.Manifest known = branch == null ? null : depot.manifests.get(branch);
            String override = manifests == null ? null : manifests.get(depot.id);
            if (override != null) {
                // Sizes are only known when the override is the manifest Steam lists on some branch.
                SteamAppInfo.Manifest same = null;
                for (SteamAppInfo.Manifest candidate : depot.manifests.values()) if (candidate.gid.equals(override)) same = candidate;
                picks.add(new Pick(depot.id, override, same == null ? 0 : same.size, same == null ? 0 : same.download));
            }
            else if (known != null) {
                picks.add(new Pick(depot.id, known.gid, known.size, known.download));
            }
        }
        return picks;
    }

    /**
     * Branches that have a main depot for the platform (a depot not meant for one language only), newest build first.
     * Branches that need a password are left out.
     */
    public static List<SteamAppInfo.Branch> branches(SteamAppInfo info, String platform) {
        Set<String> names = new LinkedHashSet<>();
        for (SteamAppInfo.Depot depot : info.depots) {
            if (depot.fromApp != 0 || depot.dlcAppId != 0 || !depot.forPlatform(platform) || depot.osList.isEmpty() && !depot.language.isEmpty()) continue;
            names.addAll(depot.manifests.keySet());
        }
        List<SteamAppInfo.Branch> result = new ArrayList<>();
        for (String name : names) {
            SteamAppInfo.Branch branch = info.branches.get(name);
            if (branch != null && !branch.passwordRequired) result.add(branch);
        }
        Collections.sort(result, new Comparator<SteamAppInfo.Branch>() {
            @Override public int compare(SteamAppInfo.Branch a, SteamAppInfo.Branch b) {
                return Long.compare(b.buildId, a.buildId);
            }
        });
        return result;
    }

    public static long totalSize(List<Pick> picks) {
        long total = 0;
        for (Pick pick : picks) total += pick.size;
        return total;
    }
}
