package kynarain.cn.optiforge.oldml;

import cpw.mods.modlauncher.api.ITransformationService;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Offline loadability probe for the Java-8 artifact (used by {@code tools/check-oldml.ps1}).
 *
 * <p>Runs on a Java 8 runtime with the artifact plus the target ModLauncher jar on the classpath. It
 * does <b>not</b> launch a game: {@link ServiceLoader} finds the service the same way ModLauncher's
 * {@code ModDirTransformerDiscoverer} does, then the ModLauncher-2-8 abstract methods are invoked with
 * no environment, which is a pure "does this class file link against this ModLauncher" check.
 */
public final class OldMlProbe {

    private OldMlProbe() {
    }

    public static void main(String[] args) {
        System.out.println("java       : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");

        Iterator<ITransformationService> services = ServiceLoader.load(ITransformationService.class).iterator();
        int count = 0;
        String name = null;
        String className = null;
        while (services.hasNext()) {
            ITransformationService service = services.next();
            count++;
            if (count == 1) {
                name = service.name();
                className = service.getClass().getName();
                // Old-ModLauncher contract: these are abstract, so a mismatch is an AbstractMethodError
                // right here instead of at launch. No environment is available offline, and both calls
                // degrade to "no pack" rather than throwing.
                service.beginScanning(null);
                int transformers = service.transformers() == null ? -1 : service.transformers().size();
                System.out.println("service    : " + name + " class=" + className
                        + " transformers=" + transformers);
            }
        }

        if (count == 0) {
            System.out.println("RESULT   : FAIL - no ITransformationService provider found in the jar");
            System.exit(1);
        }
        if (!"optiforge".equals(name)) {
            System.out.println("RESULT   : FAIL - service name is \"" + name + "\", expected \"optiforge\"");
            System.exit(1);
        }
        if (!OldModLauncherService.class.getName().equals(className)) {
            System.out.println("RESULT   : FAIL - provider is " + className + ", expected "
                    + OldModLauncherService.class.getName());
            System.exit(1);
        }
        System.out.println("RESULT   : OK - " + count + " service, " + className
                + " links against this ModLauncher on Java " + System.getProperty("java.version"));
    }
}
