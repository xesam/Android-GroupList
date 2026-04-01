package io.github.xesam.android.grouplist.vnext.engine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.Map;

/**
 * Resolves viewTypes for the Engine's row hierarchy.
 *
 * Resolution priority chain for a given (role, model):
 * <ol>
 *   <li>Dynamic {@link ViewTypeResolver}s, in registration order; the first resolver that returns
 *       a value other than {@link ViewTypeResolver#UNHANDLED} wins. Use these when the viewType
 *       depends on runtime state (e.g. loading placeholders), not only on the model class.</li>
 *   <li>Exact class mapping registered for the role.</li>
 *   <li>Assignable (superclass/interface) class mapping for the role, in registration order.</li>
 *   <li>Otherwise: IllegalStateException (unmapped model).</li>
 * </ol>
 */
public final class BinderRegistry {

    public enum ViewRole {
        STANDALONE,
        GROUP_HEADER,
        GROUP_ITEM,
        GROUP_FOOTER
    }

    public interface ViewTypeResolver {
        int UNHANDLED = Integer.MIN_VALUE;

        int resolve(ViewRole role, Object model);
    }

    private final Map<ViewRole, Map<Class<?>, Integer>> viewTypeByRole = new HashMap<>();
    private final Map<String, ViewTypeResolver> dynamicResolvers = new LinkedHashMap<>();

    public BinderRegistry() {
        for (ViewRole role : ViewRole.values()) {
            viewTypeByRole.put(role, new LinkedHashMap<Class<?>, Integer>());
        }
    }

    public BinderRegistry registerForAllRoles(Class<?> modelClass, int viewType) {
        for (ViewRole role : ViewRole.values()) {
            register(role, modelClass, viewType);
        }
        return this;
    }

    public BinderRegistry register(ViewRole role, Class<?> modelClass, int viewType) {
        if (role == null) {
            throw new IllegalArgumentException("Role must not be null");
        }
        if (modelClass == null) {
            throw new IllegalArgumentException("Model class must not be null");
        }
        viewTypeByRole.get(role).put(modelClass, viewType);
        return this;
    }

    public BinderRegistry registerResolver(String key, ViewTypeResolver resolver) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Resolver key must not be empty");
        }
        if (resolver == null) {
            throw new IllegalArgumentException("Resolver must not be null");
        }
        dynamicResolvers.put(key, resolver);
        return this;
    }

    public int resolveViewType(ViewRole role, Object model) {
        if (role == null) {
            throw new IllegalArgumentException("Role must not be null");
        }
        if (model == null) {
            throw new IllegalStateException("No viewType mapping for null model under role " + role);
        }

        for (Entry<String, ViewTypeResolver> entry : dynamicResolvers.entrySet()) {
            int resolved = entry.getValue().resolve(role, model);
            if (resolved != ViewTypeResolver.UNHANDLED) {
                return resolved;
            }
        }

        Map<Class<?>, Integer> mappings = viewTypeByRole.get(role);
        Class<?> modelClass = model.getClass();
        Integer resolved = mappings.get(modelClass);
        if (resolved != null) {
            return resolved;
        }

        for (Map.Entry<Class<?>, Integer> entry : mappings.entrySet()) {
            if (entry.getKey().isAssignableFrom(modelClass)) {
                return entry.getValue();
            }
        }

        throw new IllegalStateException(
                "No viewType mapping for role=" + role + ", modelClass=" + modelClass.getName()
        );
    }
}
