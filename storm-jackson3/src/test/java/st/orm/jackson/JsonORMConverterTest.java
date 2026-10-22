package st.orm.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.ORMTemplate.of;

import javax.sql.DataSource;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.Json;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.jackson.model.Address;
import st.orm.jackson.model.Owner;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class JsonORMConverterTest {

    private DataSource dataSource;

    @BeforeEach
    void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithFailOnUnknown(
            @PK Integer id,
            String firstName,
            String lastName,
            @Json(failOnUnknown = true) Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithFailOnMissing(
            @PK Integer id,
            String firstName,
            String lastName,
            @Json(failOnMissing = true) Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithBothFailOptions(
            @PK Integer id,
            String firstName,
            String lastName,
            @Json(failOnUnknown = true, failOnMissing = true) Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithNullableAddress(
            @PK Integer id,
            String firstName,
            String lastName,
            @Nullable @Json Address address,
            @Nullable String telephone
    ) implements Entity<Integer> {}

    @Test
    public void failOnUnknownTrueShouldStillWorkWithValidJson() {
        // Standard address JSON has no unknown properties, so this should succeed.
        var orm = of(dataSource);
        var owner = orm.entity(OwnerWithFailOnUnknown.class).getById(1);
        assertNotNull(owner.address());
        assertEquals("638 Cardinal Ave.", owner.address().address());
    }

    @Test
    public void failOnMissingTrueShouldStillWorkWithCompleteJson() {
        // Standard address JSON has all required properties, so this should succeed.
        var orm = of(dataSource);
        var owner = orm.entity(OwnerWithFailOnMissing.class).getById(1);
        assertNotNull(owner.address());
        assertEquals("638 Cardinal Ave.", owner.address().address());
    }

    @Test
    public void failOnUnknownAndFailOnMissingBothTrueShouldWork() {
        // Both options enabled should work with valid complete JSON.
        var orm = of(dataSource);
        var owner = orm.entity(OwnerWithBothFailOptions.class).getById(1);
        assertNotNull(owner.address());
        assertEquals("638 Cardinal Ave.", owner.address().address());
    }

    @Test
    public void insertOwnerShouldSerializeJsonAddressToDatabase() {
        // Tests the toDatabase path with a non-null JSON field.
        var orm = of(dataSource);
        var repository = orm.entity(Owner.class);
        var address = new Address("271 University Ave", "Palo Alto");
        var owner = Owner.builder()
                .firstName("Simon")
                .lastName("McDonald")
                .address(address)
                .telephone("555-555-5555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        assertNotNull(inserted.address());
        assertEquals("271 University Ave", inserted.address().address());
        assertEquals("Palo Alto", inserted.address().city());
    }

    @Test
    public void toDatabaseWithNullJsonFieldShouldProduceNull() {
        // Tests the toDatabase path when the JSON field is null (nullable address).
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithNullableAddress.class);
        var owner = OwnerWithNullableAddress.builder()
                .firstName("Test")
                .lastName("NullAddress")
                .address(null)
                .telephone("555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        assertNull(inserted.address());
    }

    @Test
    public void fromDatabaseWithNullJsonValueShouldReturnNullForNullableField() {
        // Fetch an owner whose address was set to null.
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithNullableAddress.class);
        var owner = OwnerWithNullableAddress.builder()
                .firstName("Null")
                .lastName("Address")
                .address(null)
                .telephone("555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        assertNull(inserted.address());
    }

    @Test
    public void fromDatabaseWithInvalidJsonShouldThrowException() {
        // Query that produces invalid JSON for the address column.
        var orm = of(dataSource);
        var query = orm.query("SELECT id, first_name, last_name, 'not-valid-json' AS address, telephone FROM owner WHERE id = 1");
        assertThrows(PersistenceException.class,
                () -> query.getSingleResult(OwnerWithFailOnUnknown.class));
    }

    @Test
    public void failOnUnknownShouldRejectJsonWithExtraProperties() {
        // Use raw query to inject JSON with an extra property.
        var orm = of(dataSource);
        var query = orm.query("""
                SELECT id, first_name, last_name,
                       '{"address":"test","city":"test","extraField":"unexpected"}' AS address,
                       telephone
                FROM owner WHERE id = 1""");
        assertThrows(PersistenceException.class,
                () -> query.getSingleResult(OwnerWithFailOnUnknown.class));
    }

    @Test
    public void selectAllOwnersWithFailOnUnknownShouldReturnAll() {
        // All 10 standard owners have valid address JSON with no unknown properties.
        var orm = of(dataSource);
        var owners = orm.entity(OwnerWithFailOnUnknown.class).select().getResultList();
        assertEquals(10, owners.size());
    }

    @Test
    public void selectAllOwnersWithFailOnMissingShouldReturnAll() {
        // All 10 standard owners have complete address JSON.
        var orm = of(dataSource);
        var owners = orm.entity(OwnerWithFailOnMissing.class).select().getResultList();
        assertEquals(10, owners.size());
    }

    @Test
    public void updateOwnerJsonFieldShouldPersistChanges() {
        // Tests the toDatabase path during an update.
        var orm = of(dataSource);
        var repository = orm.entity(Owner.class);
        var owner = repository.getById(1);
        var newAddress = new Address("100 Main St", "Springfield");
        repository.update(owner.toBuilder().address(newAddress).build());
        var updated = repository.getById(1);
        assertEquals("100 Main St", updated.address().address());
        assertEquals("Springfield", updated.address().city());
    }

    @Test
    public void updateOwnerNullableAddressToNullAndBackShouldRoundTrip() {
        // Insert with address, update to null, read back (null), update back to address, read back.
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithNullableAddress.class);
        var address = new Address("test", "city");
        var owner = OwnerWithNullableAddress.builder()
                .firstName("Round")
                .lastName("Trip")
                .address(address)
                .telephone("555")
                .build();
        var inserted = repository.insertAndFetch(owner);
        assertNotNull(inserted.address());

        // Update to null address.
        repository.update(inserted.toBuilder().address(null).build());
        var withNullAddress = repository.getById(inserted.id());
        assertNull(withNullAddress.address());

        // Update back to non-null address.
        repository.update(withNullAddress.toBuilder().address(new Address("new", "addr")).build());
        var restored = repository.getById(inserted.id());
        assertNotNull(restored.address());
        assertEquals("new", restored.address().address());
    }

    @Test
    public void selectNullableAddressWithNullValueShouldReturnNull() {
        // Directly query for a row with NULL address via raw SQL to exercise fromDatabase null path.
        var orm = of(dataSource);
        var repository = orm.entity(OwnerWithNullableAddress.class);
        var owner = OwnerWithNullableAddress.builder()
                .firstName("Direct")
                .lastName("Null")
                .address(null)
                .telephone("555")
                .build();
        repository.insert(owner);

        // Use select to get all including the new null-address owner.
        var allOwners = repository.select().getResultList();
        long nullAddressCount = allOwners.stream().filter(o -> o.address() == null).count();
        assertTrue(nullAddressCount >= 1);
    }
}
