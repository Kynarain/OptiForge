import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.ITransformationService;

import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * Checks the one thing Forge requires of this jar before any of its code runs.
 *
 * <p>Forge's {@code ModDirTransformerDiscoverer.visitFile} decides "is this a transformation
 * service?" by reading the jar's module descriptor - {@code SecureJar.from(path).moduleDataProvider()
 * .descriptor().provides()} - and matching the service names it knows. A jar that passes that test is
 * excluded from ordinary mod discovery. So the check below reproduces Forge's own test, then loads
 * the class through {@link ServiceLoader} to prove the declaration and the class agree.
 *
 * <p>Run through {@code tools/check.ps1}; it needs {@code --add-opens java.base/java.lang.invoke=ALL-UNNAMED}
 * because securejarhandler reflects into {@code MethodHandles.Lookup} (ModLauncher sets the same flag).
 */
public final class ServiceProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ServiceProbe <jar>");
            System.exit(2);
        }
        Path jar = Path.of(args[0]);

        boolean declared = false;
        SecureJar secureJar = SecureJar.from(jar);
        for (var provides : secureJar.moduleDataProvider().descriptor().provides()) {
            System.out.println("provides : " + provides.service() + " -> " + provides.providers());
            if (provides.service().equals(ITransformationService.class.getName())) {
                declared = true;
            }
        }
        if (!declared) {
            System.out.println("RESULT   : FAIL - no ITransformationService declaration, Forge will not see this jar");
            System.exit(1);
        }

        int loaded = 0;
        for (ITransformationService service : ServiceLoader.load(ITransformationService.class,
                ServiceProbe.class.getClassLoader())) {
            System.out.println("loaded   : " + service.name() + " (" + service.getClass().getName() + ")");
            loaded++;
        }
        if (loaded == 0) {
            System.out.println("RESULT   : FAIL - declared but not loadable");
            System.exit(1);
        }
        System.out.println("RESULT   : OK - " + loaded + " service(s) declared and loadable");
    }
}
