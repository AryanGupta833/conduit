package com.aryan.conduit.plugin;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;
import com.aryan.conduit.plugin.sdk.PluginContext;
import com.aryan.conduit.plugin.sdk.PluginMetadata;
import com.aryan.conduit.plugin.sdk.PluginResult;
import com.aryan.conduit.plugin.sdk.WorkflowPlugin;
import com.aryan.conduit.plugin.security.SsrfAddressPolicy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Fetches a bounded public HTML page sequence and extracts data with CSS selectors. */
@Component
public class WebScrapePlugin implements WorkflowPlugin {
    private static final int MAX_ITEMS = 100;
    private static final int MAX_FIELD_CHARS = 8192;
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;
    private static final java.util.concurrent.Semaphore BROWSER_SLOTS = new java.util.concurrent.Semaphore(2);
    private final RestTemplate restTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public WebScrapePlugin(RestTemplate restTemplate, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public PluginMetadata metadata() {
        return new PluginMetadata("WEB_SCRAPE", "Public Web Scrape",
                "Fetches one or more public HTML pages and extracts text or attributes using CSS selectors. Does not bypass sign-in or bot controls.",
                "1.3.0", Map.of("type", "object", "required", List.of("selectors"),
                "properties", Map.ofEntries(
                        Map.entry("url", Map.of("type", "string", "format", "uri")),
                        Map.entry("urls", Map.of("type", "array", "description", "Explicit list of up to 10 page URLs")),
                        Map.entry("nextSelector", Map.of("type", "string", "description", "CSS selector for the next-page link; must remain on the starting host")),
                        Map.entry("maxPages", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
                        Map.entry("pageDelayMs", Map.of("type", "integer", "minimum", 250, "maximum", 10000)),
                        Map.entry("selectors", Map.of("type", "object", "description", "Field names mapped to a CSS selector or selector options; selector can be a string or ordered fallback selector list")),
                        Map.entry("itemSelector", Map.of("type", "string")),
                        Map.entry("maxItems", Map.of("type", "integer", "minimum", 1, "maximum", MAX_ITEMS)),
                        Map.entry("headers", Map.of("type", "object")),
                        Map.entry("userAgent", Map.of("type", "string")),
                        Map.entry("renderJavaScript", Map.of("type", "boolean", "description", "Render a public page in isolated headless Chromium before extraction; requires browser runtime")),
                        Map.entry("waitForSelector", Map.of("type", "string", "description", "Optional CSS selector to wait for after browser rendering")),
                        Map.entry("waitForSelectorTimeoutMs", Map.of("type", "integer", "minimum", 250, "maximum", 15000)),
                        Map.entry("waitAfterLoadMs", Map.of("type", "integer", "minimum", 0, "maximum", 10000))
                )));
    }

    @Override
    public void validateConfiguration(Map<String, Object> config) {
        if (config == null) throw new PluginConfigurationException("WEB_SCRAPE configuration is required");
        if (config.containsKey("urls") && !(config.get("urls") instanceof List<?>))
            throw new PluginConfigurationException("WEB_SCRAPE urls must be an array of URLs");
        boolean hasUrls = config.get("urls") instanceof List<?>;
        if (hasUrls == (config.get("url") != null))
            throw new PluginConfigurationException("Provide either url or urls, but not both");
        List<URI> startUris = new ArrayList<>();
        if (hasUrls) {
            List<?> urls = (List<?>) config.get("urls");
            if (urls.isEmpty() || urls.size() > 10) throw new PluginConfigurationException("WEB_SCRAPE urls must contain 1 to 10 page URLs");
            urls.forEach(value -> startUris.add(parsePublicUri(text(value, "urls entry"))));
            if (!startUris.stream().allMatch(uri -> sameOrigin(startUris.getFirst(), uri)))
                throw new PluginConfigurationException("All WEB_SCRAPE page URLs must use the same website origin");
            if (config.get("nextSelector") != null) throw new PluginConfigurationException("nextSelector cannot be combined with urls");
        } else startUris.add(parsePublicUri(text(config.get("url"), "url")));
        int maxPages = intValue(config.get("maxPages"), hasUrls ? startUris.size() : (config.get("nextSelector") == null ? 1 : 10));
        if (maxPages < 1 || maxPages > 10) throw new PluginConfigurationException("WEB_SCRAPE maxPages must be between 1 and 10");
        if (config.get("nextSelector") != null) {
            if (hasUrls) throw new PluginConfigurationException("nextSelector cannot be combined with urls");
            validateSelector(String.valueOf(config.get("nextSelector")));
        }
        if (intValue(config.get("maxPages"), 1) > 1 && config.get("nextSelector") == null && !hasUrls)
            throw new PluginConfigurationException("maxPages greater than 1 requires nextSelector or urls");
        if (!(config.get("selectors") instanceof Map<?, ?> selectors) || selectors.isEmpty() || selectors.size() > 50)
            throw new PluginConfigurationException("WEB_SCRAPE requires 1 to 50 named selectors");
        selectors.forEach((name, definition) -> {
            if (!(name instanceof String key) || !key.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
                throw new PluginConfigurationException("WEB_SCRAPE selector names must be simple identifiers");
            selectorSpec(definition);
        });
        if (config.get("itemSelector") != null) validateSelector(String.valueOf(config.get("itemSelector")));
        int maxItems = intValue(config.get("maxItems"), 25);
        if (maxItems < 1 || maxItems > MAX_ITEMS) throw new PluginConfigurationException("WEB_SCRAPE maxItems must be between 1 and " + MAX_ITEMS);
        int pageDelayMs = intValue(config.get("pageDelayMs"), 1000);
        if (pageDelayMs < 250 || pageDelayMs > 10000)
            throw new PluginConfigurationException("WEB_SCRAPE pageDelayMs must be between 250 and 10000");
        if (config.get("headers") != null && !(config.get("headers") instanceof Map<?, ?>))
            throw new PluginConfigurationException("WEB_SCRAPE headers must be an object");
        if (config.get("headers") instanceof Map<?, ?> headers) {
            if (headers.size() > 50) throw new PluginConfigurationException("WEB_SCRAPE accepts at most 50 request headers");
            headers.forEach((key, value) -> {
                if (!(key instanceof String name) || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}")
                        || List.of("host", "content-length", "transfer-encoding", "connection", "proxy-authorization").contains(name.toLowerCase(java.util.Locale.ROOT)))
                    throw new PluginConfigurationException("WEB_SCRAPE contains a restricted or invalid request header");
                if (value != null && String.valueOf(value).length() > 8192)
                    throw new PluginConfigurationException("WEB_SCRAPE request header value is too long");
            });
        }
        if (config.get("userAgent") != null && text(config.get("userAgent"), "userAgent").length() > 200)
            throw new PluginConfigurationException("WEB_SCRAPE userAgent is too long");
        if (config.get("renderJavaScript") != null && !(config.get("renderJavaScript") instanceof Boolean))
            throw new PluginConfigurationException("WEB_SCRAPE renderJavaScript must be true or false");
        if (config.get("waitForSelector") != null) validateSelector(String.valueOf(config.get("waitForSelector")));
        int selectorTimeout = intValue(config.get("waitForSelectorTimeoutMs"), 5000);
        if (selectorTimeout < 250 || selectorTimeout > 15000)
            throw new PluginConfigurationException("WEB_SCRAPE waitForSelectorTimeoutMs must be between 250 and 15000");
        int waitAfterLoadMs = intValue(config.get("waitAfterLoadMs"), 1000);
        if (waitAfterLoadMs < 0 || waitAfterLoadMs > 10000)
            throw new PluginConfigurationException("WEB_SCRAPE waitAfterLoadMs must be between 0 and 10000");
    }

    @Override
    public PluginResult execute(PluginContext context) {
        Map<String, Object> config = context.configuration();
        validateConfiguration(config);
        try {
            boolean paginated = config.get("urls") != null || config.get("nextSelector") != null || intValue(config.get("maxPages"), 1) > 1;
            int maxPages = intValue(config.get("maxPages"), config.get("urls") instanceof List<?> urls ? urls.size()
                    : (config.get("nextSelector") == null ? 1 : 10));
            List<URI> starts = config.get("urls") instanceof List<?> urls
                    ? urls.stream().map(value -> URI.create(String.valueOf(value))).toList()
                    : List.of(URI.create(String.valueOf(config.get("url"))));
            URI initial = starts.getFirst();
            List<Map<String, Object>> pages = new ArrayList<>();
            List<Map<String, Object>> scrapeDiagnostics = new ArrayList<>();
            java.util.Set<String> visited = new java.util.LinkedHashSet<>();
            outer: for (URI start : starts) {
                URI pageUri = start;
                int pageLimit = Math.min(maxPages, 10);
                for (int index = 0; index < pageLimit; index++) {
                    URI normalized = parsePublicUri(pageUri.toString());
                    if (!visited.add(normalized.normalize().toString())) break;
                    if (visited.size() > 1) Thread.sleep(intValue(config.get("pageDelayMs"), 1000));
                    ScrapeResponse response = Boolean.TRUE.equals(config.get("renderJavaScript"))
                            ? fetchRendered(config, normalized) : fetch(config, normalized);
                    if (response == null) throw new IllegalStateException("No response from target page");
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        return failed(response.statusCode(), "Public page returned HTTP " + response.statusCode());
                    if (response.contentType() == null || !(response.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("text/html")
                            || response.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/xhtml+xml")))
                        return failed(response.statusCode(), "Target response is not HTML");
                    if (response.truncated()) return failed(response.statusCode(), "HTML response exceeded the 1 MiB extraction limit");
                    Document document = Jsoup.parse(response.body(), normalized.toString());
                    Map<String, Object> pageDiagnostics = response.diagnostics() == null
                            ? pageDiagnostics(document, normalized) : new LinkedHashMap<>(response.diagnostics());
                    if (!pageDiagnostics.containsKey("selectorMatches"))
                        pageDiagnostics.put("selectorMatches", selectorMatchCounts(document, config));
                    scrapeDiagnostics.add(pageDiagnostics);
                    document.select("script,style,noscript,template").remove();
                    Object extracted = response.extractedData() == null ? extractPage(document, config) : response.extractedData();
                    if (!hasUsableData(extracted)) {
                        String diagnosticText = String.valueOf(pageDiagnostics.getOrDefault("pageTextPreview", document.text()));
                        String reason = looksLikeLoadingPage(diagnosticText)
                                ? "The page appears to still be loading and has no usable results. Use renderJavaScript with waitForSelector for a result element, or increase waitForSelectorTimeoutMs."
                                : "No configured selectors matched usable page data. Check the selectors or confirm the site exposes listings to an unauthenticated browser.";
                        return failed(response.statusCode(), reason, pageDiagnostics);
                    }
                    pages.add(new LinkedHashMap<>(Map.of("sourceHost", normalized.getHost(), "data", extracted)));
                    String tentative = objectMapper.writeValueAsString(paginated ? Map.of("pages", pages) : extracted);
                    if (tentative.length() > MAX_OUTPUT_CHARS) return failed(response.statusCode(), "Extracted result exceeded the 256 KiB output limit");
                    if (config.get("nextSelector") == null || index + 1 >= pageLimit) break;
                    Element next = document.selectFirst(String.valueOf(config.get("nextSelector")));
                    if (next == null || next.attr("href").isBlank()) break;
                    URI nextUri = normalized.resolve(next.attr("href"));
                    if (!sameOrigin(initial, nextUri)) throw new PluginConfigurationException("Pagination links must remain on the starting website host");
                    pageUri = nextUri;
                }
                if (visited.size() >= maxPages) break outer;
            }
            Object extracted = paginated ? Map.of("pages", pages) : pages.getFirst().get("data");
            String output = objectMapper.writeValueAsString(extracted);
            if (output.length() > MAX_OUTPUT_CHARS) return failed(200, "Extracted result exceeded the 256 KiB output limit");
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("firstPageUrl", initial.toString());
            diagnostics.put("pagesWithResults", pages.size());
            diagnostics.put("pageDetails", scrapeDiagnostics);
            return PluginResult.builder().success(true).output(output)
                    .metadata(Map.of("pagesFetched", visited.size(), "sourceHost", initial.getHost(), "truncated", false,
                            "javascriptRendered", Boolean.TRUE.equals(config.get("renderJavaScript")), "diagnostics", diagnostics)).build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PluginResult.builder().success(false).output("WEB_SCRAPE was interrupted").build();
        } catch (PluginConfigurationException configurationFailure) {
            return PluginResult.builder().success(false).output("WEB_SCRAPE failed: " + configurationFailure.getMessage()).build();
        } catch (Exception ex) {
            String detail;
            if (ex instanceof IllegalStateException && ex.getMessage() != null) detail = ex.getMessage();
            else if (ex instanceof IllegalArgumentException) detail = "Browser rejected a request URL or redirect; check the target site's redirects and browser request logs";
            else detail = ex.getClass().getSimpleName();
            return PluginResult.builder().success(false).output("WEB_SCRAPE failed: " + detail).build();
        }
    }

    private Object extract(Element root, Selector spec, boolean all, int limit) {
        List<Element> matches = List.of();
        for (String css : spec.css()) {
            matches = root.select(css).stream().limit(limit).toList();
            if (!matches.isEmpty()) break;
        }
        List<String> values = matches.stream().map(element -> bounded(
                spec.attribute() == null ? element.text() : element.attr(spec.attribute()))).toList();
        if (all) return values;
        return values.isEmpty() ? null : values.get(0);
    }

    private Object extractPage(Document document, Map<String, Object> config) {
        Map<String, Object> selectors = (Map<String, Object>) config.get("selectors");
        int limit = intValue(config.get("maxItems"), 25);
        Object itemSelector = config.get("itemSelector");
        if (itemSelector instanceof String itemCss && !itemCss.isBlank()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Element item : document.select(itemCss).stream().limit(limit).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                selectors.forEach((key, spec) -> row.put(key, extract(item, selectorSpec(spec), false, 1)));
                rows.add(row);
            }
            return rows;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        selectors.forEach((key, spec) -> {
            Selector selector = selectorSpec(spec);
            fields.put(key, extract(document, selector, selector.all(), limit));
        });
        return fields;
    }

    private ScrapeResponse fetch(Map<String, Object> config, URI uri) {
        SsrfAddressPolicy.validate(uri, false);
        return restTemplate.execute(uri, org.springframework.http.HttpMethod.GET, request -> {
            request.getHeaders().set(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml;q=0.9");
            request.getHeaders().set(HttpHeaders.USER_AGENT, String.valueOf(config.getOrDefault("userAgent", "ConduitPublicScraper/1.0")));
            if (config.get("headers") instanceof Map<?, ?> headers) headers.forEach((key, value) -> {
                if (key != null && value != null && !"Host".equalsIgnoreCase(key.toString()))
                    request.getHeaders().set(key.toString(), value.toString());
            });
        }, this::readResponse);
    }

    /** Renders one public page in a fresh, isolated headless Chromium context. */
    private ScrapeResponse fetchRendered(Map<String, Object> config, URI uri)
            throws InterruptedException, com.fasterxml.jackson.core.JsonProcessingException {
        if (!BROWSER_SLOTS.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS))
            throw new IllegalStateException("Browser rendering capacity is busy; retry the task");
        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(true);
            try (Browser browser = playwright.chromium().launch(launchOptions);
                 BrowserContext browserContext = browser.newContext(new Browser.NewContextOptions()
                         .setJavaScriptEnabled(true)
                         .setAcceptDownloads(false)
                         .setServiceWorkers(com.microsoft.playwright.options.ServiceWorkerPolicy.BLOCK)
                         .setUserAgent(String.valueOf(config.getOrDefault("userAgent", "ConduitPublicScraper/1.0"))))) {
                if (config.get("headers") instanceof Map<?, ?> headers) {
                    Map<String, String> safeHeaders = new LinkedHashMap<>();
                    headers.forEach((key, value) -> { if (key instanceof String name && value != null) safeHeaders.put(name, value.toString()); });
                    browserContext.setExtraHTTPHeaders(safeHeaders);
                }
                AtomicReference<RuntimeException> policyFailure = new AtomicReference<>();
                java.util.concurrent.atomic.AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger();
                browserContext.route("**/*", route -> {
                    try {
                        String requestUrl = route.request().url();
                        URI destination = URI.create(requestUrl);
                        if (!"http".equalsIgnoreCase(destination.getScheme()) && !"https".equalsIgnoreCase(destination.getScheme())) {
                            route.abort();
                            return;
                        }
                        SsrfAddressPolicy.validate(destination, false);
                        if (requestCount.incrementAndGet() > 200) {
                            policyFailure.compareAndSet(null, new IllegalStateException("Rendered page exceeded the 200-request limit"));
                            route.abort();
                            return;
                        }
                        String resourceType = route.request().resourceType();
                        if (List.of("image", "font", "media").contains(resourceType)) route.abort();
                        else route.resume();
                    } catch (RuntimeException failure) {
                        policyFailure.compareAndSet(null, failure);
                        try { route.abort(); } catch (RuntimeException ignored) { }
                    }
                });
                Page page = browserContext.newPage();
                page.setDefaultNavigationTimeout(20_000);
                com.microsoft.playwright.Response navigationResponse = page.navigate(uri.toString(), new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(20_000));
                if (policyFailure.get() != null) throw policyFailure.get();
                if (config.get("waitForSelector") instanceof String selector && !selector.isBlank()) {
                    try {
                        page.waitForSelector(selector, new Page.WaitForSelectorOptions()
                                .setTimeout(intValue(config.get("waitForSelectorTimeoutMs"), 5000)));
                    } catch (com.microsoft.playwright.TimeoutError timeout) {
                        throw new IllegalStateException("Timed out waiting for waitForSelector '" + selector + "'; the page may be loading, blocked, or the selector may be incorrect", timeout);
                    }
                }
                int waitAfterLoadMs = intValue(config.get("waitAfterLoadMs"), 1000);
                if (waitAfterLoadMs > 0) page.waitForTimeout(waitAfterLoadMs);
                if (policyFailure.get() != null) throw policyFailure.get();
                URI finalUri = URI.create(page.url());
                SsrfAddressPolicy.validate(finalUri, false);
                Object extracted = extractRenderedPage(page, config);
                String extractedJson = objectMapper.writeValueAsString(extracted);
                if (extractedJson.length() > MAX_OUTPUT_CHARS)
                    throw new IllegalStateException("Extracted result exceeded the 256 KiB output limit");
                int status = navigationResponse == null ? 200 : navigationResponse.status();
                String contentType = navigationResponse == null ? "text/html" : navigationResponse.headerValue("content-type");
                return new ScrapeResponse(status, contentType, "", false, extracted, renderedDiagnostics(page, config));
            }
        } catch (com.microsoft.playwright.PlaywrightException browserFailure) {
            String message = browserFailure.getMessage() == null ? "" : browserFailure.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (message.contains("executable doesn't exist") || message.contains("browser was not found"))
                throw new IllegalStateException("JavaScript rendering needs the Playwright Chromium browser installed on this host", browserFailure);
            throw new IllegalStateException("Headless browser rendering failed", browserFailure);
        } finally {
            BROWSER_SLOTS.release();
        }
    }

    /** Extract only configured fields in Chromium; avoids copying oversized page HTML into the worker heap. */
    private Object extractRenderedPage(Page page, Map<String, Object> config) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("selectors", config.get("selectors"));
        args.put("itemSelector", config.get("itemSelector"));
        args.put("maxItems", intValue(config.get("maxItems"), 25));
        args.put("maxFieldChars", MAX_FIELD_CHARS);
        return page.evaluate("""
                arg => {
                  const value = (element, spec) => {
                    const attribute = typeof spec === 'object' && spec !== null ? spec.attribute : null;
                    const text = attribute ? (element.getAttribute(attribute) || '') : (element.innerText || element.textContent || '');
                    return text.slice(0, arg.maxFieldChars);
                  };
                  const read = (root, spec, limit) => {
                    const css = typeof spec === 'string' ? [spec] : (Array.isArray(spec.selector) ? spec.selector : [spec.selector]);
                    let matches = [];
                    for (const candidate of css) {
                      matches = Array.from(root.querySelectorAll(candidate)).slice(0, limit);
                      if (matches.length) break;
                    }
                    const values = matches.map(element => value(element, spec)).filter(text => text.trim().length > 0);
                    return typeof spec === 'object' && spec !== null && spec.all === true
                      ? values : (values.length ? values[0] : null);
                  };
                  const selectors = arg.selectors;
                  if (typeof arg.itemSelector === 'string' && arg.itemSelector.trim()) {
                    return Array.from(document.querySelectorAll(arg.itemSelector)).slice(0, arg.maxItems).map(item => {
                      const row = {};
                      for (const [key, spec] of Object.entries(selectors)) {
                        const field = read(item, spec, 1);
                        if (field !== null && (!Array.isArray(field) || field.length > 0)) row[key] = field;
                      }
                      return Object.keys(row).length ? row : null;
                    }).filter(row => row !== null);
                  }
                  const fields = {};
                  for (const [key, spec] of Object.entries(selectors)) {
                    const all = typeof spec === 'object' && spec !== null && spec.all === true;
                    const field = read(document, spec, all ? arg.maxItems : 1);
                    if (field !== null && (!Array.isArray(field) || field.length > 0)) fields[key] = field;
                  }
                  return fields;
                }
                """, args);
    }

    private Map<String, Object> renderedDiagnostics(Page page, Map<String, Object> config) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("selectors", config.get("selectors"));
        args.put("itemSelector", config.get("itemSelector"));
        return (Map<String, Object>) page.evaluate("""
                arg => {
                  const counts = {};
                  if (typeof arg.itemSelector === 'string' && arg.itemSelector.trim())
                    counts.items = document.querySelectorAll(arg.itemSelector).length;
                  for (const [key, spec] of Object.entries(arg.selectors)) {
                    const alternatives = typeof spec === 'string' ? [spec]
                      : (Array.isArray(spec.selector) ? spec.selector : [spec.selector]);
                    let count = 0;
                    for (const css of alternatives) {
                      count = document.querySelectorAll(css).length;
                      if (count) break;
                    }
                    counts[key] = count;
                  }
                  const preview = (document.body?.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 500);
                  return { finalUrl: location.href, pageTitle: document.title.slice(0, 8192),
                    pageTextPreview: preview, selectorMatches: counts };
                }
                """, args);
    }

    private boolean hasUsableData(Object value) {
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(this::hasUsableData);
        if (value instanceof Iterable<?> values) {
            for (Object item : values) if (hasUsableData(item)) return true;
            return false;
        }
        return value != null;
    }

    private Map<String, Object> pageDiagnostics(Document document, URI url) {
        String preview = document.text().replaceAll("\\s+", " ").trim();
        if (preview.length() > 500) preview = preview.substring(0, 500);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("finalUrl", url.toString());
        diagnostics.put("pageTitle", bounded(document.title()));
        diagnostics.put("pageTextPreview", preview);
        return diagnostics;
    }

    private Map<String, Integer> selectorMatchCounts(Document document, Map<String, Object> config) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (config.get("itemSelector") instanceof String itemSelector && !itemSelector.isBlank())
            counts.put("items", document.select(itemSelector).size());
        Map<String, Object> selectors = (Map<String, Object>) config.get("selectors");
        selectors.forEach((name, raw) -> {
            Selector spec = selectorSpec(raw);
            int count = 0;
            for (String css : spec.css()) {
                count = document.select(css).size();
                if (count > 0) break;
            }
            counts.put(name, count);
        });
        return counts;
    }

    private boolean looksLikeLoadingPage(String text) {
        String normalized = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.contains("searching...") || normalized.contains("loading loading")
                || normalized.matches(".*\\bloading\\b.*") || normalized.contains("please wait while we search");
    }

    private URI parsePublicUri(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException e) { throw new PluginConfigurationException("WEB_SCRAPE URL is invalid", e); }
        SsrfAddressPolicy.validate(uri, false);
        return uri;
    }

    private boolean sameOrigin(URI first, URI next) {
        int firstPort = first.getPort() < 0 ? ("https".equalsIgnoreCase(first.getScheme()) ? 443 : 80) : first.getPort();
        int nextPort = next.getPort() < 0 ? ("https".equalsIgnoreCase(next.getScheme()) ? 443 : 80) : next.getPort();
        return first.getScheme().equalsIgnoreCase(next.getScheme())
                && first.getHost().equalsIgnoreCase(next.getHost()) && firstPort == nextPort;
    }

    private Selector selectorSpec(Object raw) {
        if (raw instanceof String css) return new Selector(List.of(validateSelector(css)), null, false);
        if (raw instanceof Map<?, ?> map) {
            Object css = map.get("selector");
            List<String> alternatives = new ArrayList<>();
            if (css instanceof String selector) alternatives.add(validateSelector(selector));
            else if (css instanceof List<?> list && !list.isEmpty() && list.size() <= 10) {
                for (Object item : list) {
                    if (!(item instanceof String selector) || selector.isBlank())
                        throw new PluginConfigurationException("Selector fallback entries must be non-empty CSS strings");
                    alternatives.add(validateSelector(selector));
                }
            } else throw new PluginConfigurationException("Selector object requires a CSS selector or up to 10 fallback selectors");
            Object attribute = map.get("attribute");
            Object all = map.get("all");
            if (attribute != null && (!(attribute instanceof String value) || !value.matches("[A-Za-z_:][A-Za-z0-9_.:-]{0,63}")))
                throw new PluginConfigurationException("Selector attribute name is invalid");
            if (all != null && !(all instanceof Boolean)) throw new PluginConfigurationException("Selector all must be boolean");
            return new Selector(List.copyOf(alternatives), (String) attribute, Boolean.TRUE.equals(all));
        }
        throw new PluginConfigurationException("Each selector must be CSS text or a selector object");
    }

    private String validateSelector(String css) {
        if (css == null || css.isBlank() || css.length() > 512) throw new PluginConfigurationException("CSS selector must be 1 to 512 characters");
        try { Jsoup.parseBodyFragment("").select(css); }
        catch (RuntimeException e) { throw new PluginConfigurationException("CSS selector is invalid", e); }
        return css;
    }

    private String text(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) throw new PluginConfigurationException("WEB_SCRAPE '" + name + "' is required");
        return text.trim();
    }

    private int intValue(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (!(raw instanceof Number number)) throw new PluginConfigurationException("WEB_SCRAPE maxItems must be an integer");
        return number.intValue();
    }

    private String bounded(String text) { return text.length() <= MAX_FIELD_CHARS ? text : text.substring(0, MAX_FIELD_CHARS); }
    private ScrapeResponse readResponse(ClientHttpResponse response) throws java.io.IOException {
        byte[] bytes = response.getBody().readNBytes(1024 * 1024 + 1);
        boolean truncated = bytes.length > 1024 * 1024;
        String body = new String(bytes, 0, Math.min(bytes.length, 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8);
        return new ScrapeResponse(response.getStatusCode().value(), response.getHeaders().getContentType() == null
                ? null : response.getHeaders().getContentType().toString(), body, truncated, null, null);
    }
    private PluginResult failed(int status, String message) {
        return PluginResult.builder().success(false).output(message).metadata(Map.of("statusCode", status)).build();
    }
    private PluginResult failed(int status, String message, Map<String, Object> diagnostics) {
        return PluginResult.builder().success(false).output(message)
                .metadata(Map.of("statusCode", status, "diagnostics", diagnostics)).build();
    }
    private record Selector(List<String> css, String attribute, boolean all) { }
    private record ScrapeResponse(int statusCode, String contentType, String body, boolean truncated, Object extractedData,
                                  Map<String, Object> diagnostics) { }
}
