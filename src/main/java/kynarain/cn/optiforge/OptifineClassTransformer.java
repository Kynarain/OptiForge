package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;

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

    private final boolean modern;
    private final Set<String> runtimePatched;

    public OptifineClassTransformer(OptifinePack pack) {
        this.pack = pack;
        this.modern = OptifinePack.mcVersion != null && OptifinePack.mcVersion.startsWith("26.");
        this.runtimePatched = RuntimePatches.targets(modern);
        Set<Target> set = new LinkedHashSet<>();
        for (String className : pack.patchedClasses.keySet()) {
            set.add(Target.targetPreClass(className));
        }
        for (String className : runtimePatched) {
            set.add(Target.targetPreClass(className));
        }
        this.targets = Set.copyOf(set);
    }

    @Override
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        // the game module only exists once classes are being transformed
        OptiForgeService.ensureModuleReads();
        ClassNode patchedForge = RuntimePatches.apply(input, modern);
        if (patchedForge != null) return patchedForge;
        byte[] prepared = pack.patched(context.getClassName());
        if (prepared == null) return input;
        ClassNode node = new ClassNode();
        new ClassReader(prepared).accept(node, 0);
        reportAdditions(input, node, context.getClassName());
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
        // OptiFine's payload is a delta against vanilla, so its output can lose a superclass Forge added.
        // Measured on 26.2: Forge's client jar has Gui extends ForgeLayerInstance (which carries popLayer),
        // OptiFine's version extends Object, and Screen.onClose then died with
        // "NoSuchMethodError: Gui.popLayer()". Adopting the incoming superclass restores the inherited API.
        if (incoming.superName != null && !incoming.superName.equals(prepared.superName)
                && "java/lang/Object".equals(prepared.superName)) {
            System.out.println("[OptiForge] adopting superclass " + incoming.superName + " for "
                    + prepared.name);
            String adopted = incoming.superName;
            prepared.superName = adopted;
            rewireSuperCall(prepared, adopted);
        }
        if (prepared.interfaces == null) prepared.interfaces = new java.util.ArrayList<>();
        for (String itf : incoming.interfaces) {
            if (!prepared.interfaces.contains(itf)) prepared.interfaces.add(itf);
        }
        java.util.Set<String> methods = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.MethodNode method : prepared.methods) {
            methods.add(method.name + method.desc);
        }
        java.util.List<org.objectweb.asm.tree.MethodNode> adoptedMethods = new java.util.ArrayList<>();
        for (org.objectweb.asm.tree.MethodNode method : incoming.methods) {
            // Never carry over initialisers. OptiFine's payload sometimes turns a vanilla static field
            // into an instance field (BreezeWindLayer.TEXTURE_LOCATION is the measured case); bringing the
            // vanilla <clinit> along then produced "IncompatibleClassChangeError: Expected static field"
            // on the first launch that used the merged class.
            if (method.name.equals("<clinit>") || method.name.equals("<init>")) continue;
            if (methods.add(method.name + method.desc)) {
                prepared.methods.add(method);
                adoptedMethods.add(method);
            }
        }
        java.util.Set<String> fields = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.FieldNode field : prepared.fields) {
            fields.add(field.name + field.desc);
        }
        java.util.List<org.objectweb.asm.tree.FieldNode> adopted = new java.util.ArrayList<>();
        for (org.objectweb.asm.tree.FieldNode field : incoming.fields) {
            if (fields.add(field.name + field.desc)) {
                prepared.fields.add(field);
                adopted.add(field);
            }
        }
        // The adopted static fields were initialised by the incoming <clinit>, which is deliberately not
        // merged (see above). Give the adopted methods a lazy default for the ones they read, so a getter
        // returns an empty collection instead of null. Measured on 1.21.1: Forge's Direction.getUpdateOrder()
        // returned null and Level.updateNeighbourForOutputSignal died with
        // "NullPointerException ... Direction.getUpdateOrder() is null".
        guardAdoptedFieldReads(prepared, adopted, adoptedMethods);
    }

    /** Inserts "if (FIELD == null) FIELD = <empty container>;" before each read of an adopted field. */
    private static void guardAdoptedFieldReads(org.objectweb.asm.tree.ClassNode prepared,
                                               java.util.List<org.objectweb.asm.tree.FieldNode> adopted,
                                               java.util.List<org.objectweb.asm.tree.MethodNode> methods) {
        if (adopted.isEmpty() || methods.isEmpty()) return;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.FieldNode field : adopted) {
            if (defaultFactory(field.desc) == null) continue; // primitives already default sensibly
            // The lazy write below happens in getUpdateOrder, not in <clinit>, and writing a static final
            // field from anywhere else is an IllegalAccessError (measured on 1.21.1:
            // "Update to static final field Direction.UPDATE_ORDER attempted from a different method").
            field.access &= ~Opcodes.ACC_FINAL;
            names.add(field.name + field.desc);
        }
        if (names.isEmpty()) return;
        int guards = 0;
        for (org.objectweb.asm.tree.MethodNode method : methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof org.objectweb.asm.tree.FieldInsnNode read)) continue;
                if (read.getOpcode() != org.objectweb.asm.Opcodes.GETSTATIC) continue;
                if (!names.contains(read.name + read.desc)) continue;
                org.objectweb.asm.tree.InsnList guard = new org.objectweb.asm.tree.InsnList();
                org.objectweb.asm.tree.LabelNode exists = new org.objectweb.asm.tree.LabelNode();
                guard.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, prepared.name,
                        read.name, read.desc));
                guard.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNONNULL, exists));
                guard.add(defaultFactory(read.desc));
                guard.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, prepared.name,
                        read.name, read.desc));
                guard.add(exists);
                method.instructions.insertBefore(instruction, guard);
                guards++;
            }
        }
        if (guards > 0) {
            System.out.println("[OptiForge] guarded " + guards + " read(s) of adopted field(s) in "
                    + prepared.name);
        }
    }

    /** @return instructions that build an empty container for the descriptor, or null when none applies */
    private static org.objectweb.asm.tree.InsnList defaultFactory(String desc) {
        org.objectweb.asm.tree.InsnList list = new org.objectweb.asm.tree.InsnList();
        if (desc.startsWith("[")) {
            String component = desc.substring(1);
            list.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
            int sort = org.objectweb.asm.Type.getType(component).getSort();
            if (sort == org.objectweb.asm.Type.OBJECT || sort == org.objectweb.asm.Type.ARRAY) {
                list.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.ANEWARRAY,
                        component.startsWith("[") ? component : component.substring(1,
                                component.length() - 1)));
            } else {
                String primitive = switch (component.charAt(0)) {
                    case 'Z' -> "BOOLEAN";
                    case 'B' -> "BYTE";
                    case 'C' -> "CHAR";
                    case 'S' -> "SHORT";
                    case 'I' -> "INT";
                    case 'J' -> "LONG";
                    case 'F' -> "FLOAT";
                    case 'D' -> "DOUBLE";
                    default -> "INT";
                };
                list.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.NEWARRAY,
                        primitiveArrayType(primitive)));
            }
            return list;
        }
        if (desc.equals("Ljava/util/Map;") || desc.equals("Ljava/util/HashMap;")) {
            list.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
            list.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
            list.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap",
                    "<init>", "()V", false));
            if (desc.equals("Ljava/util/Map;")) {
                // HashMap is a Map; nothing else needed
            }
            return list;
        }
        if (desc.equals("Ljava/util/List;") || desc.equals("Ljava/util/Collection;")
                || desc.equals("Ljava/lang/Iterable;")) {
            list.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/util/Collections", "emptyList", "()Ljava/util/List;", false));
            return list;
        }
        return null;
    }

    private static int primitiveArrayType(String name) {
        return switch (name) {
            case "BOOLEAN" -> Opcodes.T_BOOLEAN;
            case "BYTE" -> Opcodes.T_BYTE;
            case "CHAR" -> Opcodes.T_CHAR;
            case "SHORT" -> Opcodes.T_SHORT;
            case "INT" -> Opcodes.T_INT;
            case "LONG" -> Opcodes.T_LONG;
            case "FLOAT" -> Opcodes.T_FLOAT;
            case "DOUBLE" -> Opcodes.T_DOUBLE;
            default -> Opcodes.T_INT;
        };
    }

    /**
     * Copies the incoming {@code <clinit>} statements that initialise fields we just adopted.
     *
     * <p>Measured on 1.21.1: Forge's Mixin adds {@code Direction.getUpdateOrder()} plus the data it reads,
     * but the data is filled by a statement in {@code <clinit>}. Adopting only the field left the getter
     * returning null and the world died with
     * {@code NullPointerException: ... Direction.getUpdateOrder() is null}. The initialising statement is
     * taken together with its preceding line-number label, which is how javac delimits statements; when
     * there is no such label the copy starts at the closest preceding label instead.
     *
     * <p>A whole {@code <clinit>} is deliberately never merged: it would run the vanilla initialisers a
     * second time, which is how {@code IncompatibleClassChangeError: Expected static field} appeared before.
     */
    private static void carryOverInitialisers(org.objectweb.asm.tree.ClassNode incoming,
                                              org.objectweb.asm.tree.ClassNode prepared,
                                              java.util.List<org.objectweb.asm.tree.FieldNode> adopted) {
        org.objectweb.asm.tree.MethodNode source = null;
        org.objectweb.asm.tree.MethodNode target = null;
        for (org.objectweb.asm.tree.MethodNode method : incoming.methods) {
            if (method.name.equals("<clinit>")) source = method;
        }
        for (org.objectweb.asm.tree.MethodNode method : prepared.methods) {
            if (method.name.equals("<clinit>")) target = method;
        }
        if (source == null) return;
        if (target == null) {
            target = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_STATIC,
                    "<clinit>", "()V", null, null);
            target.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN));
            prepared.methods.add(target);
        }
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.FieldNode field : adopted) {
            wanted.add(field.name + field.desc);
        }

        java.util.List<org.objectweb.asm.tree.AbstractInsnNode> batch = new java.util.ArrayList<>();
        org.objectweb.asm.tree.AbstractInsnNode start = null;
        org.objectweb.asm.tree.AbstractInsnNode cursor = source.instructions.getFirst();
        while (cursor != null) {
            if (cursor instanceof org.objectweb.asm.tree.LineNumberNode
                    || cursor instanceof org.objectweb.asm.tree.LabelNode
                    || cursor instanceof org.objectweb.asm.tree.FrameNode) {
                start = cursor;
            }
            if (cursor instanceof org.objectweb.asm.tree.FieldInsnNode put
                    && put.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC
                    && wanted.contains(put.name + put.desc)
                    && start != null) {
                for (org.objectweb.asm.tree.AbstractInsnNode copy = start;
                        copy != null && copy != cursor.getNext(); copy = copy.getNext()) {
                    batch.add(copy);
                }
                wanted.remove(put.name + put.desc);
            }
            cursor = cursor.getNext();
        }
        if (batch.isEmpty()) return;

        org.objectweb.asm.tree.AbstractInsnNode insertAt = target.instructions.getLast();
        if (insertAt == null) {
            insertAt = new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.RETURN);
            target.instructions.add(insertAt);
        }
        for (org.objectweb.asm.tree.AbstractInsnNode instruction : batch) {
            target.instructions.insertBefore(insertAt, instruction);
        }
        System.out.println("[OptiForge] carried over " + batch.size() + " initialiser instruction(s) for "
                + adopted.size() + " adopted field(s) in " + prepared.name);
    }

    /**
     * Votes DEFER so this transformer runs as late as ModLauncher allows.
     *
     * <p>Measured on 26.2: Forge adds {@code Gui.popLayer()} with a runtime patch that lands <b>after</b>
     * the prepared class, and {@code Screen.onClose} (already compiled against it) then fails with
     * {@code NoSuchMethodError}. Running last lets {@link #mergeInPlaceAdditions} see those additions and
     * carry them over. 1.21.1 shows the same shape with {@code Direction.getUpdateOrder()}.
     */
    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        if (pack.patched(context.getClassName()) == null
                && !runtimePatched.contains(context.getClassName())) {
            return TransformerVoteResult.NO;
        }
        return Boolean.getBoolean("optiforge.deferVote")
                ? TransformerVoteResult.DEFER
                : TransformerVoteResult.YES;
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

    /** Diagnostic: names what the incoming node carries beyond the prepared class (see mergeInPlaceAdditions). */
    private static void reportAdditions(ClassNode incoming, ClassNode prepared, String className) {
        if (incoming == null || !Boolean.getBoolean("optiforge.mergeLog")) return;
        StringBuilder extra = new StringBuilder();
        for (String itf : incoming.interfaces) {
            if (!prepared.interfaces.contains(itf)) extra.append("interface ").append(itf).append(' ');
        }
        java.util.Set<String> mine = new java.util.HashSet<>();
        for (org.objectweb.asm.tree.MethodNode method : prepared.methods) mine.add(method.name + method.desc);
        for (org.objectweb.asm.tree.MethodNode method : incoming.methods) {
            if (mine.add(method.name + method.desc)) extra.append("method ").append(method.name).append(' ');
        }
        if (extra.length() > 0) {
            System.out.println("[OptiForge] incoming additions for " + className + ": " + extra);
        }
    }

    /**
     * Points the constructor's {@code Object.<init>} call at the adopted superclass.
     *
     * <p>Measured on 26.2 after adopting {@code ForgeLayerInstance} for {@code Gui}: leaving the original
     * {@code invokespecial java/lang/Object.<init>()V} produced
     * {@code VerifyError: Bad <init> method call ... Type java/lang/Object is not assignable to Gui}. Forge's
     * overlay base takes the {@code Minecraft} instance, which is the first constructor parameter here.
     */
    private static void rewireSuperCall(ClassNode prepared, String superName) {
        boolean takesMinecraft = false;
        if (superName.endsWith("ForgeLayerInstance")) takesMinecraft = true;
        for (org.objectweb.asm.tree.MethodNode method : prepared.methods) {
            if (!method.name.equals("<init>")) continue;
            boolean firstParamIsMinecraft = method.desc.startsWith("(Lnet/minecraft/client/Minecraft;");
            for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)) continue;
                if (call.getOpcode() != org.objectweb.asm.Opcodes.INVOKESPECIAL) continue;
                if (!call.owner.equals("java/lang/Object") || !call.name.equals("<init>")) continue;
                call.owner = superName;
                if (takesMinecraft && firstParamIsMinecraft) {
                    call.desc = "(Lnet/minecraft/client/Minecraft;)V";
                    method.instructions.insertBefore(call, new org.objectweb.asm.tree.VarInsnNode(
                            org.objectweb.asm.Opcodes.ALOAD, 1));
                } else {
                    call.desc = "()V";
                }
            }
        }
    }}
