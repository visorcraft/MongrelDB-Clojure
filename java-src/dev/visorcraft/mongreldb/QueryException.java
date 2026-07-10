package dev.visorcraft.mongreldb;

/** Raised for HTTP 400 or 5xx responses, and for any other request-level
 *  failure not covered by {@link AuthException}, {@link NotFoundException}, or
 *  {@link ConflictException}. */
public class QueryException extends MongrelDBException {
    private static final long serialVersionUID = 1L;

    public QueryException(String message, int status, String code, Integer opIndex) {
        super(message, status, code, opIndex);
    }

    public QueryException(String message, Throwable cause) {
        super(message, cause);
    }

    public QueryException(String message) {
        super(message);
    }
}
