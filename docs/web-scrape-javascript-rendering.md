# JavaScript rendering in WEB_SCRAPE

`WEB_SCRAPE` can optionally render a public page with isolated headless Chromium before applying its CSS selectors. The default remains the lightweight HTTP/Jsoup mode. Rendering is useful for pages that populate their visible content with JavaScript; it does not bypass sign-in, CAPTCHAs, bot checks, or access controls.

## Host setup

The Java Playwright dependency is included by Maven. Install the matching Chromium runtime once on every host that runs Conduit workers:

```powershell
mvn exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
```

The worker account also needs the operating-system libraries required by Chromium. Install them using the documented Playwright dependencies for the host OS/container. In container deployments, use a maintained Playwright browser image matching the `playwright.version` in `pom.xml`, or bake Chromium and its OS dependencies into the Conduit worker image. Browser rendering is optional; tasks with `renderJavaScript` omitted or `false` do not launch Chromium.

## Configuration

Set `renderJavaScript` to `true` to opt into browser rendering. `waitForSelector` is a CSS selector that should appear after the site loads. `waitForSelectorTimeoutMs` bounds how long to wait for it (250–15000 ms, default 5000); a timeout now returns an actionable failure. `waitAfterLoadMs` provides a bounded settling delay for pages that render asynchronously without a stable selector. Prefer a meaningful result-card selector over a fixed delay.

```json
{
  "url": "https://public.example/hotels",
  "renderJavaScript": true,
  "waitForSelector": "article.hotel-card",
  "waitAfterLoadMs": 500,
  "itemSelector": "article.hotel-card",
  "selectors": {
    "name": "h2",
    "price": "[data-testid='price']",
    "rating": "[aria-label*='star']"
  },
  "maxItems": 20,
  "maxPages": 1
}
```

Selector objects can supply an ordered list of CSS fallbacks. The first selector
that matches is used, which helps accommodate minor layout variants without
silently combining duplicate results:

```json
{
  "selectors": {
    "name": { "selector": ["h2.product-title", "h2", "[itemprop='name']"] },
    "price": { "selector": ["[data-testid='price']", ".price", "[itemprop='price']"] }
  }
}
```

Successful responses include bounded diagnostics in metadata: final page URL,
title, a short visible-text preview, and per-field selector match counts. When
the page appears to contain only loading placeholders, the task fails with a
message explaining that it did not produce usable results. Diagnostics are
intended for troubleshooting; they are bounded and do not contain raw HTML.

Rendering uses a fresh, non-persistent browser context per page; cookies and local storage are not shared between tasks. Downloads are disabled. Images, fonts, and media requests are blocked; remaining HTTP(S) requests and redirects are checked against Conduit's public-address SSRF policy. Pagination links must remain on the starting origin. Each rendered page has a 20-second navigation timeout, at most 200 intercepted requests, and a maximum 10-second post-load wait. Rendered mode extracts only the selected values in Chromium instead of copying the full page HTML into the worker. Both modes retain the 256 KiB output limit, field/item/page caps, and rendering is limited to two concurrent browser sessions per application process. Non-rendered mode retains the 1 MiB HTML response cap.

If fields still come back `null`, the requested selector does not match the rendered DOM (or the site does not expose the information to an unauthenticated public browser). Inspect the public page DOM and update selectors. Do not use this feature to defeat access restrictions.
