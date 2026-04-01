package io.github.xesam.android.grouplist.vnext.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class BinderRegistryTest {

    @Test
    public void resolveViewType_shouldUseExactRoleMapping() {
        BinderRegistry registry = new BinderRegistry()
                .register(BinderRegistry.ViewRole.GROUP_ITEM, String.class, 100);

        assertEquals(100, registry.resolveViewType(BinderRegistry.ViewRole.GROUP_ITEM, "child"));
    }

    @Test
    public void resolveViewType_shouldFallbackToAssignableClassMapping() {
        BinderRegistry registry = new BinderRegistry()
                .register(BinderRegistry.ViewRole.GROUP_ITEM, ParentModel.class, 200);

        assertEquals(200, registry.resolveViewType(BinderRegistry.ViewRole.GROUP_ITEM, new ChildModel()));
    }

    @Test
    public void resolveViewType_shouldPreferDynamicResolverOverStaticMapping() {
        BinderRegistry registry = new BinderRegistry()
                .register(BinderRegistry.ViewRole.GROUP_ITEM, String.class, 100)
                .registerResolver("dynamic", (role, model) -> {
                    if (role == BinderRegistry.ViewRole.GROUP_ITEM && "child".equals(model)) {
                        return 999;
                    }
                    return BinderRegistry.ViewTypeResolver.UNHANDLED;
                });

        assertEquals(999, registry.resolveViewType(BinderRegistry.ViewRole.GROUP_ITEM, "child"));
    }

    @Test
    public void resolveViewType_shouldUseFirstMatchingResolverInRegistrationOrder() {
        BinderRegistry registry = new BinderRegistry()
                .registerResolver("first", (role, model) -> role == BinderRegistry.ViewRole.GROUP_ITEM
                        ? 101
                        : BinderRegistry.ViewTypeResolver.UNHANDLED)
                .registerResolver("second", (role, model) -> role == BinderRegistry.ViewRole.GROUP_ITEM
                        ? 202
                        : BinderRegistry.ViewTypeResolver.UNHANDLED);

        assertEquals(101, registry.resolveViewType(BinderRegistry.ViewRole.GROUP_ITEM, "child"));
    }

    @Test
    public void registerResolver_withSameKey_shouldOverridePreviousResolver() {
        BinderRegistry registry = new BinderRegistry()
                .registerResolver("dynamic", (role, model) -> role == BinderRegistry.ViewRole.GROUP_ITEM
                        ? 101
                        : BinderRegistry.ViewTypeResolver.UNHANDLED)
                .registerResolver("dynamic", (role, model) -> role == BinderRegistry.ViewRole.GROUP_ITEM
                        ? 202
                        : BinderRegistry.ViewTypeResolver.UNHANDLED);

        assertEquals(202, registry.resolveViewType(BinderRegistry.ViewRole.GROUP_ITEM, "child"));
    }

    @Test
    public void resolveViewType_withNullModel_shouldThrow() {
        BinderRegistry registry = new BinderRegistry();

        try {
            registry.resolveViewType(BinderRegistry.ViewRole.GROUP_ITEM, null);
            fail("Expected null model to throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("null model"));
        }
    }

    @Test
    public void resolveViewType_withMissingMapping_shouldThrow() {
        BinderRegistry registry = new BinderRegistry();

        try {
            registry.resolveViewType(BinderRegistry.ViewRole.GROUP_ITEM, "child");
            fail("Expected missing mapping to throw");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("GROUP_ITEM"));
        }
    }

    private static class ParentModel {
    }

    private static final class ChildModel extends ParentModel {
    }
}
