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
package st.orm.core.template.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.core.spi.ConnectionProvider;
import st.orm.core.spi.TransactionContext;

/**
 * A prepared statement that returns its connection to the provider when it is closed: every method but
 * {@link #close()} is the driver's statement's own.
 *
 * <p>A plain delegating class, so a call to the statement is an ordinary virtual call rather than a reflective
 * dispatch through a proxy, and creating one creates no proxy class. The methods are those of
 * {@link PreparedStatement} and the interfaces it extends, the default methods included, so a driver's own
 * implementation of a newer JDBC method is the one that runs.</p>
 */
final class ReleasingPreparedStatement implements PreparedStatement {

    private final PreparedStatement statement;
    private final Connection connection;
    private final DataSource dataSource;
    private final @Nullable TransactionContext transactionContext;
    private final ConnectionProvider connectionProvider;
    private boolean released;

    ReleasingPreparedStatement(PreparedStatement statement,
                               Connection connection,
                               DataSource dataSource,
                               @Nullable TransactionContext transactionContext,
                               ConnectionProvider connectionProvider) {
        this.statement = statement;
        this.connection = connection;
        this.dataSource = dataSource;
        this.transactionContext = transactionContext;
        this.connectionProvider = connectionProvider;
    }

    /**
     * Closes the statement and returns the connection to the provider, once: a statement closed again closes the
     * driver's statement again, which a driver ignores, and returns nothing a second time.
     */
    @Override
    public void close() throws SQLException {
        try {
            statement.close();
        } finally {
            if (!released) {
                released = true;
                connectionProvider.releaseConnection(connection, dataSource, transactionContext);
            }
        }
    }

    @Override
    public String toString() {
        return statement.toString();
    }

    @Override
    public void addBatch() throws SQLException {
        statement.addBatch();
    }

    @Override
    public void addBatch(String p0) throws SQLException {
        statement.addBatch(p0);
    }

    @Override
    public void cancel() throws SQLException {
        statement.cancel();
    }

    @Override
    public void clearBatch() throws SQLException {
        statement.clearBatch();
    }

    @Override
    public void clearParameters() throws SQLException {
        statement.clearParameters();
    }

    @Override
    public void clearWarnings() throws SQLException {
        statement.clearWarnings();
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        statement.closeOnCompletion();
    }

    @Override
    public String enquoteIdentifier(String p0, boolean p1) throws SQLException {
        return statement.enquoteIdentifier(p0, p1);
    }

    @Override
    public String enquoteLiteral(String p0) throws SQLException {
        return statement.enquoteLiteral(p0);
    }

    @Override
    public String enquoteNCharLiteral(String p0) throws SQLException {
        return statement.enquoteNCharLiteral(p0);
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return statement.executeBatch();
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        return statement.executeLargeBatch();
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        return statement.executeLargeUpdate();
    }

    @Override
    public long executeLargeUpdate(String p0, int[] p1) throws SQLException {
        return statement.executeLargeUpdate(p0, p1);
    }

    @Override
    public long executeLargeUpdate(String p0, String[] p1) throws SQLException {
        return statement.executeLargeUpdate(p0, p1);
    }

    @Override
    public long executeLargeUpdate(String p0, int p1) throws SQLException {
        return statement.executeLargeUpdate(p0, p1);
    }

    @Override
    public long executeLargeUpdate(String p0) throws SQLException {
        return statement.executeLargeUpdate(p0);
    }

    @Override
    public java.sql.ResultSet executeQuery() throws SQLException {
        return statement.executeQuery();
    }

    @Override
    public java.sql.ResultSet executeQuery(String p0) throws SQLException {
        return statement.executeQuery(p0);
    }

    @Override
    public int executeUpdate() throws SQLException {
        return statement.executeUpdate();
    }

    @Override
    public int executeUpdate(String p0, int[] p1) throws SQLException {
        return statement.executeUpdate(p0, p1);
    }

    @Override
    public int executeUpdate(String p0, String[] p1) throws SQLException {
        return statement.executeUpdate(p0, p1);
    }

    @Override
    public int executeUpdate(String p0, int p1) throws SQLException {
        return statement.executeUpdate(p0, p1);
    }

    @Override
    public int executeUpdate(String p0) throws SQLException {
        return statement.executeUpdate(p0);
    }

    @Override
    public boolean execute() throws SQLException {
        return statement.execute();
    }

    @Override
    public boolean execute(String p0, int[] p1) throws SQLException {
        return statement.execute(p0, p1);
    }

    @Override
    public boolean execute(String p0, String[] p1) throws SQLException {
        return statement.execute(p0, p1);
    }

    @Override
    public boolean execute(String p0, int p1) throws SQLException {
        return statement.execute(p0, p1);
    }

    @Override
    public boolean execute(String p0) throws SQLException {
        return statement.execute(p0);
    }

    @Override
    public java.sql.Connection getConnection() throws SQLException {
        return statement.getConnection();
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return statement.getFetchDirection();
    }

    @Override
    public int getFetchSize() throws SQLException {
        return statement.getFetchSize();
    }

    @Override
    public java.sql.ResultSet getGeneratedKeys() throws SQLException {
        return statement.getGeneratedKeys();
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        return statement.getLargeMaxRows();
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        return statement.getLargeUpdateCount();
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return statement.getMaxFieldSize();
    }

    @Override
    public int getMaxRows() throws SQLException {
        return statement.getMaxRows();
    }

    @Override
    public java.sql.ResultSetMetaData getMetaData() throws SQLException {
        return statement.getMetaData();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return statement.getMoreResults();
    }

    @Override
    public boolean getMoreResults(int p0) throws SQLException {
        return statement.getMoreResults(p0);
    }

    @Override
    public java.sql.ParameterMetaData getParameterMetaData() throws SQLException {
        return statement.getParameterMetaData();
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return statement.getQueryTimeout();
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return statement.getResultSetConcurrency();
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return statement.getResultSetHoldability();
    }

    @Override
    public int getResultSetType() throws SQLException {
        return statement.getResultSetType();
    }

    @Override
    public java.sql.ResultSet getResultSet() throws SQLException {
        return statement.getResultSet();
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return statement.getUpdateCount();
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws SQLException {
        return statement.getWarnings();
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return statement.isCloseOnCompletion();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return statement.isClosed();
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return statement.isPoolable();
    }

    @Override
    public boolean isSimpleIdentifier(String p0) throws SQLException {
        return statement.isSimpleIdentifier(p0);
    }

    @Override
    public boolean isWrapperFor(Class<?> p0) throws SQLException {
        return statement.isWrapperFor(p0);
    }

    @Override
    public void setArray(int p0, java.sql.Array p1) throws SQLException {
        statement.setArray(p0, p1);
    }

    @Override
    public void setAsciiStream(int p0, java.io.InputStream p1, int p2) throws SQLException {
        statement.setAsciiStream(p0, p1, p2);
    }

    @Override
    public void setAsciiStream(int p0, java.io.InputStream p1, long p2) throws SQLException {
        statement.setAsciiStream(p0, p1, p2);
    }

    @Override
    public void setAsciiStream(int p0, java.io.InputStream p1) throws SQLException {
        statement.setAsciiStream(p0, p1);
    }

    @Override
    public void setBigDecimal(int p0, java.math.BigDecimal p1) throws SQLException {
        statement.setBigDecimal(p0, p1);
    }

    @Override
    public void setBinaryStream(int p0, java.io.InputStream p1, int p2) throws SQLException {
        statement.setBinaryStream(p0, p1, p2);
    }

    @Override
    public void setBinaryStream(int p0, java.io.InputStream p1, long p2) throws SQLException {
        statement.setBinaryStream(p0, p1, p2);
    }

    @Override
    public void setBinaryStream(int p0, java.io.InputStream p1) throws SQLException {
        statement.setBinaryStream(p0, p1);
    }

    @Override
    public void setBlob(int p0, java.io.InputStream p1, long p2) throws SQLException {
        statement.setBlob(p0, p1, p2);
    }

    @Override
    public void setBlob(int p0, java.io.InputStream p1) throws SQLException {
        statement.setBlob(p0, p1);
    }

    @Override
    public void setBlob(int p0, java.sql.Blob p1) throws SQLException {
        statement.setBlob(p0, p1);
    }

    @Override
    public void setBoolean(int p0, boolean p1) throws SQLException {
        statement.setBoolean(p0, p1);
    }

    @Override
    public void setByte(int p0, byte p1) throws SQLException {
        statement.setByte(p0, p1);
    }

    @Override
    public void setBytes(int p0, byte[] p1) throws SQLException {
        statement.setBytes(p0, p1);
    }

    @Override
    public void setCharacterStream(int p0, java.io.Reader p1, int p2) throws SQLException {
        statement.setCharacterStream(p0, p1, p2);
    }

    @Override
    public void setCharacterStream(int p0, java.io.Reader p1, long p2) throws SQLException {
        statement.setCharacterStream(p0, p1, p2);
    }

    @Override
    public void setCharacterStream(int p0, java.io.Reader p1) throws SQLException {
        statement.setCharacterStream(p0, p1);
    }

    @Override
    public void setClob(int p0, java.io.Reader p1, long p2) throws SQLException {
        statement.setClob(p0, p1, p2);
    }

    @Override
    public void setClob(int p0, java.io.Reader p1) throws SQLException {
        statement.setClob(p0, p1);
    }

    @Override
    public void setClob(int p0, java.sql.Clob p1) throws SQLException {
        statement.setClob(p0, p1);
    }

    @Override
    public void setCursorName(String p0) throws SQLException {
        statement.setCursorName(p0);
    }

    @Override
    public void setDate(int p0, java.sql.Date p1, java.util.Calendar p2) throws SQLException {
        statement.setDate(p0, p1, p2);
    }

    @Override
    public void setDate(int p0, java.sql.Date p1) throws SQLException {
        statement.setDate(p0, p1);
    }

    @Override
    public void setDouble(int p0, double p1) throws SQLException {
        statement.setDouble(p0, p1);
    }

    @Override
    public void setEscapeProcessing(boolean p0) throws SQLException {
        statement.setEscapeProcessing(p0);
    }

    @Override
    public void setFetchDirection(int p0) throws SQLException {
        statement.setFetchDirection(p0);
    }

    @Override
    public void setFetchSize(int p0) throws SQLException {
        statement.setFetchSize(p0);
    }

    @Override
    public void setFloat(int p0, float p1) throws SQLException {
        statement.setFloat(p0, p1);
    }

    @Override
    public void setInt(int p0, int p1) throws SQLException {
        statement.setInt(p0, p1);
    }

    @Override
    public void setLargeMaxRows(long p0) throws SQLException {
        statement.setLargeMaxRows(p0);
    }

    @Override
    public void setLong(int p0, long p1) throws SQLException {
        statement.setLong(p0, p1);
    }

    @Override
    public void setMaxFieldSize(int p0) throws SQLException {
        statement.setMaxFieldSize(p0);
    }

    @Override
    public void setMaxRows(int p0) throws SQLException {
        statement.setMaxRows(p0);
    }

    @Override
    public void setNCharacterStream(int p0, java.io.Reader p1, long p2) throws SQLException {
        statement.setNCharacterStream(p0, p1, p2);
    }

    @Override
    public void setNCharacterStream(int p0, java.io.Reader p1) throws SQLException {
        statement.setNCharacterStream(p0, p1);
    }

    @Override
    public void setNClob(int p0, java.io.Reader p1, long p2) throws SQLException {
        statement.setNClob(p0, p1, p2);
    }

    @Override
    public void setNClob(int p0, java.io.Reader p1) throws SQLException {
        statement.setNClob(p0, p1);
    }

    @Override
    public void setNClob(int p0, java.sql.NClob p1) throws SQLException {
        statement.setNClob(p0, p1);
    }

    @Override
    public void setNString(int p0, String p1) throws SQLException {
        statement.setNString(p0, p1);
    }

    @Override
    public void setNull(int p0, int p1, String p2) throws SQLException {
        statement.setNull(p0, p1, p2);
    }

    @Override
    public void setNull(int p0, int p1) throws SQLException {
        statement.setNull(p0, p1);
    }

    @Override
    public void setObject(int p0, Object p1, int p2, int p3) throws SQLException {
        statement.setObject(p0, p1, p2, p3);
    }

    @Override
    public void setObject(int p0, Object p1, int p2) throws SQLException {
        statement.setObject(p0, p1, p2);
    }

    @Override
    public void setObject(int p0, Object p1, java.sql.SQLType p2, int p3) throws SQLException {
        statement.setObject(p0, p1, p2, p3);
    }

    @Override
    public void setObject(int p0, Object p1, java.sql.SQLType p2) throws SQLException {
        statement.setObject(p0, p1, p2);
    }

    @Override
    public void setObject(int p0, Object p1) throws SQLException {
        statement.setObject(p0, p1);
    }

    @Override
    public void setPoolable(boolean p0) throws SQLException {
        statement.setPoolable(p0);
    }

    @Override
    public void setQueryTimeout(int p0) throws SQLException {
        statement.setQueryTimeout(p0);
    }

    @Override
    public void setRef(int p0, java.sql.Ref p1) throws SQLException {
        statement.setRef(p0, p1);
    }

    @Override
    public void setRowId(int p0, java.sql.RowId p1) throws SQLException {
        statement.setRowId(p0, p1);
    }

    @Override
    public void setSQLXML(int p0, java.sql.SQLXML p1) throws SQLException {
        statement.setSQLXML(p0, p1);
    }

    @Override
    public void setShort(int p0, short p1) throws SQLException {
        statement.setShort(p0, p1);
    }

    @Override
    public void setString(int p0, String p1) throws SQLException {
        statement.setString(p0, p1);
    }

    @Override
    public void setTime(int p0, java.sql.Time p1, java.util.Calendar p2) throws SQLException {
        statement.setTime(p0, p1, p2);
    }

    @Override
    public void setTime(int p0, java.sql.Time p1) throws SQLException {
        statement.setTime(p0, p1);
    }

    @Override
    public void setTimestamp(int p0, java.sql.Timestamp p1, java.util.Calendar p2) throws SQLException {
        statement.setTimestamp(p0, p1, p2);
    }

    @Override
    public void setTimestamp(int p0, java.sql.Timestamp p1) throws SQLException {
        statement.setTimestamp(p0, p1);
    }

    @Override
    public void setURL(int p0, java.net.URL p1) throws SQLException {
        statement.setURL(p0, p1);
    }

    @Override
    public void setUnicodeStream(int p0, java.io.InputStream p1, int p2) throws SQLException {
        statement.setUnicodeStream(p0, p1, p2);
    }

    @Override
    public <T> T unwrap(Class<T> p0) throws SQLException {
        return statement.unwrap(p0);
    }
}
