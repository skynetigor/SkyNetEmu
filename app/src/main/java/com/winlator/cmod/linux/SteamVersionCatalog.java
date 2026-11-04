package com.winlator.cmod.linux;

import com.winlator.cmod.gameprofile.GameProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** What the "Download game version" dialog offers: owned games with a profile, and for each game and platform the versions Steam can serve. */
public final class SteamVersionCatalog {
    /** One downloadable version. */
    public static final class Entry {
        public final String label;
        public final String detail;
        /** The profile's version id when the entry is one of the profile's versions, else "". */
        public final String versionId;
        public final String branch;
        public final Map<Long, String> manifests;
        public final List<DepotSelection.Pick> picks;
        /** The platform the profile says this version runs best on, or null (a plain branch, or a profile that does not say). */
        public final GameProfile.Platform preferredPlatform;

        Entry(String label, String detail, String versionId, String branch, Map<Long, String> manifests, List<DepotSelection.Pick> picks,
              GameProfile.Platform preferredPlatform) {
            this.preferredPlatform = preferredPlatform;
            this.label = label;
            this.detail = detail;
            this.versionId = versionId;
            this.branch = branch;
            this.manifests = manifests;
            this.picks = picks;
        }

        public long size() {
            return DepotSelection.totalSize(picks);
        }
    }

    private SteamVersionCatalog() {
    }

    public static String platformName(GameProfile.Platform platform) {
        return platform == GameProfile.Platform.LINUX ? "linux" : "windows";
    }

    /** The games of the registry that the account owns and whose depots the client knows: only those can be downloaded. */
    public static List<GameProfile> downloadableGames(List<GameProfile> registry, SteamAppInfo.Store store, Set<Long> owned) {
        List<GameProfile> games = new ArrayList<>();
        for (GameProfile game : registry) {
            long appId;
            try {
                appId = Long.parseLong(game.steamAppId);
            }
            catch (NumberFormatException e) {
                continue;
            }
            if (appId > 0 && owned.contains(appId) && store.has(appId)) games.add(game);
        }
        return games;
    }

    /**
     * The versions of a game on a platform: the profile's versions that name a Steam source, then every other branch of the app that has
     * a main depot for the platform. Entries with nothing to download are left out.
     */
    public static List<Entry> entries(GameProfile game, SteamAppInfo info, GameProfile.Platform platform, String language, Set<Long> owned) {
        String os = platformName(platform);
        List<Entry> entries = new ArrayList<>();
        java.util.Set<String> usedBranches = new java.util.HashSet<>();
        for (GameProfile.Version version : game.versions) {
            GameProfile.SteamSource source = version.steamSource(platform);
            if (source == null) continue;
            List<DepotSelection.Pick> picks = DepotSelection.select(info, os, language, source.branch.isEmpty() ? null : source.branch, source.manifests, owned);
            if (picks.isEmpty()) continue;
            if (!source.branch.isEmpty()) usedBranches.add(source.branch);
            // A build that is no branch any more has no size in Steam's data: the profile's one stands in for a single depot.
            if (source.size > 0 && picks.size() == 1 && picks.get(0).size == 0) {
                DepotSelection.Pick only = picks.get(0);
                picks = new ArrayList<>(java.util.Collections.singletonList(DepotSelection.pick(only.depot, only.manifest, source.size)));
            }
            entries.add(new Entry(source.label.isEmpty() ? version.label : source.label,
                    source.branch.isEmpty() ? "Selected build" : "Steam branch " + source.branch, version.id,
                    source.branch, new LinkedHashMap<>(source.manifests), picks, version.preferredPlatform));
        }
        for (SteamAppInfo.Branch branch : DepotSelection.branches(info, os)) {
            if (usedBranches.contains(branch.name)) continue;
            List<DepotSelection.Pick> picks = DepotSelection.select(info, os, language, branch.name, null, owned);
            if (picks.isEmpty()) continue;
            String detail = branch.description.isEmpty() ? "Build " + branch.buildId : branch.description + " (build " + branch.buildId + ")";
            entries.add(new Entry(branch.name, detail, "", branch.name, new LinkedHashMap<>(), picks, null));
        }
        return entries;
    }

    /** An entry for depot manifests the user typed ("284161 7188555249273500625, ..."), or null when the text is not a list of such pairs. */
    public static Entry custom(SteamAppInfo info, GameProfile.Platform platform, String language, Set<Long> owned, String text) {
        Map<Long, String> manifests = new LinkedHashMap<>();
        for (String part : text.split("[,;\\n]")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String[] pair = trimmed.split("[\\s:=]+");
            if (pair.length != 2 || !pair[0].matches("[0-9]{1,10}") || !pair[1].matches("[0-9]{1,20}")) return null;
            manifests.put(Long.parseLong(pair[0]), pair[1]);
        }
        if (manifests.isEmpty()) return null;
        List<DepotSelection.Pick> picks = new ArrayList<>();
        for (Map.Entry<Long, String> m : manifests.entrySet()) {
            long size = 0;
            for (SteamAppInfo.Depot depot : info.depots) {
                if (depot.id != m.getKey()) continue;
                for (SteamAppInfo.Manifest known : depot.manifests.values()) if (known.gid.equals(m.getValue())) size = known.size;
            }
            picks.add(DepotSelection.pick(m.getKey(), m.getValue(), size));
        }
        return new Entry("Custom manifests", manifests.size() + " depot" + (manifests.size() == 1 ? "" : "s"), "", "", manifests, picks, null);
    }

    /** Steam's name for the language of the device (the English one when Steam has no depot language for it). */
    public static String steamLanguage(Locale locale) {
        switch (locale.getLanguage()) {
            case "de": return "german";
            case "fr": return "french";
            case "es": return "spanish";
            case "it": return "italian";
            case "ru": return "russian";
            case "pl": return "polish";
            case "cs": return "czech";
            case "pt": return "BR".equals(locale.getCountry()) ? "brazilian" : "portuguese";
            case "ja": return "japanese";
            case "ko": return "koreana";
            case "zh": return "TW".equals(locale.getCountry()) || "HK".equals(locale.getCountry()) ? "tchinese" : "schinese";
            case "tr": return "turkish";
            case "nl": return "dutch";
            case "uk": return "ukrainian";
            default: return "english";
        }
    }

    /** The job for an entry: the depots to fetch and the name of the folder (and shortcut). */
    public static SteamDownloadJob job(GameProfile game, Entry entry, GameProfile.Platform platform, String language, java.io.File versionsRoot,
                                       boolean bothPlatforms) {
        String suffix = bothPlatforms ? (platform == GameProfile.Platform.LINUX ? "Linux" : "Windows") : "";
        String installDir = game.steamInstallDir == null || game.steamInstallDir.isEmpty() ? game.name : game.steamInstallDir;
        String folder = SteamDepotMerger.freeFolderName(versionsRoot, SteamDepotMerger.folderName(installDir, entry.label, suffix));
        String key = game.id + "-" + Long.toHexString(System.currentTimeMillis());
        SteamDownloadJob job = new SteamDownloadJob(key, Long.parseLong(game.steamAppId), game.id, game.name, entry.versionId, entry.label,
                platformName(platform), language, folder);
        for (DepotSelection.Pick pick : entry.picks) job.depots.add(new SteamDownloadJob.Depot(pick.depot, pick.manifest, pick.size));
        return job;
    }
}
