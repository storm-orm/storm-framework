/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.core.spi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.InvalidCursorException;
import st.orm.Position;
import st.orm.Ref;
import st.orm.core.template.impl.PositionImpl;
import st.orm.spi.CursorCodec;
import st.orm.spi.CursorCodecEntry;
import st.orm.spi.CursorCodecProvider;

/**
 * Factory for cursor serialization and deserialization. This class is called reflectively from
 * {@code st.orm.CursorHelper} in storm-foundation.
 *
 * <p>The registry is built once from the built-in codecs plus any {@link CursorCodecProvider} implementations
 * discovered via {@link ServiceLoader}.</p>
 */
public final class CursorFactory {

    private static final int CURSOR_VERSION = 2;

    private static final byte TYPE_NULL = 0;

    private record Entry(byte tag, Class<?> type, CursorCodec<?> codec) {}

    private static final Map<Class<?>, Entry> BY_CLASS;
    private static final Map<Byte, Entry> BY_TAG;
    private static final int REGISTRY_FINGERPRINT;

    static {
        Map<Class<?>, Entry> byClass = new LinkedHashMap<>();
        Map<Byte, Entry> byTag = new LinkedHashMap<>();

        // Built-in codecs (tags 1-63 reserved).
        register(byClass, byTag, (byte) 1, String.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, String v) throws IOException { writeString(out, v); }
            @Override public String read(DataInputStream in) throws IOException { return readString(in); }
        });
        register(byClass, byTag, (byte) 2, Integer.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, Integer v) throws IOException { out.writeInt(v); }
            @Override public Integer read(DataInputStream in) throws IOException { return in.readInt(); }
        });
        register(byClass, byTag, (byte) 3, Long.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, Long v) throws IOException { out.writeLong(v); }
            @Override public Long read(DataInputStream in) throws IOException { return in.readLong(); }
        });
        register(byClass, byTag, (byte) 4, Boolean.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, Boolean v) throws IOException { out.writeBoolean(v); }
            @Override public Boolean read(DataInputStream in) throws IOException { return in.readBoolean(); }
        });
        register(byClass, byTag, (byte) 5, UUID.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, UUID v) throws IOException {
                out.writeLong(v.getMostSignificantBits()); out.writeLong(v.getLeastSignificantBits());
            }
            @Override public UUID read(DataInputStream in) throws IOException {
                return new UUID(in.readLong(), in.readLong());
            }
        });
        register(byClass, byTag, (byte) 6, Instant.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, Instant v) throws IOException {
                out.writeLong(v.getEpochSecond()); out.writeInt(v.getNano());
            }
            @Override public Instant read(DataInputStream in) throws IOException {
                return Instant.ofEpochSecond(in.readLong(), in.readInt());
            }
        });
        register(byClass, byTag, (byte) 7, LocalDate.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, LocalDate v) throws IOException {
                out.writeInt(v.getYear()); out.writeByte(v.getMonthValue()); out.writeByte(v.getDayOfMonth());
            }
            @Override public LocalDate read(DataInputStream in) throws IOException {
                return LocalDate.of(in.readInt(), in.readUnsignedByte(), in.readUnsignedByte());
            }
        });
        register(byClass, byTag, (byte) 8, LocalDateTime.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, LocalDateTime v) throws IOException {
                out.writeInt(v.getYear()); out.writeByte(v.getMonthValue()); out.writeByte(v.getDayOfMonth());
                out.writeByte(v.getHour()); out.writeByte(v.getMinute()); out.writeByte(v.getSecond());
                out.writeInt(v.getNano());
            }
            @Override public LocalDateTime read(DataInputStream in) throws IOException {
                return LocalDateTime.of(in.readInt(), in.readUnsignedByte(), in.readUnsignedByte(),
                        in.readUnsignedByte(), in.readUnsignedByte(), in.readUnsignedByte(), in.readInt());
            }
        });
        register(byClass, byTag, (byte) 9, BigDecimal.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, BigDecimal v) throws IOException {
                writeString(out, v.toPlainString());
            }
            @Override public BigDecimal read(DataInputStream in) throws IOException {
                return new BigDecimal(readString(in));
            }
        });
        register(byClass, byTag, (byte) 10, Short.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, Short v) throws IOException { out.writeShort(v); }
            @Override public Short read(DataInputStream in) throws IOException { return in.readShort(); }
        });
        register(byClass, byTag, (byte) 11, Byte.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, Byte v) throws IOException { out.writeByte(v); }
            @Override public Byte read(DataInputStream in) throws IOException { return in.readByte(); }
        });
        register(byClass, byTag, (byte) 12, OffsetDateTime.class, new CursorCodec<>() {
            @Override public void write(DataOutputStream out, OffsetDateTime v) throws IOException {
                LocalDateTime l = v.toLocalDateTime();
                out.writeInt(l.getYear()); out.writeByte(l.getMonthValue()); out.writeByte(l.getDayOfMonth());
                out.writeByte(l.getHour()); out.writeByte(l.getMinute()); out.writeByte(l.getSecond());
                out.writeInt(l.getNano()); out.writeInt(v.getOffset().getTotalSeconds());
            }
            @Override public OffsetDateTime read(DataInputStream in) throws IOException {
                return OffsetDateTime.of(LocalDateTime.of(in.readInt(), in.readUnsignedByte(), in.readUnsignedByte(),
                        in.readUnsignedByte(), in.readUnsignedByte(), in.readUnsignedByte(), in.readInt()),
                        ZoneOffset.ofTotalSeconds(in.readInt()));
            }
        });

        // SPI-discovered codecs.
        for (CursorCodecProvider provider : ServiceLoader.load(CursorCodecProvider.class)) {
            for (CursorCodecEntry<?> entry : provider.codecs()) {
                int tag = entry.tag();
                if (tag < 64 || tag > 255) {
                    throw new IllegalArgumentException(
                            "Custom codec tags must be in range [64, 255], got: " + tag + ".");
                }
                byte byteTag = (byte) tag;
                if (byTag.containsKey(byteTag)) {
                    throw new IllegalArgumentException("Cursor codec tag " + tag + " is already registered.");
                }
                if (byClass.containsKey(entry.type())) {
                    throw new IllegalArgumentException(
                            "Cursor codec for type " + entry.type().getName() + " is already registered.");
                }
                registerEntry(byClass, byTag, byteTag, entry);
            }
        }

        BY_CLASS = Map.copyOf(byClass);
        BY_TAG = Map.copyOf(byTag);
        REGISTRY_FINGERPRINT = computeFingerprint(BY_TAG);
    }

    private CursorFactory() {}


    /**
     * Serializes a position into a Base64 URL-safe string.
     *
     * <p>The cursor carries the fingerprint of the ordering it was issued for, the registry fingerprint, whether
     * the request continues after or before the row, and the row's values, one per sort field and one for the
     * key. The window size is not part of it: the size belongs to the request.</p>
     *
     * @param orderingFingerprint the fingerprint of the key and sort fields with their directions.
     * @param position the position to serialize.
     * @return the encoded cursor string.
     */
    public static String toCursor(int orderingFingerprint, Position position) {
        var values = PositionImpl.of(position).values();
        try (var byteStream = new ByteArrayOutputStream();
             var dataStream = new DataOutputStream(byteStream)) {
            dataStream.writeByte(CURSOR_VERSION);
            dataStream.writeInt(orderingFingerprint);
            dataStream.writeInt(REGISTRY_FINGERPRINT);
            dataStream.writeBoolean(position.after());
            dataStream.writeByte(values.size());
            for (var value : values) {
                writeValue(dataStream, value);
            }
            dataStream.flush();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(byteStream.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize cursor.", e);
        }
    }

    /**
     * Deserializes a cursor string into a position.
     *
     * @param orderingFingerprint the fingerprint of the ordering the request states; the cursor must match it.
     * @param cursor the cursor string.
     * @param valueTypes the declared field types, one per sort field and one for the key; a value is checked
     *                   against its type where the type is a plain value type, and left alone where the field is
     *                   a reference, whose column carries the referenced key.
     * @return the position the cursor carries.
     * @throws InvalidCursorException if the cursor is invalid, was issued for another ordering or registry, or
     *                                carries a value of the wrong type.
     */
    public static Position fromCursor(int orderingFingerprint, String cursor, Class<?>[] valueTypes) {
        try (var byteStream = new ByteArrayInputStream(Base64.getUrlDecoder().decode(cursor));
             var dataStream = new DataInputStream(byteStream)) {
            int version = dataStream.readUnsignedByte();
            if (version != CURSOR_VERSION) {
                throw new InvalidCursorException("Unsupported cursor version: " + version + ".");
            }
            int actualOrderingFingerprint = dataStream.readInt();
            if (orderingFingerprint != actualOrderingFingerprint) {
                throw new InvalidCursorException("Cursor does not match the requested key and sort definition.");
            }
            int actualRegistryFingerprint = dataStream.readInt();
            if (REGISTRY_FINGERPRINT != actualRegistryFingerprint) {
                throw new InvalidCursorException(
                        "Cursor was produced with a different codec registry configuration.");
            }
            boolean after = dataStream.readBoolean();
            int count = dataStream.readUnsignedByte();
            if (count != valueTypes.length) {
                throw new InvalidCursorException(
                        "Invalid cursor: carries " + count + " values, the ordering has " + valueTypes.length + ".");
            }
            var values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Object value = readValue(dataStream);
                if (value == null) {
                    throw new InvalidCursorException("Invalid cursor: value " + i + " is null.");
                }
                if (isValueType(valueTypes[i])) {
                    validateType("value " + i, value, valueTypes[i]);
                }
                values.add(value);
            }
            if (dataStream.read() != -1) {
                throw new InvalidCursorException("Invalid cursor: trailing bytes found.");
            }
            return new PositionImpl(values, after);
        } catch (InvalidCursorException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new InvalidCursorException("Invalid cursor.", e);
        }
    }

    /**
     * A reference field's column carries the referenced key, so its declared type says nothing about the value.
     */
    private static boolean isValueType(Class<?> type) {
        return type != Object.class && !Data.class.isAssignableFrom(type) && !Ref.class.isAssignableFrom(type);
    }

    private static void validateType(String label, Object value, Class<?> expectedType) {
        Class<?> boxed = box(expectedType);
        // Object.class means "accept any type" (used in tests or untyped metamodels).
        if (boxed != Object.class && !boxed.isInstance(value)) {
            throw new InvalidCursorException(
                    "Invalid cursor: " + label + " has type " + value.getClass().getName()
                            + " but metamodel expects " + boxed.getName() + ".");
        }
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(DataOutputStream out, @Nullable Object value) throws IOException {
        if (value == null) {
            out.writeByte(TYPE_NULL);
            return;
        }
        Entry entry = BY_CLASS.get(value.getClass());
        if (entry == null) {
            throw new IllegalStateException(
                    "Unsupported cursor value type: " + value.getClass().getName()
                            + ". Register a CursorCodec via CursorCodecProvider SPI.");
        }
        out.writeByte(entry.tag);
        ((CursorCodec<Object>) entry.codec).write(out, value);
    }

    @Nullable
    private static Object readValue(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        if (tag == TYPE_NULL) {
            return null;
        }
        Entry entry = BY_TAG.get((byte) tag);
        if (entry == null) {
            throw new IOException("Unknown cursor value type tag: " + tag + ".");
        }
        return entry.codec.read(in);
    }

    private static <T> void register(Map<Class<?>, Entry> byClass, Map<Byte, Entry> byTag,
                                      byte tag, Class<T> type, CursorCodec<T> codec) {
        Entry entry = new Entry(tag, type, codec);
        byClass.put(type, entry);
        byTag.put(tag, entry);
    }

    @SuppressWarnings("unchecked")
    private static <T> void registerEntry(Map<Class<?>, Entry> byClass, Map<Byte, Entry> byTag,
                                           byte tag, CursorCodecEntry<?> codecEntry) {
        register(byClass, byTag, tag, (Class<T>) codecEntry.type(), (CursorCodec<T>) codecEntry.codec());
    }

    @SuppressWarnings("SameParameterValue")
    private static int computeFingerprint(Map<Byte, Entry> byTag) {
        int hash = 0;
        boolean first = true;
        for (var entry : byTag.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!first) {
                hash = 31 * hash + ';';
            }
            first = false;
            // Hash the tag number, colon, and fully qualified type name as a contiguous character sequence,
            // matching the output of String.hashCode().
            for (char c : Integer.toString(Byte.toUnsignedInt(entry.getKey())).toCharArray()) {
                hash = 31 * hash + c;
            }
            hash = 31 * hash + ':';
            for (char c : entry.getValue().type.getName().toCharArray()) {
                hash = 31 * hash + c;
            }
        }
        return hash;
    }

    static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative string length.");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected end of cursor.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
