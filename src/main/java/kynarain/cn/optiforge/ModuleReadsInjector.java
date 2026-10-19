package kynarain.cn.optiforge;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes the game module read Forge's modules, from code that is entitled to say so.
 *
 * <p>{@code Module.addReads} only accepts calls made from inside the module being changed, and this
 * launch does not open {@code java.base/java.lang}, so reflection into {@code implAddReads} is refused
 * (measured: {@code InaccessibleObjectException ... module java.base does not "opens java.lang" to module
 * OptiForge}). What does work is running the call from a class that belongs to the game module, so two
 * small static methods are generated into a patched game class and called from its static initialiser.
 *
 * <p>Why this is needed at all: in Forge 56 {@code net.minecraftforge.eventbus} is a module of its own
 * and {@code minecraft} does not read it, while OptiFine 1.21.6's patched classes reference
 * {@code net.minecraftforge.eventbus.api.Event} directly. Forge 51 kept eventbus inside the forge module,
 * which is why the 1.21 line never hit this. {@link ModuleLayer#findModule} only searches one layer and
 * the Forge modules live in a parent, hence the parent walk.
 */
public final class ModuleReadsInjector {

    private static final String INSTALL = "optiforge$installModuleReads";
    private static final String IN_LAYER = "optiforge$addReadsInLayer";
    private static final String[] MODULES = {
            "net.minecraftforge.eventbus",
            "net.minecraftforge.forge",
            "net.minecraftforge.fmlcore",
            "net.minecraftforge.fmlloader",
            "net.minecraftforge.coremod",
            "net.neoforged.bus",
            "net.neoforged.neoforge",
    };

    private ModuleReadsInjector() {
    }

    /** @return bytes with the injection, or the input when the class is not the host class */
    public static byte[] apply(byte[] classBytes, String dottedClassName) {
        // CrashReport is patched on every line examined, and its static initialiser runs from
        // CrashReport.preload() at the top of Main.main - early enough for every later Forge reference.
        if (!dottedClassName.equals("net.minecraft.CrashReport")) return classBytes;
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(classBytes).accept(node, 0);

        for (MethodNode existing : node.methods) {
            if (existing.name.equals(INSTALL)) return classBytes; // already injected
        }
        MethodNode clinit = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                clinit = method;
                break;
            }
        }
        if (clinit == null) return classBytes;

        node.methods.add(buildInstaller(node.name));
        node.methods.add(buildLayerHelper(node.name));
        clinit.instructions.insert(
                new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, INSTALL, "()V", false));
        System.out.println("[OptiForge] injected module-read installer into " + node.name);

        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code for (ModuleLayer cur = self.getLayer(), depth = 0; cur != null && depth < 4; depth++) { addReadsInLayer(cur, self); cur = cur.parents().isEmpty() ? null : (ModuleLayer) cur.parents().get(0); }}
     *  ModuleLayer has {@code parents()} (a List), not {@code parent()} - using the non-existent one
     *  threw NoSuchMethodError from inside the try/catch, which is why an earlier version "ran" and
     *  changed nothing. */
    private static MethodNode buildInstaller(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                INSTALL, "()V", null, null);
        InsnList code = method.instructions;
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode noParent = new LabelNode();
        LabelNode afterParent = new LabelNode();
        LabelNode done = new LabelNode();

        code.add(tryStart);
        code.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out",
                "Ljava/io/PrintStream;"));
        code.add(new LdcInsnNode("[OptiForge] injected module-read installer running"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false));
        // Module self = Host.class.getModule(); ModuleLayer cur = self.getLayer(); int depth = 0;
        code.add(new LdcInsnNode(Type.getObjectType(owner)));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getModule",
                "()Ljava/lang/Module;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Module", "getLayer",
                "()Ljava/lang/ModuleLayer;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));

        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new JumpInsnNode(Opcodes.IFNULL, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.ICONST_4));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, IN_LAYER,
                "(Ljava/lang/ModuleLayer;Ljava/lang/Module;)V", false));
        // List parents = cur.parents();
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ModuleLayer", "parents",
                "()Ljava/util/List;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z", true));
        code.add(new JumpInsnNode(Opcodes.IFNE, noParent));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/ModuleLayer"));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new JumpInsnNode(Opcodes.GOTO, afterParent));
        code.add(noParent);
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(afterParent);
        code.add(new org.objectweb.asm.tree.IincInsnNode(3, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));

        code.add(tryEnd);
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(handler);
        code.add(new InsnNode(Opcodes.POP));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));

        method.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, "java/lang/Throwable"));
        method.maxLocals = 5;
        method.maxStack = 4;
        return method;
    }
    /** {@code for (m : layer.modules()) try { self.addReads(m); } catch (Throwable ignored) {} }
     *  No name guessing: the module names differ between Forge lines, and adding a read to a module
     *  the game already reads is a no-op. */
    private static MethodNode buildLayerHelper(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                IN_LAYER, "(Ljava/lang/ModuleLayer;Ljava/lang/Module;)V", null, null);
        InsnList code = method.instructions;
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();

        code.add(tryStart);
        // Iterator it = layer.modules().iterator();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ModuleLayer", "modules",
                "()Ljava/util/Set;", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "iterator",
                "()Ljava/util/Iterator;", true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));

        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1)); // self
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Module"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Module", "addReads",
                "(Ljava/lang/Module;)Ljava/lang/Module;", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));

        code.add(tryEnd);
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(handler);
        code.add(new InsnNode(Opcodes.POP));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));

        method.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, "java/lang/Throwable"));
        method.maxLocals = 3;
        method.maxStack = 3;
        return method;
    }
}