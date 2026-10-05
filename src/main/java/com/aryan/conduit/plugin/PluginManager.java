package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/** Registry for built-in Spring plugins and trusted ServiceLoader plugin JARs. */
@Component
public class PluginManager {
    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    private final List<WorkflowPlugin> builtInPlugins;
    private final Path pluginDirectory;
    private volatile Map<String, WorkflowPlugin> registry = Map.of();
    private final List<URLClassLoader> pluginClassLoaders = new ArrayList<>();

    public PluginManager(List<WorkflowPlugin> builtInPlugins,
                         @Value("${conduit.plugins.directory:plugins}") String pluginDirectory) {
        this.builtInPlugins = List.copyOf(builtInPlugins);
        this.pluginDirectory = Path.of(pluginDirectory).toAbsolutePath().normalize();
    }

    @PostConstruct
    public synchronized void initialize() {
        if (!registry.isEmpty()) return;
        Map<String, WorkflowPlugin> discovered = new HashMap<>();
        builtInPlugins.forEach(plugin -> register(discovered, plugin));

        for (URL jarUrl : externalPluginJars()) loadExternalJar(jarUrl, discovered);
        registry = Map.copyOf(discovered);
    }

    private void loadExternalJar(URL jarUrl, Map<String, WorkflowPlugin> discovered) {
        URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, WorkflowPlugin.class.getClassLoader());
        boolean retained = false;
        try {
            var providers = ServiceLoader.load(WorkflowPlugin.class, loader).iterator();
            int failures = 0;
            while (true) {
                WorkflowPlugin plugin;
                try {
                    if (!providers.hasNext()) break;
                    plugin = providers.next();
                    failures = 0;
                } catch (java.util.ServiceConfigurationError | LinkageError | RuntimeException error) {
                    log.error("Skipping invalid plugin provider in {}: {}", jarUrl, safeMessage(error));
                    if (++failures >= 2) break;
                    continue;
                }
                try {
                    String key = validatedType(plugin);
                    if (discovered.containsKey(key)) {
                        log.error("Skipping duplicate external plugin type '{}' from {}", key, jarUrl);
                        continue;
                    }
                    register(discovered, plugin);
                    retained = true;
                } catch (RuntimeException | LinkageError error) {
                    log.error("Skipping invalid plugin from {}: {}", jarUrl, safeMessage(error));
                }
            }
            if (retained) pluginClassLoaders.add(loader);
        } finally {
            if (!retained) close(loader);
        }
    }

    private String validatedType(WorkflowPlugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("Plugin provider returned null");
        PluginMetadata metadata = plugin.metadata();
        if (metadata == null || metadata.type() == null || !metadata.type().trim().matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Plugin metadata contains an invalid type");
        if (metadata.displayName().length() > 200 || metadata.description().length() > 2000 || metadata.version().length() > 100)
            throw new IllegalArgumentException("Plugin metadata exceeds allowed length");
        return metadata.type().trim().toUpperCase(Locale.ROOT);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.replaceAll("[\\r\\n]", " ");
    }

    private void close(URLClassLoader loader) {
        try { loader.close(); } catch (IOException e) { log.warn("Unable to close plugin class loader", e); }
    }

    private List<URL> externalPluginJars() {
        if (!Files.exists(pluginDirectory)) return List.of();
        if (!Files.isDirectory(pluginDirectory)) {
            throw new IllegalStateException("Plugin location is not a directory: " + pluginDirectory);
        }
        try (var entries = Files.list(pluginDirectory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(this::toUrl)
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read plugin directory " + pluginDirectory, error);
        }
    }

    private URL toUrl(Path jar) {
        try { return jar.toUri().toURL(); }
        catch (IOException error) { throw new IllegalStateException("Invalid plugin JAR path: " + jar, error); }
    }

    private void register(Map<String, WorkflowPlugin> target, WorkflowPlugin plugin) {
        if (plugin == null || plugin.metadata() == null) {
            throw new IllegalStateException("Plugin and plugin metadata must not be null");
        }
        PluginMetadata metadata = plugin.metadata();
        String key = metadata.type().trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) throw new IllegalStateException("Plugin type must not be blank");
        WorkflowPlugin previous = target.putIfAbsent(key, plugin);
        if (previous != null) {
            throw new IllegalStateException("Duplicate plugin type '" + key + "' from "
                    + previous.getClass().getName() + " and " + plugin.getClass().getName());
        }
    }

    public WorkflowPlugin getPlugin(String type) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Plugin type is required");
        WorkflowPlugin plugin = registry.get(type.trim().toUpperCase(Locale.ROOT));
        if (plugin == null) throw new IllegalArgumentException("Unknown plugin type '" + type + "'. Registered plugins: " + getRegisteredPlugins());
        return plugin;
    }

    public List<String> getRegisteredPlugins() {
        return registry.keySet().stream().sorted().toList();
    }

    public List<PluginMetadata> getPluginMetadata() {
        return registry.values().stream().map(WorkflowPlugin::metadata)
                .sorted(Comparator.comparing(PluginMetadata::type, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @PreDestroy
    public synchronized void closePluginLoader() {
        pluginClassLoaders.forEach(this::close);
        pluginClassLoaders.clear();
    }
}
