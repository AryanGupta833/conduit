package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.external.TestCustomPlugin;
import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {
    @TempDir
    Path pluginDirectory;

    @Test
    void discoversExternalServiceJarAndExposesMetadataAndPluginContract() throws Exception {
        writePluginJar(pluginDirectory.resolve("example.jar"));
        PluginManager manager = new PluginManager(List.of(), pluginDirectory.toString());
        try {
            manager.initialize();
            assertEquals(List.of("CUSTOM_EXAMPLE"), manager.getRegisteredPlugins());
            var metadata = manager.getPluginMetadata().getFirst();
            assertEquals("Test Custom Plugin", metadata.displayName());
            assertEquals("test-1.0", metadata.version());
            assertTrue(metadata.configurationSchema().containsKey("required"));

            WorkflowPlugin plugin = manager.getPlugin("custom_example");
            plugin.validateConfiguration(Map.of("message", "Custom plugin executed successfully"));
            var result = plugin.execute(new PluginContext("demo", 20,
                    Map.of("message", "Custom plugin executed successfully"), Map.of("username", "Aryan")));
            assertTrue(result.isSuccess());
            assertEquals("Custom plugin executed successfully for Aryan", result.getOutput());
            assertEquals("Custom plugin executed successfully for Aryan", result.getVariables().get("customMessage"));
            assertEquals("CUSTOM_EXAMPLE", result.getMetadata().get("plugin"));
            assertThrows(PluginConfigurationException.class,
                    () -> plugin.validateConfiguration(Map.of("message", " ")));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> manager.getPlugin("MISSING"))
                    .getMessage().contains("Registered plugins"));
        } finally {
            manager.closePluginLoader();
        }
    }

    @Test
    void rejectsDuplicatePluginTypes() {
        PluginManager manager = new PluginManager(List.of(new TestCustomPlugin(), new TestCustomPlugin()),
                pluginDirectory.toString());
        IllegalStateException error = assertThrows(IllegalStateException.class, manager::initialize);
        assertTrue(error.getMessage().contains("Duplicate plugin type 'CUSTOM_EXAMPLE'"));
    }

    private void writePluginJar(Path jarPath) throws Exception {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            addEntry(jar, "META-INF/services/com.aryan.conduit.plugin.sdk.WorkflowPlugin",
                    (TestCustomPlugin.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            String classPath = TestCustomPlugin.class.getName().replace('.', '/') + ".class";
            try (InputStream bytecode = TestCustomPlugin.class.getClassLoader().getResourceAsStream(classPath)) {
                assertNotNull(bytecode);
                addEntry(jar, classPath, bytecode.readAllBytes());
            }
        }
    }

    private void addEntry(JarOutputStream jar, String name, byte[] content) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }
}
