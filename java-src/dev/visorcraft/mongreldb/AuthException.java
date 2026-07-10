package dev.visorcraft.mongreldb;

/** Raised for HTTP 401 or 403 responses - bad or missing credentials. */
public class AuthException extends MongrelDBException {
    private static final long serialVersionUID = 1L;

    public AuthException(String message, int status, String code, Integer opIndex) {
        super(message, status, code, opIndex);
    }
}
