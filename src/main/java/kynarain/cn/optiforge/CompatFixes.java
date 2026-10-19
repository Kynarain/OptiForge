package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
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

    /** Set when OptiFine's reflective call cannot match the running Forge's method arity. */
    static volatile boolean bypassNameplateEvent;

    private CompatFixes() {
    }

    /** @return bytes with the known-incompatible calls rewritten, or the input when there is nothing to do */
    public static byte[] apply(byte[] classBytes, String dottedClassName) {
        return apply(classBytes, dottedClassName, false);
    }

    /**
     * @param modernWindow true on MC 26.x, where Forge's
     *                     {@code ImmediateWindowHandler.setupMinecraftWindow} gained a fifth parameter
     *                     ({@code Supplier<Object>}) that OptiFine's reflection call does not pass -
     *                     measured: "wrong number of arguments: 4 expected: 5" from
     *                     {@code Reflector.callLong} inside {@code Window.<init>}
     */
    public static byte[] apply(byte[] classBytes, String dottedClassName, boolean modernWindow) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);

        int rewritten = 0;
        int ambient = 0;
        int window = 0;
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
        if (modernWindow) {
            window = bypassImmediateWindowHandler(node, dottedClassName);
        }
        if (bypassNameplateEvent && dottedClassName.equals("net.minecraft.client.renderer.entity.EntityRenderer")) {
            window += bypassNameplateEvent(node);
        }
        if (rewritten == 0 && ambient == 0 && window == 0) return classBytes;
        if (rewritten > 0) {
            System.out.println("[OptiForge] compat fix in " + dottedClassName
                    + ": withDefaultNamespace -> parse x" + rewritten);
        }
        if (window > 0) {
            System.out.println("[OptiForge] compat fix in " + dottedClassName + ": bypassed "
                    + "ImmediateWindowHandler.setupMinecraftWindow (arity changed on 26.x) x" + window);
        }
        if (ambient > 0) {
            System.out.println("[OptiForge] compat fix in " + dottedClassName
                    + ": useAmbientOcclusion(state,type) -> () x" + ambient);
        }
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Makes the reflective {@code ImmediateWindowHandler.setupMinecraftWindow} call return 0.
     *
     * <p>The call site pushes the ReflectorMethod, asks {@code exists()}, and branches into OptiFine's own
     * early-window path. Faking the answer keeps the branch guard intact but sends control down the
     * vanilla window creation instead, which is what works on Forge 26 - there the reflective call would
     * need a fifth argument OptiFine never supplies.
     *
     * @return number of call sites rewritten
     */
    private static int bypassImmediateWindowHandler(ClassNode node, String dottedClassName) {
        if (!dottedClassName.equals("com.mojang.blaze3d.platform.Window")) return 0;
        int changed = 0;
        for (MethodNode method : node.methods) {
            boolean afterSetupField = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.owner.equals("net/optifine/reflect/Reflector")
                        && field.name.startsWith("ImmediateWindowHandler")) {
                    afterSetupField = true;
                    continue;
                }
                if (!afterSetupField) continue;
                if (instruction instanceof MethodInsnNode call
                        && "net/optifine/reflect/ReflectorMethod".equals(call.owner)
                        && "callLong".equals(call.name)
                        && "([Ljava/lang/Object;)J".equals(call.desc)) {
                    // Four arguments are supplied, Forge 26's method wants five. Return 0 instead of
                    // invoking it: Window stores the result and only uses it when it is non-zero, so 0 sends
                    // control down the vanilla window creation (measured layout: callLong -> lstore 8 ->
                    // if (handle != 0) { this.handle = handle; }).
                    method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                    method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                    method.instructions.set(instruction, new InsnNode(Opcodes.LCONST_0));
                    changed++;
                    afterSetupField = false;
                }
            }
        }
        return changed;
    }

    /**
     * Makes {@code Reflector.ForgeEventFactoryClient_fireRenderNameTagEvent.exists()} answer false.
     *
     * <p>Measured on 26.1.2: OptiFine passes six arguments, Forge's method takes five, so {@code exists()} is
     * true while the call returns null and {@code EntityRenderer.submitNameDisplay} throws
     * "NullPointerException: renderNameplateEvent is null". Skipping the block keeps the vanilla nameplate
     * path, whose state (show flag and text) is already computed just before it.
     */
    private static int bypassNameplateEvent(ClassNode node) {
        int changed = 0;
        for (MethodNode method : node.methods) {
            boolean afterField = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && "net/optifine/reflect/Reflector".equals(field.owner)
                        && field.name.contains("fireRenderNameTagEvent")) {
                    afterField = true;
                    continue;
                }
                if (!afterField) continue;
                if (instruction instanceof MethodInsnNode call && "exists".equals(call.name)) {
                    method.instructions.insertBefore(instruction, new InsnNode(Opcodes.POP));
                    method.instructions.set(instruction, new InsnNode(Opcodes.ICONST_0));
                    changed++;
                    afterField = false;
                }
            }
        }
        if (changed > 0) {
            System.out.println("[OptiForge] bypassed the nameplate event reflector (arity mismatch) x" + changed);
        }
        return changed;
    }}
