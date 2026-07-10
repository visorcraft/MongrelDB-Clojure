package dev.visorcraft.mongreldb;

/** Raised for HTTP 409 responses - a unique, foreign-key, check, or trigger
 *  constraint violation. Carries the structured {@link #code() code} and the
 *  offending {@link #opIndex() opIndex} within the batch. */
public class ConflictException extends MongrelDBException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message, int status, String code, Integer opIndex) {
        super(message, status, code, opIndex);
    }
}
