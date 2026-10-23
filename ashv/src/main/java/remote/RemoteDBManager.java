package remote;

import config.profile.ConfigProfile;
import config.profile.ConnProfile;
import core.manager.ConfigurationManager;
import core.manager.ConstantManager.Profile;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.dbcp2.BasicDataSource;

@Slf4j
@Singleton
public class RemoteDBManager {
    private ConfigurationManager configurationManager;
    private ConnProfile connProfile;
    @Getter private BasicDataSource basicDataSource;

    /** Timeout to get / open / validate a connection */
    public static final int TIMEOUT_SECONDS = 15;

    @Inject
    public RemoteDBManager(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
    }

    public void init(ConfigProfile configProfile) {
        try {
            this.connProfile = configProfile.getConnProfile();
            this.basicDataSource = new BasicDataSource();
            this.basicDataSource.setDriverClassLoader(getClassLoader());
            this.basicDataSource.setDriverClassName(this.connProfile.getDriver());
            this.basicDataSource.setUrl(this.connProfile.getUrl());
            this.basicDataSource.setUsername(this.connProfile.getUserName());
            this.basicDataSource.setPassword(configurationManager.getPassword(configProfile));
            this.basicDataSource.setInitialSize(3);
            this.basicDataSource.setMaxTotal(5);

            // Do not hang forever when the network is down
            this.basicDataSource.setMaxWaitMillis(TIMEOUT_SECONDS * 1000L);       // wait for a free pooled connection
            this.basicDataSource.setTestOnBorrow(true);                          // discard dead pooled connections...
            this.basicDataSource.setValidationQueryTimeout(TIMEOUT_SECONDS);     // ...checked with isValid(timeout)
            if (this.connProfile.getDriver() != null && this.connProfile.getDriver().toLowerCase().contains("oracle")) {
                this.basicDataSource.addConnectionProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(TIMEOUT_SECONDS * 1000));
            } else {
                this.basicDataSource.addConnectionProperty("connectTimeout", String.valueOf(TIMEOUT_SECONDS)); // PostgreSQL
            }

        } catch (Exception e) {
            log.error(e.toString());
        }
    }

    public Connection getConnection() throws SQLException {
        Connection connection = this.basicDataSource.getConnection();
        bootstrapConnection(connection);
        return connection;
    }

    private void bootstrapConnection(Connection connection) throws SQLException {
      Profile profile = Profile.getValue(connProfile.getProfileName());
      switch (profile) {
        case OracleEE :
        case OracleEE10g :
        case OracleEEObject :
        case OracleSE :
          log.debug("Setting optimizer_mode = 'ALL_ROWS'");
          try (PreparedStatement stmt = connection.prepareStatement("ALTER SESSION SET optimizer_mode = 'ALL_ROWS'")) {
            stmt.executeUpdate();
          }
          break;
        case Postgres :
          // no action
          break;
        case Postgres96 :
          // no action
          break;
        default :
          throw new IllegalArgumentException("Unsupported profile name: " + connProfile.getProfileName());
      }
    }

    private ClassLoader getClassLoader() throws MalformedURLException {
        URL url = new File(this.connProfile.getJar().trim()).toURI().toURL();
        return new URLClassLoader(new URL[]{url});
    }

}
