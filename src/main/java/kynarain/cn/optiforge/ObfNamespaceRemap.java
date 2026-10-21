package kynarain.cn.optiforge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Obfuscated-name -> runtime-name bridge for the 1.13.2 OptiFine build.
 *
 * <p>That build is a different payload generation from 1.14+: it ships flat
 * {@code patch/<obf>.class.xdelta} deltas and its own classes under {@code net/optifine/**}, all
 * compiled against the <b>obfuscated</b> client jar. The Forge 1.13.2 runtime, however, uses the
 * namespace of {@code mcp_config}'s {@code config/joined.tsrg}: official class names and joined
 * member names ({@code func_}/{@code field_} where MCP has no human name). The bridge applies that
 * table: classes, fields and methods are renamed obf -> joined, descriptors included.
 *
 * <p>The table is keyed per declaring class, but OptiFine's code (like javac's output) may reference an
 * inherited member through a subclass owner ({@code BlockPosM} calls {@code BlockPos.o()}, which is
 * declared on {@code Vec3i}). The remapper therefore walks the obf hierarchy of the vanilla jar -
 * loaded once from the base jar - until it finds the declaring class, exactly how the JVM resolves the
 * member. Without that step 436 of the own-class references stayed obfuscated (measured).
 *
 * <p>Java 8 only: this class is compiled into the oldml artifact.
 */
public final class ObfNamespaceRemap {

    private static final int MAX_EXAMPLES = 8;

    private final Map<String, String> classMap;
    private final Map<String, String> fieldMap;
    private final Map<String, String> methodMap;
    private final Map<String, String> superMap = new HashMap<>();
    private final Map<String, List<String>> interfaceMap = new HashMap<>();
    private final Set<String> interfaceNames = new HashSet<>();
    private final Remapper remapper;

    private int remappedRefs;
    private int unresolvedRefs;
    private final List<String> unresolvedExamples = new ArrayList<>();
    private int overrideMisses;
    private final List<String> overrideMissExamples = new ArrayList<>();

    private ObfNamespaceRemap(Map<String, String> classMap, Map<String, String> fieldMap,
                              Map<String, String> methodMap) {
        this.classMap = classMap;
        this.fieldMap = fieldMap;
        this.methodMap = methodMap;
        this.remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                String mapped = ObfNamespaceRemap.this.classMap.get(internalName);
                return mapped != null ? mapped : internalName;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String mapped = resolve(owner, name, null, true);
                if (mapped != null) {
                    remappedRefs++;
                    return mapped;
                }
                countUnresolved(owner, name, true);
                return name;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (name.startsWith("<")) return name;
                String mapped = resolve(owner, name, descriptor, false);
                if (mapped != null) {
                    remappedRefs++;
                    return mapped;
                }
                countUnresolved(owner, name, false);
                return name;
            }
        };
    }

    /**
     * @param tsrg     {@code config/joined.tsrg} from the 1.13.2 mcp_config artifact
     * @param obfBase  the vanilla (obfuscated) client jar, whose hierarchy is used to resolve
     *                 references that go through a subclass owner
     */
    public static ObfNamespaceRemap load(InputStream tsrg, Path obfBase) throws IOException {
        Map<String, String> classes = new HashMap<>();
        Map<String, String> fields = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        String text = new String(J8.readAll(tsrg), StandardCharsets.UTF_8);
        String owner = null;
        for (String raw : text.split("\\R")) {
            if (raw.isEmpty()) continue;
            if (raw.charAt(0) != '\t') {
                String[] parts = raw.trim().split("\\s+");
                if (parts.length >= 2) {
                    owner = parts[0];
                    classes.put(parts[0], parts[1]);
                }
                continue;
            }
            String line = raw.trim();
            String[] parts = line.split("\\s+");
            if (parts.length == 3 && parts[1].startsWith("(")) {
                methods.put(owner + "\u0000" + parts[0] + "\u0000" + parts[1], parts[2]);
            } else if (parts.length >= 2) {
                fields.put(owner + "\u0000" + parts[0], parts[1]);
            }
        }
        ObfNamespaceRemap remap = new ObfNamespaceRemap(classes, fields, methods);
        remap.loadHierarchy(obfBase);
        return remap;
    }

    private void loadHierarchy(Path obfBase) throws IOException {
        try (ZipFile zip = new ZipFile(obfBase.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.equals("module-info.class")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(J8.readAll(in)).accept(node,
                            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    if (node.superName != null) superMap.put(node.name, node.superName);
                    if (node.interfaces != null && !node.interfaces.isEmpty()) {
                        interfaceMap.put(node.name, new ArrayList<>(node.interfaces));
                        interfaceNames.addAll(node.interfaces);
                    }
                } catch (RuntimeException ignored) {
                    // a class this ASM generation cannot read only loses its own hierarchy entry
                }
            }
        }
    }

    /** Walks the obf hierarchy from {@code owner} to the class that actually declares the member. */
    private String resolve(String owner, String name, String descriptor, boolean field) {
        if (owner == null) return null;
        ArrayDeque<String> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!seen.add(current)) continue;
            String mapped = field
                    ? fieldMap.get(current + "\u0000" + name)
                    : methodMap.get(current + "\u0000" + name + "\u0000" + descriptor);
            if (mapped != null) return mapped;
            String superName = superMap.get(current);
            if (superName != null) queue.add(superName);
            List<String> interfaces = interfaceMap.get(current);
            if (interfaces != null) queue.addAll(interfaces);
        }
        return null;
    }

    private void countUnresolved(String owner, String name, boolean field) {
        // OptiFine's own classes call members OptiFine itself adds to game classes; those names are
        // plain and legitimately absent from the table, so only obf-shaped names are interesting.
        boolean looksObf = name.matches("[a-z][a-z0-9]{0,3}") || name.matches("[a-z][a-z0-9]*_[a-z0-9]?");
        if (!looksObf || !classMap.containsKey(owner)) return;
        unresolvedRefs++;
        if (unresolvedExamples.size() < MAX_EXAMPLES) {
            unresolvedExamples.add((field ? "field " : "method ") + classMap.get(owner) + "." + name);
        }
    }

    public byte[] remap(byte[] classBytes) {
        ClassNode probe = new ClassNode();
        new ClassReader(classBytes).accept(probe,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        // OptiFine's own classes are not in the vanilla jar's hierarchy. Register their super/interfaces
        // before remapping, otherwise a method that implements an *interface* (TextureUtils$1 implementing
        // IResourceManagerReloadListener, measured on 1.13.2) cannot resolve to the interface's runtime id:
        // it stayed `a(...)` while the interface side became `func_195410_a(...)`, and the game died with
        // AbstractMethodError on the first resource reload.
        if (probe.name != null && !superMap.containsKey(probe.name)) {
            if (probe.superName != null) superMap.put(probe.name, probe.superName);
            if (probe.interfaces != null && !probe.interfaces.isEmpty()) {
                interfaceMap.put(probe.name, new ArrayList<>(probe.interfaces));
                interfaceNames.addAll(probe.interfaces);
            }
        }
        Map<String, java.util.Set<String>> expectedOverrides = expectedInterfaceOverrides(probe);
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes).accept(new ClassRemapper(writer, remapper), 0);
        byte[] out = writer.toByteArray();
        if (!expectedOverrides.isEmpty()) verifyOverrides(probe.name, out, expectedOverrides);
        return out;
    }

    /**
     * For every method this class declares that overrides an interface method, the runtime id that method
     * must carry after remapping. Computed straight from the interface table, independent of whether the
     * name resolver found it, so a resolver miss shows up as a validation failure instead of a crash.
     */
    private Map<String, java.util.Set<String>> expectedInterfaceOverrides(ClassNode probe) {
        Map<String, java.util.Set<String>> expected = new HashMap<>();
        // Interfaces this class is responsible for: its own, plus the ones its vanilla superclass chain
        // already implements (OptiFine subclasses a game class and overrides one of its interface methods).
        java.util.Set<String> implemented = new java.util.LinkedHashSet<>();
        if (probe.interfaces != null) implemented.addAll(probe.interfaces);
        String superName = probe.superName;
        Set<String> visitedSupers = new HashSet<>();
        while (superName != null && visitedSupers.add(superName)) {
            List<String> superInterfaces = interfaceMap.get(superName);
            if (superInterfaces != null) implemented.addAll(superInterfaces);
            superName = superMap.get(superName);
        }
        if (implemented.isEmpty()) return expected;
        for (org.objectweb.asm.tree.MethodNode method : probe.methods) {
            if (method.name.startsWith("<")) continue;
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT)) != 0) {
                continue;
            }
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(implemented);
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!seen.add(current)) continue;
                String mapped = methodMap.get(current + "\u0000" + method.name + "\u0000" + method.desc);
                if (mapped != null) ids.add(mapped + remapper.mapMethodDesc(method.desc));
                List<String> parents = interfaceMap.get(current);
                if (parents != null) queue.addAll(parents);
            }
            if (!ids.isEmpty()) expected.put(method.name + "\u0000" + method.desc, ids);
        }
        return expected;
    }

    private void verifyOverrides(String className, byte[] remapped,
                                 Map<String, java.util.Set<String>> expected) {
        ClassNode out = new ClassNode();
        new ClassReader(remapped).accept(out,
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<String> declared = new HashSet<>();
        for (org.objectweb.asm.tree.MethodNode method : out.methods) {
            if ((method.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            declared.add(method.name + method.desc);
        }
        for (Map.Entry<String, java.util.Set<String>> entry : expected.entrySet()) {
            boolean found = false;
            for (String id : entry.getValue()) {
                if (declared.contains(id)) {
                    found = true;
                    break;
                }
            }
            if (found) continue;
            overrideMisses++;
            if (overrideMissExamples.size() < MAX_EXAMPLES) {
                overrideMissExamples.add(className + ": " + entry.getKey().replace('\u0000', ' ')
                        + " -> expected " + entry.getValue());
            }
        }
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("obf->runtime map: ").append(classMap.size()).append(" classes, ")
                .append(fieldMap.size()).append(" fields, ").append(methodMap.size()).append(" methods")
                .append("; remapped ").append(remappedRefs).append(" member ref(s)");
        if (unresolvedRefs > 0) {
            sb.append(", ").append(unresolvedRefs).append(" obf-looking ref(s) not in the table");
            if (!unresolvedExamples.isEmpty()) sb.append(" e.g. ").append(unresolvedExamples);
        }
        if (overrideMisses > 0) {
            sb.append("; ").append(overrideMisses).append(" interface override(s) NOT renamed");
            if (!overrideMissExamples.isEmpty()) sb.append(" e.g. ").append(overrideMissExamples);
        }
        return sb.toString();
    }

    public int unresolvedRefs() {
        return unresolvedRefs;
    }

    /** @return how many methods should implement an interface method but do not carry its runtime id. */
    public int overrideMisses() {
        return overrideMisses;
    }

    public List<String> overrideMissExamples() {
        return overrideMissExamples;
    }
}
