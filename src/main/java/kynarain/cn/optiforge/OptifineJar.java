package kynarain.cn.optiforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Inventory of one OptiFine jar, read from the zip itself and nothing else.
 *
 * <p>Everything here is a fact about the file: no Minecraft or Forge types are touched, so this
 * class works the same on 1.20.x, 1.21.x and 26.x, and it can be run offline (see {@link Diagnose}).
 *
 * <p>The three numbers that matter for a Forge target are:
 * <ul>
 *   <li>{@link #patchSrg} — how many game classes OptiFine's patch payload covers;</li>
 *   <li>{@link #forgePatchTargets} — which of those are <em>Forge</em> classes
 *       ({@code patch/srg/net/minecraftforge/**}), i.e. the part pinned to one Forge version;</li>
 *   <li>{@link #srgForgeOwnClasses} — how many precompiled OptiFine classes reference the
 *       {@code net.minecraftforge} packages that OptiFine ships inside {@code srg/}.</li>
 * </ul>
 */
public final class OptifineJar {

    /** Entry names that mark a jar as an OptiFine build (installer or pre-extracted form). */
    public static final String MARKER_BUILDOF = "buildof.txt";
    public static final String MARKER_INSTALLER = "optifine/Installer.class";
    public static final String PREFIX_PATCH_SRG = "patch/srg/";
    public static final String PREFIX_PATCH_NOTCH = "patch/notch/";
    public static final String PREFIX_PATCH_ASSETS = "patch/assets/";
    public static final String SUFFIX_XDELTA = ".class.xdelta";
    private static final int MAX_FORGE_TARGETS = 400;

    public final Path path;
    public final long size;
    public final String sha256;

    public final String buildDate;
    public final int entryCount;

    public final int patchSrg;
    public final int patchNotch;
    /** Flat {@code patch/<obf>.class.xdelta} entries: the 1.13.2 / pre-1.14 payload generation. */
    public final int patchFlat;
    public final int patchAssets;
    public final List<String> forgePatchTargets;

    public final int srgOwnClasses;
    public final int notchOwnClasses;
    public final int srgForgeOwnClasses;
    public final int notchForgeOwnClasses;
    /** Root-level {@code net/optifine/**} classes carried by the 1.13.2 generation. */
    public final int rootOwnClasses;

    public final List<String> services;
    public final boolean hasPatchCfg;
    public final boolean hasPatch2Cfg;
    public final boolean hasModsToml;
    public final boolean hasInstaller;

    private OptifineJar(Path path, long size, String sha256, String buildDate, int entryCount,
                        int patchSrg, int patchNotch, int patchFlat, int patchAssets,
                        List<String> forgePatchTargets,
                        int srgOwnClasses, int notchOwnClasses, int srgForgeOwnClasses, int notchForgeOwnClasses,
                        int rootOwnClasses,
                        List<String> services, boolean hasPatchCfg, boolean hasPatch2Cfg,
                        boolean hasModsToml, boolean hasInstaller) {
        this.path = path;
        this.size = size;
        this.sha256 = sha256;
        this.buildDate = buildDate;
        this.entryCount = entryCount;
        this.patchSrg = patchSrg;
        this.patchNotch = patchNotch;
        this.patchFlat = patchFlat;
        this.patchAssets = patchAssets;
        this.forgePatchTargets = Collections.unmodifiableList(forgePatchTargets);
        this.srgOwnClasses = srgOwnClasses;
        this.notchOwnClasses = notchOwnClasses;
        this.srgForgeOwnClasses = srgForgeOwnClasses;
        this.notchForgeOwnClasses = notchForgeOwnClasses;
        this.rootOwnClasses = rootOwnClasses;
        this.services = Collections.unmodifiableList(services);
        this.hasPatchCfg = hasPatchCfg;
        this.hasPatch2Cfg = hasPatch2Cfg;
        this.hasModsToml = hasModsToml;
        this.hasInstaller = hasInstaller;
    }

    public String fileName() {
        return path.getFileName().toString();
    }

    /** Reads one jar. Throws if the file is not a readable zip; callers decide whether to care. */
    public static OptifineJar read(Path jar) throws IOException {
        String buildDate = null;
        int entryCount = 0;
        int patchSrg = 0, patchNotch = 0, patchFlat = 0, patchAssets = 0;
        List<String> forgeTargets = new ArrayList<>();
        int srgOwn = 0, notchOwn = 0, srgForgeOwn = 0, notchForgeOwn = 0, rootOwn = 0;
        List<String> services = new ArrayList<>();
        boolean hasPatchCfg = false, hasPatch2Cfg = false, hasModsToml = false, hasInstaller = false;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                entryCount++;
                String name = e.getName();

                if (name.equals(MARKER_BUILDOF)) {
                    try (InputStream in = zip.getInputStream(e)) {
                        buildDate = new String(J8.readAll(in), StandardCharsets.US_ASCII).trim();
                    }
                    continue;
                }
                if (name.equals("patch.cfg")) hasPatchCfg = true;
                else if (name.equals("patch2.cfg")) hasPatch2Cfg = true;
                else if (name.equals("META-INF/mods.toml")) hasModsToml = true;
                else if (name.equals(MARKER_INSTALLER)) hasInstaller = true;
                else if (name.startsWith("META-INF/services/")) {
                    try (InputStream in = zip.getInputStream(e)) {
                        String body = new String(J8.readAll(in), StandardCharsets.UTF_8).trim()
                                .replace("\r", "").replace("\n", ",");
                        services.add(name.substring("META-INF/services/".length()) + " = " + body);
                    } catch (IOException ignored) {
                        services.add(name.substring("META-INF/services/".length()) + " = <unreadable>");
                    }
                }

                if (name.startsWith(PREFIX_PATCH_SRG) && name.endsWith(SUFFIX_XDELTA)) {
                    patchSrg++;
                    String logical = name.substring(PREFIX_PATCH_SRG.length(), name.length() - SUFFIX_XDELTA.length());
                    if (logical.startsWith("net/minecraftforge/")) {
                        if (forgeTargets.size() < MAX_FORGE_TARGETS) forgeTargets.add(logical);
                    }
                } else if (name.startsWith(PREFIX_PATCH_NOTCH) && name.endsWith(SUFFIX_XDELTA)) {
                    patchNotch++;
                } else if (name.startsWith("patch/") && name.endsWith(SUFFIX_XDELTA)
                        && name.indexOf('/', "patch/".length()) < 0) {
                    patchFlat++;
                } else if (name.startsWith(PREFIX_PATCH_ASSETS) && name.endsWith(".xdelta")) {
                    patchAssets++;
                } else if (name.startsWith("srg/") && name.endsWith(".class")) {
                    srgOwn++;
                    if (name.startsWith("srg/net/minecraftforge/")) srgForgeOwn++;
                } else if (name.startsWith("net/optifine/") && name.endsWith(".class")) {
                    rootOwn++;
                } else if (name.startsWith("notch/") && name.endsWith(".class")) {
                    notchOwn++;
                    if (name.startsWith("notch/net/minecraftforge/")) notchForgeOwn++;
                }
            }
        }

        return new OptifineJar(jar, Files.size(jar), sha256(jar), buildDate, entryCount,
                patchSrg, patchNotch, patchFlat, patchAssets, forgeTargets,
                srgOwn, notchOwn, srgForgeOwn, notchForgeOwn, rootOwn,
                services, hasPatchCfg, hasPatch2Cfg, hasModsToml, hasInstaller);
    }

    /** True when the zip looks like an OptiFine build, in either the installer or the mod-jar form. */
    public boolean looksLikeOptifine() {
        return hasPatchCfg && (hasInstaller || srgOwnClasses > 0 || rootOwnClasses > 0)
                && (patchSrg + patchNotch + patchFlat) > 0;
    }

    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unavailable(" + e.getClass().getSimpleName() + ")";
        }
    }
}
