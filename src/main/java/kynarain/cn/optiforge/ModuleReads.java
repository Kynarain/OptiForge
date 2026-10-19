package kynarain.cn.optiforge;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Gives the game module the module reads OptiFine's patches need.
 *
 * <p>Measured on Forge 56.0.9 + OptiFine 1.21.6: the launch dies with
 * {@code NoClassDefFoundError: net.minecraftforge.eventbus.api.Event} from a patched game class. In
 * vanilla Forge the same game classes reference Forge types ({@code DefaultVertexFormat implements
 * IForgeVertexFormat}) and work, so {@code minecraft} does read the {@code forge} module - but
 * {@code net.minecraftforge.eventbus} is a <b>separate</b> module in Forge 56, and {@code minecraft}
 * does not read it. OptiFine 1.21.6's patched classes reference {@code Event} directly. (Forge 51 kept
 * eventbus inside the forge module, which is why the 1.21 line worked.)
 *
 * <p>{@link Module#addReads(Module)} refuses callers outside the module, so the internal
 * {@code implAddReads} is used; that needs {@code --add-opens java.base/java.lang}. Whether the launch
 * grants that to the unnamed module is measured at runtime and reported, never assumed.
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

        Module minecraft = null;
        List<Module> forgeModules = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ModuleLayer layer : layers) {
            for (Module module : layer.modules()) {
                String name = module.getName();
                if (names.size() < 24) names.add(name);
                // Forge renames the game module; identify it by the packages it owns rather than by name.
                if (module.getDescriptor() != null && module.getDescriptor().packages().contains("net.minecraft")) minecraft = module;
                else if (name.equals("minecraft")) minecraft = module;
                else if (name.startsWith("net.minecraftforge") || name.startsWith("net.neoforged")) {
                    forgeModules.add(module);
                }
            }
        }
        if (minecraft == null) return "module reads: could not identify the game module among " + names;
        if (forgeModules.isEmpty()) return "module reads: no Forge modules found";

        Method implAddReads;
        try {
            implAddReads = Module.class.getDeclaredMethod("implAddReads", Module.class);
            implAddReads.setAccessible(true);
        } catch (Throwable t) {
            return "module reads: cannot reach Module.implAddReads (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + "); the launch does not open java.base/java.lang";
        }

        List<String> added = new ArrayList<>();
        for (Module candidate : forgeModules) {
            if (minecraft.canRead(candidate)) continue;
            try {
                implAddReads.invoke(minecraft, candidate);
                added.add(candidate.getName());
            } catch (Throwable t) {
                return "module reads: failed to add " + candidate.getName() + " (" + t.getCause() + ")";
            }
        }
        return "module reads: game module now reads " + added + " (already read "
                + (forgeModules.size() - added.size()) + ")";
    }

}
