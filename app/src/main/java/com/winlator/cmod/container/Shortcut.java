package com.winlator.cmod.container;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GameSaveManager;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class Shortcut {
    public static final String EXTRA_LIBRARY_ORIGIN = "libraryOrigin";
    public static final String ORIGIN_MANUAL = "manual";
    public final Container container;
    public final String name;
    public final String path;
    public Bitmap icon;
    public final File file;
    public File iconFile;
    public final String wmClass;
    private final JSONObject extraData = new JSONObject();
    private Bitmap coverArt;
    private String customCoverArtPath;

    private static final String COVER_ART_DIR = "app_data/cover_arts/";

    public Shortcut(Container container, File file) {
        this.container = container;
        this.file = file;

        String execArgs = "";
        Bitmap icon = null;
        File iconFile = null;
        String wmClass = "";

        File[] iconDirs = {container.getIconsDir(64), container.getIconsDir(48), container.getIconsDir(32), container.getIconsDir(16)};
        String section = "";

        int index;
        for (String line : FileUtils.readLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[")) {
                section = line.substring(1, line.indexOf("]"));
            }
            else {
                index = line.indexOf("=");
                if (index == -1) continue;
                String key = line.substring(0, index);
                String value = line.substring(index+1);

                if (section.equals("Desktop Entry")) {
                    if (key.equals("Exec")) execArgs = value;
                    if (key.equals("Icon")) {
                        for (File iconDir : iconDirs) {
                            File potentialIcon = new File(iconDir, value+".png");
                            if (!potentialIcon.exists()) potentialIcon = new File(iconDir, value+".ico");

                            if (potentialIcon.isFile()){
                                icon = BitmapFactory.decodeFile(potentialIcon.getPath());
                                iconFile = potentialIcon;
                                break;
                            }
                        }
                    }
                    if (key.equals("StartupWMClass")) wmClass = value;
                }
                else if (section.equals("Extra Data")) {
                    try {
                        extraData.put(key, value);
                    }
                    catch (JSONException e) {}
                }
            }
        }

        try {
            File externalStorage = Environment.getExternalStorageDirectory();
            File customIconsDir = new File(externalStorage, "Winlator/icons");
            String baseName = FileUtils.getBasename(file.getPath());
            File userIcon = new File(customIconsDir, baseName + ".user.png");
            File customIcon = userIcon.isFile() ? userIcon : new File(customIconsDir, baseName + ".png");

            if (customIcon.isFile()) {
                Bitmap customBitmap = BitmapFactory.decodeFile(customIcon.getAbsolutePath());
                if (customBitmap != null) {
                    icon = customBitmap;
                    iconFile = customIcon;
                }
            }
        } catch (Exception e) {}

        this.name = FileUtils.getBasename(file.getPath());

        this.icon = icon;
        this.iconFile = iconFile;

        if (execArgs.contains("wine ")) {
            this.path = StringUtils.unescape(execArgs.substring(execArgs.lastIndexOf("wine ") + 4));
        } else {
            this.path = execArgs;
        }

        this.wmClass = wmClass;

        this.customCoverArtPath = getExtra("customCoverArtPath");
        loadCoverArt();
        Container.checkObsoleteOrMissingProperties(extraData);
    }

    private void loadCoverArt() {
        if (customCoverArtPath != null && !customCoverArtPath.isEmpty()) {
            File customCoverArtFile = new File(customCoverArtPath);
            if (customCoverArtFile.isFile()) {
                this.coverArt = BitmapFactory.decodeFile(customCoverArtFile.getPath());
                return;
            }
        }
        File defaultCoverArtFile = new File(COVER_ART_DIR, this.name + ".png");
        if (defaultCoverArtFile.isFile()) {
            this.coverArt = BitmapFactory.decodeFile(defaultCoverArtFile.getPath());
        }
    }

    public Bitmap getCoverArt() { return coverArt; }
    public void setCoverArt(Bitmap coverArt) { this.coverArt = coverArt; }
    public String getCustomCoverArtPath() { return customCoverArtPath; }

    public void setCustomCoverArtPath(String customCoverArtPath) {
        this.customCoverArtPath = customCoverArtPath;
        putExtra("customCoverArtPath", customCoverArtPath);
        saveData();
    }

    public String getExtra(String name) { return getExtra(name, ""); }

    public String getExtra(String name, String fallback) {
        try { return extraData.has(name) ? extraData.getString(name) : fallback; }
        catch (JSONException e) { return fallback; }
    }

    public void putExtra(String name, String value) {
        try {
            if (value != null) {
                extraData.put(name, value);
            }
            else extraData.remove(name);

            if ("hudMode".equals(name) && container != null && !container.isLinux() && value != null) {
                int mode = 0;
                try { mode = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                container.putExtra("hudMode", String.valueOf(mode));
                container.setShowFPS(mode != 0);
                container.saveData();
            }
        }
        catch (JSONException e) {}
    }

    public void saveData() {
        String content = "[Desktop Entry]\n";
        for (String line : FileUtils.readLines(file)) {
            if (line.contains("[Extra Data]")) break;
            if (!line.contains("[Desktop Entry]") && !line.isEmpty()) content += line + "\n";
        }

        if (extraData.length() > 0) {
            content += "\n[Extra Data]\n";
            Iterator<String> keys = extraData.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    content += key + "=" + extraData.getString(key) + "\n";
                } catch (JSONException e) {}
            }
        }

        if (!file.getName().endsWith(".desktop")) {
            return;
        }

        FileUtils.writeString(file, content);
    }

    public void genUUID() {
        if (getExtra("uuid").equals("")) {
            putExtra("uuid", UUID.randomUUID().toString());
            saveData();
        }
    }

    public void saveCustomCoverArt(Bitmap coverArt) {
        try {
            File coverArtDir = new File(container.getRootDir(), COVER_ART_DIR);
            if (!coverArtDir.exists()) {
                coverArtDir.mkdirs();
            }

            File coverFile = new File(coverArtDir, this.name + ".png");
            if (FileUtils.saveBitmapToFile(coverArt, coverFile)) {
                this.coverArt = coverArt;
                setCustomCoverArtPath(coverFile.getPath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void removeCustomCoverArt() {
        if (customCoverArtPath != null && !customCoverArtPath.isEmpty()) {
            File customCoverArtFile = new File(customCoverArtPath);
            if (customCoverArtFile.exists()) customCoverArtFile.delete();
        }

        this.customCoverArtPath = null;
        this.coverArt = null;
        putExtra("customCoverArtPath", null);
        saveData();
    }

    /** Characters a file name cannot hold are replaced; the result is what the shortcut will be called. */
    public static String cleanName(String requested) {
        return requested == null ? "" : requested.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * Renames the shortcut (its .desktop file, which is its name) and moves what is kept under the old name: the Wine
     * .lnk, custom icons and cover art, the play time and play count, and the save backups folder. This object keeps
     * the old name, so the caller reloads the shortcuts afterwards.
     *
     * @return false when the name is empty or taken, or the file could not be renamed; nothing is changed then
     */
    public boolean rename(Context context, String requested) {
        String clean = cleanName(requested);
        if (clean.isEmpty()) return false;
        if (clean.equals(name)) return true;

        File parent = file.getParentFile();
        int dot = file.getName().lastIndexOf('.');
        File target = new File(parent, clean + (dot > 0 ? file.getName().substring(dot) : ".desktop"));
        if (target.exists() || !file.renameTo(target)) return false;

        File oldLink = new File(parent, name + ".lnk");
        File newLink = new File(parent, clean + ".lnk");
        if (oldLink.isFile() && !newLink.exists()) oldLink.renameTo(newLink);

        File iconsDir = new File(Environment.getExternalStorageDirectory(), "Winlator/icons");
        for (String suffix : new String[]{".user.png", ".png"}) moveIfFree(new File(iconsDir, name + suffix), new File(iconsDir, clean + suffix));
        if (container != null) {
            File coverArtDir = new File(container.getRootDir(), COVER_ART_DIR);
            moveIfFree(new File(coverArtDir, name + ".png"), new File(coverArtDir, clean + ".png"));
        }
        File savesRoot = new File(Environment.getExternalStorageDirectory(), "Winlator/Saves");
        moveIfFree(new File(savesRoot, GameSaveManager.sanitize(name)), new File(savesRoot, GameSaveManager.sanitize(clean)));

        SharedPreferences prefs = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.contains(name + "_playtime")) editor.putLong(clean + "_playtime", prefs.getLong(name + "_playtime", 0)).remove(name + "_playtime");
        if (prefs.contains(name + "_play_count")) editor.putInt(clean + "_play_count", prefs.getInt(name + "_play_count", 0)).remove(name + "_play_count");
        editor.apply();
        return true;
    }

    private static void moveIfFree(File from, File to) {
        if (from.exists() && !to.exists()) from.renameTo(to);
    }

    public boolean cloneToContainer(Container newContainer) {
        if (container.isLinux() || newContainer.isLinux()) return false;
        try {
            File newShortcutFile = new File(newContainer.getDesktopDir(), this.file.getName());
            ArrayList<String> lines = FileUtils.readLines(this.file);
            StringBuilder updatedContent = new StringBuilder();
            boolean containerIdFound = false;

            for (String line : lines) {
                if (line.startsWith("container_id:")) {
                    updatedContent.append("container_id:").append(newContainer.id).append("\n");
                    containerIdFound = true;
                } else {
                    updatedContent.append(line).append("\n");
                }
            }

            if (!containerIdFound) {
                updatedContent.append("container_id:").append(newContainer.id).append("\n");
            }

            FileUtils.writeString(newShortcutFile, updatedContent.toString());

            if (this.iconFile != null && this.iconFile.isFile()) {
                File newIconFile = new File(newContainer.getIconsDir(64), this.iconFile.getName());
                FileUtils.copy(this.iconFile, newIconFile);
            }

            return true;
        } catch (Exception e) {
            Log.e("Shortcut", "Failed to clone shortcut to new container", e);
            return false;
        }
    }

    /**
     * A shortcut the container owns: the Steam client of a Linux container and the games installed in it. It cannot be
     * removed (the games' follow Steam: a game uninstalled there loses its shortcut) and has no icon to change.
     */
    public boolean isContainerOwned() {
        return container.isLinux()
                && (com.winlator.cmod.linux.LinuxSession.isSteam(path) || com.winlator.cmod.linux.LinuxSession.isSteamGame(path));
    }

    /** The Steam client uses the container's settings; games keep their own launch settings. */
    public boolean hasFixedSettings() {
        return container.isLinux() && com.winlator.cmod.linux.LinuxSession.isSteam(path);
    }

    public boolean isSteamGame() {
        return container.isLinux() && com.winlator.cmod.linux.LinuxSession.isSteamGame(path);
    }

    public int getContainerId() {
        return container.id;
    }

    public String getExecutable() {
        String exe = "";
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            for (String line : lines) {
                if (line.startsWith("Exec")) {
                    exe = line.substring(line.lastIndexOf("\\") + 1, line.length()).replaceAll("\\s+$", "");
                    break;
                }
            }
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }

        return exe;
    }

    public boolean getRendererNative() {
        String v = getExtra("rendererNative", null);
        return v != null ? v.equals("1") : container.isRendererNative();
    }
    public void setRendererNative(boolean v) { putExtra("rendererNative", v ? "1" : "0"); }

    public boolean getUseDisplayX() {
        String v = getExtra("useDisplayX", null);
        if (v != null) return v.equals("1");
        String legacyDriver = getExtra("displayDriver", null);
        if (legacyDriver != null) return legacyDriver.equalsIgnoreCase("displayx");
        return container.getUseDisplayX();
    }
    public void setUseDisplayX(boolean v) { putExtra("useDisplayX", v ? "1" : "0"); }

    public boolean getTrueDisplayX() {
        String v = getExtra("trueDisplayX", null);
        if (v != null) return v.equals("1");
        v = getLegacyDisplayXValue("trueDisplayX");
        return v != null ? v.equals("1") : container.getTrueDisplayX();
    }
    public void setTrueDisplayX(boolean v) { putExtra("trueDisplayX", v ? "1" : "0"); }

    public String getSurfaceFormat() {
        String v = getExtra("surfaceFormat", null);
        if (v == null || v.isEmpty()) v = getLegacyDisplayXValue("surfaceFormat");
        if (v == null || v.isEmpty()) {
            boolean overridesRenderer = getExtra("rendererNative", null) != null
                    || getExtra("useDisplayX", null) != null
                    || getExtra("displayDriver", null) != null;
            if (!overridesRenderer) return container.getSurfaceFormat();
            return getUseDisplayX() ? "rgba8" : "bgra8";
        }
        return "bgra8".equalsIgnoreCase(v) ? "bgra8" : "rgba8";
    }
    public void setSurfaceFormat(String value) {
        putExtra("surfaceFormat", "bgra8".equalsIgnoreCase(value) ? "bgra8" : "rgba8");
    }

    public boolean getDisplayXPerformanceMode() {
        String v = getExtra("displayXPerformanceMode", null);
        if (v == null) v = getLegacyDisplayXValue("performanceMode");
        return v != null ? v.equals("1") : container.getDisplayXPerformanceMode();
    }
    public void setDisplayXPerformanceMode(boolean v) {
        putExtra("displayXPerformanceMode", v ? "1" : "0");
    }

    public boolean getDisplayXPresentAtRefreshRate() {
        String v = getExtra("displayXPresentAtRefreshRate", null);
        if (v == null) v = getLegacyDisplayXValue("presentRR");
        return v != null ? v.equals("1") : container.getDisplayXPresentAtRefreshRate();
    }
    public void setDisplayXPresentAtRefreshRate(boolean v) {
        putExtra("displayXPresentAtRefreshRate", v ? "1" : "0");
    }

    public boolean getDisplayXBackPressure() {
        String v = getExtra("displayXBackPressure", null);
        if (v == null) v = getLegacyDisplayXValue("backPressure");
        return v != null ? v.equals("1") : container.getDisplayXBackPressure();
    }
    public void setDisplayXBackPressure(boolean v) {
        putExtra("displayXBackPressure", v ? "1" : "0");
    }

    public boolean getDisplayXPrecisePresentation() {
        String v = getExtra("displayXPrecisePresentation", null);
        if (v == null) v = getLegacyDisplayXValue("precisePresentation");
        return v != null ? v.equals("1") : container.getDisplayXPrecisePresentation();
    }
    public void setDisplayXPrecisePresentation(boolean v) {
        putExtra("displayXPrecisePresentation", v ? "1" : "0");
    }

    private String getLegacyDisplayXValue(String key) {
        String config = getExtra("displayxConfig", null);
        if (config == null || config.isEmpty()) return null;
        String value = new KeyValueSet(config).get(key);
        return value.isEmpty() ? null : value;
    }

    public String getRendererPresentMode() {
        String v = getExtra("rendererPresentMode", null);
        return v != null && !v.isEmpty() ? v : container.getRendererPresentMode();
    }
    public void setRendererPresentMode(String v) { putExtra("rendererPresentMode", v != null ? v : "fifo"); }

    public String getRendererDriverId() {
        String v = getExtra("rendererDriverId", null);
        return v != null ? v : container.getRendererDriverId();
    }
    public void setRendererDriverId(String v) { putExtra("rendererDriverId", v != null ? v : ""); }

    public int getRendererFilterMode() {
        String v = getExtra("rendererFilterMode", null);
        try { return v != null && !v.isEmpty() ? Integer.parseInt(v) : container.getRendererFilterMode(); }
        catch (NumberFormatException e) { return 0; }
    }
    public void setRendererFilterMode(int v) { putExtra("rendererFilterMode", String.valueOf(v)); }

    public boolean getRendererSwapRB() {
        String v = getExtra("rendererSwapRB", null);
        return v != null ? v.equals("1") : container.getRendererSwapRB();
    }
    public void setRendererSwapRB(boolean v) { putExtra("rendererSwapRB", v ? "1" : "0"); }
}
