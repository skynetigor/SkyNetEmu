package com.winlator.cmod.gameprofile;

import com.winlator.cmod.contents.ContainerConfigCatalog;

import java.util.Map;

/**
 * Picks the container config for a game version on this device. In order:
 * the version's choice for the processor family; its preferred config when that suits the family (or is made for any);
 * the first listed config made for the family; the preferred config anyway, flagged as not tested on this processor.
 */
public final class ConfigChooser {
    public static final class Choice {
        public final String configId;
        /** False when the config was not made for this processor (the screen says so). */
        public final boolean suitsDevice;

        Choice(String configId, boolean suitsDevice) {
            this.configId = configId;
            this.suitsDevice = suitsDevice;
        }
    }

    private ConfigChooser() {}

    /** @return null when the version lists no config */
    public static Choice choose(GameProfile.Version version, String socFamily, Map<String, ContainerConfigCatalog.Config> configs) {
        if (version == null || version.containers.isEmpty()) return null;
        String bySoc = socFamily == null ? null : version.preferredBySoc.get(socFamily);
        if (bySoc != null && configs.containsKey(bySoc)) return new Choice(bySoc, true);
        String preferred = version.preferredContainer != null && configs.containsKey(version.preferredContainer)
                ? version.preferredContainer : null;
        if (preferred != null && configs.get(preferred).suits(socFamily)) return new Choice(preferred, true);
        for (String id : version.containers) {
            ContainerConfigCatalog.Config config = configs.get(id);
            if (config != null && !config.socs.isEmpty() && config.suits(socFamily)) return new Choice(id, true);
        }
        if (preferred != null) return new Choice(preferred, false);
        for (String id : version.containers) if (configs.containsKey(id)) return new Choice(id, false);
        return null;
    }
}
