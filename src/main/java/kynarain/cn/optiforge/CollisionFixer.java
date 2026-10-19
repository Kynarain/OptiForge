package kynarain.cn.optiforge;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes the OptiFine-added member that stands in the way of a clean SRG-to-official rename.
 *
 * <p>A payload compiled against SRG names can contain both the original member (under its SRG id) and
 * an OptiFine-added wrapper that happens to carry the official name. {@code ModelPart} is the measured
 * case: {@code m_171324_(String)} is the real {@code children.get(name)} method, while the added
 * {@code getChild(String)} is a deep search that returns {@code null} when it finds nothing. Renaming
 * the original onto the wrapper name produces "Duplicate method name"; keeping the original under its
 * SRG id (the first attempt) left vanilla callers - {@code DragonHeadModel} - calling the wrapper and
 * dying with "Cannot invoke ModelPart.getChild because this.head is null".
 *
 * <p>So the wrapper is dropped and the original is renamed onto the official name. Every caller that
 * mattered was compiled against SRG and therefore calls the SRG id, which now resolves to the original
 * implementation - the same code vanilla expects. This only touches classes that actually collide.
 */
public final class CollisionFixer {

    private static final Pattern SRG = Pattern.compile("[fm]_\\d+_");

    private CollisionFixer() {
    }

    /** @return the class bytes with superseded wrappers removed, or the input when nothing collides. */
    public static byte[] fix(byte[] classBytes, SrgRemap remap) {
        if (remap == null) return classBytes;
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);

        List<String> removed = new ArrayList<>();

        // Only members whose name is an SRG id AND whose official name differs are "originals" that a
        // wrapper could shadow. Without the first condition <init>/<clinit> (which the table maps to
        // themselves) became wrappers and every constructor was dropped - measured as
        // "NoSuchMethodError: void net.minecraft.CrashReport.<init>(String, Throwable)".
        Map<String, String> fieldOwners = new HashMap<>(); // officialName|desc -> srg name
        for (FieldNode field : node.fields) {
            if (!SRG.matcher(field.name).matches()) continue;
            String official = remap.officialName(field.name);
            if (official != null && !official.equals(field.name)) {
                fieldOwners.put(official + "|" + field.desc, field.name);
            }
        }
        for (FieldNode field : new ArrayList<>(node.fields)) {
            if (SRG.matcher(field.name).matches()) continue;
            if (!fieldOwners.containsKey(field.name + "|" + field.desc)) continue;
            node.fields.remove(field);
            removed.add("field " + field.name);
        }

        Map<String, String> methodOwners = new HashMap<>();
        for (MethodNode method : node.methods) {
            if (!SRG.matcher(method.name).matches()) continue;
            String official = remap.officialName(method.name);
            if (official != null && !official.equals(method.name)) {
                methodOwners.put(official + "|" + method.desc, method.name);
            }
        }
        for (MethodNode method : new ArrayList<>(node.methods)) {
            if (SRG.matcher(method.name).matches()) continue;
            if (!methodOwners.containsKey(method.name + "|" + method.desc)) continue;
            node.methods.remove(method);
            removed.add("method " + method.name + method.desc);
        }

        if (removed.isEmpty()) return classBytes;
        System.out.println("[OptiForge] collision fix in " + node.name + ": dropped " + removed);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
