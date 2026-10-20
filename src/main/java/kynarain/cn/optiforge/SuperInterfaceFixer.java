package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Declares interface super-calls a prepared class forgot to list.
 *
 * <p>The JVM only allows {@code invokespecial} on an interface method when that interface is a <b>direct</b>
 * superinterface of the caller. OptiFine's payload can emit such a call without listing the interface - on
 * 1.20.1 its {@code Mob} does
 * {@code invokespecial net/minecraftforge/common/extensions/IForgeLivingEntity.jumpInFluid(FluidType)V}
 * while {@code Mob} declares only {@code Targeting}. Whether that goes unnoticed depended on the runtime
 * merge picking the interface up from the incoming class, which in turn depended on which jar the launcher
 * had made the game classes come from - measured: PCL's install produced
 * {@code VerifyError: Bad invokespecial instruction: interface method reference is in an indirect
 * superinterface} while the same jar in the test rig did not.
 *
 * <p>Adding the interface at pack time removes that dependency. Only interfaces that actually exist in this
 * launch are added (checked against the classpath index), so a genuinely missing type still degrades the same
 * way {@link ForgeInterfaces} handles it.
 */
public final class SuperInterfaceFixer {

    private SuperInterfaceFixer() {
    }

    /** @return the interfaces that were added (internal names) */
    public static List<String> fix(ClassNode node, Set<String> available) {
        if (node.interfaces == null) node.interfaces = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.getOpcode() != Opcodes.INVOKESPECIAL || !call.itf) continue;
                String owner = call.owner;
                if (node.interfaces.contains(owner) || added.contains(owner)) continue;
                if (owner.equals(node.name)) continue; // super call inside the interface itself
                if (!available.isEmpty() && !available.contains(owner)) continue;
                node.interfaces.add(owner);
                added.add(owner);
                System.out.println("[OptiForge] declared missing interface " + owner.replace('/', '.')
                        + " on " + node.name.replace('/', '.') + " (invokespecial requirement)");
            }
        }
        return added;
    }
}
