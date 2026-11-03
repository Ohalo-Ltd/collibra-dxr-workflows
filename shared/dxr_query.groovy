// dxr_query.groovy — composing the DataXray files query from search criteria.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// Every selected label / extractor / annotator becomes its own clause and ALL
// clauses are AND-ed: a file must carry every one of them. The only OR is inside
// the annotated-text clause, between the annotators explicitly picked for the
// phrase (see buildAnnotatorPhraseClause). Field names are the ones the
// DataXray files API expects: labels.name, annotators.name, and
// extractedMetadata.name (extractor results are exposed on file rows as
// extractedMetadata entries — verified against a live instance; a query on
// extractors.name matches nothing).
//
// The phrase filter, if given, is a "contains" match (wildcards; case-insensitive
// in the API) scoped via the nested object syntax to the annotators picked in
// the separate "Annotated text annotators" field — or to all annotators when
// that field is empty. It is one more AND clause alongside the criteria, e.g.
//   extractedMetadata.name:"E" AND annotators.name:"A" AND annotators.name:"B"
//   AND annotators: { (name:"A" OR name:"C") AND annotations.phrase:"*text*" }
//
// v2.12 adds the "Extracted value" clause (buildExtractedValueClause below):
//   extractedMetadata: { (name:"E" OR name:"F") AND value:"yes" }
//
// Semantics proven against a live instance by tools/verify_query_semantics.py.

// Compose the full query string ('' means "all files"). valueFilter (v2.12):
// the "Extracted value" text, already checked by parseValueFilter; with it, one
// more AND clause scoped to valueFilterExtractorNames (all extractors if empty).
def composeDxrQuery(List labelNames, List extractorNames, List annotatorNames, String filter, List filterAnnotatorNames,
                    String valueFilter = null, List valueFilterExtractorNames = null) {
    def clauses = []
    appendNameClause(clauses, 'labels.name', labelNames)
    appendNameClause(clauses, 'extractedMetadata.name', extractorNames)
    appendNameClause(clauses, 'annotators.name', annotatorNames)
    def phrase = (filter ?: '').toString().trim()
    if (!phrase.isEmpty()) {
        clauses << buildAnnotatorPhraseClause(filterAnnotatorNames ?: [], phrase)
    }
    def spec = parseValueFilter(valueFilter)
    if (spec.ok && !spec.empty) {
        clauses << buildExtractedValueClause(valueFilterExtractorNames ?: [], spec)
    }
    return clauses.join(' AND ')
}

// --- Extracted value (v2.12) ------------------------------------------------------
//
// The "Extracted value" search field matches the VALUE an extractor produced,
// not just that it ran (the Extractors criterion). Semantics, the same in both
// editions (verified on a live DataXray, Nov 2026):
//   – the whole value, ignoring case: "yes" finds "Yes", not "Yes, partly";
//   – `*` only at the start and/or end: "*inv*" contains, "inv*" starts with,
//     "*inv" ends with. An inner `*`, a `?`, or only `*`s are refused: the two
//     DataXray APIs read them differently (KQL: regex-style, `?` = 0 or 1 char;
//     the internal search: Elasticsearch wildcard, `?` = exactly 1 char).
//   – `true`/`false` and plain numbers also match boolean / number extractors.
// Values longer than DataXray indexes for search (256–1,024 characters in the
// Cloud + Edge edition) cannot be matched; the help text says so.
//
// Returns [ok:, error:, empty:, value: (as typed, trimmed), core: (no outer *),
//          strategy: exact|contains|startsWith|endsWith, literal: text|boolean|number]
def parseValueFilter(Object raw) {
    def v = (raw ?: '').toString().trim()
    if (v.isEmpty()) { return [ok: true, empty: true, value: ''] }
    if (v.contains('?')) {
        return [ok: false, error: "The extracted value '${v}' contains '?'. Only * is supported, at the start and/or end of the value.".toString()]
    }
    boolean lead = v.startsWith('*')
    boolean trail = v.endsWith('*')
    def core = v.replaceAll(/^\*+/, '').replaceAll(/\*+$/, '')
    if (core.isEmpty()) {
        return [ok: false, error: "An extracted value of only '*' matches every value: pick the extractor under Extractors instead.".toString()]
    }
    if (core.contains('*')) {
        return [ok: false, error: "The extracted value '${v}' has a * in the middle. Use * only at the start and/or end of the value.".toString()]
    }
    def strategy = (lead && trail) ? 'contains' : trail ? 'startsWith' : lead ? 'endsWith' : 'exact'
    def literal = 'text'
    if (strategy == 'exact') {
        if (core ==~ /(?i)true|false/) { literal = 'boolean' }
        else if (core ==~ /-?\d+(\.\d+)?/) { literal = 'number' }
    }
    return [ok: true, empty: false, value: v, core: core, strategy: strategy, literal: literal]
}

// extractedMetadata: { (name:"A" OR name:"B") AND value:"<v>" } — name and value
// on the SAME extractor entry (KQL evaluates the nested block per entry). KQL
// compares strings only with string values, so `true`/`false` and numbers also
// try the unquoted form, which matches boolean/number extractors.
def buildExtractedValueClause(List names, Map spec) {
    def present = (names ?: []).findAll { it != null && !it.toString().trim().isEmpty() }
    def valueTerm = quoteTerm('value', spec.value)
    if (spec.literal == 'boolean') {
        valueTerm = "(${valueTerm} OR value:${spec.core.toLowerCase()})"
    } else if (spec.literal == 'number') {
        valueTerm = "(${valueTerm} OR value:${spec.core})"
    }
    def inner
    if (present.isEmpty()) {
        inner = valueTerm
    } else {
        def nameTerms = present.collect { quoteTerm('name', it) }
        def nameClause = nameTerms.size() == 1 ? nameTerms[0] : "(${nameTerms.join(' OR ')})"
        inner = "${nameClause} AND ${valueTerm}"
    }
    return "extractedMetadata: { ${inner} }".toString()
}

// Escape a value for use inside a double-quoted query term: backslashes and
// double quotes would otherwise terminate the term (DataXray answers HTTP 400
// for an unbalanced quote; "\"" and "\\" are accepted — verified live).
def quoteTerm(String field, Object value) {
    def escaped = value.toString().replace('\\', '\\\\').replace('"', '\\"')
    return "${field}:\"${escaped}\"".toString()
}

// Append one "field:\"value\"" term per selected value. Every term is a separate
// top-level clause, so the final `clauses.join(' AND ')` requires ALL of them.
def appendNameClause(List clauses, String field, List names) {
    def present = (names ?: []).findAll { it != null && !it.toString().trim().isEmpty() }
    present.each { clauses << quoteTerm(field, it) }
}

// Build the nested annotator clause that ties a "contains" phrase match to the
// annotators picked for the annotated-text filter. These are deliberately OR-ed:
// the phrase must appear in ANY one of them —
//   annotators: { (name:"A" OR name:"C") AND annotations.phrase:"*text*" }
// The nested block keeps phrase and annotator identity on the SAME annotator.
// With no names, scopes to all annotators: annotators: { annotations.phrase:"*text*" }
// (The Annotators criterion itself is AND-ed via appendNameClause, independently.)
def buildAnnotatorPhraseClause(List names, String phrase) {
    def present = (names ?: []).findAll { it != null && !it.toString().trim().isEmpty() }
    def phraseTerm = quoteTerm('annotations.phrase', "*${phrase}*")
    def inner
    if (present.isEmpty()) {
        inner = phraseTerm
    } else {
        def nameTerms = present.collect { quoteTerm('name', it) }
        def nameClause = nameTerms.size() == 1 ? nameTerms[0] : "(${nameTerms.join(' OR ')})"
        inner = "${nameClause} AND ${phraseTerm}"
    }
    return "annotators: { ${inner} }".toString()
}

// The query as stored/displayed: '' is shown as "(all files)".
def displayQuery(String queryString) {
    return (queryString == null || queryString.isEmpty()) ? '(all files)' : queryString
}
