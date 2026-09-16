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
