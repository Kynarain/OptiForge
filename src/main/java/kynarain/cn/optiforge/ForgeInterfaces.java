package kynarain.cn.optiforge;

import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Removes interfaces that OptiFine's payload declares but the running Forge does not have.
 *
 * <p>Measured on Forge 64.1.5 (MC 26.1.2): the prepared {@code VertexFormat} came out as
 * {@code implements net.minecraftforge.client.extensions.IForgeVertexFormat} - OptiFine's 26.x patch was
 * built against an API that has it - but that class exists in no jar of the 26.1.2 profile (the interface is
 * present in every 1.21.x universal jar and absent from 26.x). Defining the class then fails with
 * {@code NoClassDefFoundError: net/minecraftforge/client/extensions/IForgeVertexFormat} raised from
 * {@code DefaultVertexFormat.<clinit>}, because that is where {@code VertexFormat} is first touched.
 *
 * <p>The classpath is a fact, so the check is a fact too: an interface whose class file is not in any
 * launch jar is dropped from the prepared class. Interfaces that do exist are kept untouched, so 1.21.x is
 * unaffected (the interface is there).
 */
public final class ForgeInterfaces {

    private ForgeInterfaces() {
    }

    /** Every class name (internal form) present in the jars of this launch. */
    public static Set<String> availableClasses(Path gameDir, String versionTag) {
        Set<String> names = new HashSet<>();
        for (Path jar : LaunchPaths.jars(gameDir)) {
            // LaunchPaths scans the whole libraries tree and a rig keeps several Forge versions side by
            // side. Availability has to reflect *this* launch: measured, the 1.21.x universal jars still
            // carry IForgeVertexFormat, which made the check report "present" while Forge 64 lacked it.
            String path = jar.toString().replace('\\', '/');
            if (versionTag != null && !versionTag.isBlank()
                    && path.contains("/net/minecraftforge/forge/")
                    && !path.contains("/" + versionTag + "/")) {
                continue;
            }
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                        names.add(name.substring(0, name.length() - ".class".length()));
                    }
                }
            } catch (IOException ignored) {
                // a jar we cannot read simply contributes nothing
            }
        }
        return names;
    }

    /**
     * @return the dotted names that were dropped, empty when nothing changed
     */
    public static List<String> strip(ClassNode node, Set<String> available) {
        if (node.interfaces == null || node.interfaces.isEmpty() || available.isEmpty()) return List.of();
        List<String> dropped = new ArrayList<>();
        for (String itf : new ArrayList<>(node.interfaces)) {
            if (!itf.startsWith("net/minecraftforge/") && !itf.startsWith("net/neoforged/")) continue;
            if (available.contains(itf)) continue;
            node.interfaces.remove(itf);
            dropped.add(itf.replace('/', '.'));
        }
        return dropped;
    }

    /** Convenience: how many classes were indexed, for the diagnostic report. */
    public static String describe(Set<String> available) {
        return "classpath index: " + available.size() + " classes";
    }
}
