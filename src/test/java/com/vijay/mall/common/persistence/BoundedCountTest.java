package com.vijay.mall.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/** Totals that are always correct, never stale, and cheap: nothing is counted unless another page follows, and never beyond the cap. */
class BoundedCountTest {
  private static List<Integer> rows(int n) { return IntStream.range(0, n).boxed().toList(); }

  @Test
  void aPageThatIsNotFullSettlesTheTotalWithoutCountingAnything() {
    AtomicInteger counts = new AtomicInteger();
    Page<Integer> first = BoundedCount.assemble(rows(7), 0, 20, () -> { counts.incrementAndGet(); return 0; });
    assertThat(first.total()).isEqualTo(7);
    assertThat(first.totalExact()).isTrue();
    assertThat(first.hasMore()).isFalse();
    assertThat(first.items()).hasSize(7);
    Page<Integer> last = BoundedCount.assemble(rows(5), 3, 20, () -> { counts.incrementAndGet(); return 0; });
    assertThat(last.total()).as("three full pages and five rows").isEqualTo(65);
    assertThat(last.hasMore()).isFalse();
    Page<Integer> empty = BoundedCount.assemble(List.of(), 0, 20, () -> { counts.incrementAndGet(); return 0; });
    assertThat(empty.total()).isZero();
    assertThat(empty.items()).isEmpty();
    assertThat(counts).as("no count query at all").hasValue(0);
  }

  @Test
  void aFullPageFollowedByMoreRowsIsCountedAndExactBelowTheCap() {
    Page<Integer> p = BoundedCount.assemble(rows(21), 0, 20, () -> 4_321);
    assertThat(p.items()).hasSize(20);
    assertThat(p.total()).isEqualTo(4_321);
    assertThat(p.totalExact()).isTrue();
    assertThat(p.hasMore()).isTrue();
    Page<Integer> atTheCap = BoundedCount.assemble(rows(21), 0, 20, () -> BoundedCount.CAP);
    assertThat(atTheCap.total()).isEqualTo(BoundedCount.CAP);
    assertThat(atTheCap.totalExact()).as("exactly the cap is still exact").isTrue();
  }

  @Test
  void aListLongerThanTheCapSaysSoInsteadOfPretendingToKnowTheTotal() {
    Page<Integer> p = BoundedCount.assemble(rows(21), 2, 20, () -> BoundedCount.CAP + 1L);
    assertThat(p.total()).isEqualTo(BoundedCount.CAP);
    assertThat(p.totalExact()).isFalse();
    assertThat(p.hasMore()).as("another page follows, exactly known from the extra row").isTrue();
  }

  @Test
  void aPageBeyondTheEndReportsTheRealTotalNotTheOffset() {
    Page<Integer> past = BoundedCount.assemble(List.of(), 5, 20, () -> 45L);
    assertThat(past.items()).isEmpty();
    assertThat(past.total()).as("not page * size = 100").isEqualTo(45);
    assertThat(past.totalExact()).isTrue();
    assertThat(past.hasMore()).isFalse();
    Page<Integer> pastBig = BoundedCount.assemble(List.of(), 5, 20, () -> BoundedCount.CAP + 1L);
    assertThat(pastBig.total()).isEqualTo(BoundedCount.CAP);
    assertThat(pastBig.totalExact()).isFalse();
    assertThat(BoundedCount.assemble(List.of(), 5, 20, () -> 0L).total()).as("the list shrank to nothing").isZero();
  }

  @Test
  void aTotalNeverContradictsTheRowsAlreadyShown() {
    // rows were added between reading the page and counting: the total still covers what is on screen plus the next row
    Page<Integer> p = BoundedCount.assemble(rows(21), 1, 20, () -> 10L);
    assertThat(p.total()).isEqualTo(41);
  }

  @Test
  void theWindowReadsOneExtraRowAtTheRightOffset() {
    Window w = Window.of(3, 20, Sort.by("createdAt"));
    assertThat(w.getOffset()).isEqualTo(60);
    assertThat(w.getPageSize()).isEqualTo(21);
    assertThat(w.getPageNumber()).isEqualTo(3);
    assertThat(w.getSort()).isEqualTo(Sort.by("createdAt"));
    assertThat(w.hasPrevious()).isTrue();
    assertThat(w.first().getOffset()).isZero();
    assertThat(w.next().getOffset()).isEqualTo(80);
    assertThat(w.previousOrFirst().getOffset()).isEqualTo(40);
    assertThat(Window.of(0, 20, Sort.unsorted()).previousOrFirst().getOffset()).isZero();
    assertThat(Window.of(0, 20, Sort.unsorted()).hasPrevious()).isFalse();
    assertThat(w.withPage(1).getOffset()).isEqualTo(20);
  }

  @Test
  void theNativeCountStopsAtTheCapPlusOne() {
    EntityManager em = mock(EntityManager.class);
    Query q = mock(Query.class);
    when(em.createNativeQuery(anyString())).thenReturn(q);
    when(q.getSingleResult()).thenReturn(10_001L);
    assertThat(BoundedCount.count(em, "audit_log a where a.action = :action", Map.of("action", "X"))).isEqualTo(10_001);
    verify(em).createNativeQuery("select count(*) from (select 1 from audit_log a where a.action = :action limit 10001) bounded");
    verify(q).setParameter("action", "X");
  }

  @Test
  void aPageWithATotalKeepsTheOldFourArgumentShape() {
    Page<Integer> p = new Page<>(rows(5), 0, 20, 5);
    assertThat(p.totalExact()).isTrue();
    assertThat(p.hasMore()).isFalse();
    assertThat(new Page<>(rows(20), 0, 20, 45).hasMore()).isTrue();
    assertThat(Page.clampSize(500)).isEqualTo(50);
  }
}
