package kynarain.cn.optiforge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 8 shims for APIs that only exist on Java 9+, plus compact replacements for the
 * {@code List.of}/{@code Set.of}/{@code Map.entry}/{@code Path.of} family.
 *
 * <p>The project ships one artifact for Java 17+ (compiled with {@code --release 17}) and one for the
 * 1.14.4-1.16.5 line (compiled with {@code --release 8}), from the same sources. Every helper here must
 * therefore stay within the Java 8 API. Behaviour is the same as the calls it replaces: the collection
 * factories return unmodifiable views, the IO helpers read the whole stream, and
 * {@link #platformOrBootstrap()} returns the Java 9+ platform loader when it exists and the bootstrap
 * (Java 8: {@code null} parent, which still sees the full {@code rt.jar}) when it does not.
 *
 * <p>Package-private on purpose: this is an implementation detail of the pipeline, not an API.
 */
final class J8 {

    private J8() {
    }

    // ---- collections -------------------------------------------------------------------------

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    @SafeVarargs
    static <T> List<T> listOf(T... items) {
        return Collections.unmodifiableList(new ArrayList<T>(Arrays.asList(items)));
    }

    @SafeVarargs
    static <T> Set<T> setOf(T... items) {
        return Collections.unmodifiableSet(new LinkedHashSet<T>(Arrays.asList(items)));
    }

    static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return new AbstractMap.SimpleImmutableEntry<K, V>(key, value);
    }

    static <T> List<T> copyOf(Iterable<? extends T> items) {
        List<T> out = new ArrayList<T>();
        for (T item : items) out.add(item);
        return Collections.unmodifiableList(out);
    }

    static <T> Set<T> setCopyOf(Iterable<? extends T> items) {
        Set<T> out = new LinkedHashSet<T>();
        for (T item : items) out.add(item);
        return Collections.unmodifiableSet(out);
    }

    static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    // ---- IO ----------------------------------------------------------------------------------

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    static byte[] readN(InputStream in, int count) throws IOException {
        byte[] out = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = in.read(out, offset, count - offset);
            if (read < 0) {
                byte[] shorter = new byte[offset];
                System.arraycopy(out, 0, shorter, 0, offset);
                return shorter;
            }
            offset += read;
        }
        return out;
    }

    static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    static void writeString(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    static void appendString(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ---- platform ----------------------------------------------------------------------------

    /**
     * The parent for OptiFine's own class loader. Java 9+ has {@code getPlatformClassLoader}
     * (through reflection, so the same class file compiles for 8); Java 8 has no platform loader and
     * {@code null} (bootstrap) sees the whole JDK, which is what OptiFine's patcher needs there.
     */
    static ClassLoader platformOrBootstrap() {
        try {
            Object loader = ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
            return (ClassLoader) loader;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
