package com.vijay.mall.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.rolling.DefaultTimeBasedFileNamingAndTriggeringPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLoggerFactory;

class LogSettingsApplierTest {
  @TempDir Path dir;

  private LoggerContext ctx;
  private RuntimeSettings settings;
  private LogSettingsApplier applier;
  private ListAppender<ILoggingEvent> own; // sees what the applier itself logs
  private Level realApp;
  private Level realRoot;

  @BeforeEach
  void setUp() {
    // The applier logs through the JVM-wide logger; remember its levels so the suite is not affected.
    var real = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    realApp = real.getLogger("com.vijay.mall").getLevel();
    realRoot = real.getLogger("ROOT").getLevel();
    ctx = LogTestSupport.context();
    settings = mock(RuntimeSettings.class);
    answer("INFO", "INFO", "OFF", 30);
    applier = new LogSettingsApplier(settings, () -> ctx);
    own = new ListAppender<>();
    own.start();
    ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LogSettingsApplier.class)).addAppender(own);
  }

  @AfterEach
  void tearDown() {
    var real = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
    ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LogSettingsApplier.class)).detachAppender(own);
    real.getLogger("com.vijay.mall").setLevel(realApp);
    real.getLogger("ROOT").setLevel(realRoot);
    ctx.stop();
  }

  private void answer(String app, String root, String sql, int days) {
    when(settings.get(Settings.LOG_LEVEL_APP)).thenReturn(app);
    when(settings.get(Settings.LOG_LEVEL_ROOT)).thenReturn(root);
    when(settings.get(Settings.LOG_LEVEL_SQL)).thenReturn(sql);
    when(settings.getInt(Settings.LOG_RETENTION_DAYS)).thenReturn(days);
  }

  private RollingFileAppender<ILoggingEvent> attach(int days) {
    var a = LogTestSupport.prodLike(ctx, dir, days, true);
    ctx.getLogger("ROOT").addAppender(a);
    return a;
  }

  private static int days(RollingFileAppender<ILoggingEvent> a) {
    return ((TimeBasedRollingPolicy<ILoggingEvent>) a.getRollingPolicy()).getMaxHistory();
  }

  private Level level(String name) { return ctx.getLogger(name).getLevel(); }

  @Test
  void startupAppliesLevelsAndRetention() {
    var a = attach(30);
    answer("DEBUG", "WARN", "DEBUG", 7);
    applier.onReady();
    assertThat(level("com.vijay.mall")).isEqualTo(Level.DEBUG);
    assertThat(level("ROOT")).isEqualTo(Level.WARN);
    assertThat(level("org.hibernate.SQL")).isEqualTo(Level.DEBUG);
    assertThat(days(a)).isEqualTo(7);
    assertThat(own.list).anyMatch(e -> e.getFormattedMessage().contains("app=DEBUG, root=WARN, sql=DEBUG, keep rotated files 7 days (1 file appender(s))"));
  }

  @Test
  void eachSettingTriggersApply() {
    var a = attach(30);
    answer("TRACE", "INFO", "OFF", 30);
    applier.onChanged(new SettingsChanged(Set.of("log.level.app")));
    assertThat(level("com.vijay.mall")).isEqualTo(Level.TRACE);

    answer("TRACE", "ERROR", "OFF", 30);
    applier.onChanged(new SettingsChanged(Set.of("log.level.root")));
    assertThat(level("ROOT")).isEqualTo(Level.ERROR);

    answer("TRACE", "ERROR", "DEBUG", 30);
    applier.onChanged(new SettingsChanged(Set.of("log.level.sql")));
    assertThat(level("org.hibernate.SQL")).isEqualTo(Level.DEBUG);

    answer("TRACE", "ERROR", "DEBUG", 15);
    applier.onChanged(new SettingsChanged(Set.of("log.retentionDays")));
    assertThat(days(a)).isEqualTo(15);
  }

  @Test
  void unrelatedChangeIsIgnored() {
    var a = attach(30);
    applier.onChanged(new SettingsChanged(Set.of("delivery.feePaise")));
    verify(settings, never()).get(any());
    assertThat(days(a)).isEqualTo(30);
  }

  @Test
  void clearedSettingFallsBackToWhatSettingsReturn() {
    var a = attach(30);
    answer("DEBUG", "INFO", "OFF", 7);
    applier.onReady();
    // admin clears the values: RuntimeSettings now answers with the environment / default
    answer("INFO", "INFO", "OFF", 30);
    applier.onChanged(new SettingsChanged(Set.of("log.level.app", "log.retentionDays")));
    assertThat(level("com.vijay.mall")).isEqualTo(Level.INFO);
    assertThat(days(a)).isEqualTo(30);
  }

  @Test
  void noFileAppenderOnlyLevelsApply() {
    answer("WARN", "ERROR", "OFF", 15);
    applier.onReady();
    assertThat(level("com.vijay.mall")).isEqualTo(Level.WARN);
    assertThat(level("org.hibernate.SQL")).isEqualTo(Level.OFF);
    assertThat(own.list).anyMatch(e -> e.getFormattedMessage().contains("0 file appender(s)"));
  }

  @Test
  void otherAppendersAreLeftAlone() {
    var fixed = LogTestSupport.fixedWindow(ctx, dir);
    var plain = new FileAppender<ILoggingEvent>();
    plain.setContext(ctx);
    plain.setName("PLAIN");
    ctx.getLogger("ROOT").addAppender(fixed);
    ctx.getLogger("ROOT").addAppender(plain);
    var timed = attach(30);
    ctx.getLogger("com.other").addAppender(timed); // same appender on a second logger counts once
    answer("INFO", "INFO", "OFF", 7);
    applier.onReady();
    assertThat(days(timed)).isEqualTo(7);
    assertThat(own.list).anyMatch(e -> e.getFormattedMessage().contains("(1 file appender(s))"));
  }

  @Test
  void policyNotStartedOrWithoutRemoverStillGetsTheDays() {
    var a = LogTestSupport.prodLike(ctx, dir, 30, false);
    ctx.getLogger("ROOT").addAppender(a);
    answer("INFO", "INFO", "OFF", 15);
    applier.onReady(); // no naming policy yet: only the field is set
    assertThat(days(a)).isEqualTo(15);
    var p = (TimeBasedRollingPolicy<ILoggingEvent>) a.getRollingPolicy();
    var naming = new DefaultTimeBasedFileNamingAndTriggeringPolicy<ILoggingEvent>();
    p.setTimeBasedFileNamingAndTriggeringPolicy(naming); // present but not started: no archive remover
    answer("INFO", "INFO", "OFF", 7);
    applier.onReady();
    assertThat(days(a)).isEqualTo(7);
  }

  @Test
  void errorsAreSwallowed() {
    when(settings.get(Settings.LOG_LEVEL_APP)).thenThrow(new IllegalStateException("db down"));
    applier.onReady();
    applier.onChanged(new SettingsChanged(Set.of("log.level.app")));
    assertThat(own.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("db down"));
  }

  @Test
  void invalidLevelAndInvalidDaysAreSkippedWithWarning() {
    var a = attach(30);
    answer("LOUD", "INFO", "OFF", 0);
    level("com.vijay.mall");
    applier.onReady();
    assertThat(level("com.vijay.mall")).isNull();
    assertThat(days(a)).isEqualTo(30);
    assertThat(own.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("'LOUD'"));
    assertThat(own.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("0 days"));
  }

  @Test
  void notLogbackIsReportedNotThrown() {
    var other = new LogSettingsApplier(settings, NOPLoggerFactory::new);
    other.onReady();
    assertThat(other.context()).isNull();
    assertThat(own.list).anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("not Logback"));
  }

  @Test
  void defaultConstructorUsesTheRealLoggingSystem() {
    assertThat(new LogSettingsApplier(settings).context()).isSameAs(org.slf4j.LoggerFactory.getILoggerFactory());
  }

  /** Proof on a real rolling appender: after the change the next roll-over deletes by the NEW number of days. */
  @Test
  void newRetentionReallyDecidesWhatIsDeleted() throws Exception {
    var a = attach(30);
    ctx.getLogger("ROOT").setLevel(Level.INFO);
    for (int d = 5; d <= 14; d++) LogTestSupport.touch(dir, name(LocalDate.now().minusDays(d)), 0);
    String old8 = name(LocalDate.now().minusDays(8));

    // control: with 30 days a file 8 days old survives a day change
    newDay(a, "line on a new day");
    Thread.sleep(700);
    assertThat(dir.resolve(old8)).exists();

    answer("INFO", "INFO", "OFF", 7);
    applier.onChanged(new SettingsChanged(Set.of("log.retentionDays")));
    newDay(a, "line on the next new day");
    long until = System.currentTimeMillis() + 10_000;
    while (Files.exists(dir.resolve(old8)) && System.currentTimeMillis() < until) Thread.sleep(50);
    assertThat(dir.resolve(old8)).doesNotExist();
  }

  /** Declares the next time check as due (as if midnight just passed), so the next log line rolls the file like production does. */
  private void newDay(RollingFileAppender<ILoggingEvent> a, String line) throws Exception {
    var naming = ((TimeBasedRollingPolicy<ILoggingEvent>) a.getRollingPolicy()).getTimeBasedFileNamingAndTriggeringPolicy();
    var f = naming.getClass().getSuperclass().getDeclaredField("atomicNextCheck");
    f.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicLong) f.get(naming)).set(0L);
    // Logback cleans once per elapsed day: pretend the last clean-up was yesterday.
    var remover = naming.getArchiveRemover();
    var h = ch.qos.logback.core.rolling.helper.TimeBasedArchiveRemover.class.getDeclaredField("lastHeartBeat");
    h.setAccessible(true);
    if (h.getLong(remover) > 0) h.setLong(remover, System.currentTimeMillis() - 86_400_000L);
    ctx.getLogger("t").info(line);
  }

  private static String name(LocalDate d) { return "mall." + d + ".0.log.gz"; }
}
