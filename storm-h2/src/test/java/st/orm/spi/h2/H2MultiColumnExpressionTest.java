package st.orm.spi.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import st.orm.Operator;
import st.orm.core.template.SqlTemplateException;

public class H2MultiColumnExpressionTest {

    private static final H2SqlDialect DIALECT = new H2SqlDialect();

    private static SequencedMap<String, Object> row(String column1, Object value1,
                                                     String column2, Object value2) {
        SequencedMap<String, Object> map = new LinkedHashMap<>();
        map.put(column1, value1);
        map.put(column2, value2);
        return map;
    }

    private static SequencedMap<String, Object> row(String column1, Object value1,
                                                     String column2, Object value2,
                                                     String column3, Object value3) {
        SequencedMap<String, Object> map = new LinkedHashMap<>();
        map.put(column1, value1);
        map.put(column2, value2);
        map.put(column3, value3);
        return map;
    }

    @Test
    void equals_twoColumns() throws SqlTemplateException {
        var values = List.of(row("a", 1, "b", 2));
        String sql = DIALECT.multiColumnExpression(Operator.EQUALS, values, v -> "?");
        assertEquals("(a, b) = (?, ?)", sql);
    }

    @Test
    void equals_threeColumns() throws SqlTemplateException {
        var values = List.of(row("a", 1, "b", 2, "c", 3));
        String sql = DIALECT.multiColumnExpression(Operator.EQUALS, values, v -> "?");
        assertEquals("(a, b, c) = (?, ?, ?)", sql);
    }

    @Test
    void notEquals_twoColumns() throws SqlTemplateException {
        var values = List.of(row("a", 1, "b", 2));
        String sql = DIALECT.multiColumnExpression(Operator.NOT_EQUALS, values, v -> "?");
        assertEquals("(a, b) <> (?, ?)", sql);
    }

    @Test
    void in_multipleRows() throws SqlTemplateException {
        var values = List.of(
                row("a", 1, "b", 2),
                row("a", 3, "b", 4));
        String sql = DIALECT.multiColumnExpression(Operator.IN, values, v -> "?");
        assertEquals("(a, b) IN ((?, ?), (?, ?))", sql);
    }

    @Test
    void notIn_multipleRows() throws SqlTemplateException {
        var values = List.of(
                row("a", 1, "b", 2),
                row("a", 3, "b", 4));
        String sql = DIALECT.multiColumnExpression(Operator.NOT_IN, values, v -> "?");
        assertEquals("(a, b) NOT IN ((?, ?), (?, ?))", sql);
    }

    @Test
    void greaterThan_twoColumns() throws SqlTemplateException {
        var values = List.of(row("a", 1, "b", 2));
        String sql = DIALECT.multiColumnExpression(Operator.GREATER_THAN, values, v -> "?");
        assertEquals("(a, b) > (?, ?)", sql);
    }

    @Test
    void greaterThan_threeColumns() throws SqlTemplateException {
        var values = List.of(row("a", 1, "b", 2, "c", 3));
        String sql = DIALECT.multiColumnExpression(Operator.GREATER_THAN, values, v -> "?");
        assertEquals("(a, b, c) > (?, ?, ?)", sql);
    }

    @Test
    void greaterThanOrEqual_twoColumns() throws SqlTemplateException {
        var values = List.of(row("a", 1, "b", 2));
        String sql = DIALECT.multiColumnExpression(Operator.GREATER_THAN_OR_EQUAL, values, v -> "?");
        assertEquals("(a, b) >= (?, ?)", sql);
    }

    @Test
    void lessThan_twoColumns() throws SqlTemplateException {
        var values = List.of(row("a", 1, "b", 2));
        String sql = DIALECT.multiColumnExpression(Operator.LESS_THAN, values, v -> "?");
        assertEquals("(a, b) < (?, ?)", sql);
    }

    @Test
    void lessThanOrEqual_twoColumns() throws SqlTemplateException {
        var values = List.of(row("a", 1, "b", 2));
        String sql = DIALECT.multiColumnExpression(Operator.LESS_THAN_OR_EQUAL, values, v -> "?");
        assertEquals("(a, b) <= (?, ?)", sql);
    }

    @Test
    void between_twoColumns() throws SqlTemplateException {
        var values = List.of(
                row("a", 1, "b", 2),
                row("a", 5, "b", 6));
        String sql = DIALECT.multiColumnExpression(Operator.BETWEEN, values, v -> "?");
        assertEquals("(a, b) BETWEEN (?, ?) AND (?, ?)", sql);
    }

    @Test
    void isNull_fallsBackToDefault() throws SqlTemplateException {
        var values = List.of(row("a", null, "b", null));
        String sql = DIALECT.multiColumnExpression(Operator.IS_NULL, values, v -> "?");
        assertEquals("a IS NULL AND b IS NULL", sql);
    }

    @Test
    void isNotNull_fallsBackToDefault() throws SqlTemplateException {
        var values = List.of(row("a", null, "b", null));
        String sql = DIALECT.multiColumnExpression(Operator.IS_NOT_NULL, values, v -> "?");
        assertEquals("a IS NOT NULL AND b IS NOT NULL", sql);
    }

    @Test
    void like_throwsForMultiColumn() {
        var values = List.of(row("a", 1, "b", 2));
        assertThrows(SqlTemplateException.class,
                () -> DIALECT.multiColumnExpression(Operator.LIKE, values, v -> "?"));
    }

    @Test
    void parameterBindingOrder_greaterThan() throws SqlTemplateException {
        var values = List.of(row("a", "v1", "b", "v2"));
        StringBuilder bindOrder = new StringBuilder();
        DIALECT.multiColumnExpression(Operator.GREATER_THAN, values, v -> {
            bindOrder.append(v).append(",");
            return "?";
        });
        assertEquals("v1,v2,", bindOrder.toString());
    }

    @Test
    void parameterBindingOrder_in_multipleRows() throws SqlTemplateException {
        var values = List.of(
                row("a", "v1", "b", "v2"),
                row("a", "v3", "b", "v4"));
        StringBuilder bindOrder = new StringBuilder();
        DIALECT.multiColumnExpression(Operator.IN, values, v -> {
            bindOrder.append(v).append(",");
            return "?";
        });
        assertEquals("v1,v2,v3,v4,", bindOrder.toString());
    }
}
