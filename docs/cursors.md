import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Cursor Serialization

This page covers the low-level details of cursor serialization for scrolling. For a high-level introduction to scrolling, see [Pagination and Scrolling: Scrolling](pagination-and-scrolling.md#scrolling).

## Overview

When a `Window` is returned from a scroll operation, it carries `Scrollable` navigation tokens that encode the cursor position. These tokens can be serialized to opaque, URL-safe strings using `toCursor()` and deserialized using `Scrollable.fromCursor()`. This allows REST APIs to pass scroll state as a query parameter between requests.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Server: serialize cursor into response
val cursor: String? = window.nextCursor()

// Client sends cursor back in next request
// Server: reconstruct scrollable
cursor?.let {
    val scrollable = Scrollable.fromCursor(User_.id, it)
    val next = userRepository.scroll(scrollable)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Server: serialize cursor into response
String cursor = window.nextCursor();

// Client sends cursor string back in next request
// Server: reconstruct scrollable
var scrollable = Scrollable.fromCursor(User_.id, cursor);
var next = userRepository.scroll(scrollable);
```

</TabItem>
</Tabs>

## Cursor format

The serialized cursor is a Base64 URL-safe encoded binary payload. The format is intentionally opaque: clients should treat it as an immutable token and never parse or modify it. The internal structure includes:

- A version byte for forward compatibility
- Fingerprints of the metamodel key/sort paths and the codec registry, used to detect mismatches on deserialization
- The scroll direction (forward or backward)
- The page size
- The cursor value(s) for the key and optional sort fields

Cursors produced by one application instance can be consumed by another, as long as both use the same entity model and codec registry. A cursor becomes invalid if the metamodel paths change (for example, renaming the key field) or if the codec registry changes (for example, adding or removing a custom codec).

## Security

The cursor format is opaque but **not tamper-proof**. A malicious client can decode the Base64 payload, modify cursor values, and re-encode it. Storm validates structural integrity (version, fingerprints, type tags, trailing bytes), but it does not detect value tampering.

If your cursors are exposed to untrusted clients (for example, in a public REST API), consider one of the following mitigations:

- **HMAC wrapping.** Sign the cursor string with a server-side secret and verify the signature before passing it to `fromCursor()`. This prevents modification without detection.
- **Encryption.** Encrypt the cursor string before sending it to the client and decrypt it on the server. This prevents both reading and modification.
- **Server-side storage.** Store the cursor state on the server (for example, in a session or cache) and give the client an opaque session key instead of the actual cursor.

Storm does not provide built-in signing or encryption because the appropriate security mechanism depends on your application's threat model and infrastructure.

## Supported types

The following Java types can be used as cursor values (key or sort fields) out of the box:

| Type | Binary size | Notes |
|------|------------|-------|
| `Integer` / `int` | 4 bytes | |
| `Long` / `long` | 8 bytes | |
| `Short` / `short` | 2 bytes | |
| `Byte` / `byte` | 1 byte | |
| `Boolean` / `boolean` | 1 byte | |
| `String` | 4 + length | UTF-8 encoded |
| `UUID` | 16 bytes | |
| `Instant` | 12 bytes | Epoch seconds + nanos |
| `LocalDate` | 6 bytes | Year (4) + month (1) + day (1) |
| `LocalDateTime` | 11 bytes | Date (6) + hour/min/sec (3) + nanos (4) |
| `OffsetDateTime` | 15 bytes | LocalDateTime (11) + offset seconds (4) |
| `BigDecimal` | 4 + length | Serialized as plain string |

If your key or sort field uses a type not in this list, serialization via `toCursor()` will throw an `IllegalStateException`. You can either use one of the supported types for your key/sort columns, or register a custom codec.

Note that in-memory navigation (using `next()` and `previous()` directly, without serializing to a cursor string) works with any type, including inline records and other composite types. The type restriction only applies to `toCursor()` serialization.

## Custom cursor codecs

To add cursor serialization support for a custom type, implement the `CursorCodecProvider` SPI. Storm discovers providers via `ServiceLoader`.

### Step 1: Implement the codec

Create a class that implements `CursorCodecProvider` and returns codec entries for your custom types. Each entry binds a unique tag (in the range 64-255), a Java type, and a `CursorCodec` implementation. Tags below 64 are reserved for built-in types and will be rejected at startup.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
class MyCursorCodecProvider : CursorCodecProvider {
    override fun codecs(): List<CursorCodecEntry<*>> = listOf(
        CursorCodecEntry(64, UserId::class.java, object : CursorCodec<UserId> {
            override fun write(out: DataOutputStream, value: UserId) {
                out.writeLong(value.value)
            }

            override fun read(`in`: DataInputStream): UserId {
                return UserId(`in`.readLong())
            }
        })
    )
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public class MyCursorCodecProvider implements CursorCodecProvider {
    @Override
    public List<CursorCodecEntry<?>> codecs() {
        return List.of(
            new CursorCodecEntry<>(64, UserId.class, new CursorCodec<UserId>() {
                @Override
                public void write(DataOutputStream out, UserId value) throws IOException {
                    out.writeLong(value.value());
                }

                @Override
                public UserId read(DataInputStream in) throws IOException {
                    return new UserId(in.readLong());
                }
            })
        );
    }
}
```

</TabItem>
</Tabs>

### Step 2: Register the provider

Create a service file at `META-INF/services/st.orm.core.spi.CursorCodecProvider` containing the fully qualified class name of your provider:

```
com.example.MyCursorCodecProvider
```

### Constraints

- Custom tags must be in the range **64-255**. Tags 0-63 are reserved for built-in types. Using a reserved tag throws an `IllegalArgumentException` at startup.
- Each tag and each type can only be registered once. Duplicate registrations throw an `IllegalArgumentException` at startup.
- The codec registry is built once at class load time. Adding or removing codecs changes the registry fingerprint, which invalidates all previously serialized cursors.
- The `write` method receives a non-null value; null handling is done by the framework. The `read` method must return a non-null value.

## Size limit

Cursor strings carry a page size that is validated during deserialization. The maximum size defaults to 1000 and can be configured via the `st.orm.scrollable.maxSize` system property. This limit only applies to cursors deserialized from external input via `fromCursor()`, not to programmatic `Scrollable.of()` calls.
