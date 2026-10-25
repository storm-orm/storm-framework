package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static st.orm.Operator.EQUALS;

import java.util.List;
import javax.sql.DataSource;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Convert;
import st.orm.Converter;
import st.orm.DbColumn;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.Inline;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.Version;
import st.orm.core.model.City;
import st.orm.core.template.ORMTemplate;

/**
 * Regression test for <a href="https://github.com/storm-orm/storm-framework/issues/141">issue #141</a>:
 * a {@code @Convert} converter declared on a field of an embedded (inline) component must work on save.
 *
 * <p>Previously the save failed with a {@code ClassCastException} ({@code object is not an instance of declaring
 * class}): a converter resolves its value by reflectively invoking the field accessor on the record passed to
 * {@code ORMConverter.toDatabase}, but {@code ModelImpl} passed the <em>root</em> record. For a top-level field the
 * root record is the correct receiver, so existing converter tests passed; for a field inside an inline component the
 * accessor belongs to the component type, so invoking it on the root record threw. The fix navigates the root record
 * down to the enclosing component instance before invoking the converter.</p>
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class EmbeddedConverterIntegrationTest {

    @Autowired
    private DataSource dataSource;

    /**
     * Domain value type stored in the {@code address} column. A converter maps it to/from the database {@code String}.
     */
    public record Street(String value) {}

    public static class StreetConverter implements Converter<String, Street> {
        @Override
        public String toDatabase(@Nullable Street value) {
            return value == null ? null : value.value();
        }

        @Override
        public Street fromDatabase(@Nullable String dbValue) {
            return dbValue == null ? null : new Street(dbValue);
        }
    }

    /**
     * Embedded (inline) component holding a field whose value is mapped through {@link StreetConverter}.
     */
    @Builder(toBuilder = true)
    public record AddressWithConverter(
            @Convert(converter = StreetConverter.class) @DbColumn("address") Street street,
            @FK City city
    ) {}

    /**
     * Entity mapped to the existing {@code owner} table, embedding {@link AddressWithConverter} inline. The converted
     * {@code street} field maps to the {@code address} column and the {@code @FK City} maps to the {@code city_id}
     * column.
     */
    @Builder(toBuilder = true)
    @DbTable("owner")
    public record OwnerWithEmbeddedConverter(
            @PK Integer id,
            String firstName,
            String lastName,
            @Inline AddressWithConverter address,
            @Nullable String telephone,
            @Version int version
    ) implements Entity<Integer> {}

    @Test
    void testInsertWithConverterOnEmbeddedComponentField() {
        var owners = ORMTemplate.of(dataSource).entity(OwnerWithEmbeddedConverter.class);
        var owner = OwnerWithEmbeddedConverter.builder()
                .firstName("Ada")
                .lastName("Lovelace")
                .address(AddressWithConverter.builder()
                        .street(new Street("42 Converter Lane"))
                        .city(City.builder().id(1).build())
                        .build())
                .telephone("6085551749")
                .version(0)
                .build();

        // The insert must succeed and the converted value must round-trip through the embedded component.
        var inserted = owners.insertAndFetch(owner);

        assertNotNull(inserted.id());
        var reloaded = owners.getById(inserted.id());
        assertEquals("42 Converter Lane", reloaded.address().street().value());
        assertEquals(1, reloaded.address().city().id());
    }

    @Test
    void testWhereOnConverterInEmbeddedComponentConvertsTheValue() {
        var owners = ORMTemplate.of(dataSource).entity(OwnerWithEmbeddedConverter.class);
        var address = AddressWithConverter.builder()
                .street(new Street("42 Converter Lane"))
                .city(City.builder().id(1).build())
                .build();
        var inserted = owners.insertAndFetch(OwnerWithEmbeddedConverter.builder()
                .firstName("Ada")
                .lastName("Lovelace")
                .address(address)
                .version(0)
                .build());
        Metamodel<OwnerWithEmbeddedConverter, Street> street = Metamodel.of(OwnerWithEmbeddedConverter.class, "address.street");
        var byStreet = owners.select().where(street, EQUALS, new Street("42 Converter Lane")).getResultList();
        assertEquals(List.of(inserted.id()), byStreet.stream().map(OwnerWithEmbeddedConverter::id).toList());
        Metamodel<OwnerWithEmbeddedConverter, AddressWithConverter> embedded = Metamodel.of(OwnerWithEmbeddedConverter.class, "address");
        var byAddress = owners.select().where(embedded, EQUALS, address).getResultList();
        assertEquals(List.of(inserted.id()), byAddress.stream().map(OwnerWithEmbeddedConverter::id).toList());
        assertEquals(1, owners.update().set(street, new Street("1 Main St.")).where(street, EQUALS, new Street("42 Converter Lane")).executeUpdate());
        assertEquals("1 Main St.", owners.getById(inserted.id()).address().street().value());
    }
}
