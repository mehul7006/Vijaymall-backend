package com.vijay.mall.notification;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vijay.mall.notification.infra.LoggingPushSender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingPushSenderTest {
  @Test
  void logsHowManyDevicesAndTheMessage() {
    var logger = (Logger) LoggerFactory.getLogger(LoggingPushSender.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      new LoggingPushSender().send(List.of("a", "b"), "Hello", "World", Map.of());
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list).singleElement()
        .satisfies(e -> assertThat(e.getFormattedMessage()).contains("2 device(s)").contains("Hello").contains("World"));
  }
}
