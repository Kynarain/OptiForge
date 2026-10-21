package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

/**
 * Removes hard links from OptiFine's own classes to Forge/NeoForge types the mod module cannot read.
 *
 * <p>Measured on Forge 56.0.9 + OptiFine 1.21.6 {@code J6_pre3}: only three of the 709 own classes mention
 * Forge at all ({@code net.optifine.reflect.Reflector}, {@code shaders.ShadersRender},
 * {@code util.ResUtils}), and the fatal one is a class literal for
 * {@code net.minecraftforge.eventbus.api.Event} in {@code Reflector.<clinit>}:
 *
 * <pre>
 * NoClassDefFoundError: net/minecraftforge/eventbus/api/Event
 *   at optiforge_optifine_classes/net.optifine.reflect.Reflector.&lt;clinit&gt;
 * </pre>
 *
 * <p>The mod layer cannot read the service layer's eventbus module and FML refuses a module-info in a mod
 * jar (measured), so the reference itself has to go. OptiFine's {@code Reflector} already treats "class
 * not present" as a normal answer, so pointing the literal at {@code Object} degrades its Forge hooks
 * instead of killing the launch. String-based lookups ({@code ldc "net.minecraftforge..."}) are untouched:
 * they resolve reflectively and already tolerate absence.
 */
public final class OwnRefs {

    private OwnRefs() {
    }

    /** @return bytes with foreign class literals neutralised, or the input when there is nothing to do */
    public static byte[] apply(byte[] classBytes, String dottedClassName) {
        return apply(classBytes, dottedClassName, J8.<String>setOf());
    }

    /**
     * @param visible classes present in this launch' jars - a literal for one of those must be kept, because
     *                OptiFine's {@code Reflector} resolves the Forge API through them. Measured on 26.1.2:
     *                replacing every {@code net/minecraftforge/**} literal broke
     *                {@code Reflector.ForgeEventFactoryClient_fireRenderNameTagEvent} (the call returned null and
     *                {@code EntityRenderer.submitNameDisplay} died with
     *                "NullPointerException: renderNameplateEvent is null").
     */
    public static byte[] apply(byte[] classBytes, String dottedClassName, java.util.Set<String> visible) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);

        int rewritten = 0;
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof LdcInsnNode)) continue;
                LdcInsnNode ldc = (LdcInsnNode) instruction;
                if (!(ldc.cst instanceof Type)) continue;
                Type type = (Type) ldc.cst;
                if (type.getSort() != Type.OBJECT) continue;
                String internal = type.getInternalName();
                if (!internal.startsWith("net/minecraftforge/") && !internal.startsWith("net/neoforged/")) {
                    continue;
                }
                if (visible.contains(internal)) continue; // the class is reachable, keep the reference
                ldc.cst = Type.getType(Object.class);
                rewritten++;
            }
        }
        if (rewritten == 0) return classBytes;
        System.out.println("[OptiForge] neutralised " + rewritten + " foreign class literal(s) in "
                + dottedClassName);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
