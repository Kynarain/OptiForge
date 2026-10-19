package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Replaces the game classes OptiFine patches with the versions {@link OptifinePack} prepared.
 *
 * <p>Targets are registered as {@code targetPreClass}, which is what OptiFine itself does when the
 * ModLauncher spec is 7 or newer (its log says "Target.PRE_CLASS is available"). That means this
 * transformer sees the <b>raw</b> class - before Forge's own transformers - and Forge's patches are
 * applied on top afterwards, exactly the layering a working OptiFine-on-Forge run has. Returning a
 * fresh {@link ClassNode} for the target class is therefore not "dropping someone else's work": at
 * this stage nobody else has run.
 */
public final class OptifineClassTransformer implements ITransformer<ClassNode> {

    private final OptifinePack pack;
    private final Set<Target> targets;
    private int applied;

    public OptifineClassTransformer(OptifinePack pack) {
        this.pack = pack;
        Set<Target> set = new LinkedHashSet<>();
        for (String className : pack.patchedClasses.keySet()) {
            set.add(Target.targetPreClass(className));
        }
        this.targets = Set.copyOf(set);
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        // the game module only exists once classes are being transformed
        OptiForgeService.ensureModuleReads();
        byte[] prepared = pack.patched(context.getClassName());
        if (prepared == null) return input;
        ClassNode node = new ClassNode();
        new ClassReader(prepared).accept(node, 0);
        mergeInPlaceAdditions(input, node);
        applied++;
        return node;
    }

    /**
     * Keeps members and interfaces that other transformers already added to the incoming class.
     *
     * <p>The {@code targetPreClass} order is not stable across Forge versions: on Forge 64 (MC 26.1.2)
     * Mixin runs first and adds {@code IForgeVertexFormat} to {@code DefaultVertexFormat}; returning our
     * own node then dropped it and the launch died with
     * {@code NoClassDefFoundError: net/minecraftforge/client/extensions/IForgeVertexFormat} from that very
     * class's static initialiser. Anything the incoming node has and the prepared one does not is carried
     * over, so OptiFine's patch wins where both define the same member and nobody's additions are lost.
     */
    private static void mergeInPlaceAdditions(ClassNode incoming, ClassNode prepared) {
        if (incoming == null) return;
        if (prepared.interfaces == null) prepared.interfaces = new java.util.ArrayList<>();
        for (String itf : incoming.interfaces) {
            if (!prepared.interfaces.contains(itf)) prepared.interfaces.add(itf);
        }
        java.util.Set<String> methods = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.MethodNode method : prepared.methods) {
            methods.add(method.name + method.desc);
        }
        for (org.objectweb.asm.tree.MethodNode method : incoming.methods) {
            if (methods.add(method.name + method.desc)) prepared.methods.add(method);
        }
        java.util.Set<String> fields = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.FieldNode field : prepared.fields) {
            fields.add(field.name + field.desc);
        }
        for (org.objectweb.asm.tree.FieldNode field : incoming.fields) {
            if (fields.add(field.name + field.desc)) prepared.fields.add(field);
        }
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return pack.patched(context.getClassName()) != null
                ? TransformerVoteResult.YES
                : TransformerVoteResult.NO;
    }

    @Override
    public Set<Target> targets() {
        return targets;
    }

    @Override
    public String[] labels() {
        return new String[]{"optiforge", "optifine-payload", "srg-remap"};
    }

    public int appliedCount() {
        return applied;
    }

    public Map<String, byte[]> prepared() {
        return pack.patchedClasses;
    }
}
