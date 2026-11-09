package com.vijay.mall.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /api/v1/admin is): shows the log levels and files in force. Never returns the contents of a log file. */
@RestController
@RequestMapping("/api/v1/admin/logs")
public class AdminLogController {
  static final int MAX_FILES = 60;

  /**
   * A log file on disk.
   * @param sizeBytes size in bytes
   */
  public record LogFile(String name, long sizeBytes, Instant lastModified) {}

  /**
   * The logging set-up in force.
   * @param levelApp level of the shop's own code
   * @param levelRoot level of the libraries
   * @param levelSql level of the SQL statement logger
   * @param retentionDays days rotated files are kept
   * @param fileLogging whether a rolling log file is in use
   * @param folder the folder holding the log files; {@code null} without file logging
   * @param files the newest files first, at most 60
   */
  public record Status(String levelApp, String levelRoot, String levelSql, int retentionDays, boolean fileLogging, String folder,
                       List<LogFile> files) {}

  private final RuntimeSettings settings;
  private final LogSettingsApplier applier;

  /** Creates the controller. */
  public AdminLogController(RuntimeSettings settings, LogSettingsApplier applier) {
    this.settings = settings;
    this.applier = applier;
  }

  /** Current log levels, retention and the list of log files (names and sizes only, never contents). */
  @GetMapping("/status")
  public Status status() {
    LoggerContext ctx = applier.context();
    String app = "", root = "", sql = "";
    RollingFileAppender<?> file = null;
    int days = settings.getInt(Settings.LOG_RETENTION_DAYS);
    if (ctx != null) {
      app = ctx.getLogger(LogSettingsApplier.APP_LOGGER).getEffectiveLevel().toString();
      root = ctx.getLogger(Logger.ROOT_LOGGER_NAME).getEffectiveLevel().toString();
      sql = ctx.getLogger(LogSettingsApplier.SQL_LOGGER).getEffectiveLevel().toString();
      List<RollingFileAppender<?>> all = LogSettingsApplier.fileAppenders(ctx);
      file = all.isEmpty() ? null : all.get(0);
    }
    if (file != null && file.getRollingPolicy() instanceof TimeBasedRollingPolicy<?> p && p.getMaxHistory() > 0) days = p.getMaxHistory();
    File dir = file == null || file.rawFileProperty() == null ? null : new File(file.rawFileProperty()).getAbsoluteFile().getParentFile();
    return new Status(app, root, sql, days, file != null, dir == null ? null : dir.getPath(), dir == null ? List.of() : list(dir));
  }

  private static List<LogFile> list(File dir) {
    File[] found = dir.listFiles(File::isFile);
    if (found == null) return List.of();
    return Arrays.stream(found)
        .sorted(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName))
        .limit(MAX_FILES)
        .map(f -> new LogFile(f.getName(), f.length(), Instant.ofEpochMilli(f.lastModified())))
        .toList();
  }
}
