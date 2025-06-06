/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.jdbc.dialect.db2;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;

import org.hibernate.SessionFactory;
import org.hibernate.dialect.DB2Dialect;
import org.hibernate.dialect.Dialect;

import io.debezium.connector.jdbc.JdbcSinkConnectorConfig;
import io.debezium.connector.jdbc.JdbcSinkRecord;
import io.debezium.connector.jdbc.dialect.DatabaseDialect;
import io.debezium.connector.jdbc.dialect.DatabaseDialectProvider;
import io.debezium.connector.jdbc.dialect.GeneralDatabaseDialect;
import io.debezium.connector.jdbc.dialect.SqlStatementBuilder;
import io.debezium.connector.jdbc.dialect.db2.connect.ConnectDateType;
import io.debezium.connector.jdbc.dialect.db2.connect.ConnectTimeType;
import io.debezium.connector.jdbc.dialect.db2.connect.ConnectTimestampType;
import io.debezium.connector.jdbc.dialect.db2.debezium.MicroTimeType;
import io.debezium.connector.jdbc.dialect.db2.debezium.MicroTimestampType;
import io.debezium.connector.jdbc.dialect.db2.debezium.NanoTimeType;
import io.debezium.connector.jdbc.dialect.db2.debezium.NanoTimestampType;
import io.debezium.connector.jdbc.dialect.db2.debezium.TimeType;
import io.debezium.connector.jdbc.dialect.db2.debezium.TimestampType;
import io.debezium.connector.jdbc.relational.TableDescriptor;
import io.debezium.sink.field.FieldDescriptor;
import io.debezium.time.ZonedTimestamp;

/**
 * A {@link DatabaseDialect} implementation for Db2.
 *
 * @author Chris Cranford
 */
public class Db2DatabaseDialect extends GeneralDatabaseDialect {

    private static final DateTimeFormatter ISO_LOCAL_DATE_TIME_WITH_SPACE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter();

    public static class Db2DatabaseProvider implements DatabaseDialectProvider {
        @Override
        public boolean supports(Dialect dialect) {
            return dialect instanceof DB2Dialect;
        }

        @Override
        public Class<?> name() {
            return Db2DatabaseDialect.class;
        }

        @Override
        public DatabaseDialect instantiate(JdbcSinkConnectorConfig config, SessionFactory sessionFactory) {
            return new Db2DatabaseDialect(config, sessionFactory);
        }
    }

    private Db2DatabaseDialect(JdbcSinkConnectorConfig config, SessionFactory sessionFactory) {
        super(config, sessionFactory);
    }

    @Override
    protected Optional<String> getDatabaseTimeZoneQuery() {
        return Optional.of("SELECT CURRENT TIMEZONE FROM sysibm.sysdummy1");
    }

    @Override
    protected void registerTypes() {
        super.registerTypes();

        registerType(BytesType.INSTANCE);
        registerType(ZonedTimestampType.INSTANCE);
        registerType(ZonedTimeType.INSTANCE);
        registerType(DateType.INSTANCE);
        registerType(ConnectDateType.INSTANCE);
        registerType(ConnectTimeType.INSTANCE);
        registerType(ConnectTimestampType.INSTANCE);
        registerType(TimestampType.INSTANCE);
        registerType(NanoTimestampType.INSTANCE);
        registerType(MicroTimestampType.INSTANCE);
        registerType(TimeType.INSTANCE);
        registerType(NanoTimeType.INSTANCE);
        registerType(MicroTimeType.INSTANCE);
    }

    @Override
    public int getMaxVarcharLengthInKey() {
        // It would seem for Db2 11.5 on Linux, the maximum key size is 1024 bytes in total for all columns.
        // If other columns participate in the primary key, this reduces the size for a string-based column.
        // For simplicity, the connector will default to 512, and ideally users for Db2 should create tables
        // manually that require more precision on column lengths within the primary key if the primary key
        // consists of a string-based column type.
        return 512;
    }

    @Override
    public int getMaxNVarcharLengthInKey() {
        return 255;
    }

    @Override
    public String getAlterTablePrefix() {
        return "";
    }

    @Override
    public String getAlterTableSuffix() {
        return "";
    }

    @Override
    public String getAlterTableColumnPrefix() {
        return "ADD COLUMN";
    }

    @Override
    public String getAlterTableColumnDelimiter() {
        return " ";
    }

    @Override
    public String getTimestampPositiveInfinityValue() {
        return "9999-12-31T23:59:59+00:00";
    }

    @Override
    public String getTimestampNegativeInfinityValue() {
        return "0001-01-01T00:00:00+00:00";
    }

    @Override
    public String getUpsertStatement(TableDescriptor table, JdbcSinkRecord record) {
        final SqlStatementBuilder builder = new SqlStatementBuilder();
        builder.append("merge into ");
        builder.append(getQualifiedTableName(table.getId()));
        builder.append(" using (values(");
        builder.appendLists(record.keyFieldNames(), record.nonKeyFieldNames(), (name) -> columnQueryBindingFromField(name, table, record));
        builder.append(")) as DAT(");
        builder.appendLists(record.keyFieldNames(), record.nonKeyFieldNames(), (name) -> columnNameFromField(name, record));
        builder.append(") on ");
        builder.appendList(" AND ", record.keyFieldNames(), (name) -> getMergeDatClause(name, table, record));
        if (!record.nonKeyFieldNames().isEmpty()) {
            builder.append(" WHEN MATCHED THEN UPDATE SET ");
            builder.appendList(", ", record.nonKeyFieldNames(), (name) -> getMergeDatClause(name, table, record));
        }

        builder.append(" WHEN NOT MATCHED THEN INSERT(");
        builder.appendLists(",", record.nonKeyFieldNames(), record.keyFieldNames(), (name) -> columnNameFromField(name, record));
        builder.append(") values (");
        builder.appendLists(",", record.nonKeyFieldNames(), record.keyFieldNames(), (name) -> "DAT." + columnNameFromField(name, record));
        builder.append(")");

        return builder.build();
    }

    private String getMergeDatClause(String fieldName, TableDescriptor table, JdbcSinkRecord record) {
        final String columnName = columnNameFromField(fieldName, record);
        return toIdentifier(table.getId()) + "." + columnName + "=DAT." + columnName;
    }

    @Override
    protected void addColumnDefaultValue(FieldDescriptor field, StringBuilder columnSpec) {
        if (field.getSchema().isOptional()) {
            // todo: should investigate why this is the case.
            // Db2 11.5 on Linux does not allow specifying default values on NULL-able fields.
            return;
        }
        super.addColumnDefaultValue(field, columnSpec);
    }

    @Override
    protected boolean isIdentifierUppercaseWhenNotQuoted() {
        return true;
    }

    @Override
    public String getFormattedTime(TemporalAccessor value) {
        return String.format("'%s'", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value));
    }

    @Override
    public String getFormattedDateTime(TemporalAccessor value) {
        return String.format("'%s'", ISO_LOCAL_DATE_TIME_WITH_SPACE.format(value));
    }

    @Override
    public String getFormattedTimestamp(TemporalAccessor value) {
        return String.format("'%s'", ISO_LOCAL_DATE_TIME_WITH_SPACE.format(value));
    }

    @Override
    public String getFormattedTimestampWithTimeZone(String value) {
        final ZonedDateTime zonedDateTime = ZonedDateTime.parse(value, ZonedTimestamp.FORMATTER);
        return String.format("'%s'", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(zonedDateTime));
    }

    @Override
    protected String resolveColumnNameFromField(String fieldName) {
        String columnName = super.resolveColumnNameFromField(fieldName);
        if (!getConfig().isQuoteIdentifiers()) {
            // There are specific use cases where we explicitly quote the column name, even if the
            // quoted identifiers is not enabled, such as the Kafka primary key mode column names.
            // If they're quoted, we shouldn't uppercase the column name.
            if (!getIdentifierHelper().toIdentifier(columnName).isQuoted()) {
                // Db2 defaults to uppercase for identifiers
                columnName = columnName.toUpperCase();
            }
        }
        return columnName;
    }

    @Override
    public String getTruncateStatement(TableDescriptor table) {
        // For some reason the TRUNCATE statement doesn't work for DB2 even if it is supported from 9.7 https://www.ibm.com/support/pages/apar/JR37942
        // The problem verifies with Hibernate, plain JDBC works good.
        // Qlik uses the below approach https://community.qlik.com/t5/Qlik-Replicate/Using-Qlik-for-DB2-TRUNCATE-option/td-p/1989498
        final SqlStatementBuilder builder = new SqlStatementBuilder();
        builder.append("ALTER TABLE ");
        builder.append(getQualifiedTableName(table.getId()));
        builder.append(" ACTIVATE NOT LOGGED INITIALLY WITH EMPTY TABLE");
        return builder.build();
    }

}
