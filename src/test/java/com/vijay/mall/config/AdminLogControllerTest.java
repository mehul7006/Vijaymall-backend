package com.vijay.mall.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLoggerFactory;

class AdminLogControllerTest {
  @TempDir Path dir;

  private LoggerContext ctx;
  private RuntimeSettings settings;
  private AdminLogController controller;

  @BeforeEach
  void setUp() {
    ctx = LogTestSupport.context();
    settings = mock(RuntimeSettings.class);
    when(settings.getInt(Settings.LOG_RETENTION_DAYS)).thenReturn(15);
    controller = new AdminLogController(settings, new LogSettingsApplier(settings, () -> ctx));
  }

  @AfterEach
  void tearDown() { ctx.stop(); }

  private RollingFileAppender<ILoggingEvent> attach(int days, boolean start) {
    var a = LogTestSupport.prodLike(ctx, dir, days, start);
    ctx.getLogger("ROOT").addAppender(a);
    return a;
  }

  @Test
  void levelsAreTheEffectiveOnes() {
    ctx.getLogger("ROOT").setLevel(Level.WARN);
    ctx.getLogger("com.vijay.mall").setLevel(Level.DEBUG);
    var s = controller.status();
    assertThat(s.levelApp()).isEqualTo("DEBUG");
    assertThat(s.levelRoot()).isEqualTo("WARN");
    assertThat(s.levelSql()).isEqualTo("WARN"); // not set itself: inherits from ROOT
  }

  @Test
  void withoutFileAppenderThereAreNoFiles() {
    var s = controller.status();
    assertThat(s.fileLogging()).isFalse();
    assertThat(s.folder()).isNull();
    assertThat(s.files()).isEmpty();
    assertThat(s.retentionDays()).isEqualTo(15); // the setting
  }

  @Test
  void withoutLogbackNothingIsReported() {
    var c = new AdminLogController(settings, new LogSettingsApplier(settings, NOPLoggerFactory::new));
    var s = c.status();
    assertThat(s.levelApp()).isEmpty();
    assertThat(s.fileLogging()).isFalse();
    assertThat(s.retentionDays()).isEqualTo(15);
  }

  @Test
  void listsFilesNewestFirstWithSizeAndNeverContents() throws Exception {
    attach(7, true);
    var older = LogTestSupport.touch(dir, "mall.2026-01-01.0.log.gz", 1_000_000L);
    var newer = LogTestSupport.touch(dir, "mall.2026-01-02.0.log.gz", 2_000_000L);
    Files.writeString(newer.toPath(), "secret-line-content");
    newer.setLastModified(2_000_000L);
    Files.createDirectory(dir.resolve("subdir")); // folders are not files
    var s = controller.status();
    assertThat(s.fileLogging()).isTrue();
    assertThat(s.retentionDays()).isEqualTo(7); // in force on the appender, not the setting
    assertThat(s.folder()).isEqualTo(dir.toAbsolutePath().toString());
    assertThat(s.files()).extracting(AdminLogController.LogFile::name)
        .containsExactly("mall.log", "mall.2026-01-02.0.log.gz", "mall.2026-01-01.0.log.gz"); // the active file is the newest
    assertThat(s.files().get(1).sizeBytes()).isEqualTo("secret-line-content".length());
    assertThat(s.files().get(1).lastModified().toEpochMilli()).isEqualTo(2_000_000L);
    assertThat(s.toString()).doesNotContain("secret-line-content");
    assertThat(older).exists();
  }

  @Test
  void atMostSixtyFiles() throws Exception {
    attach(30, true);
    for (int i = 0; i < 75; i++) LogTestSupport.touch(dir, "mall.f" + i + ".log.gz", 1_000_000L + i);
    var s = controller.status();
    assertThat(s.files()).hasSize(60);
    assertThat(s.files().get(0).name()).isEqualTo("mall.log");
    assertThat(s.files().get(1).name()).isEqualTo("mall.f74.log.gz");
  }

  @Test
  void equalTimesAreOrderedByName() throws Exception {
    attach(30, true);
    LogTestSupport.touch(dir, "b.log", 5_000L);
    LogTestSupport.touch(dir, "a.log", 5_000L);
    assertThat(controller.status().files()).extracting(AdminLogController.LogFile::name).containsExactly("mall.log", "a.log", "b.log");
  }

  @Test
  void policyWithoutDaysFallsBackToSetting() {
    attach(0, false); // not started, 0 = keep forever
    var s = controller.status();
    assertThat(s.fileLogging()).isTrue();
    assertThat(s.retentionDays()).isEqualTo(15);
  }

  @Test
  void otherKindOfRollingPolicyReportsTheSetting() {
    ctx.getLogger("ROOT").addAppender(LogTestSupport.fixedWindow(ctx, dir));
    var s = controller.status();
    assertThat(s.fileLogging()).isTrue();
    assertThat(s.retentionDays()).isEqualTo(15);
  }

  @Test
  void appenderWithoutFilePropertyHasNoFolder() {
    var a = new RollingFileAppender<ILoggingEvent>(); // file name only comes from the rolling pattern
    a.setContext(ctx);
    ctx.getLogger("ROOT").addAppender(a);
    var s = controller.status();
    assertThat(s.fileLogging()).isTrue();
    assertThat(s.folder()).isNull();
    assertThat(s.files()).isEmpty();
  }

  @Test
  void missingFolderGivesNoFiles() throws Exception {
    attach(30, true);
    LogTestSupport.touch(dir, "x.log", 1L);
    var gone = dir.resolve("gone");
    var a = new RollingFileAppender<ILoggingEvent>();
    a.setContext(ctx);
    a.setFile(gone.resolve("mall.log").toString());
    ctx.getLogger("ROOT").detachAndStopAllAppenders();
    ctx.getLogger("ROOT").addAppender(a);
    var s = controller.status();
    assertThat(s.folder()).isEqualTo(gone.toAbsolutePath().toString());
    assertThat(s.files()).isEmpty();
  }
}
