// dxr_http_direct.groovy — direct HTTPS transport to the Data X-Ray API.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// ON-PREM VARIANT ONLY: the Collibra Cloud + Edge variant never opens HTTP
// connections from Groovy — it routes every call through an External API task
// (see dxr_edge.groovy) — so nothing here may be included by an edge script.
//
// Every request carries the admin-configured Bearer token. Non-200 responses
// raise a RuntimeException carrying the status and a truncated error body.

// {{include:dxr_rows.groovy}}

// GET /api/v1/files?q=<query> for a base URL (no trailing slash) and a query string.
def dxrFilesUrl(String baseUrl, String queryString) {
    def encoded = java.net.URLEncoder.encode(queryString ?: '', java.nio.charset.StandardCharsets.UTF_8.toString())
    return "${baseUrl}/api/v1/files?q=${encoded}".toString()
}

// Open a GET and fail fast on anything but HTTP 200. The read timeout is
// BETWEEN bytes, not total: a healthy stream can run for minutes, but a long
// silence means the server has stalled — fail so a retry gets its chance.
def openDxrGet(String url, String authToken, int readTimeoutMs) {
    def conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('GET')
    conn.setRequestProperty('Authorization', "Bearer ${authToken}")
    conn.setRequestProperty('Content-Type', 'application/json')
    conn.setConnectTimeout(30_000)
    conn.setReadTimeout(readTimeoutMs)

    def code = conn.getResponseCode()
    if (code != 200) {
        def errBody = ''
        try {
            errBody = conn.getErrorStream()?.getText('UTF-8') ?: ''
        } catch (Exception ignored) {
            // best-effort
        }
        throw new RuntimeException("Data X-Ray API returned HTTP ${code}: ${truncateText(errBody, 500)}")
    }
    return conn
}

// Stream a newline-delimited JSON response, calling perRow(Map) for each
// object without ever buffering the whole body (Jackson's MappingIterator
// reads one value at a time off the input stream).
def streamDxrNdjson(String url, String authToken, int readTimeoutMs, Closure perRow) {
    def conn = openDxrGet(url, authToken, readTimeoutMs)
    def input = conn.getInputStream()
    try {
        def reader = new com.fasterxml.jackson.databind.ObjectMapper().readerFor(Map).readValues(input)
        while (reader.hasNextValue()) {
            perRow(reader.nextValue())
        }
    } catch (Exception parseEx) {
        throw new RuntimeException("Data X-Ray API response could not be parsed as JSON: ${parseEx.message}")
    } finally {
        try { input.close() } catch (Exception ignored) { /* best-effort */ }
        conn.disconnect()
    }
}

// GET a JSON endpoint and return the raw body text.
def fetchDxrText(String url, String authToken, int readTimeoutMs) {
    def conn = openDxrGet(url, authToken, readTimeoutMs)
    try {
        return conn.getInputStream().getText('UTF-8')
    } finally {
        conn.disconnect()
    }
}

// Run `attempt` up to `attempts` times, sleeping `sleepMs` between failures.
// Data X-Ray can transiently stall an NDJSON stream mid-response (observed
// live: a query that normally streams in seconds hung after a fraction of its
// rows, then the connection died with "Premature EOF"); a fresh attempt
// usually succeeds. The last failure is rethrown for the caller to report.
def withDxrRetries(int attempts, long sleepMs, String what, Closure attempt) {
    for (int n = 1; n <= attempts; n++) {
        try {
            return attempt()
        } catch (Exception attemptEx) {
            if (n < attempts) {
                loggerApi.warn("${what} attempt ${n}/${attempts} failed (${attemptEx.message}) — retrying")
                sleep(sleepMs)
            } else {
                throw attemptEx
            }
        }
    }
    return null
}
