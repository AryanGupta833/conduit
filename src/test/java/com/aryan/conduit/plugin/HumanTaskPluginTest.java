package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HumanTaskPluginTest {
    private final HumanTaskPlugin plugin = new HumanTaskPlugin();

    @Test void acceptsApprovalConfiguration() {
        assertDoesNotThrow(() -> plugin.validateConfiguration(Map.of("title", "Deploy?", "type", "APPROVAL", "timeoutSeconds", 3600)));
    }

    @Test void rejectsUnknownTypeAndInvalidTimeoutPolicy() {
        assertThrows(PluginConfigurationException.class, () -> plugin.validateConfiguration(Map.of("type", "MAGIC")));
        assertThrows(PluginConfigurationException.class, () -> plugin.validateConfiguration(Map.of("timeoutPolicy", "WAIT")));
    }

    @Test void exposesAsSuspendingBuiltInPlugin() {
        assertEquals("HUMAN_TASK", plugin.metadata().type());
        assertTrue(plugin.suspendsExecution());
    }

    @Test void rejectsInvalidDescriptionAssigneeAndMetadata() {
        assertThrows(PluginConfigurationException.class, () -> plugin.validateConfiguration(Map.of("description", 42)));
        assertThrows(PluginConfigurationException.class, () -> plugin.validateConfiguration(Map.of("assignee", "  ")));
        assertThrows(PluginConfigurationException.class, () -> plugin.validateConfiguration(Map.of("metadata", "not-an-object")));
    }

    @Test void advertisesConfigurationFieldsForTheDesigner() {
        Map<?, ?> properties = (Map<?, ?>) plugin.metadata().configurationSchema().get("properties");
        assertTrue(properties.containsKey("timeoutPolicy"));
        assertTrue(properties.containsKey("assignee"));
        assertTrue(properties.containsKey("metadata"));
    }
}
