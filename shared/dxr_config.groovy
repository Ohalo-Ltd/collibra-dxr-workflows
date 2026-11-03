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

// --- Settings borrowed from Search DataXray (v2.11.1) ------------------------
//
// Rerun DataXray Search declares no DataXray settings of its own: Collibra
// refuses every change to its definition — including saving its settings
// page (workflowWrongRoles) — once Configure has given it global start roles,
// which it needs because it is asset-scoped. So the rerun uses the settings
// of Search DataXray, a global workflow whose settings page always saves: the
// same connection settings, one place to set them.

def dxrSettingsSourceProcessId() { return 'searchDataXray' }

// Copy Search DataXray's configuration variables `names` onto this execution.
// A variable that already has a non-placeholder value here (passed when the
// run was started) is kept. Never throws: when Search DataXray can't be read,
// the variables stay unset and the caller's missing-settings message applies.
// Returns [ok: Boolean, error: String].
def adoptSearchDataXraySettings(List names) {
    Map vars
    try {
        def wd = workflowDefinitionApi.getWorkflowDefinitionByProcessId(dxrSettingsSourceProcessId())
        vars = (wd?.getConfigurationVariables() ?: [:]) as Map
    } catch (Exception readEx) {
        loggerApi.warn("Could not read the settings of Search DataXray (${dxrSettingsSourceProcessId()}): ${readEx.message}")
        return [ok: false, error: readEx.message ?: readEx.getClass().getSimpleName()]
    }
    names.each { name ->
        def own = (execution.getVariable(name) ?: '').toString().trim()
        if ((own.isEmpty() || isPlaceholderValue(own)) && vars[name] != null) {
            execution.setVariable(name, vars[name].toString())
        }
    }
    return [ok: true, error: '']
}

// The user-facing message for settings Search DataXray does not supply.
def searchSettingsMissingMessage(String what, Map adopted) {
    def why = adopted.ok ? '' : " (Search DataXray's settings could not be read: ${adopted.error})"
    return ("Rerun DataXray Search uses the DataXray settings of the Search DataXray workflow, and ${what} not set there${why}. " +
        'An admin sets it in Settings → Workflows → Definitions → Search DataXray → Variables.').toString()
}
