package com.winlator.cmod.core;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WineInfo implements Parcelable {
    public static final WineInfo MAIN_WINE_VERSION = new WineInfo("proton", "9.0", "arm64ec");
    /** A dedicated prefix containing native ARM64 Windows builtins, rather than x86 DirectX replacements. */
    public static final String NATIVE_ARM64_CONTAINER_KEY = "windowsArchitecture";
    public static final String NATIVE_ARM64_CONTAINER_VALUE = "arm64";
    // type, optional label prefix ("exp-cachyos-"), version, optional build number, optional label suffix ("-slr"), arch
    private static final Pattern pattern = Pattern.compile("^(wine|proton)\\-((?:[a-z][a-z0-9]*\\-)*)([0-9\\.]+)\\-?([0-9\\.]+)?((?:\\-[a-z][a-z0-9]*)*)\\-(x86|x86_64|arm64ec|arm64)$");
    public final String version;
    public final String type;
    public String subversion;
    /** Label words before / after the version, e.g. "exp-cachyos-" and "-slr"; empty for plain names. */
    private String labelPrefix = "";
    private String labelSuffix = "";
    public final String path;
    private String arch;

    public WineInfo(String type, String version, String arch) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = null;
    }

    public WineInfo(String type, String version, String subversion, String arch, String path) {
        this.type = type;
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.arch = arch;
        this.path = path;
    }

    public WineInfo(String type, String version, String arch, String path) {
        this.type = type;
        this.version = version;
        this.arch = arch;
        this.path = path;
    }

    private WineInfo(Parcel in) {
        type = in.readString();
        version = in.readString();
        subversion = in.readString();
        arch = in.readString();
        path = in.readString();
        labelPrefix = in.readString();
        labelSuffix = in.readString();
    }

    private WineInfo withLabels(String prefix, String suffix) {
        this.labelPrefix = prefix != null ? prefix : "";
        this.labelSuffix = suffix != null ? suffix : "";
        return this;
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public boolean isWin64() {
        return arch.equals("x86_64") || arch.equals("arm64ec") || arch.equals("arm64");
    }

    public boolean isArm64EC() { return arch.equals("arm64ec"); }

    /** The package's system32 source; this does not enable x86 emulation for pure ARM64 Wine. */
    public boolean usesArm64WindowsFiles() { return arch.equals("arm64") || arch.equals("arm64ec"); }

    /**
     * Native ARM64 guest support is a property of the installed runtime, not its ARM64EC product label.
     * Both pure ARM64 and ARM64X Windows builtins can serve an ARM64 process. Check the Unix loader
     * as well: an x86-64 Wine with similarly named DLL directories still needs a CPU translator.
     */
    public boolean supportsNativeArm64() {
        if (path == null || path.isEmpty() || !isAarch64Elf(new File(path, "bin/wine"))) return false;
        return isArm64Builtin(new File(path, "lib/wine/aarch64-windows/ntdll.dll"))
                && isArm64Builtin(new File(path, "lib/wine/aarch64-windows/kernel32.dll"));
    }

    private static boolean isArm64Builtin(File file) {
        WindowsExecutable.Architecture architecture = WindowsExecutable.detect(file);
        return architecture == WindowsExecutable.Architecture.ARM64 || architecture == WindowsExecutable.Architecture.ARM64X;
    }

    private static boolean isAarch64Elf(File file) {
        byte[] header = new byte[20];
        try (FileInputStream stream = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int count = stream.read(header, offset, header.length - offset);
                if (count < 0) return false;
                offset += count;
            }
            return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F'
                    && header[4] == 2 && header[5] == 1 && (header[18] & 0xff) == 183 && header[19] == 0;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    public String identifier() {
        if (type.equals("proton"))
            return "proton-" + fullVersion() + "-"+ arch;
        else
            return "wine-" + fullVersion() + "-" + arch;
    }

    public String fullVersion() {
        return labelPrefix + version + (subversion != null ? "-" + subversion : "") + labelSuffix;
    }

    @NonNull
    @Override
    public String toString() {
        if (type.equals("proton"))
            return "Proton "+fullVersion()+(this == MAIN_WINE_VERSION ? " (Custom)" : "");
        else
            return "Wine "+fullVersion()+(this == MAIN_WINE_VERSION ? " (Custom)" : "");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WineInfo> CREATOR = new Parcelable.Creator<WineInfo>() {
        public WineInfo createFromParcel(Parcel in) {
            return new WineInfo(in);
        }

        public WineInfo[] newArray(int size) {
            return new WineInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeString(version);
        dest.writeString(subversion);
        dest.writeString(arch);
        dest.writeString(path);
        dest.writeString(labelPrefix);
        dest.writeString(labelSuffix);
    }

    @NonNull
    public static WineInfo fromIdentifier(Context context, ContentsManager contentsManager, String identifier) {
        ImageFs imageFs = ImageFs.find(context);
        String path = "";

        Log.d("WineInfo", "Creating WineInfo from identifier " + identifier);

        if (identifier.equals(MAIN_WINE_VERSION.identifier())) return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());

        ContentProfile wineProfile = contentsManager.getProfileByEntryName(identifier);

        if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            identifier = identifier.substring(0, identifier.length() - 2).toLowerCase();
        }

        Matcher matcher = pattern.matcher(identifier);

        if (matcher.find()) {
            String[] wineVersions = context.getResources().getStringArray(R.array.wine_entries);
            for (String wineVersion : wineVersions) {
                if (wineVersion.contains(identifier)) {
                    path = imageFs.getRootDir().getPath() + "/opt/" + identifier;
                    break;
                }
            }
            if (path.isEmpty() && ProtonPackageManager.isInstalled(context, identifier)) path = imageFs.getRootDir().getPath() + "/opt/" + identifier;

            if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON))
                path = contentsManager.getInstallDir(context, wineProfile).getPath();

            return new WineInfo(matcher.group(1), matcher.group(3), matcher.group(4), matcher.group(6), path)
                    .withLabels(matcher.group(2), matcher.group(5));
        }
        else return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null ||wineVersion.equals(MAIN_WINE_VERSION.identifier());
    }
}
