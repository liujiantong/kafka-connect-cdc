/**
 * Copyright (C) 2016 Jeremy Custenborder (jcustenborder@gmail.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.kafka.connect.cdc.xstream;

import io.confluent.kafka.connect.cdc.CDCSourceTask;
import oracle.jdbc.OracleConnection;
import oracle.streams.ColumnValue;
import oracle.streams.DDLLCR;
import oracle.streams.LCR;
import oracle.streams.RowLCR;
import oracle.streams.StreamsException;
import oracle.streams.XStreamOut;
import org.apache.kafka.common.utils.SystemTime;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;

public class XStreamSourceTask extends CDCSourceTask<XStreamSourceConnectorConfig> implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(XStreamSourceTask.class);
  OracleConnection xStreamOutConnection;
  Connection metadataConnection;
  XStreamOut xStreamOut;
  String xStreamServerName;
  Time time = new SystemTime();

  @Override
  protected XStreamSourceConnectorConfig getConfig(Map<String, String> map) {
    return new XStreamSourceConnectorConfig(map);
  }

  @Override
  public void start(Map<String, String> map) {
    super.start(map);

    this.xStreamServerName = this.config.xStreamServerNames.get(0);
    if (log.isInfoEnabled()) {
      log.info("Setting XStreamOut for this task to '{}'", this.xStreamServerName);
    }

    this.xStreamOutConnection = OracleUtils.openConnection(this.config);
    this.metadataConnection = OracleUtils.openConnection(this.config);

    try {
      DatabaseMetaData databaseMetaData = this.xStreamOutConnection.getMetaData();

      if (log.isInfoEnabled()) {
        log.info("Driver loaded {}: Driver Name {} - Version {} Database Version {}.{} ({})",
            this.xStreamOutConnection.getClass().getName(),
            databaseMetaData.getDriverName(),
            databaseMetaData.getDriverVersion(),
            databaseMetaData.getDatabaseMajorVersion(),
            databaseMetaData.getDatabaseMinorVersion(),
            databaseMetaData.getDatabaseProductVersion()
        );
      }
    } catch (SQLException ex) {
      throw new ConnectException("Exception thrown while getting database metadata.", ex);
    }

    try {
      if (log.isInfoEnabled()) {
        log.info("Attaching XStreamOut to xStreamOutConnection.");
      }

      byte[] position = null;

//      Map<String, Object> offsets = this.context.offsetStorageReader().offset(Position.partition);
//
//      if (null != offsets) {
//        String storedPosition = (String) offsets.get(this.xStreamServerName);
//
//        if (!Strings.isNullOrEmpty(storedPosition)) {
//          position = Position.toBytes(storedPosition);
//          if (log.isInfoEnabled()) {
//            log.info("Requesting position {} for XStream Out Server {}", position, this.xStreamServerName);
//          }
//        }
//      }

      this.xStreamOut = XStreamOut.attach(
          this.xStreamOutConnection,
          this.xStreamServerName,
          position,
          this.config.xStreamBatchInterval,
          this.config.xStreamIdleTimeout,
          XStreamOut.DEFAULT_MODE
      );

    } catch (StreamsException ex) {
      if (ex.getCause() instanceof SQLFeatureNotSupportedException) {
        throw new ConnectException("Please connect using the OCI(Thick) JDBC URI.", ex.getCause());
      }

      throw new ConnectException("Exception thrown while setting up XStreamOut", ex);
    }
  }

  @Override
  public void stop() {
    try {
      if (null != this.xStreamOut) {
        this.xStreamOut.detach(XStreamOut.DEFAULT_MODE);
      }
    } catch (StreamsException ex) {
      if (log.isErrorEnabled()) {
        log.error("Exception thrown while calling xStreamOut.detach", ex);
      }
    }

//    OracleUtils.closeConnection(this.xStreamOutConnection);
//    OracleUtils.closeConnection(this.metadataConnection);
  }

  @Override
  public void run() {

    try {
      while (true) {
        LCR lcr = this.xStreamOut.receiveLCR(XStreamOut.DEFAULT_MODE);

        if (null == lcr) {
          if (log.isDebugEnabled()) {
            log.debug("receiveLCR() returned null, sleeping {} ms before next call.", this.config.xStreamReceiveWait);
          }
          this.time.sleep(this.config.xStreamReceiveWait);
          continue;
        }

        if (log.isDebugEnabled()) {
          log.debug("lcr = {}", lcr);
        }


        if (!this.config.allowedCommands.contains(lcr.getCommandType())) {
          if (log.isDebugEnabled()) {
            log.debug("Skipping LCR because it is not in the {} config.", XStreamSourceConnectorConfig.XSTREAM_ALLOWED_COMMANDS_CONF);
          }
          continue;
        }

        if (lcr instanceof DDLLCR) {
//          this.schemaGenerator.invalidate(lcr);
          continue;
        } else if (lcr instanceof RowLCR) {
          RowLCR row = (RowLCR) lcr;
//          OracleChange.Builder builder = OracleChange.builder();
//          OracleChange change = builder.build(row);

          for (ColumnValue columnValue : row.getNewValues()) {
            if (log.isDebugEnabled()) {
              log.debug("Processing ColumnValue for column {}", columnValue.getColumnName());
            }


          }

//          if (rowLCR.hasChunkData()) {
//            ChunkColumnValue chunk;
//            do {
//              chunk = this.xStreamOut.receiveChunk(XStreamOut.DEFAULT_MODE);
//              if (log.isDebugEnabled()) {
//                log.debug("Processing ChunkColumnValue for column {}", chunk.getColumnName());
//              }
//
//
//
//              Field field = schemaPair.getValue().field(chunk.getColumnName());
//              Object connectValue = this.typeConversion.toConnect(field.schema(), chunk.getColumnData());
//              if (log.isDebugEnabled()) {
//                log.debug("Setting {} to '{}'.", field.name(), connectValue);
//              }
//              try {
//                valueStruct.put(field, connectValue);
//              } catch (DataException ex) {
//                throw new DataException("Exception thrown while processing " + field.name(), ex);
//              }
//            } while (!chunk.isEndOfRow());
//          }


//          return Arrays.asList(sourceRecord);
        } else {
          throw new UnsupportedOperationException(
              lcr.getClass().getName() + " is not supported."
          );
        }

      }
    } catch (StreamsException ex) {
      if (log.isErrorEnabled()) {
        log.error("Exception thrown", ex);
      }
    }
  }
}
