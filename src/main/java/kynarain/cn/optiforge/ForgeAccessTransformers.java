package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Re-applies Forge's access transformers to the classes OptiForge prepares.
 *
 * <p>Measured on Forge 51.0.33 + OptiFine 1.21: the game crashed with
 * {@code IllegalAccessError: net.minecraftforge.client.ForgeRenderTypes$CustomizableTextureState
 * tried to access private field net.minecraft.client.renderer.RenderStateShard.setupState}. Forge's
 * own code is compiled against the access its {@code META-INF/accesstransformer.cfg} grants, and
 * Forge widens those members before transformers run. OptiFine's payload is a delta against
 * <b>vanilla</b>, so the class OptiForge produces has the vanilla (private) flags again - silently
 * undoing the access Forge granted. This class replays those grants onto the produced classes.
 *
 * <p>Only widening is ever applied; nothing is narrowed.
 */
public final class ForgeAccessTransformers {

    /** class name (dotted) -> grants. */
    private final Map<String, List<Grant>> grants = new HashMap<>();
    private SrgRemap remap;
    /**
     * When true, a grant may widen access but never narrow it.
     *
     * <p>Forge 26.x configs contain narrowing lines (26.2: {@code private-f ... LevelRenderer
     * weatherEffectRenderer}) that break same-package callers once applied to our prepared copy. Making this
     * global was tried and regressed the verified 1.21.7 line, so it is only enabled for 26.x.
     */
    private boolean widenOnly;

    private static final class Grant {
        final String member;
        final String descriptor; // empty when the config line carries no descriptor
        final int access;
        /** Forge's "-f" suffix means "and drop final"; {@code protected-f} is what Forge uses for setupState. */
        final boolean clearFinal;
        /**
         * True for Forge's {@code protected <class> *} / {@code public <class> *} wildcard lines: "every
         * member of this class". 1.14.4 and 1.15.2 grant the whole {@code IngameGui} that way, and without
         * it {@code ForgeIngameGui} died on {@code IllegalAccessError ... IngameGui.field_73839_d} even
         * though the config looked complete. A wildcard is applied as a widening only: OptiFine adds members
         * to the same class that its own classes call, and narrowing those back to protected would break
         * those callers.
         */
        final boolean wildcard;

        Grant(String member, String descriptor, int access, boolean clearFinal) {
            this.member = member;
            this.descriptor = descriptor;
            this.access = access;
            this.clearFinal = clearFinal;
            this.wildcard = "*".equals(member);
        }
    }

    /**
     * Loads every access-transformer config this launch uses.
     *
     * <p>Only the running line's configs are read: the launch jar list (class/module path) restricted to
     * the running version, plus the library jars of {@code net/minecraftforge/forge/<mc>-<forge>/}, plus
     * any {@code <game>/mods/*.jar} that carries {@code META-INF/accesstransformer.cfg} (Forge 1.15+
     * reads mod ATs through the same file). Scanning every Forge version under a shared rig was measured
     * to apply a 1.14/1.15 grant to a 1.16.5 class: {@code protected ... ModelBakery field_177606_o}
     * narrowed a field that 1.16.5's own config does not mention (vanilla already public), and the first
     * model bake died with {@code IllegalAccessError ... bakeHelper}. Forge's runtime AT plugin reads the
     * same cfg format (old {@code net.minecraftforge.fml.common.asm.transformers.AccessTransformer}), so
     * the replay must mirror exactly this line's files.
     */
    public static ForgeAccessTransformers load(SrgRemap remap, Path gameDir, boolean widenOnly,
                                              String mcVersion, String forgeVersion,
                                              StringBuilder notes) {
        ForgeAccessTransformers at = new ForgeAccessTransformers();
        at.widenOnly = widenOnly;
        at.remap = remap;
        int files = 0;
        int lines = 0;
        java.util.LinkedHashSet<Path> candidates = new java.util.LinkedHashSet<>(
                LaunchPaths.runtimeJars(gameDir, mcVersion, forgeVersion));
        if (gameDir != null) {
            Path mods = gameDir.resolve("mods");
            if (Files.isDirectory(mods)) {
                try (java.nio.file.DirectoryStream<Path> stream =
                             Files.newDirectoryStream(mods, "*.jar")) {
                    for (Path jar : stream) candidates.add(jar);
                } catch (IOException ignored) {
                    // carry on with the launch jars
                }
            }
        }
        for (Path jar : candidates) {
            lines += at.parseConfig(jar);
            if (at.foundIn(jar)) files++;
        }
        if (files == 0) {
            // Last resort (an unknown launcher layout): scan the full launch list but keep only entries
            // that clearly belong to the running version, never another line.
            String tag = (J8.isBlank(mcVersion) || J8.isBlank(forgeVersion))
                    ? "" : mcVersion + "-" + forgeVersion;
            for (Path jar : LaunchPaths.jars(gameDir)) {
                if (candidates.contains(jar)) continue;
                String path = jar.toString().replace('\\', '/');
                if (!tag.isEmpty() && !path.contains("/forge/" + tag + "/")
                        && !path.contains("/versions/" + tag + "/")) {
                    continue;
                }
                lines += at.parseConfig(jar);
                if (at.foundIn(jar)) files++;
            }
        }
        notes.append("access transformers: ").append(lines).append(" grant(s) from ")
                .append(files).append(" config file(s) of the running line");
        if (!J8.isBlank(mcVersion)) {
            notes.append(" (").append(mcVersion);
            if (!J8.isBlank(forgeVersion)) notes.append("-").append(forgeVersion);
            notes.append(')');
        }
        notes.append('\n');
        return at;
    }

    /** Jars that actually carried a config, so the note counts files rather than candidates. */
    private final java.util.Set<Path> configJars = new java.util.HashSet<>();

    private boolean foundIn(Path jar) {
        return configJars.contains(jar);
    }

    /** Parses one jar's config; returns the number of grant lines read. */
    private int parseConfig(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry cfg = zip.getEntry("META-INF/accesstransformer.cfg");
            if (cfg == null) return 0;
            configJars.add(jar);
            try (InputStream in = zip.getInputStream(cfg)) {
                return parse(new String(J8.readAll(in), StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // not a readable jar
        }
        return 0;
    }

    /** Maps one access-transformer keyword to ASM access bits, or -1 for an unknown keyword. */
    private static int accessFor(String accessName) {
        if ("public".equals(accessName) || "public-f".equals(accessName)) return Opcodes.ACC_PUBLIC;
        if ("protected".equals(accessName) || "protected-f".equals(accessName)) return Opcodes.ACC_PROTECTED;
        if ("private".equals(accessName) || "private-f".equals(accessName)) return Opcodes.ACC_PRIVATE;
        if ("fdefault".equals(accessName) || "fdefault-f".equals(accessName)) return 0;
        return -1;
    }

    private int parse(String text) {
        int count = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // Forge writes "protected-f net.minecraft.client.renderer.RenderStateShard f_110131_ # setupState":
            // the member is an SRG id and the official name is only a trailing comment. The comment has
            // to go, and the id needs translating before it can match a prepared class.
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            // A class-level line is "public a.b.C" - only two tokens. Skipping those (parts.length < 3)
            // left Forge's own access transformers that make a *class* public unapplied, and 1.21.6 died
            // with "IllegalAccessError: failed to access class ModelDiscovery$ModelWrapper from class
            // ModelContext", because Forge declares "public net.minecraft...ModelDiscovery$ModelWrapper".
            if (parts.length == 2) {
                String accessName = parts[0];
                int access = accessFor(accessName);
                if (access < 0) continue;
                String classNameOnly = parts[1].replace('/', '.');
                grants.computeIfAbsent(classNameOnly, k -> new ArrayList<>())
                        .add(new Grant("", "", access, accessName.endsWith("-f")));
                count++;
                continue;
            }
            if (parts.length < 3) continue;
            String accessName = parts[0];
            int access = accessFor(accessName);
            if (access < 0) continue;
            boolean clearFinal = accessName.endsWith("-f");
            String className = parts[1].replace('/', '.');
            // Forge writes methods as "m_173215_(Ljava/lang/String;...)L...;" - name and descriptor
            // concatenated, not space-separated like fields. Split them so the SRG id can be translated
            // and the descriptor can be compared (measured: without this, RenderType.create was never
            // widened and Forge's ForgeRenderTypes died on IllegalAccessError).
            String member = parts[2];
            String descriptor = "";
            int paren = member.indexOf('(');
            if (paren >= 0) {
                descriptor = member.substring(paren);
                member = member.substring(0, paren);
            } else if (parts.length >= 4) {
                descriptor = parts[3];
            }
            if (member.equals("*")) {
                // Wildcard: "every member of this class". No remapping, no descriptor.
                grants.computeIfAbsent(className, k -> new ArrayList<>())
                        .add(new Grant("*", "", access, clearFinal));
                count++;
                continue;
            }
            if (remap != null) {
                String official = remap.officialName(member);
                if (official != null) member = official;
            }
            String descriptorFinal = descriptor;
            grants.computeIfAbsent(className, k -> new ArrayList<>())
                    .add(new Grant(member, descriptorFinal, access, clearFinal));
            count++;
        }
        return count;
    }

    public boolean isEmpty() {
        return grants.isEmpty();
    }

    /** Widens {@code classBytes} for {@code dottedClassName}, returning the (possibly new) bytes. */
    public byte[] transform(byte[] classBytes, String dottedClassName) {
        List<Grant> list = grants.get(dottedClassName);
        if (list == null || list.isEmpty()) return classBytes;
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);
        int touched = 0;
        for (Grant grant : list) {
            if (grant.member.isEmpty()) {
                // class-level grant: replace the visibility bits of the class itself
                node.access = widen(node.access, grant.access, grant.clearFinal, widenOnly);
                touched++;
                continue;
            }
            boolean forceWiden = widenOnly || grant.wildcard;
            for (FieldNode field : node.fields) {
                if (!grant.wildcard && !field.name.equals(grant.member)) continue;
                if (!grant.descriptor.isEmpty() && !field.desc.equals(grant.descriptor)) continue;
                field.access = widen(field.access, grant.access, grant.clearFinal, forceWiden);
                touched++;
            }
            for (MethodNode method : node.methods) {
                if (!grant.wildcard && !method.name.equals(grant.member)) continue;
                if (!grant.descriptor.isEmpty() && !method.desc.equals(grant.descriptor)) continue;
                method.access = widen(method.access, grant.access, grant.clearFinal, forceWiden);
                touched++;
            }
        }
        if (touched == 0) return classBytes;
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private int widen(int access, int newAccess, boolean clearFinal, boolean forceWidenOnly) {
        int visibilityMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
        int requested = newAccess & visibilityMask;
        int current = access & visibilityMask;
        int effective = newAccess;
        if (forceWidenOnly && requested != 0 && current != 0) {
            // Keep the member as visible as it already is: a narrowing line must not make a same-package
            // caller fail. Widening lines still apply, and "-f" is still honoured.
            effective = current == Opcodes.ACC_PUBLIC ? Opcodes.ACC_PUBLIC
                    : current == Opcodes.ACC_PROTECTED ? (requested == Opcodes.ACC_PUBLIC
                            ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PROTECTED)
                    : requested;
        }
        int result = (access & ~visibilityMask) | effective;
        return clearFinal ? (result & ~Opcodes.ACC_FINAL) : result;
    }
}
