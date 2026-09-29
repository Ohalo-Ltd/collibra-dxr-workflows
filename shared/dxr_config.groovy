// dxr_config.groovy — workflow configuration-variable helpers.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// Collibra requires a non-empty default for every non-readable form property,
// so variables that have no sensible default (auth token, base URL) ship with a
// "<paste … here>" sentinel in the BPMN. Any value of that shape is "unset".

def isPlaceholderValue(Object raw) {
    def s = raw == null ? '' : raw.toString().trim()
    return s.startsWith('<paste ') && s.endsWith('>')
}

// Read the given configuration variables off the execution.
//   requiredConfig: [variableName: 'Human label', …]
// Returns [config: [variableName: trimmedValue], missing: ['Label  (variable: name)', …]].
def readRequiredConfig(Map<String, String> requiredConfig) {
    def config  = [:]
    def missing = []
    requiredConfig.each { varName, label ->
        def raw = execution.getVariable(varName)
        def v = raw == null ? '' : raw.toString().trim()
        if (v.isEmpty() || isPlaceholderValue(v)) {
            missing << "${label}  (variable: ${varName})".toString()
        } else {
            config[varName] = v
        }
    }
    return [config: config, missing: missing]
}

// Bullet list of missing variables for a misconfiguration message.
def missingConfigBullets(List missing) {
    return '\n  • ' + missing.join('\n  • ')
}

// A base URL without trailing slashes, ready to prefix API paths.
def normalizeBaseUrl(Object url) {
    return (url == null ? '' : url.toString()).trim().replaceAll('/+$', '')
}

// Which edition this process was built for. The Cloud + Edge BPMN declares the
// Edge HTTP connection name; the on-prem BPMN declares the Bearer token instead.
def dxrEdition() {
    return execution.hasVariable('dataxrayConnectionName') ? 'edge' : 'onprem'
}

// Check the transport's configuration variables.
// Returns [ok: boolean, missing: ['Label  (variable: name)', …], via: String for logs].
def checkDxrTransport() {
    if (dxrEdition() == 'edge') {
        def r = readRequiredConfig([dataxrayConnectionName: 'Edge HTTP connection name (Data X-Ray)'])
        return [ok: r.missing.isEmpty(), missing: r.missing,
                via: "Edge connection '${r.config.dataxrayConnectionName ?: ''}'".toString()]
    }
    def r = readRequiredConfig([dataxrayUrl: 'Data X-Ray Base URL', dataxrayAuthToken: 'Data X-Ray Auth Token (Bearer)'])
    return [ok: r.missing.isEmpty(), missing: r.missing, via: normalizeBaseUrl(r.config.dataxrayUrl)]
}

// "<prefix> — this configuration variable is not set: • … Open the workflow's
// settings page and <what to enter for this edition>" (no trailing period).
def transportMisconfiguredMessage(String prefix, Map check) {
    def fix = dxrEdition() == 'edge'
        ? 'enter the name of the HTTP connection to Data X-Ray on your Edge site'
        : 'enter the Data X-Ray base URL and a Data X-Ray Bearer token'
    def which = check.missing.size() == 1 ? 'this configuration variable is' : 'these configuration variables are'
    return "${prefix} — ${which} not set:${missingConfigBullets(check.missing)}\n\nOpen the workflow's settings page and ${fix}".toString()
}

// Where to look when Data X-Ray cannot be reached (appended to an error).
def transportCheckHint() {
    return dxrEdition() == 'edge'
        ? "Check the Edge HTTP connection '${execution.getVariable('dataxrayConnectionName')}'.".toString()
        : 'Check the Data X-Ray base URL and Bearer token on the workflow settings page.'
}
