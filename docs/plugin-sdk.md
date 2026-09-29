# Conduit Plugin SDK

Conduit plugins are Java implementations of the small `WorkflowPlugin` interface in the `com.aryan.conduit.plugin.sdk` package. The SDK exposes only plugin metadata, task configuration, the existing workflow context, and a `PluginResult`; it does not expose database entities, repositories, workers, Redis, Kafka, or execution backends.

## Build a plugin

Install the Conduit build and its SDK classifier to the local Maven repository, then package the example extension:

```powershell
scripts\mvn.cmd install -DskipTests
cd examples\conduit-example-plugin
mvn package
```

Use the `conduit` dependency with classifier `plugin-sdk` and `provided` scope, as shown in [the example POM](../examples/conduit-example-plugin/pom.xml). The classifier contains only the SDK API classes. Keep the SDK dependency provided so those classes are supplied by Conduit at runtime. Bundle any other plugin dependencies into the plugin JAR.

Implement `WorkflowPlugin` with:

- `metadata()` returning a stable type, display name, description, version, and optional JSON-schema-shaped configuration description.
- `validateConfiguration(Map<String, Object>)` checking plugin-owned configuration and throwing `PluginConfigurationException` with an actionable message.
- `execute(PluginContext)` returning `PluginResult` with success, output, optional variables, and metadata.

`PluginContext` contains the task name, task timeout, parsed configuration, and the current expression context. The context includes workflow variables and prior task outputs through Conduit's existing variable mechanism. Its maps are read-only snapshots.

## Discovery and installation

Conduit reads direct `.jar` files from `conduit.plugins.directory`, which defaults to `plugins/` relative to the Conduit working directory. Each extension JAR declares providers with Java's `ServiceLoader` file:

```text
META-INF/services/com.aryan.conduit.plugin.sdk.WorkflowPlugin
```

The file contains the fully qualified plugin implementation class name, one per line. Build the JAR, copy it into the configured directory, and restart Conduit. The registry rejects duplicate plugin types and reports unknown types with the registered list. `GET /api/plugins` lists metadata for built-in and discovered plugins.

The independent example implementation and service declaration are in [examples/conduit-example-plugin](../examples/conduit-example-plugin). Its workflow task can use:

```json
{
  "pluginType": "CUSTOM_EXAMPLE",
  "configurationJson": "{\"message\":\"Hello\",\"recipientVariable\":\"username\"}"
}
```

If the existing workflow context has `username: "Aryan"`, the example returns `Hello for Aryan`, along with a `customMessage` result variable and simulated delivery metadata. Its `failuresBeforeSuccess` and `scenario` settings demonstrate the standard Conduit retry policy; they are not a separate retry system.

## Execution, errors, and metrics

The normal task execution flow discovers the plugin from the registry, resolves configuration placeholders with the existing expression context, parses a JSON object, validates it through the plugin, and passes it a `PluginContext`. The returned `PluginResult` follows the existing task output, idempotency, retry, worker, and workflow completion handling. A false result or thrown exception uses the configured task retries and then reaches the existing FAILED/TIMEOUT outcome. No custom worker or persistence path is added. Task counts and durations use the existing metrics, with `plugin_type` set to the uppercase plugin type.

Plugins run in the Conduit JVM. Task timeout uses Conduit's existing thread interruption mechanism; interruption is cooperative and is not a hard execution boundary for plugin code that ignores interruption. Plugins should keep work bounded and respond to interruption where appropriate.

## Trust and security

**Custom plugins are trusted code.** A plugin JAR loaded from the configured directory runs with the privileges of the Conduit process and can access that process's files, credentials, network, and other resources. The plugin loader is not a sandbox and does not provide isolation. Only install reviewed, trusted JARs. Docker/Kubernetes isolation remains available through the existing shell backend for workloads that should run out of process; it is not automatically applied to SDK plugins.
