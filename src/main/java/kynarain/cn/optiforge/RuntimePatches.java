package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small runtime patches applied to <b>Forge's own</b> classes on MC 26.x.
 *
 * <p>Why this exists: on 26.1.2 and 26.2 the integrated server ticks before its resource reload has run, so
 * {@code ForgeInternalHandler.getLootModifierManager()} still throws
 * {@code IllegalStateException: Can not retrieve LootModifierManager until resources have loaded once} and
 * the client dies the moment the world starts (the vanilla Forge control on the same world does not, so the
 * timing is changed by OptiFine's resource handling).
 *
 * <p>{@code ForgeHooks.modifyLoot(LootTable, ObjectArrayList, LootContext)} is the only caller in that path.
 * Rather than touching the manager, the method gets a {@code catch (Throwable)} that returns the untouched
 * loot list: when the manager is there nothing changes, and when it is not the loot simply arrives without
 * global loot modifiers instead of killing the world. Scoped to 26.x, so verified lines never see it.
 */
public final class RuntimePatches {

    private static final String FORGE_HOOKS = "net.minecraftforge.common.ForgeHooks";

    private RuntimePatches() {
    }

    /** @return the classes this pass wants as transformation targets (empty unless enabled) */
    public static Set<String> targets(boolean modern) {
        Set<String> names = new LinkedHashSet<>();
        if (modern) names.add(FORGE_HOOKS);
        return names;
    }

    /** @return the patched node, or {@code null} when this class is not handled here */
    public static ClassNode apply(ClassNode node, boolean modern) {
        if (!modern || !FORGE_HOOKS.equals(node.name.replace('/', '.'))) return null;
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("modifyLoot") || !method.desc.contains("ObjectArrayList")) continue;
            if (method.tryCatchBlocks.stream().anyMatch(block -> "java/lang/Throwable".equals(block.type))) {
                continue; // already wrapped
            }
            InsnList code = method.instructions;
            LabelNode tryStart = new LabelNode();
            LabelNode tryEnd = new LabelNode();
            LabelNode handler = new LabelNode();
            code.insert(tryStart);
            code.add(tryEnd);
            code.add(handler);
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new InsnNode(Opcodes.ARETURN));
            method.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, "java/lang/Throwable"));
            method.maxStack = Math.max(method.maxStack, 2);
            patched++;
            System.out.println("[OptiForge] guarded ForgeHooks.modifyLoot against an unloaded loot manager");
        }
        return patched == 0 ? null : node;
    }
}
