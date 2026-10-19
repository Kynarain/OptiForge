package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    private static final class Grant {
        final String member;
        final String descriptor; // empty when the config line carries no descriptor
        final int access;
        /** Forge's "-f" suffix means "and drop final"; {@code protected-f} is what Forge uses for setupState. */
        final boolean clearFinal;

        Grant(String member, String descriptor, int access, boolean clearFinal) {
            this.member = member;
            this.descriptor = descriptor;
            this.access = access;
            this.clearFinal = clearFinal;
        }
    }

    public static ForgeAccessTransformers load(SrgRemap remap, Path gameDir, StringBuilder notes) {
        ForgeAccessTransformers at = new ForgeAccessTransformers();
        at.remap = remap;
        int files = 0;
        int lines = 0;
        for (Path jar : LaunchPaths.jars(gameDir)) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry cfg = zip.getEntry("META-INF/accesstransformer.cfg");
                if (cfg == null) continue;
                files++;
                try (InputStream in = zip.getInputStream(cfg)) {
                    lines += at.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // not a readable jar
            }
        }
        notes.append("access transformers: ").append(lines).append(" grant(s) from ")
                .append(files).append(" config file(s)\n");
        return at;
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
            if (parts.length < 3) continue;
            String accessName = parts[0];
            int access = switch (accessName) {
                case "public", "public-f" -> Opcodes.ACC_PUBLIC;
                case "protected", "protected-f" -> Opcodes.ACC_PROTECTED;
                case "private", "private-f" -> Opcodes.ACC_PRIVATE;
                case "fdefault", "fdefault-f" -> 0;
                default -> -1;
            };
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
            for (FieldNode field : node.fields) {
                if (!field.name.equals(grant.member)) continue;
                if (!grant.descriptor.isEmpty() && !field.desc.equals(grant.descriptor)) continue;
                field.access = widen(field.access, grant.access, grant.clearFinal);
                touched++;
            }
            for (MethodNode method : node.methods) {
                if (!method.name.equals(grant.member)) continue;
                if (!grant.descriptor.isEmpty() && !method.desc.equals(grant.descriptor)) continue;
                method.access = widen(method.access, grant.access, grant.clearFinal);
                touched++;
            }
        }
        if (touched == 0) return classBytes;
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static int widen(int access, int newAccess, boolean clearFinal) {
        int result = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) | newAccess;
        return clearFinal ? (result & ~Opcodes.ACC_FINAL) : result;
    }
}
