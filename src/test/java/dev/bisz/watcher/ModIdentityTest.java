package dev.bisz.watcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModIdentityTest {
    @Test
    void createsNamespacedResourceLocations() {
        assertEquals("watcher:probe", ModIdentity.namespaced("probe"));
    }
}
