package dev.visorcraft.mongreldb;

/** Base class for all errors raised by the MongrelDB Clojure client.
 *
 *  <p>Every non-2xx response from the daemon is mapped to a typed subclass.
 *  Each carries the HTTP status, the server's structured error code (e.g.
 *  {@code UNIQUE_VIOLATION}), and the offending op index within a transaction. */
public class MongrelDBException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final Integer opIndex;

    public MongrelDBException(String message) {
        this(message, -1, null, null, null);
    }

    public MongrelDBException(String message, Throwable cause) {
        this(message, -1, null, null, cause);
    }

    public MongrelDBException(String message, int status, String code, Integer opIndex) {
        this(message, status, code, opIndex, null);
    }

    public MongrelDBException(String message, int status, String code, Integer opIndex, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.opIndex = opIndex;
    }

    /** The HTTP status code returned by the daemon, or -1 when unknown. */
    public int status() {
        return status;
    }

    /** The server's structured error code, or null when absent. */
    public String code() {
        return code;
    }

    /** The offending op index within a batch, or null when not reported. */
    public Integer opIndex() {
        return opIndex;
    }
}
