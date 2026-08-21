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
//      phrase filter (a "contains" match scoped to the picked annotators), and
//      store it on the asset as its Description.
//   6. Call the Data X-Ray files API with that query, streaming every matching
//      file into a CSV that is compressed on the fly into a ZIP, and keep the
//      first `maxResults` rows for an inline HTML preview.
//   7. Attach the results ZIP to the asset, store the HTML preview table, and
//      publish process variables the results form renders.
//
// The only configuration variables are the per-instance secrets (base URL and
// auth token), set by an admin on the workflow settings page; they ship with a
// "<paste … here>" sentinel default that this script treats as "unset". All
// operating-model IDs are fixed constants (see below), created by the
// configure-data-xray-workflows admin workflow.
//
// Process variables produced (for the results form / audit):
//   conditionName  (String)  – the search name the user entered
//   queryString    (String)  – the composed Data X-Ray query, or "(all files)"
//   searchUrl      (String)  – the full Data X-Ray files API URL that was called
//   resultCount        (Integer) – total matching files (exact)
//   resultCountDisplay (String)  – resultCount as text
//   shownCount         (Integer) – rows shown in the inline preview table (≤ maxResults)
//   attachmentName     (String)  – filename of the attached results ZIP, or "" if none
//   zipCapped          (Boolean) – true if the ZIP was truncated at the 25 MB size cap
//   queryAssetId       (String)  – UUID of the created search-query asset
//   importAllowed      (Boolean) – results may be imported as file assets (> 0 results
//                                  and the projected Data X-Ray Files domain population
//                                  stays within the instance-wide 25k cap)
//   importWarn         (Boolean) – import allowed but large (> 10k files): the results
//                                  form shows a duration warning
//   importBlocked      (Boolean) – results exist but importing would exceed the cap
//   importBlockedMessage (String) – human-readable refusal, '' unless importBlocked
//   filesDomainCount   (Integer) – current Data X-Ray Files domain population
//   importDecision / keepInSync (Boolean) – defaults (false) for the results form's
//                                  decision fields; the form overwrites them
//
// Every matching file is streamed straight into a CSV that is compressed on the
// fly into a ZIP (only compressed bytes are ever held in memory), which is then
// attached to the search-query asset. The response is never buffered whole, so
// the exact total is counted without loading every file into memory. The ZIP is
// capped at 25 MB: once that size is reached the CSV stops growing but the total
// keeps counting, so the attachment is a bounded sample of a very large result set.
// The first `maxResults` rows are additionally rendered as an inline HTML preview
// table attribute on the asset.

import com.collibra.dgc.core.api.dto.instance.asset.AddAssetRequest
import com.collibra.dgc.core.api.dto.instance.attribute.AddAttributeRequest
import com.collibra.dgc.core.api.dto.instance.attachment.AddAttachmentRequest
import com.collibra.dgc.core.api.dto.instance.relation.AddRelationRequest
import com.collibra.dgc.workflow.api.exception.WorkflowException
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.xml.MarkupBuilder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// --- Read & validate workflow configuration variables -----------------------

// Only the per-instance secrets are configuration variables. The operating-model
// IDs are fixed across instances (see the constants block below), so they're not
// configurable here.
def requiredConfig = [
    dataxrayUrl      : 'Data X-Ray Base URL',
    dataxrayAuthToken: 'Data X-Ray Auth Token (Bearer)',
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

def dataxrayUrl           = config.dataxrayUrl.replaceAll('/+$', '')
def dataxrayAuthToken     = config.dataxrayAuthToken

// Canonical operating-model IDs — fixed across all instances. The
// configure-data-xray-workflows admin workflow creates the matching elements
// with exactly these UUIDs, so they're constants here rather than configuration
// variables (which would just be indirection wrapping a constant). The same
// IDs are also baked into searchDataXrayForm.form's asset pickers.
def queryDomainId         = string2Uuid('019dcf96-233a-72e1-bf25-8398b8c9146e')
def queryAssetTypeId      = string2Uuid('019dcf97-3bac-72c3-8b59-b6ddbe8a8396')
def groupsRelationTypeId  = string2Uuid('00000000-0000-0000-0000-000000007017')
def descriptionAttrTypeId = string2Uuid('00000000-0000-0000-0000-000000003114')
def filesAttrTypeId       = string2Uuid('019e2736-8bd0-727a-b4ab-6899517a3e73')
def queryAttrTypeId       = string2Uuid('019e9210-cf9b-751a-85f2-d30c7a48b9e6')  // Data X-Ray Query
def filterAttrTypeId      = string2Uuid('019e9210-e04d-7c6b-a481-5f29d8036c7a')  // Annotated Text Filter
def filesDomainId         = string2Uuid('019e9210-52a4-7c31-9b5e-3d8f0a6c1e42')  // Data X-Ray Files

// Max files to preview on the query asset (fixed; previously a config variable).
def maxResults            = 50

// Import capacity is INSTANCE-WIDE: the Data X-Ray Files domain must never hold
// more than MAX_TOTAL_FILE_ASSETS assets in total — not per import. Imports
// projected to exceed it are refused; above WARN_IMPORT_FILES the form shows a
// "this will take a while" warning. The import collector re-checks this guard
// (forms can be bypassed via the task-completion REST API).
def MAX_TOTAL_FILE_ASSETS = 25_000
def WARN_IMPORT_FILES     = 10_000

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
// OR-ed together, and the categories are AND-ed. Mirrors the field names the
// Data X-Ray files API expects: labels.name, annotators.name, and
// extractedMetadata.name (extractor results are exposed on file rows as
// extractedMetadata entries — verified against a live instance; a query on
// extractors.name matches nothing).
//
// The phrase filter, if given, is a "contains" match scoped to the picked
// annotators via the nested object syntax — annotators: { name:… AND
// annotations.phrase:"*text*" } — so the phrase and the annotator identity must
// belong to the same annotator (see buildAnnotatorPhraseClause).
def clauses = []
appendNameClause(clauses, 'labels.name', labelNames)
appendNameClause(clauses, 'extractedMetadata.name', extractorNames)

if (filter.isEmpty()) {
    // No phrase: annotator selection is an independent name filter, as before.
    appendNameClause(clauses, 'annotators.name', annotatorNames)
} else {
    // Phrase present: scope it to the picked annotators using the nested object
    // syntax so the phrase and annotator identity belong to the SAME annotator.
    // Wildcards make it a "contains" match (anchored, case-insensitive in the API).
    // No annotators picked => search the phrase across all annotators.
    clauses << buildAnnotatorPhraseClause(annotatorNames, filter)
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

// Structured criteria for the rerun workflow. The classification criteria are
// already recoverable from the query asset's "groups" relations (rebuilt from
// CURRENT names at rerun time, so Data X-Ray renames don't break saved
// queries); the free-text phrase has no relation, so it gets its own
// attribute. The composed query string is stored too — as a human-readable
// record of what ran, not as rerun input.
addAttribute(queryId, queryAttrTypeId, queryString ?: '(all files)')
if (!filter.isEmpty()) {
    addAttribute(queryId, filterAttrTypeId, filter)
}

// --- Call the Data X-Ray files API ------------------------------------------

def encodedQuery = URLEncoder.encode(queryString, StandardCharsets.UTF_8.toString())
def searchUrl = "${dataxrayUrl}/api/v1/files?q=${encodedQuery}"

// Stream the (potentially huge) NDJSON response rather than buffering it:
// compress every matching file straight into a ZIP'd CSV (only compressed bytes
// are held in memory), keep the first maxResults rows for the inline preview
// table, and count the exact total. The ZIP stops growing at MAX_ZIP_BYTES but
// counting continues, so a very large result set yields a bounded sample plus an
// accurate total.
def MAX_ZIP_BYTES = 25 * 1024 * 1024  // 25 MB cap on the attached results ZIP

// Data X-Ray can transiently stall an NDJSON stream mid-response (observed
// live: a query that normally streams in seconds hung after a fraction of its
// rows, then the connection died with "Premature EOF"). A fresh attempt
// usually succeeds, so retry before giving up.
def FETCH_ATTEMPTS = 3
def result
for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
    try {
        result = fetchAndArchive(searchUrl, dataxrayAuthToken, maxResults, MAX_ZIP_BYTES)
        break
    } catch (Exception fetchEx) {
        if (attempt < FETCH_ATTEMPTS) {
            loggerApi.warn("Data X-Ray fetch attempt ${attempt}/${FETCH_ATTEMPTS} failed (${fetchEx.message}) — retrying")
            sleep(5000)
        } else {
            loggerApi.error("Failed to fetch files from Data X-Ray after ${FETCH_ATTEMPTS} attempts: ${fetchEx.message}")
            def wf = new WorkflowException("Data X-Ray file search failed: ${fetchEx.message}", fetchEx)
            wf.setTitleMessage('Search Data X-Ray failed')
            // NOTE: this exception rolls the whole task back — the query asset
            // created above does NOT survive, so tell the user the truth.
            wf.setUserMessage("Fetching matching files from Data X-Ray failed after ${FETCH_ATTEMPTS} attempts (${fetchEx.message}). Nothing was saved — this is usually a transient Data X-Ray stall; start the search again.")
            throw wf
        }
    }
}

def shown = result.shown
int total = result.total
boolean zipCapped = result.zipCapped
def zipBytes = result.zipBytes
def totalDisplay = total.toString()

loggerApi.info("Data X-Ray returned ${total} file(s); ZIP is ${zipBytes.length} byte(s)${zipCapped ? ' (capped at 25 MB)' : ''}, preview shows ${shown.size()}")

// --- Attach the full results as a ZIP'd CSV to the search-query asset ---------

// Filename derived from the search name; keep only filesystem-safe characters.
def safeName = conditionName.replaceAll('[^A-Za-z0-9._-]+', '_').replaceAll('^_+|_+$', '')
if (safeName.isEmpty()) safeName = 'search'
def attachmentName = "${safeName}-results.zip".toString()

if (total > 0) {
    try {
        attachmentApi.addAttachment(AddAttachmentRequest.builder()
            .baseResourceId(queryId)
            .baseResourceDiscriminator('Asset')
            .fileName(attachmentName)
            .fileStream(new ByteArrayInputStream(zipBytes))
            .build())
        loggerApi.info("Attached ${attachmentName} to asset ${queryId}")
    } catch (Exception attachEx) {
        // Best-effort: the asset and inline preview still stand if the attach fails.
        loggerApi.warn("Failed to attach results ZIP to asset ${queryId}: ${attachEx.message}")
        attachmentName = ''
    }
} else {
    attachmentName = ''
}

// --- Store the first maxResults matches as an HTML preview table --------------

if (shown.isEmpty()) {
    addAttribute(queryId, filesAttrTypeId, 'Query returned 0 results')
} else {
    def moreNote = total > shown.size()
        ? " The full result set (${totalDisplay} file(s)${zipCapped ? ', capped at 25 MB' : ''}) is attached to this asset as ${attachmentName ?: 'a ZIP'}."
        : ''
    def writer = new StringWriter()
    def html = new MarkupBuilder(writer)
    html.div {
        p("Preview — first ${shown.size()} of ${totalDisplay} matching file(s).${moreNote}".toString())
        table {
            thead { tr { th('Datasource'); th('Path') } }
            tbody {
                shown.each { f ->
                    tr { td(dataSourceName(f)); td(filePath(f)) }
                }
            }
        }
    }
    addAttribute(queryId, filesAttrTypeId, writer.toString())
}

// --- Import feasibility (instance-wide cap) ----------------------------------

// The cap applies to the TOTAL population of the Data X-Ray Files domain, not
// to this result set alone. This is a fresh query asset with no imported files
// yet, so the projection is simply current domain population + result count —
// conservative when results overlap files another query already imported
// (those would be updates, not new assets).
int filesDomainCount = 0
try {
    filesDomainCount = countAssetsInDomain(filesDomainId)
} catch (Exception countEx) {
    loggerApi.warn("Could not count assets in the Data X-Ray Files domain: ${countEx.message}")
}
int projectedTotal = filesDomainCount + total

boolean importAllowed = total > 0 && projectedTotal <= MAX_TOTAL_FILE_ASSETS
boolean importWarn    = importAllowed && total > WARN_IMPORT_FILES
boolean importBlocked = total > 0 && !importAllowed
def importBlockedMessage = importBlocked
    ? "Importing is disabled for this search: the Data X-Ray Files domain holds ${filesDomainCount} file asset(s) and this search matched ${totalDisplay} file(s) — the projected ${projectedTotal} would exceed the ${MAX_TOTAL_FILE_ASSETS} instance-wide limit. Narrow the query, or retire imported searches you no longer need."
    : ''

// --- Publish process variables for the results form -------------------------

execution.setVariable('conditionName', conditionName)
execution.setVariable('queryString', queryString ?: '(all files)')
execution.setVariable('searchUrl', searchUrl)
execution.setVariable('resultCount', total)
execution.setVariable('resultCountDisplay', totalDisplay)
execution.setVariable('shownCount', shown.size())
execution.setVariable('attachmentName', attachmentName)
execution.setVariable('zipCapped', zipCapped)
execution.setVariable('queryAssetId', queryId.toString())
execution.setVariable('importAllowed', importAllowed)
execution.setVariable('importWarn', importWarn)
execution.setVariable('importBlocked', importBlocked)
execution.setVariable('importBlockedMessage', importBlockedMessage)
execution.setVariable('filesDomainCount', filesDomainCount)
// Defaults for the results form's decision fields; completing the form
// overwrites them.
execution.setVariable('importDecision', false)
execution.setVariable('keepInSync', false)

loggerApi.info("Search Data X-Ray complete: asset=${queryId}, total=${totalDisplay}, shown=${shown.size()}, attachment=${attachmentName ?: '(none)'}, importAllowed=${importAllowed} (files domain holds ${filesDomainCount})")

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

// Build a nested annotator clause that ties a "contains" phrase match to the
// picked annotators:  annotators: { (name:"A" OR name:"B") AND annotations.phrase:"*text*" }
// With no names, scopes to all annotators: annotators: { annotations.phrase:"*text*" }
def buildAnnotatorPhraseClause(List names, String phrase) {
    def present = names.findAll { it != null && !it.toString().trim().isEmpty() }
    def phraseTerm = "annotations.phrase:\"*${phrase}*\""
    def inner
    if (present.isEmpty()) {
        inner = phraseTerm
    } else {
        def nameTerms = present.collect { "name:\"${it}\"".toString() }
        def nameClause = nameTerms.size() == 1 ? nameTerms[0] : "(${nameTerms.join(' OR ')})"
        inner = "${nameClause} AND ${phraseTerm}"
    }
    return "annotators: { ${inner} }".toString()
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

// Exact population of a domain, counted by cursor-paging. findAssets' total
// field is unreliable on cursor pages, so counting the pages is the safe way;
// at the 25k cap this is ~25 fast calls.
def countAssetsInDomain(UUID domainId) {
    int count = 0
    def cursor = ''
    while (true) {
        def page = assetApi.findAssets(com.collibra.dgc.core.api.dto.instance.asset.FindAssetsRequest.builder()
            .domainId(domainId)
            .limit(1000)
            .cursor(cursor)
            .build())
        count += page.getResults().size()
        cursor = page.getNextCursor()
        if (!cursor) break
    }
    return count
}

// Escape one CSV field per RFC 4180: quote it when it contains a comma, quote,
// CR or LF, doubling any embedded quotes.
def csvEscape(value) {
    def s = (value ?: '').toString()
    if (s.indexOf('"') >= 0 || s.indexOf(',') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
        return '"' + s.replace('"', '""') + '"'
    }
    return s
}

// GET the Data X-Ray files endpoint and stream the response. The endpoint
// returns newline-delimited JSON (one file object per line), which can be very
// large, so we never buffer the whole body: Jackson's MappingIterator reads one
// value at a time off the input stream. Each row is written to a CSV that is
// compressed on the fly into a single-entry ZIP (results.csv) held in a
// ByteArrayOutputStream — so only *compressed* bytes accumulate in memory. The
// first `keepLimit` rows are also retained for the inline preview table.
//
// The ZIP stops growing once its compressed size reaches `maxZipBytes` (we stop
// a headroom margin early so the final flush + central directory stay under the
// cap), but every row is still counted, so `total` is always exact.
//
// Returns [shown: List (≤ keepLimit), total: int, zipCapped: boolean, zipBytes: byte[]].
def fetchAndArchive(String url, String authToken, int keepLimit, int maxZipBytes) {
    def conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('GET')
    conn.setRequestProperty('Authorization', "Bearer ${authToken}")
    conn.setRequestProperty('Content-Type', 'application/json')
    // Read timeout is BETWEEN bytes, not total: a healthy stream can run for
    // minutes, but a 2-minute silence means the server has stalled — fail fast
    // so the retry loop gets its chance instead of hanging the task.
    conn.setConnectTimeout(30_000)
    conn.setReadTimeout(120_000)

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

    // Stop the CSV a little before the hard cap so the deflater's final flush and
    // the ZIP central directory (written on close) cannot push it over 25 MB.
    def stopAt = Math.max(0, maxZipBytes - 256 * 1024)

    def shown = []
    int total = 0
    boolean zipCapped = false

    def baos = new ByteArrayOutputStream()
    def zos = new ZipOutputStream(baos)
    zos.putNextEntry(new ZipEntry('results.csv'))
    def csv = new OutputStreamWriter(zos, StandardCharsets.UTF_8)
    csv.write('Datasource,Path\r\n')

    def input = conn.getInputStream()
    try {
        def reader = new ObjectMapper().readerFor(Map).readValues(input)
        while (reader.hasNextValue()) {
            def f = reader.nextValue()
            if (total < keepLimit) {
                shown << f
            }
            if (!zipCapped) {
                csv.write(csvEscape(dataSourceName(f)) + ',' + csvEscape(filePath(f)) + '\r\n')
                csv.flush()
                if (baos.size() >= stopAt) {
                    zipCapped = true
                }
            }
            total++
        }
    } catch (Exception parseEx) {
        throw new RuntimeException("Data X-Ray API response could not be parsed as JSON: ${parseEx.message}")
    } finally {
        try { input.close() } catch (Exception ignored) { /* best-effort */ }
        conn.disconnect()
    }

    try {
        csv.flush()
        zos.closeEntry()
        zos.close()
    } catch (Exception ignored) {
        // best-effort: baos already holds the compressed bytes written so far
    }

    return [shown: shown, total: total, zipCapped: zipCapped, zipBytes: baos.toByteArray()]
}

def truncate(String s, int max) {
    if (s == null) return ''
    return s.length() <= max ? s : s.substring(0, max) + '…'
}
