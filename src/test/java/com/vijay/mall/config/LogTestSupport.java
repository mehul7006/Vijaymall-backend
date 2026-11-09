package com.vijay.mall.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import java.io.File;
import java.nio.file.Path;

/** Builds real, private Logback contexts and appenders, so no test touches the logging of the shared JVM. */
final class LogTestSupport {
  private LogTestSupport() {}

  static LoggerContext context() {
    LoggerContext ctx = new LoggerContext();
    ctx.setName("test-" + System.nanoTime());
    ctx.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
    return ctx;
  }

  private static PatternLayoutEncoder encoder(LoggerContext ctx) {
    PatternLayoutEncoder e = new PatternLayoutEncoder();
    e.setContext(ctx);
    e.setPattern("%msg%n");
    e.start();
    return e;
  }

  /** Same shape as production: active file mall.log, rolled by day and size, gzipped, with the given days. */
  static RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> prodLike(LoggerContext ctx, Path dir, int days, boolean start) {
    RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> a = new RollingFileAppender<>();
    a.setContext(ctx);
    a.setName("FILE");
    a.setFile(dir.resolve("mall.log").toString());
    a.setEncoder(encoder(ctx));
    SizeAndTimeBasedRollingPolicy<ch.qos.logback.classic.spi.ILoggingEvent> p = new SizeAndTimeBasedRollingPolicy<>();
    p.setContext(ctx);
    p.setParent(a);
    p.setFileNamePattern(dir.resolve("mall.%d{yyyy-MM-dd}.%i.log.gz").toString());
    p.setMaxFileSize(FileSize.valueOf("20MB"));
    p.setMaxHistory(days);
    p.setTotalSizeCap(FileSize.valueOf("5GB"));
    a.setRollingPolicy(p);
    if (start) {
      p.start();
      a.start();
    }
    return a;
  }

  /** A rolling appender whose policy is not time based (not our business). */
  static RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> fixedWindow(LoggerContext ctx, Path dir) {
    RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> a = new RollingFileAppender<>();
    a.setContext(ctx);
    a.setName("FIXED");
    a.setFile(dir.resolve("fixed.log").toString());
    a.setEncoder(encoder(ctx));
    FixedWindowRollingPolicy p = new FixedWindowRollingPolicy();
    p.setContext(ctx);
    p.setParent(a);
    p.setFileNamePattern(dir.resolve("fixed.%i.log").toString());
    p.setMinIndex(1);
    p.setMaxIndex(3);
    a.setRollingPolicy(p);
    SizeBasedTriggeringPolicy<ch.qos.logback.classic.spi.ILoggingEvent> t = new SizeBasedTriggeringPolicy<>();
    t.setContext(ctx);
    t.setMaxFileSize(FileSize.valueOf("1MB"));
    t.start();
    a.setTriggeringPolicy(t);
    p.start();
    a.start();
    return a;
  }

  static File touch(Path dir, String name, long modified) throws Exception {
    File f = dir.resolve(name).toFile();
    if (!f.createNewFile() && !f.exists()) throw new IllegalStateException(name);
    f.setLastModified(modified);
    return f;
  }
}
