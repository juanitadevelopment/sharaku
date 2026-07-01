package net.teppan.shazo.http.internal;

/**
 * Wire-protocol constants shared between the server-side handler and the
 * client-side adapter.
 */
public final class Protocol {

    /** Verify whether an entity exists. */
    public static final byte OP_CONTAINS = 1;
    /** Persist an entity. */
    public static final byte OP_STORE    = 2;
    /** Remove an entity. */
    public static final byte OP_DELETE   = 3;
    /** Retrieve a single entity (lenient: first match or not-found). */
    public static final byte OP_RETRIEVE = 4;
    /** Gather all matching entities into typed objects. */
    public static final byte OP_GATHER   = 5;
    /** Find the unique matching entity (strict: not-found / multiple-found). */
    public static final byte OP_FIND     = 6;
    /** Catalog the matching rows as a raw table ({@code RawResult}). */
    public static final byte OP_CATALOG  = 7;

    /** The operation completed without error. */
    public static final byte STATUS_OK             = 0;
    /** The requested entity was not found (retrieve / find). */
    public static final byte STATUS_NOT_FOUND      = 1;
    /** The operation failed; payload is a UTF-8 error message. */
    public static final byte STATUS_EXCEPTION      = 2;
    /** {@code find} matched more than one entity; payload is int32 match count. */
    public static final byte STATUS_MULTIPLE_FOUND = 3;

    private Protocol() {}
}
