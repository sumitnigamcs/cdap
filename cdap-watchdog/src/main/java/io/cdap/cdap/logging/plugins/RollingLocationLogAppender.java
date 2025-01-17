/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.logging.plugins;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LogbackException;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.rolling.RollingPolicy;
import ch.qos.logback.core.rolling.RolloverFailure;
import ch.qos.logback.core.rolling.TriggeringPolicy;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.cdap.cdap.api.logging.AppenderContext;
import io.cdap.cdap.common.conf.Constants;
import io.cdap.cdap.common.io.Syncable;
import io.cdap.cdap.proto.id.NamespaceId;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.twill.filesystem.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Rolling Appender for {@link Location}
 */
public class RollingLocationLogAppender extends OutputStreamAppender<ILoggingEvent> implements Flushable, Syncable {
  private static final Logger LOG = LoggerFactory.getLogger(RollingLocationLogAppender.class);

  private TriggeringPolicy<ILoggingEvent> triggeringPolicy;
  private RollingPolicy rollingPolicy;

  // used as cache to avoid type casting of triggeringPolicy on every event
  private LocationTriggeringPolicy locationTriggeringPolicy;
  // used as cache to avoid type casting of triggeringPolicy on every event
  private LocationRollingPolicy locationRollingPolicy;

  // log file path will be created by this appender as: <basePath>/namespaceId/applicationId/<filePath>
  private String basePath;
  private String filePath;
  private String filePermissions;
  private String dirPermissions;
  private LocationManager locationManager;
  private long fileMaxInactiveTimeMs;

  //Declaring the delegator that handles switching between various locationOutputStreams as and when required
  private final DelegatingOutputStream delegatingOutputStream;

  public RollingLocationLogAppender() {
    setName(getClass().getName());

    //Instantiating delegatingOutputStream class with a null OutputStream delegate which in turn gets
    // delegated the respective locationOutputStream when required
    delegatingOutputStream = new DelegatingOutputStream(new NullOutputStream());

    //setting the outputStream defined in OutputStreamAppender to the delegator to enable
    // switching between streams without involvement of OutputStreamAppender
    setOutputStream(delegatingOutputStream);
  }

  @Override
  public void start() {
    // These should all passed. The settings are from the custom-log-pipeline.xml and
    // the context must be AppenderContext
    Preconditions.checkState(basePath != null, "Property basePath must be base directory.");
    Preconditions.checkState(filePath != null, "Property filePath must be filePath along with filename.");
    Preconditions.checkState(triggeringPolicy != null, "Property triggeringPolicy must be specified.");
    Preconditions.checkState(rollingPolicy != null, "Property rollingPolicy must be specified");
    Preconditions.checkState(encoder != null, "Property encoder must be specified.");
    Preconditions.checkState(dirPermissions != null, "Property dirPermissions cannot be null");
    Preconditions.checkState(filePermissions != null, "Property filePermissions cannot be null");

    if (context instanceof AppenderContext) {
      AppenderContext context = (AppenderContext) this.context;
      locationManager = new LocationManager(context.getLocationFactory(), basePath, dirPermissions, filePermissions,
                                            fileMaxInactiveTimeMs);
      filePath = filePath.replace("instanceId", Integer.toString(context.getInstanceId()));
    } else if (!Boolean.TRUE.equals(context.getObject(Constants.Logging.PIPELINE_VALIDATION))) {
      throw new IllegalStateException("Expected logger context instance of " + AppenderContext.class.getName() +
                                        " but got " + context.getClass().getName());
    }

    started = true;
  }

  @Override
  protected void append(ILoggingEvent eventObject) throws LogbackException {
    try {
      String namespaceId = eventObject.getMDCPropertyMap().get(LocationManager.TAG_NAMESPACE_ID);

      if (namespaceId != null && !namespaceId.equals(NamespaceId.SYSTEM.getNamespace())) {
        LocationIdentifier logLocationIdentifier = locationManager.getLocationIdentifier(eventObject
                                                                                           .getMDCPropertyMap());
        rollover(logLocationIdentifier, eventObject);
        OutputStream locationOutputStream = locationManager.getLocationOutputStream(logLocationIdentifier, filePath);

        //change the locationOutputStream to which outputStream in the OutputStreamAppender points
        delegatingOutputStream.setDelegate((FilterOutputStream) locationOutputStream);

        writeOut(eventObject);
      }
    } catch (IllegalArgumentException iae) {
      // this shouldn't happen
      LOG.error("Unrecognized context ", iae);
    } catch (IOException ioe) {
      throw new LogbackException("Exception while appending event. ", ioe);
    } catch (RolloverFailure rolloverFailure) {
      throw new LogbackException("Exception while rolling over. ", rolloverFailure);
    }
  }

  private void rollover(final LocationIdentifier identifier, ILoggingEvent event) throws RolloverFailure, IOException {
    // Close unclosed outputstream before proceeding further
    closeInvalidStream();

    if (!locationManager.getActiveLocations().containsKey(identifier)) {
      return;
    }

    final LocationOutputStream locationOutputStream = locationManager.getActiveLocations().get(identifier);

    if (triggeringPolicy instanceof LocationTriggeringPolicy) {
      // no need to type cast on every event
      if (locationTriggeringPolicy == null) {
        locationTriggeringPolicy = ((LocationTriggeringPolicy) triggeringPolicy);
      }

      locationTriggeringPolicy.setLocation(locationOutputStream.getLocation());
      // set number of bytes written to locationOutputStream, we need to do this because HDFS does not provide
      // correct size of the file
      locationTriggeringPolicy.setActiveLocationSize(locationOutputStream.getNumOfBytes());

      if (locationTriggeringPolicy.isTriggeringEvent(event)) {
        if (rollingPolicy instanceof LocationRollingPolicy) {
          // no need to type cast on every event
          if (locationRollingPolicy == null) {
            locationRollingPolicy = ((LocationRollingPolicy) rollingPolicy);
          }

          locationRollingPolicy.setLocation(locationOutputStream.getLocation(), new Closeable() {
            @Override
            public void close() throws IOException {
              locationManager.getActiveLocations().remove(identifier);
              try {
                locationOutputStream.close();
              } catch (IOException e) {
                // If there is an exception while closing the outputstream, remember it and throw an exception so
                // that it can be closed on another event append
                locationManager.setInvalidOutputStream(locationOutputStream);
                LOG.trace("Exception while closing the output stream for {}, will retry to close it later",
                          identifier, e);
                throw e;
              }
            }
          });

          locationRollingPolicy.rollover();
        }
      }
    }
  }

  private void closeInvalidStream() throws IOException {
    if (locationManager.getInvalidOutputStream() != null) {
      LocationOutputStream invalidOutputStream = locationManager.getInvalidOutputStream();
      try {
        invalidOutputStream.close();
        LOG.info("Successfully closed output stream {}", invalidOutputStream.getLocation());
        // because close was successful make this output stream null
        locationManager.setInvalidOutputStream(null);
      } catch (IOException e) {
        LOG.warn("Exception while closing invalid output stream for {}, will retry to close it later",
                 invalidOutputStream.getLocation().toURI().toString(), e);
        throw e;
      }
    }
  }

  @Override
  public void flush() throws IOException {
    if (locationManager != null) {
      locationManager.flush();
    }
  }

  @Override
  public void sync() throws IOException {
    if (locationManager != null) {
      locationManager.sync();
    }
  }

  @Override
  public void stop() {
    LOG.info("Stopping appender {}", this.name);
    super.stop();
    locationManager.close();

  }

  @VisibleForTesting
  LocationManager getLocationManager() {
    return locationManager;
  }

  public void setRollingPolicy(RollingPolicy policy) {
    rollingPolicy = policy;
  }

  public void setTriggeringPolicy(TriggeringPolicy<ILoggingEvent> policy) {
    triggeringPolicy = policy;
  }

  public String getBasePath() {
    return basePath;
  }

  public void setBasePath(String basePath) {
    this.basePath = basePath;
  }

  public String getFilePermissions() {
    return filePermissions;
  }

  public void setFilePermissions(String filePermissions) {
    this.filePermissions = filePermissions;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public String getDirPermissions() {
    return dirPermissions;
  }

  public void setDirPermissions(String dirPermissions) {
    this.dirPermissions = dirPermissions;
  }

  // It is an optional parameter, which takes number of miliseconds. Appender will close a file if it is not
  // modified for fileMaxInactiveTimeMs period of time
  public void setFileMaxInactiveTimeMs(long fileMaxInactiveTimeMs) {
    this.fileMaxInactiveTimeMs = fileMaxInactiveTimeMs;
  }

  public long getFileMaxInactiveTimeMs() {
    return fileMaxInactiveTimeMs;
  }

  //Implement the class that handles delegation of locationOutputStream to outputStream defined in OutputStreamAppender
  private class DelegatingOutputStream extends FilterOutputStream {
    DelegatingOutputStream(OutputStream outputStream) {
      super(outputStream);
    }

    public void setDelegate(FilterOutputStream delegate) {
      this.out = delegate;
    }

    //Override in order to execute the function in the scope of LocationOutputStream instead of FilterOutputStream
    @Override
    public void write(byte[] b) throws IOException {
      this.out.write(b);
    }
  }
}
