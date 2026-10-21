package kynarain.cn.optiforge.oldml;

import kynarain.cn.optiforge.Bootstrap;
import kynarain.cn.optiforge.OptifinePack;

import java.lang.reflect.Field;
import java.nio.file.Paths;

/**
 * Offline launch simulation for the oldml artifact (used by the Lead's bug report reproduction).
 *
 * <p>Runs on a Java 8 runtime with the artifact, the 1.14.4 ModLauncher and ASM on the classpath. It
 * sets the game directory, then runs the real {@link Bootstrap#ensurePack} path with the same system
 * properties a launch would pass ({@code -Doptiforge.mc}, {@code -Doptiforge.forge},
 * {@code -Doptiforge.vanilla}, {@code -Doptiforge.libraries}) and prints the diagnostic notes plus the
 * prepared pack summary. No game is started.
 *
 * <p>Usage: {@code java -cp "<artifact>;<ml4>;<jopt>;<asm>" -Doptiforge.mc=1.16.5
 * -Doptiforge.forge=36.2.39 [-Doptiforge.vanilla=<jar>] [-Doptiforge.libraries=<dir>]
 * kynarain.cn.optiforge.oldml.LaunchProbe <gameDir>}
 */
public final class LaunchProbe {

    private LaunchProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("usage: LaunchProbe <gameDir>");
            System.exit(2);
        }
        System.out.println("java        : " + System.getProperty("java.version"));
        System.out.println("gameDir     : " + args[0]);
        System.out.println("optiforge.mc        : " + System.getProperty("optiforge.mc"));
        System.out.println("optiforge.forge     : " + System.getProperty("optiforge.forge"));
        System.out.println("optiforge.vanilla   : " + System.getProperty("optiforge.vanilla"));
        System.out.println("optiforge.libraries : " + System.getProperty("optiforge.libraries"));
        System.out.println();

        Bootstrap.setLegacy(true);
        Bootstrap.setTargetFactory(new OldMlTargets());
        Field gameDir = Bootstrap.class.getDeclaredField("gameDir");
        gameDir.setAccessible(true);
        gameDir.set(null, Paths.get(args[0]).toAbsolutePath());

        OptifinePack pack = Bootstrap.ensurePack(null);
        System.out.println();
        if (pack == null) {
            System.out.println("RESULT   : FAIL - no pack prepared (see the notes above)");
            System.exit(1);
        }
        System.out.println("pack        : " + pack.summary());
        // Instantiates the shared transformer with this ModLauncher's Target factory; under ML 2.1.1 this
        // also proves the class links without ITransformerVotingContext.getClassName.
        Object transformer = kynarain.cn.optiforge.Bootstrap.transformer();
        System.out.println("transformer : " + (transformer == null ? "null" : transformer.getClass().getName()));
        System.out.println("RESULT   : OK - pack prepared, " + pack.patchedCount + " patched classes, "
                + pack.ownClasses.size() + " own classes");
    }
}
