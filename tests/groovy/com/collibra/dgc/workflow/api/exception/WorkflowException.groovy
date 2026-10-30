package com.collibra.dgc.workflow.api.exception

// Hand-written fake of Collibra's WorkflowException (workflow API 32.1.43):
// a RuntimeException carrying the title and message Collibra shows the user.
class WorkflowException extends RuntimeException {
    String titleMessage
    String userMessage

    WorkflowException(String message) { super(message) }
    WorkflowException(Throwable cause) { super(cause) }
    WorkflowException(String message, Throwable cause) { super(message, cause) }
}
