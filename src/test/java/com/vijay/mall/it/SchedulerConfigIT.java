package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * With virtual threads on, Spring builds a scheduler that gives every task run its own virtual thread. A pool size setting would
 * therefore do nothing, which is why none is configured.
 */
class SchedulerConfigIT extends IntegrationTestBase {
  @Autowired TaskScheduler scheduler;

  @Test
  void theSchedulerStartsEachTaskOnItsOwnVirtualThreadSoThereIsNoPoolToSize() {
    assertThat(scheduler).isInstanceOf(SimpleAsyncTaskScheduler.class);
  }
}
