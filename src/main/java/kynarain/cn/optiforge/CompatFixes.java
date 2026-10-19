package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
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
    private static final String BLOCK_MODEL_PART = "BlockModelPart";
    private static final String AO_WITH_ARGS =
            "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/RenderType;)Z";

    private CompatFixes() {
    }

    /** @return bytes with the known-incompatible calls rewritten, or the input when there is nothing to do */
    public static byte[] apply(byte[] classBytes, String dottedClassName) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);

        int rewritten = 0;
        int ambient = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                // Rule 1: OptiFine's own ShaderInstance cache passes namespaced shader names to
                // ResourceLocation.withDefaultNamespace, which rejects them (Forge registers
                // 'forge:rendertype_*'). parse() accepts both forms.
                if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                        && dottedClassName.equals("net.minecraft.client.renderer.ShaderInstance")
                        && RESOURCE_LOCATION.equals(call.owner)
                        && "withDefaultNamespace".equals(call.name)) {
                    call.name = "parse";
                    call.itf = false;
                    rewritten++;
                    continue;
                }
                // Rule 2: OptiFine 1.21.6 was built against an API that has
                // BlockModelPart.useAmbientOcclusion(BlockState, RenderType); Forge's interface has the
                // no-argument form only. Measured: NoSuchMethodError at ModelBlockRenderer.renderModel
                // once a world loads. Drop the two arguments and call the no-argument form - the receiver
                // is already underneath them on the stack.
                if (call.name.equals("useAmbientOcclusion")
                        && AO_WITH_ARGS.equals(call.desc)
                        && call.owner.endsWith(BLOCK_MODEL_PART)) {
                    method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                    method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                    call.desc = "()Z";
                    call.itf = call.getOpcode() == Opcodes.INVOKEINTERFACE;
                    ambient++;
                }
            }
        }
        if (rewritten == 0 && ambient == 0) return classBytes;
        if (rewritten > 0) {
            System.out.println("[OptiForge] compat fix in " + dottedClassName
                    + ": withDefaultNamespace -> parse x" + rewritten);
        }
        if (ambient > 0) {
            System.out.println("[OptiForge] compat fix in " + dottedClassName
                    + ": useAmbientOcclusion(state,type) -> () x" + ambient);
        }
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
