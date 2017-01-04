package io.confluent.kafka.connect.cdc.xstream;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import io.confluent.kafka.connect.cdc.Change;
import io.confluent.kafka.connect.cdc.ChangeKey;
import io.confluent.kafka.connect.cdc.TableMetadataProvider;
import io.confluent.kafka.connect.cdc.Utils;
import oracle.sql.CHAR;
import oracle.sql.DATE;
import oracle.sql.Datum;
import oracle.sql.TIMESTAMPLTZ;
import oracle.sql.TIMESTAMPTZ;
import oracle.streams.LCR;
import oracle.streams.RowLCR;
import oracle.streams.StreamsException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

class OracleChange implements Change {
  private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  private static final Logger log = LoggerFactory.getLogger(OracleChange.class);
  public static final String ROWID_FIELD = "__ROWID";
  public static final String POSITION_KEY = "position";
  public static final String METADATA_COMMAND_KEY = "command";
  public static final String METADATA_TRANSACTIONID_KEY = "transactionID";

  Map<String, String> metadata;
  Map<String, Object> sourcePartition;
  Map<String, Object> sourceOffset;
  List<ColumnValue> keyColumns = new ArrayList<>();
  List<ColumnValue> valueColumns = new ArrayList<>();


  @Override
  public Map<String, String> metadata() {
    return this.metadata;
  }

  @Override
  public Map<String, Object> sourcePartition() {
    return this.sourcePartition;
  }

  @Override
  public Map<String, Object> sourceOffset() {
    return this.sourceOffset;
  }

  @Override
  public String databaseName() {
    return this.databaseName;
  }

  @Override
  public String schemaName() {
    return this.schemaName;
  }

  @Override
  public String tableName() {
    return this.tableName;
  }

  @Override
  public List<ColumnValue> keyColumns() {
    return this.keyColumns;
  }

  @Override
  public List<ColumnValue> valueColumns() {
    return this.valueColumns;
  }

  @Override
  public ChangeType changeType() {
    return this.changeType;
  }

  @Override
  public long timestamp() {
    return this.timestamp;
  }


  String databaseName;
  String schemaName;
  String tableName;

  ChangeType changeType;
  long timestamp;

  static Object convert(XStreamOutput xStreamOutput, oracle.streams.ColumnValue columnValue) throws SQLException {
    Datum datum = columnValue.getColumnData();

    if (null == datum) {
      return null;
    }

    Object value;

    switch (columnValue.getColumnDataType()) {
      case oracle.streams.ColumnValue.BINARY_DOUBLE:
        value = datum.doubleValue();
        break;
      case oracle.streams.ColumnValue.BINARY_FLOAT:
        value = datum.floatValue();
        break;
      case oracle.streams.ColumnValue.CHAR:
        value = datum.stringValue();
        break;
      case oracle.streams.ColumnValue.DATE:
        value = new Date(((DATE) datum).timestampValue(Calendar.getInstance()).getTime());
        break;
      case oracle.streams.ColumnValue.NUMBER:
        value = datum.toJdbc();
        break;
      case oracle.streams.ColumnValue.TIMESTAMPLTZ:
        TIMESTAMPLTZ timestampltz = (TIMESTAMPLTZ) datum;
        value = new Date(timestampltz.timestampValue(xStreamOutput.connection(), UTC).getTime());
        break;
      case oracle.streams.ColumnValue.TIMESTAMPTZ:
        TIMESTAMPTZ timestamptz = (TIMESTAMPTZ) datum;
        value = new Date(timestamptz.timestampValue(xStreamOutput.connection()).getTime());
        break;
      default:
        value = datum.toJdbc();
    }


    return value;
  }

  public static OracleChange build(XStreamOutput xStreamOutput, TableMetadataProvider.TableMetadata tableMetadata, RowLCR row) throws StreamsException {
    Preconditions.checkNotNull(row, "row cannot be null.");
    Preconditions.checkNotNull(row.getSourceTime(), "row.getSourceTime() cannot be null.");
    ChangeKey changeKey = new ChangeKey(row.getSourceDatabaseName(), row.getObjectOwner(), row.getObjectName());
    OracleChange change = new OracleChange();
    change.timestamp = row.getSourceTime().timestampValue().getTime();
    change.databaseName = row.getSourceDatabaseName();
    change.schemaName = row.getObjectOwner();
    change.tableName = row.getObjectName();
    Map<String, String> metadata = new LinkedHashMap<>(2);
    metadata.put(METADATA_COMMAND_KEY, row.getCommandType());
    metadata.put(METADATA_TRANSACTIONID_KEY, row.getTransactionId());
    change.metadata = metadata;
    change.sourcePartition = ImmutableMap.of();
    final String position = BaseEncoding.base32Hex().encode(row.getPosition());
    change.sourceOffset = ImmutableMap.of(
        POSITION_KEY, position
    );

    switch (row.getCommandType()) {
      case RowLCR.INSERT:
        change.changeType = ChangeType.INSERT;
        break;
      case RowLCR.UPDATE:
        change.changeType = ChangeType.UPDATE;
        break;
      default:
        throw new UnsupportedOperationException(
            String.format("CommandType of '%s' is not supported.", row.getCommandType())
        );
    }

    if (log.isTraceEnabled()) {
      log.trace("{}: Processing {} column(s) for row='{}'.", changeKey, row.getNewValues().length, position, row.getObjectName());
    }

    List<ColumnValue> valueColumns = new ArrayList<>(tableMetadata.columnSchemas().size());
    List<ColumnValue> keyColumns = new ArrayList<>(tableMetadata.keyColumns().size());

    for (oracle.streams.ColumnValue columnValue : row.getNewValues()) {
      if (log.isTraceEnabled()) {
        log.trace("{}: Processing row.getNewValues({}) for row='{}'", changeKey, columnValue.getColumnName(), position);
      }
      Object value;
      Schema schema = tableMetadata.columnSchemas().get(columnValue.getColumnName());

      try {
        if (log.isTraceEnabled()) {
          log.trace("{}: Converting value row.getNewValues({}) to {} for row='{}'", changeKey, columnValue.getColumnName(), Utils.toString(schema), position);
        }
        value = convert(xStreamOutput, columnValue);
        if (log.isTraceEnabled()) {
          log.trace("{}: Converted value row.getNewValues({}) to {} for row='{}'", changeKey, columnValue.getColumnName(), value, position);
        }
      } catch (SQLException ex) {
        String message = String.format("Exception thrown while processing row. %s: row='%s'", changeKey, position);
        throw new DataException(message, ex);
      }

      ColumnValue outputColumnValue = new OracleColumnValue(
          columnValue.getColumnName(),
          schema,
          value
      );
      valueColumns.add(outputColumnValue);

      if (tableMetadata.keyColumns().contains(columnValue.getColumnName())) {
        if (log.isTraceEnabled()) {
          log.trace("{}: Adding key({}) for row='{}'", changeKey, columnValue.getColumnName(), position);
        }
        keyColumns.add(outputColumnValue);
      }
    }

    //TODO: Handle the chunk columns
    if (row.hasChunkData()) {
      oracle.streams.ChunkColumnValue columnValue;

      do {
        if (log.isTraceEnabled()) {
          log.trace("Receiving chunk for row {} in {}", position, row.getObjectName());
        }
        columnValue = xStreamOutput.receiveChunk();
        if (log.isTraceEnabled()) {
          log.trace("Received chunk for row {} in {}", position, row.getObjectName());
        }
      } while (!columnValue.isLastChunk());
    }

    if (tableMetadata.columnSchemas().containsKey(OracleChange.ROWID_FIELD)) {
      Schema schema = tableMetadata.columnSchemas().get(OracleChange.ROWID_FIELD);
      CHAR rowID = (CHAR) row.getAttribute(LCR.ATTRIBUTE_ROW_ID);
      ColumnValue columnValue = new OracleColumnValue(OracleChange.ROWID_FIELD, schema, rowID.stringValue());
      valueColumns.add(columnValue);
      keyColumns.add(columnValue);
    }
    change.keyColumns = keyColumns;
    change.valueColumns = valueColumns;

    if (log.isTraceEnabled()) {
      log.trace("{}: Converted {} key(s) {} value(s) for row='{}'", changeKey, change.keyColumns().size(), change.valueColumns().size(), position);
    }

    return change;
  }


  static class OracleColumnValue implements ColumnValue {
    final String columnName;
    final Schema schema;
    final Object value;

    OracleColumnValue(String columnName, Schema schema, Object value) {
      this.columnName = columnName;
      this.schema = schema;
      this.value = value;
    }

    @Override
    public String columnName() {
      return this.columnName;
    }

    @Override
    public Schema schema() {
      return this.schema;
    }

    @Override
    public Object value() {
      return this.value;
    }
  }
}
