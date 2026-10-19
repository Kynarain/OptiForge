package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Small, surgical rewrites of the prepared classes where an OptiFine patch assumes vanilla input.
 *
 * <p>Measured on Forge 51.0.33: Forge registers its own core shaders under names that already carry a
 * namespace ({@code forge:rendertype_entity_unlit_translucent}). OptiFine's added
 * {@code ShaderInstance.getOrCreate} builds the {@code ResourceLocation} with
 * {@code ResourceLocation.withDefaultNamespace(name)}, which rejects that string - the launch died with
 *
 * <pre>
 * ResourceLocationException: Non [a-z0-9/._-] character in path of location:
 *     minecraft:forge:rendertype_entity_unlit_translucent
 *   at ShaderInstance.getOrCreate
 *   at ForgeHooksClient$ClientEvents.registerShaders
 * </pre>
 *
 * <p>{@code ResourceLocation.parse(String)} accepts both forms: an unprefixed name resolves to
 * {@code minecraft:<name>} exactly as before, and a prefixed one keeps its namespace. Vanilla call
 * sites never pass a colon, so the rewrite is behaviour-preserving for them.
 */
public final class CompatFixes {

    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";

    private CompatFixes() {
    }

    /** @return bytes with the known-incompatible calls rewritten, or the input when there is nothing to do */
    public static byte[] apply(byte[] classBytes, String dottedClassName) {
        if (!dottedClassName.equals("net.minecraft.client.renderer.ShaderInstance")) return classBytes;
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);

        int rewritten = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (instruction.getOpcode() != Opcodes.INVOKESTATIC) continue;
                if (!RESOURCE_LOCATION.equals(call.owner)) continue;
                if (!"withDefaultNamespace".equals(call.name)) continue;
                call.name = "parse";
                call.itf = false;
                rewritten++;
            }
        }
        if (rewritten == 0) return classBytes;
        System.out.println("[OptiForge] compat fix in " + dottedClassName + ": withDefaultNamespace -> parse x"
                + rewritten);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
