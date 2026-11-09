package com.vijay.mall.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.ArchiveRemover;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Puts the logging settings the admin chose into effect without a restart: the log levels (shop code, libraries, SQL statements) and
 * how many days rotated log files are kept. Runs once when the application is ready and again after every save that touches one of
 * these settings. It never throws: a problem is logged as a warning and the server carries on.
 */
@Component
public class LogSettingsApplier {
  private static final Logger LOG = LoggerFactory.getLogger(LogSettingsApplier.class);
  static final String APP_LOGGER = "com.vijay.mall";
  static final String SQL_LOGGER = "org.hibernate.SQL";

  private final RuntimeSettings settings;
  private final Supplier<ILoggerFactory> factory;

  /** Creates the applier using the real logging system. */
  @Autowired
  public LogSettingsApplier(RuntimeSettings settings) {
    this(settings, LoggerFactory::getILoggerFactory);
  }

  LogSettingsApplier(RuntimeSettings settings, Supplier<ILoggerFactory> factory) {
    this.settings = settings;
    this.factory = factory;
  }

  /** Applies the saved logging settings once the application has started. */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    apply();
  }

  /** Re-applies the logging settings when a save touched one of them. */
  @EventListener
  public void onChanged(SettingsChanged event) {
    if (event.touches(Settings.LOG_LEVEL_APP) || event.touches(Settings.LOG_LEVEL_ROOT) || event.touches(Settings.LOG_LEVEL_SQL)
        || event.touches(Settings.LOG_RETENTION_DAYS)) {
      apply();
    }
  }

  /** Applies all four settings. Safe to call at any time. */
  void apply() {
    try {
      LoggerContext ctx = context();
      if (ctx == null) {
        LOG.warn("Log settings not applied: the logging system is not Logback");
        return;
      }
      String app = settings.get(Settings.LOG_LEVEL_APP);
      String root = settings.get(Settings.LOG_LEVEL_ROOT);
      String sql = settings.get(Settings.LOG_LEVEL_SQL);
      setLevel(ctx, APP_LOGGER, app);
      setLevel(ctx, Logger.ROOT_LOGGER_NAME, root);
      setLevel(ctx, SQL_LOGGER, sql);
      int days = settings.getInt(Settings.LOG_RETENTION_DAYS);
      int files = setRetention(ctx, days);
      LOG.info("Log settings applied: app={}, root={}, sql={}, keep rotated files {} days ({} file appender(s))", app, root, sql, days, files);
    } catch (RuntimeException e) {
      LOG.warn("Log settings could not be applied: {}", e.toString());
    }
  }

  private static void setLevel(LoggerContext ctx, String name, String value) {
    Level level = Level.toLevel(value, null);
    if (level == null) {
      LOG.warn("Log level '{}' for {} is not valid; left as it was", value, name);
      return;
    }
    ctx.getLogger(name).setLevel(level);
  }

  /** Changes the days kept on every time-based rolling file appender; returns how many appenders were changed. */
  private static int setRetention(LoggerContext ctx, int days) {
    if (days < 1) {
      LOG.warn("Retention of {} days is not valid; left as it was", days);
      return 0;
    }
    int changed = 0;
    for (RollingFileAppender<?> a : fileAppenders(ctx)) {
      if (a.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> p) {
        p.setMaxHistory(days); // what a later start of the policy would use
        // The running policy handed its value to the archive remover when it started: that copy decides what is deleted.
        ArchiveRemover remover = p.getTimeBasedFileNamingAndTriggeringPolicy() == null ? null
            : p.getTimeBasedFileNamingAndTriggeringPolicy().getArchiveRemover();
        if (remover != null) remover.setMaxHistory(days);
        changed++;
      }
    }
    return changed;
  }

  /** The Logback context, or null if SLF4J is not backed by Logback. */
  LoggerContext context() {
    return factory.get() instanceof LoggerContext c ? c : null;
  }

  /** Every rolling file appender attached to any logger, each once. */
  static List<RollingFileAppender<?>> fileAppenders(LoggerContext ctx) {
    List<RollingFileAppender<?>> out = new ArrayList<>();
    for (ch.qos.logback.classic.Logger l : ctx.getLoggerList()) {
      Iterator<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> it = l.iteratorForAppenders();
      while (it.hasNext()) {
        if (it.next() instanceof RollingFileAppender<?> r && out.stream().noneMatch(x -> x == r)) out.add(r);
      }
    }
    return out;
  }
}
