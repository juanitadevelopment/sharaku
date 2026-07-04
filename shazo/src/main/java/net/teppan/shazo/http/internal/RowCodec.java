package net.teppan.shazo.http.internal;

import net.teppan.shazo.RawResult;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Wire (de)serialization for a {@link RawResult} — the raw table returned by
 * {@code catalog}.
 *
 * <p>Unlike domain objects, catalog rows cross the wire as a
 * <strong>typed cell format</strong> rather than Java serialization: every
 * value carries a one-byte type tag and a fixed primitive encoding, and decode
 * reconstructs only a closed set of JDBC scalar types. Arbitrary object graphs
 * are never deserialized, so a hostile server (or a corrupted response) cannot
 * drive a gadget-chain attack through {@code catalog} the way an unrestricted
 * {@code ObjectInputStream} could.
 *
 * <h2>Format</h2>
 * <pre>
 *   int32  columnCount
 *   columnCount × string   (column names, in table order)
 *   int32  rowCount
 *   rowCount × (columnCount × cell)
 *
 *   cell   = byte tag, then:
 *     NULL      → (nothing)
 *     STRING    → string
 *     BOOL      → byte (0/1)
 *     INT       → int32
 *     LONG      → int64
 *     DOUBLE    → float64
 *     DECIMAL   → string (BigDecimal.toString)
 *     BYTES     → int32 len, byte[len]
 *     TIMESTAMP → int64 millis, int32 nanos  → java.sql.Timestamp
 *     DATE      → int64 millis               → java.sql.Date
 *     TIME      → int64 millis               → java.sql.Time
 *
 *   string = int32 len, byte[len] (UTF-8)   (length-prefixed; no 64 KiB cap)
 * </pre>
 *
 * <p>Values whose runtime type is outside the supported set are encoded as
 * their {@code toString()} under the {@code STRING} tag — safe, but lossy;
 * describers that need exact types over HTTP should catalog those columns as a
 * supported scalar.
 */
public final class RowCodec {

    private static final byte NULL      = 0;
    private static final byte STRING    = 1;
    private static final byte BOOL      = 2;
    private static final byte INT       = 3;
    private static final byte LONG      = 4;
    private static final byte DOUBLE    = 5;
    private static final byte DECIMAL   = 6;
    private static final byte BYTES     = 7;
    private static final byte TIMESTAMP = 8;
    private static final byte DATE      = 9;
    private static final byte TIME      = 10;

    private RowCodec() {}

    /** Writes {@code result} to {@code out} in the typed table format above. */
    public static void write(DataOutputStream out, RawResult result) throws IOException {
        // Column order is the union of keys across rows, first-seen order preserved.
        var columns = new LinkedHashSet<String>();
        for (Map<String, Object> row : result.rows()) columns.addAll(row.keySet());

        out.writeInt(columns.size());
        for (String col : columns) writeString(out, col);

        out.writeInt(result.size());
        for (Map<String, Object> row : result.rows()) {
            for (String col : columns) writeCell(out, row.get(col));
        }
    }

    /** Reads a {@link RawResult} previously written by {@link #write}. */
    public static RawResult read(DataInputStream in) throws IOException {
        int nCols = Frames.readBounded(in, "columnCount");
        var columns = new ArrayList<String>(nCols);
        for (int c = 0; c < nCols; c++) columns.add(readString(in));

        int nRows = Frames.readBounded(in, "rowCount");
        var rows = new ArrayList<Map<String, Object>>(nRows);
        for (int r = 0; r < nRows; r++) {
            var row = new LinkedHashMap<String, Object>();
            for (int c = 0; c < nCols; c++) row.put(columns.get(c), readCell(in));
            rows.add(row);
        }
        return RawResult.of(rows);
    }

    // ── cells ──────────────────────────────────────────────────────────────────

    private static void writeCell(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case null              -> out.writeByte(NULL);
            case String s          -> { out.writeByte(STRING); writeString(out, s); }
            case Boolean b         -> { out.writeByte(BOOL); out.writeByte(b ? 1 : 0); }
            case Byte n            -> { out.writeByte(INT); out.writeInt(n); }
            case Short n           -> { out.writeByte(INT); out.writeInt(n); }
            case Integer n         -> { out.writeByte(INT); out.writeInt(n); }
            case Long n            -> { out.writeByte(LONG); out.writeLong(n); }
            case Float n           -> { out.writeByte(DOUBLE); out.writeDouble(n); }
            case Double n          -> { out.writeByte(DOUBLE); out.writeDouble(n); }
            case BigDecimal d      -> { out.writeByte(DECIMAL); writeString(out, d.toString()); }
            case byte[] bytes      -> { out.writeByte(BYTES); out.writeInt(bytes.length); out.write(bytes); }
            case java.sql.Timestamp t -> { out.writeByte(TIMESTAMP); out.writeLong(t.getTime()); out.writeInt(t.getNanos()); }
            case java.sql.Date d   -> { out.writeByte(DATE); out.writeLong(d.getTime()); }
            case java.sql.Time t   -> { out.writeByte(TIME); out.writeLong(t.getTime()); }
            default                -> { out.writeByte(STRING); writeString(out, v.toString()); }
        }
    }

    private static Object readCell(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case NULL      -> null;
            case STRING    -> readString(in);
            case BOOL      -> in.readByte() != 0;
            case INT       -> in.readInt();
            case LONG      -> in.readLong();
            case DOUBLE    -> in.readDouble();
            case DECIMAL   -> new BigDecimal(readString(in));
            case BYTES     -> in.readNBytes(Frames.readBounded(in, "bytesLength"));
            case TIMESTAMP -> { var t = new java.sql.Timestamp(in.readLong()); t.setNanos(in.readInt()); yield t; }
            case DATE      -> new java.sql.Date(in.readLong());
            case TIME      -> new java.sql.Time(in.readLong());
            default        -> throw new IOException("Unknown cell type tag: " + tag);
        };
    }

    // ── length-prefixed UTF-8 strings (no DataOutput.writeUTF 64 KiB limit) ──────

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(in.readNBytes(Frames.readBounded(in, "stringLength")), StandardCharsets.UTF_8);
    }
}
