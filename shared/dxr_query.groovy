// dxr_query.groovy — composing the Data X-Ray files query from search criteria.
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// Every selected label / extractor / annotator becomes its own clause and ALL
// clauses are AND-ed: a file must carry every one of them. The only OR is inside
// the annotated-text clause, between the annotators explicitly picked for the
// phrase (see buildAnnotatorPhraseClause). Field names are the ones the
// Data X-Ray files API expects: labels.name, annotators.name, and
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
// Semantics proven against a live instance by tools/verify_query_semantics.py.

// Compose the full query string ('' means "all files").
def composeDxrQuery(List labelNames, List extractorNames, List annotatorNames, String filter, List filterAnnotatorNames) {
    def clauses = []
    appendNameClause(clauses, 'labels.name', labelNames)
    appendNameClause(clauses, 'extractedMetadata.name', extractorNames)
    appendNameClause(clauses, 'annotators.name', annotatorNames)
    def phrase = (filter ?: '').toString().trim()
    if (!phrase.isEmpty()) {
        clauses << buildAnnotatorPhraseClause(filterAnnotatorNames ?: [], phrase)
    }
    return clauses.join(' AND ')
}

// Escape a value for use inside a double-quoted query term: backslashes and
// double quotes would otherwise terminate the term (Data X-Ray answers HTTP 400
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
