package com.winlator.cmod.container;

import android.content.Context;

import com.winlator.cmod.contents.ContainerConfigCatalog;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.telemetry.ErrorReporter;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives a game the container (its "environment") for a registry config: the one made for it earlier when there is one, otherwise
 * a new one made from the config's profile with every missing component installed. A container made here remembers the config
 * ({@code configId}) and that the user did not make it by hand ({@code autoCreated}); two games with the same config and the
 * same Wine share the container.
 *
 * Call {@link #ensure} on a background thread: it downloads and builds.
 */
public final class EnvironmentProvider {
    public static final String EXTRA_CONFIG_ID = "configId";
    public static final String EXTRA_AUTO_CREATED = "autoCreated";

    public static final class Result {
        /** Null when the container could not be made; {@link #warnings} says why. */
        public final Container container;
        /** True when the container was made now, false when an earlier one was reused. */
        public final boolean created;
        public final List<String> warnings;

        Result(Container container, boolean created, List<String> warnings) {
            this.container = container;
            this.created = created;
            this.warnings = warnings;
        }
    }

    private EnvironmentProvider() {}

    /**
     * @param wineOverride a Wine/Proton identifier to use instead of the config's, or null
     */
    public static Result ensure(Context context, ContainerManager manager, ContentsManager contents, ContainerConfigCatalog.Config config,
                                String wineOverride, ContainerImporter.Progress progress) {
        List<String> warnings = new ArrayList<>();
        String text = ContainerConfigCatalog.profileText(context, config, true);
        if (text == null) {
            warnings.add("Cannot download the configuration \"" + config.name + "\" and there is no copy of it on the device.");
            return new Result(null, false, warnings);
        }
        ContainerProfile.Envelope envelope;
        try {
            envelope = ContainerProfile.parse(text);
        }
        catch (JSONException e) {
            warnings.add("The configuration \"" + config.name + "\" is not valid: " + e.getMessage());
            return new Result(null, false, warnings);
        }

        JSONObject data = envelope.container;
        String wine = wineOverride != null ? wineOverride : data.optString("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier());
        Container existing = find(manager, config.id, wine);
        if (existing != null) return new Result(existing, false, warnings);

        try {
            if (wineOverride != null) {
                data.put("wineVersion", wineOverride);
                data.put("name", config.name + " · " + wineOverride);
            }
            else data.put("name", config.name);
            JSONObject extra = data.optJSONObject("extraData");
            if (extra == null) extra = new JSONObject();
            extra.put(EXTRA_CONFIG_ID, config.id);
            extra.put(EXTRA_AUTO_CREATED, "1");
            data.put("extraData", extra);
        }
        catch (JSONException e) {
            warnings.add(e.getMessage());
            return new Result(null, false, warnings);
        }

        ContainerImporter.Result result = ContainerImporter.run(context, manager, contents, envelope, progress);
        warnings.addAll(result.warnings);
        for (String warning : result.warnings) ErrorReporter.report("import_warning", "ImportWarning", warning);
        return new Result(result.container, result.container != null, warnings);
    }

    /** The container made from the config with this Wine, or null. */
    public static Container find(ContainerManager manager, String configId, String wineVersion) {
        for (Container container : manager.getContainers()) {
            if (configId.equals(container.getExtra(EXTRA_CONFIG_ID)) && wineVersion.equals(container.getWineVersion())) return container;
        }
        return null;
    }
}
