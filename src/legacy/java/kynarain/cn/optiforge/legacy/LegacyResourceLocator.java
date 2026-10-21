package kynarain.cn.optiforge.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Serves OptiFine's own assets without loading OptiFine's launcher classes.
 *
 * <p>Measured from the 1.12.2 jar: {@code optifine.OptiFineResourceLocator} keeps one static
 * {@code IOptiFineResourceLocator} and {@code net/optifine/**} asks it for every resource
 * ({@code getOptiFineResourceStream(String)}, one method). OptiFine normally installs itself there from
 * its class transformer; because this artifact does not register that transformer, the locator is
 * installed here with a proxy over the staged jar, so the contract is satisfied without linking against
 * a user-supplied jar at build time.
 */
public final class LegacyResourceLocator {

    private static final String TAG = "[OptiForge/legacy]";

    private static ZipFile zip;
    private static Path jar;

    private LegacyResourceLocator() {
    }

    /** Called after the staged jar has been added to the launch class loader. */
    public static void install(Path stagedJar) {
        try {
            ClassLoader loader = LegacyResourceLocator.class.getClassLoader();
            Class<?> locatorClass;
            try {
                locatorClass = Class.forName("optifine.OptiFineResourceLocator", true, loader);
            } catch (Throwable absent) {
                // Measured: the 1.7.10 build (HD_U_E7) has no resource-locator contract at all - its own
                // classes read assets with Class.getResourceAsStream, so the staged jar is enough. The
                // 1.8+ builds have the contract and need it installed.
                System.out.println(TAG + " this OptiFine build has no resource locator;"
                        + " own classes read assets from the staged jar through the class loader");
                return;
            }
            Class<?> contract = Class.forName("optifine.IOptiFineResourceLocator", true, loader);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{contract}, new Handler(stagedJar));
            locatorClass.getMethod("setResourceLocator", contract).invoke(null, proxy);
            System.out.println(TAG + " resource locator installed over " + stagedJar.getFileName());
        } catch (Throwable t) {
            // Resources that cannot be located fall back to whatever OptiFine can find by itself; this
            // must never abort the launch.
            System.out.println(TAG + " resource locator not installed: " + t);
        }
    }

    private static final class Handler implements InvocationHandler {
        private final Path stagedJar;

        Handler(Path stagedJar) {
            this.stagedJar = stagedJar;
        }

        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getOptiFineResourceStream".equals(name)) {
                String resource = args == null || args.length == 0 ? null : String.valueOf(args[0]);
                return open(resource);
            }
            if ("toString".equals(name)) {
                return "OptiForge legacy resource locator";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == (args == null || args.length == 0 ? null : args[0]);
            }
            return null;
        }

        private InputStream open(String resource) {
            if (resource == null) {
                return null;
            }
            String path = resource;
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            try {
                ZipFile file = zip(stagedJar);
                ZipEntry entry = file.getEntry(path);
                if (entry == null) {
                    return null;
                }
                return file.getInputStream(entry);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static synchronized ZipFile zip(Path path) throws IOException {
        if (zip == null || !path.equals(jar)) {
            if (zip != null) {
                LegacyPaths.closeQuietly(zip);
            }
            zip = new ZipFile(path.toFile());
            jar = path;
        }
        return zip;
    }
}
