package com.cardswitch.iso8583;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Packs and unpacks ISO 8583 messages.
 * <p>
 * Wire layout: 4 ASCII MTI digits, an 8-byte binary primary bitmap, an 8-byte secondary bitmap when bit 1 is set,
 * then each present data element in ascending order, ASCII encoded. Variable-length elements carry a 2 (LLVAR) or
 * 3 (LLLVAR) digit ASCII length prefix.
 * <p>
 * Instances are immutable and thread-safe.
 */
public final class IsoCodec {

    private static final int MTI_LENGTH = 4;
    private static final int BITMAP_LENGTH = 8;

    private final IsoSpec spec;

    public IsoCodec(IsoSpec spec) {
        this.spec = spec;
    }

    public IsoSpec spec() {
        return spec;
    }

    public byte[] pack(IsoMessage message) {
        byte[] bitmap = new byte[BITMAP_LENGTH * 2];
        boolean secondary = false;
        for (int field = 2; field <= IsoMessage.MAX_FIELD; field++) {
            if (message.has(field)) {
                if (spec.field(field) == null) {
                    throw new IsoFormatException("Field " + field + " is not defined in the spec");
                }
                setBit(bitmap, field);
                secondary |= field > 64;
            }
        }
        if (secondary) {
            setBit(bitmap, 1);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.writeBytes(message.mti().getBytes(StandardCharsets.US_ASCII));
        out.write(bitmap, 0, secondary ? BITMAP_LENGTH * 2 : BITMAP_LENGTH);
        for (int field = 2; field <= IsoMessage.MAX_FIELD; field++) {
            String value = message.get(field);
            if (value != null) {
                writeField(out, spec.field(field), value);
            }
        }
        return out.toByteArray();
    }

    public IsoMessage unpack(byte[] data) {
        return unpack(data, 0, data.length);
    }

    public IsoMessage unpack(byte[] data, int offset, int length) {
        Reader reader = new Reader(data, offset, length);
        String mti = reader.ascii(MTI_LENGTH, "MTI");
        if (!Mti.isWellFormed(mti)) {
            throw new IsoFormatException("MTI is not 4 digits");
        }
        IsoMessage message = new IsoMessage(mti);

        byte[] bitmap = new byte[BITMAP_LENGTH * 2];
        reader.bytes(bitmap, 0, BITMAP_LENGTH, "primary bitmap");
        boolean secondary = isSet(bitmap, 1);
        if (secondary) {
            reader.bytes(bitmap, BITMAP_LENGTH, BITMAP_LENGTH, "secondary bitmap");
        }

        int lastField = secondary ? 128 : 64;
        for (int field = 2; field <= lastField; field++) {
            if (!isSet(bitmap, field)) {
                continue;
            }
            FieldDefinition def = spec.field(field);
            if (def == null) {
                throw new IsoFormatException("Field " + field + " is present but not defined in the spec");
            }
            int fieldLength = def.lengthType() == LengthType.FIXED
                    ? def.length()
                    : reader.digits(def.lengthType().prefixDigits(), "length of field " + field);
            if (fieldLength > def.length()) {
                throw new IsoFormatException("Field " + field + " length " + fieldLength + " exceeds maximum " + def.length());
            }
            String value = reader.ascii(fieldLength, "field " + field);
            checkCharacters(def, value);
            message.set(field, value);
        }

        if (reader.remaining() > 0) {
            throw new IsoFormatException(reader.remaining() + " unexpected trailing bytes");
        }
        return message;
    }

    private static void writeField(ByteArrayOutputStream out, FieldDefinition def, String value) {
        checkCharacters(def, value);
        if (value.length() > def.length()) {
            throw new IsoFormatException("Field " + def.number() + " length " + value.length()
                    + " exceeds " + (def.lengthType() == LengthType.FIXED ? "fixed length " : "maximum ") + def.length());
        }
        String encoded;
        if (def.lengthType() == LengthType.FIXED) {
            encoded = pad(value, def);
        } else {
            String prefix = String.valueOf(value.length());
            encoded = "0".repeat(def.lengthType().prefixDigits() - prefix.length()) + prefix + value;
        }
        out.writeBytes(encoded.getBytes(StandardCharsets.US_ASCII));
    }

    private static String pad(String value, FieldDefinition def) {
        int missing = def.length() - value.length();
        if (missing == 0) {
            return value;
        }
        String padding = String.valueOf(def.type().padChar()).repeat(missing);
        return def.type().padsLeft() ? padding + value : value + padding;
    }

    private static void checkCharacters(FieldDefinition def, String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!def.type().accepts(value.charAt(i))) {
                throw new IsoFormatException("Field " + def.number() + " contains a character not allowed for type " + def.type());
            }
        }
    }

    private static void setBit(byte[] bitmap, int field) {
        int index = field - 1;
        bitmap[index / 8] |= (byte) (0x80 >>> (index % 8));
    }

    private static boolean isSet(byte[] bitmap, int field) {
        int index = field - 1;
        return (bitmap[index / 8] & (0x80 >>> (index % 8))) != 0;
    }

    /** Bounds-checked cursor over the input buffer. */
    private static final class Reader {

        private final byte[] data;
        private final int end;
        private int position;

        Reader(byte[] data, int offset, int length) {
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IllegalArgumentException("offset/length outside of buffer");
            }
            this.data = data;
            this.position = offset;
            this.end = offset + length;
        }

        int remaining() {
            return end - position;
        }

        void bytes(byte[] target, int targetOffset, int count, String what) {
            require(count, what);
            System.arraycopy(data, position, target, targetOffset, count);
            position += count;
        }

        String ascii(int count, String what) {
            require(count, what);
            for (int i = position; i < position + count; i++) {
                if (data[i] < 0) {
                    throw new IsoFormatException(what + " contains non-ASCII bytes");
                }
            }
            String value = new String(data, position, count, StandardCharsets.US_ASCII);
            position += count;
            return value;
        }

        int digits(int count, String what) {
            require(count, what);
            int value = 0;
            for (int i = 0; i < count; i++) {
                byte b = data[position + i];
                if (b < '0' || b > '9') {
                    throw new IsoFormatException(what + " is not numeric");
                }
                value = value * 10 + (b - '0');
            }
            position += count;
            return value;
        }

        private void require(int count, String what) {
            if (remaining() < count) {
                throw new IsoFormatException("Message truncated while reading " + what);
            }
        }
    }
}
