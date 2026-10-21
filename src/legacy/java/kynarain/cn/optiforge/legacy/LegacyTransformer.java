package kynarain.cn.optiforge.legacy;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Hands OptiFine's packed classes to the 1.12.2 launch class loader.
 *
 * <p>Measured on Forge 1.12.2-14.23.5.2860 (docs/PHASE3.md §2.1): FML's {@code PatchingTransformer} is
 * registered first, then cascading tweakers, then coremod plugins. A coremod with the default
 * {@code @SortingIndex(1000)} therefore sees the class after Forge's binpatch has been applied to the
 * vanilla bytes, and its adler32 check has already passed - returning our own bytes here cannot make
 * FML call {@code System.exit(1)}.
 *
 * <p>The pack's class is a whole-class replacement built from the vanilla base, so it drops members that
 * the running Forge added to that class after OptiFine's build (measured: 16 of 248 classes on
 * 1.12.2 G5 vs Forge 2860; worst is {@code GuiVideoSettings} with 7). Those members are adopted here from
 * the transformer input; constructors and static initialisers are not adopted, because the payload
 * already carries its own and merging a second {@code <clinit>} would run initialisation twice.
 */
public final class LegacyTransformer implements IClassTransformer {

    private static final String TAG = "[OptiForge/legacy]";
    private static final boolean MERGE =
            !"false".equalsIgnoreCase(System.getProperty("optiforge.legacy.merge", "true"));
    /** {@code -Doptiforge.legacy.skipClasses=a,b}: deliver Forge's own class instead of the payload one. */
    private static final Set<String> SKIP = parseSkip(System.getProperty("optiforge.legacy.skipClasses", ""));
    /** True while this thread is inside the lazy pack build; a transform there must be a pass-through. */
    private static final ThreadLocal<Boolean> PREPARING = new ThreadLocal<Boolean>();

    /**
     * Packages that must never be transformed by us.
     *
     * <p>Measured in a real 1.12.2 launch: the lazy build loads {@code LegacyPayload} (and its siblings)
     * through the launch class loader, which re-enters this transformer and produced
     * {@code ClassCircularityError: kynarain/cn/optiforge/legacy/LegacyPayload}. FML/launchwrapper classes
     * are infrastructure OptiFine never patches, so they are skipped as well.
     */
    private static boolean excluded(String name) {
        if (name == null) {
            return false;
        }
        String dotted = name.replace('/', '.');
        return dotted.startsWith("kynarain.cn.optiforge.")
                || dotted.startsWith("net.minecraft.launchwrapper.")
                || dotted.startsWith("net.minecraftforge.");
    }

    private static Set<String> parseSkip(String value) {
        Set<String> out = new HashSet<String>();
        for (String part : value.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            out.add(name);
            out.add(name.replace('/', '.'));
            out.add(name.replace('.', '/'));
        }
        return out;
    }

    private volatile LegacyPack pack;
    private int replaced;
    private int merged;

    /**
     * No-argument form: FML instantiates transformer classes reflectively by name. The pack may not exist
     * yet ({@code injectData} runs before the version/base jar are known), so {@link #transform} asks
     * {@link LegacyPack#current()} again on every class until it succeeds.
     */
    public LegacyTransformer() {
        this(LegacyPack.current());
    }

    LegacyTransformer(LegacyPack pack) {
        this.pack = pack;
    }

    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        if (excluded(name) || excluded(transformedName)) {
            return basicClass;
        }
        if (Boolean.TRUE.equals(PREPARING.get())) {
            // A nested class load while the pack is being prepared must pass through untouched.
            return basicClass;
        }
        LegacyPack current = pack;
        if (current == null) {
            // First game class load after injectData: FMLInjectionData is populated now, so this is where
            // the deferred pack build happens.
            PREPARING.set(Boolean.TRUE);
            try {
                current = LegacyPack.current();
            } finally {
                PREPARING.remove();
            }
            if (current == null) {
                return basicClass;
            }
            pack = current;
        }
        if (!SKIP.isEmpty() && (SKIP.contains(name) || SKIP.contains(transformedName))) {
            return basicClass;
        }
        byte[] prepared = current.patched(name);
        if (prepared == null) {
            return basicClass;
        }
        replaced++;
        if (!MERGE) {
            return prepared;
        }
        try {
            Merge result = merge(basicClass, prepared);
            if (result.added == 0) {
                return prepared;
            }
            merged++;
            if (merged <= 40) {
                System.out.println(TAG + " adopted " + result.added + " member(s) Forge added to "
                        + name + " after OptiFine's build");
            }
            return result.bytes;
        } catch (Throwable t) {
            // A merge that cannot be proved safe must not cost the launch: the payload bytes are at
            // least the class OptiFine itself would have produced.
            System.out.println(TAG + " merge failed for " + name + " (" + t + "), using payload bytes");
            return prepared;
        }
    }

    public int replacedCount() {
        return replaced;
    }

    public int mergedCount() {
        return merged;
    }

    private static final class Merge {
        byte[] bytes;
        int added;
    }

    /**
     * Adds the fields, methods and interfaces that exist in {@code incoming} (Forge-patched) but not in
     * {@code prepared} (our payload class).
     *
     * <p>Frames are copied verbatim from the incoming method nodes and the writer only recomputes
     * max stack/locals ({@code COMPUTE_MAXS}): copying a whole method from the same-named class keeps its
     * stack map frames valid, and it avoids asking ASM to resolve the game's class hierarchy while a
     * class is being defined.
     *
     * <p>Measured on the 16 drifted classes of 1.12.2 G5 vs Forge 2860: only one adopted member is a
     * static field ({@code GuiVideoSettings.i}, {@code private static final GameSettings$Options[]}) and it
     * keeps its default value because {@code <clinit>} is never merged. Everything else is instance level
     * or a synthetic {@code access$N} helper. If that field is ever the cause of a crash,
     * {@code -Doptiforge.legacy.skipClasses=bls} delivers Forge's own GuiVideoSettings instead.
     */
    static Merge merge(byte[] incoming, byte[] prepared) {
        ClassReader incomingReader = new ClassReader(incoming);
        ClassNode source = new ClassNode();
        incomingReader.accept(source, ClassReader.SKIP_DEBUG);

        ClassReader preparedReader = new ClassReader(prepared);
        ClassNode target = new ClassNode();
        preparedReader.accept(target, 0);

        Set<String> fields = new HashSet<String>();
        for (FieldNode field : target.fields) {
            fields.add(field.name + ":" + field.desc);
        }
        Set<String> methods = new HashSet<String>();
        for (MethodNode method : target.methods) {
            methods.add(method.name + method.desc);
        }

        int added = 0;
        for (FieldNode field : source.fields) {
            if (fields.add(field.name + ":" + field.desc)) {
                target.fields.add(field);
                added++;
            }
        }
        for (MethodNode method : source.methods) {
            if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
                continue;
            }
            if (methods.add(method.name + method.desc)) {
                target.methods.add(method);
                added++;
            }
        }
        for (String iface : source.interfaces) {
            if (!target.interfaces.contains(iface)) {
                target.interfaces.add(iface);
                added++;
            }
        }

        Merge result = new Merge();
        result.added = added;
        if (added == 0) {
            result.bytes = prepared;
            return result;
        }
        ClassWriter writer = new ClassWriter(preparedReader, ClassWriter.COMPUTE_MAXS);
        target.accept(writer);
        result.bytes = writer.toByteArray();
        return result;
    }
}
