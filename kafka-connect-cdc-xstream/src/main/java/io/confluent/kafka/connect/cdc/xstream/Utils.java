package io.confluent.kafka.connect.cdc.xstream;

import oracle.jdbc.OracleConnection;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class Utils {
  private static final Logger log = LoggerFactory.getLogger(Utils.class);

  public static OracleConnection openConnection(String jdbcUrl, String username, String password) {
    try {
      if (log.isInfoEnabled()) {
        log.info("Opening OracleConnection to {}", jdbcUrl);
      }
      return (OracleConnection) DriverManager.getConnection(
          jdbcUrl,
          username,
          password
      );
    } catch (SQLException ex) {
      throw new ConnectException("Exception thrown while connecting to oracle.", ex);
    } catch (UnsatisfiedLinkError ex) {
      if (log.isErrorEnabled()) {
        log.error("This exception is thrown when a ");
      }
      //TODO: Put together a nice message talking about troubleshooting.
      throw new ConnectException("Exception thrown while connecting to oracle.", ex);
    }

  }

  public static OracleConnection openConnection(XStreamSourceConnectorConfig config) {
    return openConnection(config.jdbcUrl, config.jdbcUsername, config.jdbcPassword);
  }

  public static void closeConnection(Connection connection) {
    try {
      connection.close();
    } catch (SQLException ex) {
      if (log.isErrorEnabled()) {
        log.error("Exception thrown while calling metadataConnection.close", ex);
      }
    }
  }
}
