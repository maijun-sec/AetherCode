package org.aethercode.workflows.engine;

import org.aethercode.workflows.ValidationError;

import java.util.List;
import java.util.Objects;

/**
 * Thrown by {@link WorkflowService} when a workflow can't be
 * loaded, validated, or run. The {@link #code()} field is a
 * stable enum so the supervisor-side
 * {@code DefaultWorkflowHandlers} can translate failures into
 * a JSON-RPC {@code {ok: false, error: {name, code, message}}}
 * envelope without parsing free-form messages.
 *
 * <p>{@link #validationErrors()} is only populated for the
 * {@link Code#VALIDATION_FAILED} case; it's empty (not null)
 * for every other code.
 */
public class WorkflowServiceException extends RuntimeException {

    private final Code code;
    private final List<ValidationError> validationErrors;

    public WorkflowServiceException(Code code, String message) {
        this(code, message, null, null);
    }

    public WorkflowServiceException(Code code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public WorkflowServiceException(Code code, String message,
                                    List<ValidationError> validationErrors) {
        this(code, message, validationErrors, null);
    }

    public WorkflowServiceException(Code code, String message,
                                    List<ValidationError> validationErrors,
                                    Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
        this.validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }

    public Code code() { return code; }

    public List<ValidationError> validationErrors() { return validationErrors; }

    // -- static factories -----------------------------------------------

    public static WorkflowServiceException notFound(String name) {
        return new WorkflowServiceException(Code.NOT_FOUND, "workflow '" + name + "' not found");
    }

    public static WorkflowServiceException notFound(String message, Throwable cause) {
        return new WorkflowServiceException(Code.NOT_FOUND, message, cause);
    }

    public static WorkflowServiceException badYaml(String message) {
        return new WorkflowServiceException(Code.BAD_YAML, message);
    }

    public static WorkflowServiceException badYaml(String message, Throwable cause) {
        return new WorkflowServiceException(Code.BAD_YAML, message, cause);
    }

    public static WorkflowServiceException validationFailed(String message,
                                                            List<ValidationError> errs) {
        return new WorkflowServiceException(Code.VALIDATION_FAILED, message, errs);
    }

    public static WorkflowServiceException ioError(String message, Throwable cause) {
        return new WorkflowServiceException(Code.IO_ERROR, message, cause);
    }

    public static WorkflowServiceException invalidName(String name, Throwable cause) {
        return new WorkflowServiceException(Code.INVALID_NAME,
                "invalid workflow name '" + name + "'", cause);
    }

    /** Stable error categories. The integer values map 1:1
     *  to the JSON-RPC codes in {@code DefaultWorkflowHandlers}. */
    public enum Code {
        NOT_FOUND(-32101),
        BAD_YAML(-32102),
        VALIDATION_FAILED(-32103),
        IO_ERROR(-32104),
        INVALID_NAME(-32105);

        private final int rpcCode;

        Code(int rpcCode) { this.rpcCode = rpcCode; }

        public int rpcCode() { return rpcCode; }
    }
}
