// search_data_xray.groovy
//
// Builds a Data X-Ray search query from a set of Collibra classification
// assets, then previews the matching files.
//
// Flow:
//   1. Read & validate workflow configuration variables (hidden admin settings).
//   2. Read the user's picks from the start form: a name, any combination of
//      annotators / extractors / labels (Collibra asset pickers, multi-value),
//      an optional free-text phrase filter, and a description.
//   3. Create a search-query asset in the configured domain.
//   4. Link that asset to each chosen classification via the configured
//      relation type, resolving each classification's name along the way.
//   5. Compose the equivalent Data X-Ray query string from those names plus the
//      phrase filter, and store it on the asset as its Description.
//   6. Call the Data X-Ray files API with that query and collect up to
//      `maxResults` matches.
//   7. Store a rich HTML results table on the asset, and publish process
//      variables the results form renders.
//
// Configuration is read from form properties on the start event; an admin sets
// them on the workflow settings page. Secrets (base URL, auth token) ship with
// a "<paste … here>" sentinel default that this script treats as "unset".
//
// Process variables produced (for the results form / audit):
//   conditionName  (String)  – the search name the user entered
//   queryString    (String)  – the composed Data X-Ray query, or "(all files)"
//   searchUrl      (String)  – the full Data X-Ray files API URL that was called
//   count          (Integer) – number of matching files (capped at maxResults)
//
// The matching files themselves are stored as an HTML table attribute on the
// search-query asset, not surfaced on the results task.

import com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest
import com.collibra.dgc.core.api.dto.instance.attribute.AddAttributeRequest
import com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest
import com.collibra.dgc.workflow.api.exception.WorkflowException
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.xml.MarkupBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// --- Read & validate workflow configuration variables -----------------------

// Required configuration variables: variable name → human label shown in errors
def requiredConfig = [
    ohaloUrl             : 'Data X-Ray Base URL',
    ohaloAuthToken       : 'Data X-Ray Auth Token (Bearer)',
    queryDomainId        : 'Query Domain ID',
    queryAssetTypeId     : 'Asset Type ID: Search Query',
    groupsRelationTypeId : 'Relation Type ID: Query → Classification',
    descriptionAttrTypeId: 'Attribute Type ID: Description',
    filesAttrTypeId      : 'Attribute Type ID: Files Table',
    maxResults           : 'Max Files To Preview',
]

// Collibra requires a non-empty default for non-readable form properties, so
// variables that have no sensible default (auth token, base URL) ship with a
// sentinel placeholder in the BPMN — any value of the form "<paste … here>"
// is treated as unset.
def isPlaceholder = { String s -> s.startsWith('<paste ') && s.endsWith('>') }

def config  = [:]
def missing = []
requiredConfig.each { varName, label ->
    def raw = execution.getVariable(varName)
    def v = raw == null ? '' : raw.toString().trim()
    if (v.isEmpty() || isPlaceholder(v)) {
        missing << "${label}  (variable: ${varName})"
    } else {
        config[varName] = v
    }
}

if (!missing.isEmpty()) {
    def bullets = '\n  • ' + missing.join('\n  • ')
    def detailMsg = "Cannot run Search Data X-Ray — the following workflow configuration variable(s) are not set:${bullets}\n\nOpen the workflow's settings page and provide a value for each, then start the workflow again."
    loggerApi.error(detailMsg)
    def wf = new WorkflowException(detailMsg)
    wf.setTitleMessage('Search Data X-Ray misconfigured')
    wf.setUserMessage(detailMsg)
    throw wf
}

def ohaloUrl              = config.ohaloUrl.replaceAll('/+$', '')
def ohaloAuthToken        = config.ohaloAuthToken
def queryDomainId         = string2Uuid(config.queryDomainId)
def queryAssetTypeId      = string2Uuid(config.queryAssetTypeId)
def groupsRelationTypeId  = string2Uuid(config.groupsRelationTypeId)
def descriptionAttrTypeId = string2Uuid(config.descriptionAttrTypeId)
def filesAttrTypeId       = string2Uuid(config.filesAttrTypeId)
def maxResults            = parseIntOrDefault(config.maxResults, 20)

// --- Read user inputs from the start form -----------------------------------

def conditionName = (execution.getVariable('conditionName') ?: '').toString().trim()
if (conditionName.isEmpty()) {
    def msg = 'A name for the search query is required.'
    def wf = new WorkflowException(msg)
    wf.setTitleMessage('Search Data X-Ray')
    wf.setUserMessage(msg)
    throw wf
}

def annotatorIds = toIdList(execution.getVariable('annotator'))
def extractorIds = toIdList(execution.getVariable('extractor'))
def labelIds     = toIdList(execution.getVariable('label'))
def filter       = (execution.getVariable('filter') ?: '').toString().trim()
def description  = (execution.getVariable('description') ?: '').toString().trim()

// --- Create the search-query asset ------------------------------------------

def queryId
try {
    def asset = assetApi.addAsset(AddAssetRequest.builder()
        .name(conditionName)
        .displayName(conditionName)
        .domainId(queryDomainId)
        .typeId(queryAssetTypeId)
        .build())
    queryId = asset.getId()
    loggerApi.info("Created search-query asset '${conditionName}' [${queryId}]")
} catch (Exception createEx) {
    loggerApi.error("Failed to create search-query asset '${conditionName}': ${createEx.message}")
    def wf = new WorkflowException("Could not create the search-query asset: ${createEx.message}", createEx)
    wf.setTitleMessage('Search Data X-Ray failed')
    wf.setUserMessage("Could not create the search-query asset '${conditionName}': ${createEx.message}")
    throw wf
}

// --- Resolve classification names and link them to the query asset ----------

// Resolve each picker's selected asset IDs to names, and relate them to the
// query asset. resolveAndRelate returns the list of resolved names.
def labelNames     = resolveAndRelate(labelIds,     queryId, groupsRelationTypeId)
def annotatorNames = resolveAndRelate(annotatorIds, queryId, groupsRelationTypeId)
def extractorNames = resolveAndRelate(extractorIds, queryId, groupsRelationTypeId)

// --- Compose the Data X-Ray query string ------------------------------------

// Each category becomes one clause; multiple selections within a category are
// OR-ed together, and the categories are AND-ed. The phrase filter, if given,
// is AND-ed on as an annotations match. Mirrors the field names the Data X-Ray
// files API expects: labels.name, annotators.name, extractors.name,
// annotators.annotations.phrase.
def clauses = []
appendNameClause(clauses, 'labels.name', labelNames)
appendNameClause(clauses, 'annotators.name', annotatorNames)
appendNameClause(clauses, 'extractors.name', extractorNames)
if (!filter.isEmpty()) {
    clauses << "annotators.annotations.phrase:\"${filter}\"".toString()
}
def queryString = clauses.join(' AND ')
if (queryString.isEmpty()) {
    loggerApi.warn('No annotators, extractors, labels or filter selected — querying all files')
}
loggerApi.info("Composed Data X-Ray query: ${queryString ?: '(all files)'}")

// --- Store the user's description plus the query as the asset's Description --

def descParts = []
if (!description.isEmpty()) descParts << description
descParts << "The query is: ${queryString ?: '(all files)'}".toString()
addAttribute(queryId, descriptionAttrTypeId, descParts.join('\n\n'))

// --- Call the Data X-Ray files API ------------------------------------------

def encodedQuery = URLEncoder.encode(queryString, StandardCharsets.UTF_8.toString())
def searchUrl = "${ohaloUrl}/api/v1/files?q=${encodedQuery}"

def files
try {
    files = fetchFiles(searchUrl, ohaloAuthToken)
} catch (Exception fetchEx) {
    loggerApi.error("Failed to fetch files from Data X-Ray: ${fetchEx.message}")
    def wf = new WorkflowException("Data X-Ray file search failed: ${fetchEx.message}", fetchEx)
    wf.setTitleMessage('Search Data X-Ray failed')
    wf.setUserMessage("The search-query asset was created, but fetching matching files from Data X-Ray failed: ${fetchEx.message}")
    throw wf
}

loggerApi.info("Data X-Ray returned ${files.size()} file(s); previewing up to ${maxResults}")

// --- Build the preview (capped at maxResults) -------------------------------

def shown = files.take(maxResults)
int count = shown.size()

// The matching files are stored as an HTML table on the search-query asset
// only; the results task just reports the count and query.
if (count == 0) {
    addAttribute(queryId, filesAttrTypeId, 'Query returned 0 results')
} else {
    def writer = new StringWriter()
    def html = new MarkupBuilder(writer)
    html.table {
        thead { tr { th('Datasource'); th('Path') } }
        tbody {
            shown.each { f ->
                tr { td(dataSourceName(f)); td(filePath(f)) }
            }
        }
    }
    addAttribute(queryId, filesAttrTypeId, writer.toString())
}

// --- Publish process variables for the results form -------------------------

execution.setVariable('conditionName', conditionName)
execution.setVariable('queryString', queryString ?: '(all files)')
execution.setVariable('searchUrl', searchUrl)
execution.setVariable('count', count)

loggerApi.info("Search Data X-Ray complete: asset=${queryId}, files shown=${count}")

// --- Helpers ----------------------------------------------------------------

// Normalize an asset-picker value to a list of trimmed, non-empty ID strings.
// Pickers may hand back a List, a single String, or a comma-separated String.
def toIdList(value) {
    if (value == null) return []
    def raw = (value instanceof Collection) ? value.toList() : value.toString().split(',').toList()
    return raw.collect { it?.toString()?.trim() }.findAll { it }
}

// Look up each asset by ID, relate it to the query asset, and return the names.
def resolveAndRelate(List ids, UUID sourceId, UUID relationTypeId) {
    def names = []
    def requests = []
    ids.each { id ->
        def targetId = string2Uuid(id)
        try {
            names << assetApi.getAsset(targetId).getName()
            requests << AddRelationRequest.builder()
                .sourceId(sourceId)
                .targetId(targetId)
                .typeId(relationTypeId)
                .build()
        } catch (Exception resolveEx) {
            loggerApi.warn("Skipping classification ${id}: ${resolveEx.message}")
        }
    }
    if (!requests.isEmpty()) {
        relationApi.addRelations(requests)
    }
    return names
}

// Append "field:\"a\"" (single) or "(field:\"a\" OR field:\"b\")" (multiple) to clauses.
def appendNameClause(List clauses, String field, List names) {
    def present = names.findAll { it != null && !it.toString().trim().isEmpty() }
    if (present.isEmpty()) return
    def terms = present.collect { "${field}:\"${it}\"".toString() }
    clauses << (terms.size() == 1 ? terms[0] : "(${terms.join(' OR ')})".toString())
}

def addAttribute(UUID assetId, UUID typeId, String value) {
    if (value == null || value.trim().isEmpty()) return
    try {
        attributeApi.addAttribute(AddAttributeRequest.builder()
            .assetId(assetId)
            .typeId(typeId)
            .value(value)
            .build())
    } catch (Exception attrEx) {
        loggerApi.warn("Failed to add attribute ${typeId} on ${assetId}: ${attrEx.message}")
    }
}

def dataSourceName(f) { (f?.datasource?.name ?: '').toString() }
def filePath(f) { (f?.path ?: f?.filePath ?: '').toString() }

def parseIntOrDefault(String s, int fallback) {
    try {
        def n = Integer.parseInt(s.trim())
        return n > 0 ? n : fallback
    } catch (Exception ignored) {
        return fallback
    }
}

// GET the Data X-Ray files endpoint and return a List of file objects. The
// files endpoint streams newline-delimited JSON (NDJSON) — one file object per
// line — so we parse line by line. We also tolerate a bare JSON array or an
// object wrapping the array under data/files/results, in case the shape varies.
def fetchFiles(String url, String authToken) {
    def conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('GET')
    conn.setRequestProperty('Authorization', "Bearer ${authToken}")
    conn.setRequestProperty('Content-Type', 'application/json')
    conn.setConnectTimeout(30_000)
    conn.setReadTimeout(60_000)

    def code = conn.getResponseCode()
    if (code != 200) {
        def errBody = ''
        try {
            errBody = conn.getErrorStream()?.getText('UTF-8') ?: ''
        } catch (Exception ignored) {
            // best-effort
        }
        throw new RuntimeException("Data X-Ray API returned HTTP ${code}: ${truncate(errBody, 500)}")
    }

    def body = conn.getInputStream().getText('UTF-8')
    if (!body?.trim()) return []

    // The files endpoint returns a stream of JSON values (one file object per
    // line). Jackson's readValues reads that sequence regardless of how the
    // values are delimited, and also iterates the elements of a top-level JSON
    // array — so it covers every shape this endpoint produces.
    def items = []
    try {
        def reader = new ObjectMapper().readerFor(Map).readValues(body)
        while (reader.hasNextValue()) {
            items << reader.nextValue()
        }
    } catch (Exception parseEx) {
        throw new RuntimeException("Data X-Ray API returned a body that could not be parsed as JSON: ${parseEx.message}; body starts: ${truncate(body, 300)}")
    }

    // Tolerate a single {data|files|results: [...]} envelope, just in case.
    if (items.size() == 1 && items[0] instanceof Map) {
        def list = items[0].data ?: items[0].files ?: items[0].results
        if (list instanceof List) return list
    }
    return items
}

def truncate(String s, int max) {
    if (s == null) return ''
    return s.length() <= max ? s : s.substring(0, max) + '…'
}
