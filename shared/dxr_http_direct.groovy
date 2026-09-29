// dxr_http_direct.groovy — the on-prem transport to Data X-Ray.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
// Included only by the on-prem edition's dxr_call_direct.groovy scripts.
//
// Both editions drive Data X-Ray the same way (dxr_search.groovy): a script
// arms a request in process variables (armDxrRequest), a transport task runs
// it, the next script reads the response (readDxrResponse). In the Cloud +
// Edge edition the transport task is a Collibra External API task bound to an
// Edge HTTP connection. In the on-prem edition it is an async script task that
// calls performArmedRequestDirect(), which runs the same request over HTTPS
// with the admin-configured Bearer token and leaves exactly the variables the
// External API task leaves:
//   dxrResponseBody (String), dxrResponseStatusCode (Integer), dxrResponseReason,
//   or dxrErrorMessage when the request could not be made at all.
// Like the External API task (ignoreException=true), it never throws: every
// HTTP status is returned as-is and the handling script decides.

// {{include:dxr_config.groovy}}

def performArmedRequestDirect() {
    ['dxrResponseBody', 'dxrResponseStatusCode', 'dxrResponseReason', 'dxrResponseHeaders', 'dxrErrorMessage'].each { n ->
        if (execution.hasVariable(n)) { execution.removeVariable(n) }
    }
    def baseUrl = normalizeBaseUrl(execution.getVariable('dataxrayUrl'))
    def token = (execution.getVariable('dataxrayAuthToken') ?: '').toString().trim()
    def method = (execution.getVariable('dxrMethod') ?: 'GET').toString()
    def path = (execution.getVariable('dxrRequestPath') ?: '').toString()
    def body = (execution.getVariable('dxrRequestBody') ?: '').toString()
    def headers = (execution.getVariable('dxrRequestHeaders') ?: '').toString()

    HttpURLConnection conn = null
    try {
        conn = (HttpURLConnection) new URL(baseUrl + path).openConnection()
        conn.setRequestMethod(method)
        conn.setInstanceFollowRedirects(false)
        conn.setConnectTimeout(30_000)
        conn.setReadTimeout(120_000)
        headers.split('\n').each { line ->
            int colon = line.indexOf(':')
            if (colon > 0) { conn.setRequestProperty(line.substring(0, colon).trim(), line.substring(colon + 1).trim()) }
        }
        conn.setRequestProperty('Authorization', "Bearer ${token}".toString())
        if (!body.isEmpty()) {
            conn.setDoOutput(true)
            conn.getOutputStream().withCloseable { out -> out.write(body.getBytes('UTF-8')) }
        }
        int code = conn.getResponseCode()
        def stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream()
        execution.setVariable('dxrResponseBody', stream == null ? '' : stream.getText('UTF-8'))
        execution.setVariable('dxrResponseStatusCode', code)
        execution.setVariable('dxrResponseReason', conn.getResponseMessage() ?: '')
    } catch (Exception callEx) {
        execution.setVariable('dxrErrorMessage', "${callEx.class.simpleName}: ${callEx.message}".toString())
    } finally {
        if (conn != null) { conn.disconnect() }
    }
}
