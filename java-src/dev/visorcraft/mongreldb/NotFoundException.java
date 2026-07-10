package dev.visorcraft.mongreldb;

/** Raised for HTTP 404 responses - a missing table, schema, or resource. */
public class NotFoundException extends MongrelDBException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String message, int status, String code, Integer opIndex) {
        super(message, status, code, opIndex);
    }
}
