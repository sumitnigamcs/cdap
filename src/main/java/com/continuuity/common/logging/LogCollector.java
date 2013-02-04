package com.continuuity.common.logging;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.conf.Constants;
import com.google.common.collect.Maps;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public class LogCollector {

  private static final Logger LOG
      = LoggerFactory.getLogger(LogCollector.class);

  ConcurrentMap<String, LogWriter> loggers = Maps.newConcurrentMap();

  private final String pathPrefix;
  private final Configuration hConfig;
  private final CConfiguration config;
  private FileSystem fs = null;

  private FileSystem getFileSystem() throws IOException {
    if (fs == null) {
      synchronized(this) {
        if (fs == null) {
          fs = FileSystem.get(hConfig);
          // TODO horrible! what worth is the FileSystem abstraction then?
          // not sure why this is, but the local file system's hflush() does
          // not appear to work. Using the raw local file system fixes it.
          if (fs instanceof LocalFileSystem)
            fs = ((LocalFileSystem) fs).getRawFileSystem();
        }
      }
    }
    return fs;
  }

  public LogCollector(CConfiguration config, Configuration hConfig) {
    this.pathPrefix = config.get(Constants.CFG_LOG_COLLECTION_ROOT,
        Constants.DEFAULT_LOG_COLLECTION_ROOT);
    this.hConfig = hConfig;

    this.config = config;
    LOG.info("Root directory for log collection is " + pathPrefix);
  }

  public void log(LogEvent event) {
    try {
      // get the logger for this tag and send the event to it
      getLogger(event.getTag()).log(event);
    } catch (IOException e) {
      // in case of error, log the error (but not the event) in the system log
      LOG.warn("Failed to log event for " +
          event.getTag() + ": " + e.getMessage(), e);
    }
  }

  private LogWriter getLogger(String tag) throws IOException {
    // figure out whether we already have a log writer for this tag
    LogWriter logger = loggers.get(tag);
    if (logger == null) {
      synchronized (this) {
        // check if it is stull null
        logger = loggers.get(tag);
        if (logger == null) {
          // create a new log configuration for this tag
          LogConfiguration conf = new
              LogConfiguration(getFileSystem(), config, this.pathPrefix, tag);
          // create a new log writer
          logger = new LogFileWriter();
          logger.configure(conf);
          // remember this logger in the map
          loggers.put(tag, logger);
        }
      }
    }
    return logger;
  }

  public List<String> tail(String tag, int size) throws IOException {

    // determine whether we have a writer open for this log
    LogWriter writer = loggers.get(tag);
    long sizeHint = -1L;
    if (writer != null) {
      sizeHint = writer.getWritePosition();
    }

    // create a new log configuration for this tag
    LogConfiguration conf =
        new LogConfiguration(getFileSystem(), config, this.pathPrefix, tag);

    // create a new log reader
    LogReader reader = new LogFileReader();
    reader.configure(conf);
    return reader.tail(size, sizeHint);
  }

  public void close() throws IOException {
    for (Map.Entry<String, LogWriter> entry : loggers.entrySet()) {
      entry.getValue().close();
      loggers.remove(entry.getKey());
    }
  }

}
