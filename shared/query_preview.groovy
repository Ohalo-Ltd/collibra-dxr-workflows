// query_preview.groovy — the query asset's "Files" preview attribute (both editions).
//
// SHARED FILE (function-only; see dxr_model.groovy for the include rules).
//
// Search writes the preview once, on the new query asset. A rerun replaces it
// with what that run found, so the attribute never contradicts the query's
// `returns` relations (a search saved with 0 results kept saying "Query returned
// 0 results" after reruns imported its files — ohalo.collibra.com, 28 Oct 2026).

// {{include:dxr_rows.groovy}}

// Rows a rerun keeps for the preview (the on-prem search shows 50 as well).
def dxrPreviewMaxRows() { return 50 }

// The "Files" attribute on the query asset: an HTML preview table of the first
// matches (or a plain sentence for an empty result).
def renderPreviewHtml(List shown, String totalDisplay, String moreNote) {
    if (shown.isEmpty()) {
        return 'Query returned 0 results' + (moreNote ? ".${moreNote}" : '')
    }
    def writer = new StringWriter()
    def html = new groovy.xml.MarkupBuilder(writer)
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
    return writer.toString()
}

// Preview rows for renderPreviewHtml(), which expects /api/v1/files-shaped maps.
def tuplesAsPreviewRows(List tuples) {
    return tuples.collect { t -> [datasource: [name: t[1]], path: t[2]] }
}

// Rerun: replace the preview with this run's first rows and total. Only call it
// for a run that read DataXray's complete answer — an aborted or partial run
// leaves the previous preview standing. The results file attached by the
// search is not rebuilt, so the note says how old it is.
def refreshQueryPreview(UUID queryId, UUID filesAttrTypeId, List tuples, int total) {
    def shown = tuplesAsPreviewRows(tuples.take(dxrPreviewMaxRows()))
    def when = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH)
        .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now())
    def note = " Refreshed by a rerun on ${when}; a results file attached to this asset is from when the search was created.".toString()
    try {
        assetApi.setAssetAttributes(com.collibra.dgc.core.api.dto.instance.asset.SetAssetAttributesRequest.builder()
            .assetId(queryId)
            .typeId(filesAttrTypeId)
            .values([renderPreviewHtml(shown, total.toString(), note)] as List<Object>)
            .build())
    } catch (Exception attrEx) {
        loggerApi.warn("Could not refresh the Files preview on ${queryId}: ${attrEx.message}")
    }
}
