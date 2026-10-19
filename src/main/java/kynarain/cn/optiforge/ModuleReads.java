package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives the game module the module reads OptiFine's patches need.
 *
 * <p>Measured on Forge 56.0.9 + OptiFine 1.21.6: the launch needed
 * {@code net.minecraftforge.eventbus.api.Event} from OptiFine's own {@code Reflector}, and Forge 56 keeps
 * eventbus in a module of its own. Forge 51 bundled eventbus into the forge module, which is why the 1.21
 * line never hit this.
 *
 * <p><b>This class can only report.</b> {@code Module.addReads} refuses callers outside the module being
 * changed and the launch does not open {@code java.base/java.lang}, so the read has to be added from
 * inside the game module - see {@link ModuleReadsInjector}. What runs here is the diagnosis: which layers
 * exist, which module is the game, and what the reflective attempt says (it is expected to fail with
 * {@code InaccessibleObjectException}, and recording that is the point).
 */
public final class ModuleReads {

    private ModuleReads() {
    }

    /** @return human-readable outcome for the diagnostic report */
    public static String install(IEnvironment environment) {
        List<ModuleLayer> layers = new ArrayList<>();
        environment.findModuleLayerManager().ifPresent(manager -> {
            for (IModuleLayerManager.Layer layer : IModuleLayerManager.Layer.values()) {
                manager.getLayer(layer).ifPresent(layers::add);
            }
        });
        if (layers.isEmpty()) return "module reads: no module layers available";

        if (Boolean.getBoolean("optiforge.layerDump")) {
            for (ModuleLayer layer : layers) {
                StringBuilder line = new StringBuilder();
                for (Module module : layer.modules()) {
                    String name = module.getName();
                    if (name.startsWith("net.minecraft") || name.startsWith("net.neoforged")
                            || name.startsWith("org.apache.logging") || name.equals("minecraft")) {
                        line.append(name).append("  ");
                    }
                }
                System.out.println("[OptiForge] layer modules=" + layer.modules().size()
                        + " parents=" + layer.parents().size() + " :: " + line);
            }
        }

        Module game = null;
        List<Module> forgeModules = new ArrayList<>();
        for (ModuleLayer layer : layers) {
            for (Module module : layer.modules()) {
                String name = module.getName();
                if (module.getDescriptor() != null
                        && module.getDescriptor().packages().contains("net.minecraft")) {
                    game = module;
                } else if (name.equals("minecraft")) {
                    game = module;
                } else if (name.startsWith("net.minecraftforge") || name.startsWith("net.neoforged")) {
                    forgeModules.add(module);
                }
            }
        }
        if (game == null) {
            return "module reads: could not identify the game module across " + layers.size() + " layers";
        }

        Method implAddReads;
        try {
            implAddReads = Module.class.getDeclaredMethod("implAddReads", Module.class);
            implAddReads.setAccessible(true);
        } catch (Throwable t) {
            return "module reads: cannot reach Module.implAddReads (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "); the launch does not open java.base/java.lang, so the"
                    + " read has to come from injected game-module code";
        }

        List<String> added = new ArrayList<>();
        for (Module candidate : forgeModules) {
            if (game.canRead(candidate)) continue;
            try {
                implAddReads.invoke(game, candidate);
                added.add(candidate.getName());
            } catch (Throwable t) {
                return "module reads: failed to add " + candidate.getName() + " (" + t.getCause() + ")";
            }
        }
        return "module reads: game module now reads " + added + " (already read "
                + (forgeModules.size() - added.size()) + ")";
    }
}
