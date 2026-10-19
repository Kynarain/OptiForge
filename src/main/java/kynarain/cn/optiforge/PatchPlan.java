package kynarain.cn.optiforge;

import java.util.ArrayList;
import java.util.List;

/**
 * What OptiForge would do for one OptiFine jar on one Forge version.
 *
 * <p>This is the seam between "diagnose" (M0) and "fix" (M1): the plan is a pure function of the jar
 * inventory and the matrix row, so it can be printed offline and asserted before any transformation
 * exists.
 *
 * <p>One thing the first measurement settled (and it contradicts the obvious guess): a pristine
 * OptiFine build carries <b>no</b> {@code patch/srg/net/minecraftforge/**} entries at all. The
 * Forge-facing material lives in the {@code notch/} tree instead, and OptiFine's own ModLauncher
 * transformer reads only {@code srg/} + {@code patch/srg/} - so those {@code notch/net/minecraftforge}
 * classes are the installer/LaunchWrapper form, not something the ModLauncher path loads. What a
 * Forge launch actually depends on is therefore narrower than "patches to Forge classes":
 * OptiFine's reflective probes of {@code net.minecraftforge.*} names, a small number of hard
 * {@code Class} constants, and its patched vanilla classes sitting underneath Forge's own patches.
 */
public final class PatchPlan {

    public enum Kind {
        /** The build states it is compatible with the Forge in use: nothing to plan. */
        OK,
        /** The build states it is not compatible with Forge at all. */
        DECLARED_INCOMPATIBLE,
        /** The build targets a different Forge version than the one running. */
        FORGE_MISMATCH,
        /** No matrix row: the mirror never listed this build. */
        UNKNOWN
    }

    public final Kind kind;
    public final List<String> lines;
    public final List<String> forgePatchTargets;
    /** {@code net/minecraftforge} classes OptiFine ships, by namespace. */
    public final int srgForgeClasses;
    public final int notchForgeClasses;

    private PatchPlan(Kind kind, List<String> lines, List<String> forgePatchTargets,
                      int srgForgeClasses, int notchForgeClasses) {
        this.kind = kind;
        this.lines = List.copyOf(lines);
        this.forgePatchTargets = List.copyOf(forgePatchTargets);
        this.srgForgeClasses = srgForgeClasses;
        this.notchForgeClasses = notchForgeClasses;
    }

    public static PatchPlan of(RuntimeInfo info, ForgeMatrix.Row row, OptifineJar jar) {
        List<String> lines = new ArrayList<>();
        Kind kind;

        if (row == null || !row.known()) {
            kind = Kind.UNKNOWN;
            lines.add("matrix: no row for " + jar.fileName()
                    + " - never listed on optifine.net, so its Forge status is undocumented");
        } else if (!row.forgeOk) {
            kind = Kind.DECLARED_INCOMPATIBLE;
            lines.add("matrix: " + row.forgeRaw + " - OptiFine states this build is NOT compatible with Forge");
        } else if (row.forgeVersion.isEmpty()) {
            kind = Kind.UNKNOWN;
            lines.add("matrix: " + row.forgeRaw + " (no version to compare)");
        } else {
            int cmp = ForgeMatrix.compareVersions(info.forgeVersion, row.forgeVersion);
            if (cmp == 0) {
                kind = Kind.OK;
                lines.add("matrix: " + row.forgeRaw + " - matches the running Forge exactly");
            } else {
                kind = Kind.FORGE_MISMATCH;
                lines.add("matrix: built for Forge " + row.forgeVersion + ", running Forge "
                        + info.forgeVersion + " (" + (cmp > 0 ? "running is newer" : "running is older")
                        + "; the page names one build, not a range)");
            }
        }

        lines.add("payload: " + jar.patchSrg + " patch/srg targets, " + jar.patchNotch
                + " patch/notch, " + jar.patchAssets + " patch/assets");
        if (jar.forgePatchTargets.isEmpty()) {
            lines.add("         no target is a net/minecraftforge class, so Forge-version drift is not"
                    + " expressed in the xdelta payload");
        } else {
            int shown = Math.min(8, jar.forgePatchTargets.size());
            lines.add("         Forge targets: " + String.join(", ", jar.forgePatchTargets.subList(0, shown))
                    + (jar.forgePatchTargets.size() > shown ? ", ..." : ""));
        }
        lines.add("own classes: srg/=" + jar.srgOwnClasses + " (net/minecraftforge: " + jar.srgForgeOwnClasses
                + "), notch/=" + jar.notchOwnClasses + " (net/minecraftforge: " + jar.notchForgeOwnClasses + ")"
                + " - the notch/ Forge classes belong to the installer/LaunchWrapper form, which the"
                + " ModLauncher path does not read");

        if (kind != Kind.OK) {
            lines.add("action: M1, not implemented in this build. Candidates, in the order the first real"
                    + " launch log (optiforge/diagnose.txt) can rank them:");
            lines.add("  1. OptiFine's reflective probes of net/minecraftforge.* names (Reflector, ReflectorForge)");
            lines.add("  2. hard net/minecraftforge Class constants inside OptiFine's own srg/ classes");
            lines.add("  3. the patched vanilla classes vs Forge's own patches of the same classes");
            lines.add("  4. the jar's buildof/META-INF metadata, if Forge turns out to inspect it at all");
        }
        return new PatchPlan(kind, lines, jar.forgePatchTargets, jar.srgForgeOwnClasses, jar.notchForgeOwnClasses);
    }

    public String verdict() {
        return switch (kind) {
            case OK -> "OK: build states Forge compatibility with the running version";
            case DECLARED_INCOMPATIBLE -> "INCOMPATIBLE: OptiFine states no Forge support for this build";
            case FORGE_MISMATCH -> "MISMATCH: build was pinned to a different Forge version";
            case UNKNOWN -> "UNKNOWN: no matrix row for this build";
        };
    }
}
