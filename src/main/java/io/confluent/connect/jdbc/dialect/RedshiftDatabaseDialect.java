/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.jdbc.dialect;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import io.confluent.connect.jdbc.dialect.DatabaseDialectProvider.SubprotocolBasedProvider;
import io.confluent.connect.jdbc.sink.metadata.SinkRecordField;
import io.confluent.connect.jdbc.util.ColumnDefinition;
import io.confluent.connect.jdbc.util.ColumnId;
import io.confluent.connect.jdbc.util.ExpressionBuilder;
import io.confluent.connect.jdbc.util.ExpressionBuilder.Transform;
import io.confluent.connect.jdbc.util.IdentifierRules;
import io.confluent.connect.jdbc.util.TableDefinition;
import io.confluent.connect.jdbc.util.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link DatabaseDialect} for MySQL.
 */
public class RedshiftDatabaseDialect extends GenericDatabaseDialect {

  private final Logger log = LoggerFactory.getLogger(RedshiftDatabaseDialect.class);

  /**
   * The provider for {@link RedshiftDatabaseDialect}.
   */
  public static class Provider extends SubprotocolBasedProvider {
    public Provider() {
      super(RedshiftDatabaseDialect.class.getSimpleName(), "redshift");
    }

    @Override
    public DatabaseDialect create(AbstractConfig config) {
      return new RedshiftDatabaseDialect(config);
    }
  }

  static final String JSON_TYPE_NAME = "json";
  static final String JSONB_TYPE_NAME = "jsonb";
  static final String UUID_TYPE_NAME = "uuid";

  /**
   * Define the PG datatypes that require casting upon insert/update statements.
   */
  private static final Set<String> CAST_TYPES = Collections.unmodifiableSet(
      Utils.mkSet(
          JSON_TYPE_NAME,
          JSONB_TYPE_NAME,
          UUID_TYPE_NAME
      )
  );

  /**
   * Create a new dialect instance with the given connector configuration.
   *
   * @param config the connector configuration; may not be null
   */
  public RedshiftDatabaseDialect(AbstractConfig config) {
    super(config, new IdentifierRules(".", "\"", "\""));
  }

  /**
   * Perform any operations on a {@link PreparedStatement} before it is used. This is called from
   * the {@link #createPreparedStatement(Connection, String)} method after the statement is
   * created but before it is returned/used.
   *
   * <p>This method sets the {@link PreparedStatement#setFetchDirection(int) fetch direction}
   * to {@link ResultSet#FETCH_FORWARD forward} as an optimization for the driver to allow it to
   * scroll more efficiently through the result set and prevent out of memory errors.
   *
   * @param stmt the prepared statement; never null
   * @throws SQLException the error that might result from initialization
   */
  @Override
  protected void initializePreparedStatement(PreparedStatement stmt) throws SQLException {
    super.initializePreparedStatement(stmt);

    log.trace("Initializing PreparedStatement fetch direction to FETCH_FORWARD for '{}'", stmt);
    stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
  }

  @Override
  protected String getSqlType(SinkRecordField field) {
    if (field.schemaName() != null) {
      switch (field.schemaName()) {
        case Decimal.LOGICAL_NAME:
          return "DECIMAL";
        case Date.LOGICAL_NAME:
          return "DATE";
        case Time.LOGICAL_NAME:
          return "TIME";
        case Timestamp.LOGICAL_NAME:
          return "TIMESTAMP";
        default:
          // fall through to normal types
      }
    }
    switch (field.schemaType()) {
      case INT8:
      case INT16:
        return "SMALLINT";
      case INT32:
        return "INT";
      case INT64:
        return "BIGINT";
      case FLOAT32:
        return "REAL";
      case FLOAT64:
        return "DOUBLE PRECISION";
      case BOOLEAN:
        return "BOOLEAN";
      case STRING:
        return "TEXT";
      case BYTES:
        return "BYTEA";
      case ARRAY:
        SinkRecordField childField = new SinkRecordField(
              field.schema().valueSchema(),
              field.name(),
              field.isPrimaryKey()
            );
        return getSqlType(childField) + "[]";
      default:
        return super.getSqlType(field);
    }
  }

  @Override
  public String buildInsertStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns,
      TableDefinition definition
  ) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("INSERT INTO ");
    builder.append(table);
    builder.append(" (");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNames())
           .of(keyColumns, nonKeyColumns);
    builder.append(") VALUES (");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(this.columnValueVariables(definition))
           .of(keyColumns, nonKeyColumns);
    builder.append(")");
    return builder.toString();
  }

  @Override
  public String buildUpsertQueryStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns,
      TableDefinition definition
  ) {
    final Transform<ColumnId> transform = (builder, col) -> {
      builder.appendColumnName(col.name())
             .append("=EXCLUDED.")
             .appendColumnName(col.name());
    };

    ExpressionBuilder builder = expressionBuilder();
    builder.append("INSERT INTO ");
    builder.append(table);
    builder.append(" (");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNames())
           .of(keyColumns, nonKeyColumns);
    builder.append(") VALUES (");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(this.columnValueVariables(definition))
           .of(keyColumns, nonKeyColumns);
    builder.append(") ON CONFLICT (");
    builder.appendList()
           .delimitedBy(",")
           .transformedBy(ExpressionBuilder.columnNames())
           .of(keyColumns);
    if (nonKeyColumns.isEmpty()) {
      builder.append(") DO NOTHING");
    } else {
      builder.append(") DO UPDATE SET ");
      builder.appendList()
              .delimitedBy(",")
              .transformedBy(transform)
              .of(nonKeyColumns);
    }
    return builder.toString();
  }

  /**
   * Return the transform that produces a prepared statement variable for each of the columns.
   * PostgreSQL may require the variable to have a type suffix, such as {@code ?::uuid}.
   *
   * @param defn the table definition; may be null if unknown
   * @return the transform that produces the variable expression for each column; never null
   */
  protected Transform<ColumnId> columnValueVariables(TableDefinition defn) {
    return (builder, columnId) -> {
      builder.append("?");
      builder.append(valueTypeCast(defn, columnId));
    };
  }

  /**
   * Return the typecast expression that can be used as a suffix for a value variable of the
   * given column in the defined table.
   *
   * <p>This method returns a blank string except for those column types that require casting
   * when set with literal values. For example, a column of type {@code uuid} must be cast when
   * being bound with with a {@code varchar} literal, since a UUID value cannot be bound directly.
   *
   * @param tableDefn the table definition; may be null if unknown
   * @param columnId  the column within the table; may not be null
   * @return the cast expression, or an empty string; never null
   */
  protected String valueTypeCast(TableDefinition tableDefn, ColumnId columnId) {
    if (tableDefn != null) {
      ColumnDefinition defn = tableDefn.definitionForColumn(columnId.name());
      if (defn != null) {
        String typeName = defn.typeName(); // database-specific
        if (typeName != null) {
          typeName = typeName.toLowerCase();
          if (CAST_TYPES.contains(typeName)) {
            return "::" + typeName;
          }
        }
      }
    }
    return "";
  }

  @Override
  protected String sanitizedUrl(String url) {
    // MySQL can also have "username:password@" at the beginning of the host list and
    // in parenthetical properties
    return super.sanitizedUrl(url)
                .replaceAll("(?i)([(,]password=)[^,)]*", "$1****")
                .replaceAll("(://[^:]*:)([^@]*)@", "$1****@");
  }
}
