// dxr_call_direct.groovy  (on-prem edition)
//
// ASYNC transport task: runs the Data X-Ray request the previous script armed,
// over HTTPS with the Bearer token. The Cloud + Edge edition uses a Collibra
// External API task in this position; both leave the same response variables
// (see shared/dxr_http_direct.groovy).

// {{include:dxr_http_direct.groovy}}

performArmedRequestDirect()
