package com.vijay.mall.order.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.ShopIdentity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** OrderRepository logic that does not need a database: row mapping, order numbers, optimistic locking, list filters. */
class OrderRepositoryCoverageTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private OrderJpa orders;
  private OrderItemJpa items;
  private ShopIdentity shop;
  private EntityManager em;
  private OrderRepository repo;

  @BeforeEach
  void setUp() {
    orders = mock(OrderJpa.class);
    items = mock(OrderItemJpa.class);
    shop = mock(ShopIdentity.class);
    em = mock(EntityManager.class);
    repo = new OrderRepository(orders, items, mock(OrderHistoryJpa.class), shop);
    ReflectionTestUtils.setField(repo, "em", em);
  }

  private void sequenceIs(long n) {
    Query q = mock(Query.class);
    when(em.createNativeQuery("select nextval('order_no_seq')")).thenReturn(q);
    when(q.getSingleResult()).thenReturn(n);
  }

  private void prefix(String p) { when(shop.identity()).thenReturn(new ShopIdentity.Info("n", "p", "a", "pr", "d", p)); }

  private static OrderEntity entity(UUID id, String status, Long version) {
    OrderEntity e = new OrderEntity();
    e.id = id;
    e.orderNo = "VSM-" + id.toString().substring(0, 4);
    e.userId = UUID.randomUUID();
    e.status = status;
    e.version = version;
    e.totalPaise = 1234;
    e.placedAt = NOW;
    return e;
  }

  @Test
  void orderNumberUsesTheShopPrefixOrFallsBackToVsm() {
    sequenceIs(1001);
    prefix("MALL");
    assertThat(repo.nextOrderNo()).isEqualTo("MALL-1001");
    prefix(null);
    assertThat(repo.nextOrderNo()).isEqualTo("VSM-1001");
    prefix("   ");
    assertThat(repo.nextOrderNo()).isEqualTo("VSM-1001");
  }

  @Test
  void aMissingVersionIsReadAsZeroAndAPresentOneIsKept() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    OrderEntity noVersion = entity(a, "PLACED", null);
    noVersion.stockReserved = true;
    when(orders.findById(a)).thenReturn(Optional.of(noVersion));
    when(orders.findById(b)).thenReturn(Optional.of(entity(b, "PLACED", 7L)));
    assertThat(repo.find(a)).get().satisfies(r -> {
      assertThat(r.version()).isZero();
      assertThat(r.stockReserved()).isTrue();
      assertThat(r.total()).isEqualTo(1234);
    });
    assertThat(repo.find(b)).get().extracting(OrderRepository.Row::version).isEqualTo(7L);
  }

  @Test
  void statusUpdateRefusesAMissingOrderAnUnversionedOneOrAStaleVersion() {
    UUID missing = UUID.randomUUID();
    UUID unversioned = UUID.randomUUID();
    UUID stale = UUID.randomUUID();
    when(orders.findById(missing)).thenReturn(Optional.empty());
    when(orders.findById(unversioned)).thenReturn(Optional.of(entity(unversioned, "PLACED", null)));
    when(orders.findById(stale)).thenReturn(Optional.of(entity(stale, "PLACED", 4L)));
    assertThat(repo.updateStatus(missing, "CONFIRMED", 0, NOW)).isFalse();
    assertThat(repo.updateStatus(unversioned, "CONFIRMED", 0, NOW)).isFalse();
    assertThat(repo.updateStatus(stale, "CONFIRMED", 3, NOW)).isFalse();
    verify(orders, never()).saveAndFlush(any());
  }

  @Test
  void statusUpdateWithTheRightVersionIsSavedAndStampedWithTheTime() {
    UUID id = UUID.randomUUID();
    OrderEntity e = entity(id, "PLACED", 4L);
    when(orders.findById(id)).thenReturn(Optional.of(e));
    assertThat(repo.updateStatus(id, "CONFIRMED", 4, NOW)).isTrue();
    assertThat(e.status).isEqualTo("CONFIRMED");
    assertThat(e.updatedAt).isEqualTo(NOW);
    verify(orders).saveAndFlush(e);
  }

  @Test
  void aLostOptimisticLockRaceIsReportedAsFalseNotThrown() {
    UUID id = UUID.randomUUID();
    OrderEntity e = entity(id, "PLACED", 4L);
    when(orders.findById(id)).thenReturn(Optional.of(e));
    when(orders.saveAndFlush(e)).thenThrow(new OptimisticLockingFailureException("raced"));
    assertThat(repo.updateStatus(id, "CONFIRMED", 4, NOW)).isFalse();
  }

  @Test
  void rowAndItemConstructorsFillDefaults() {
    UUID id = UUID.randomUUID();
    var r20 = new OrderRepository.Row(id, "N", id, "PICKUP", "PLACED", 1, null, 0, 0, 0, 0, 1, 0, false, "COD", "NONE", null, null, NOW, 5);
    assertThat(r20.deliverySlot()).isNull();
    assertThat(r20.cancelReason()).isNull();
    assertThat(r20.stockReserved()).isFalse();
    var r22 = new OrderRepository.Row(id, "N", id, "PICKUP", "PLACED", 1, null, 0, 0, 0, 0, 1, 0, false, "COD", "NONE", null, null, NOW, 5, "9-11", "why");
    assertThat(r22.deliverySlot()).isEqualTo("9-11");
    assertThat(r22.cancelReason()).isEqualTo("why");
    assertThat(r22.stockReserved()).isFalse();

    var i9 = new OrderRepository.NewItem(id, "Rice", 50, 2, 100, true, false, "Chokha", "kg");
    assertThat(i9.couponDiscount()).isZero();
    assertThat(i9.nameGu()).isEqualTo("Chokha");
    var i7 = new OrderRepository.NewItem(id, "Rice", 50, 2, 100, true, false);
    assertThat(i7.nameGu()).isEqualTo("Rice");
    assertThat(i7.unitLabel()).isEmpty();
    assertThat(i7.couponDiscount()).isZero();
  }

  @Test
  void insertItemDefaultsAMissingGujaratiNameAndUnitLabel() {
    UUID order = UUID.randomUUID();
    repo.insertItem(order, new OrderRepository.NewItem(UUID.randomUUID(), "Rice", 50, 2, 100, true, true, null, null, 7));
    ArgumentCaptor<OrderItemEntity> c = ArgumentCaptor.forClass(OrderItemEntity.class);
    verify(items).save(c.capture());
    assertThat(c.getValue().nameGu).isEqualTo("Rice");
    assertThat(c.getValue().unitLabel).isEmpty();
    assertThat(c.getValue().couponDiscountPaise).isEqualTo(7);
    assertThat(c.getValue().lineTotalPaise).isEqualTo(100);
    assertThat(c.getValue().orderId).isEqualTo(order);
  }

  @Test
  void openAndActiveOrderCountsComeFromTheJpaQueries() {
    UUID u = UUID.randomUUID();
    when(orders.countOpen(u)).thenReturn(2L);
    when(orders.countByUserIdAndStatusNot(u, "CANCELLED")).thenReturn(5L);
    assertThat(repo.openOrderCount(u)).isEqualTo(2);
    assertThat(repo.activeOrderCount(u)).isEqualTo(5);
  }

  @Test
  void numbersOfNoOrdersDoNotHitTheDatabase() {
    assertThat(repo.numbers(List.of())).isEmpty();
    verify(orders, never()).numbers(any());
    UUID a = UUID.randomUUID();
    when(orders.numbers(List.of(a))).thenReturn(Collections.singletonList(new Object[] {a, "VSM-5"}));
    assertThat(repo.numbers(List.of(a))).containsEntry(a, "VSM-5");
  }

  @Test
  void adminListPicksTheRightQueryForEveryFilterCombination() {
    var empty = new PageImpl<OrderEntity>(List.of());
    when(orders.findAll(any(Pageable.class))).thenReturn(empty);
    when(orders.searchByNumber(any(), any())).thenReturn(empty);
    when(orders.findByStatus(any(), any())).thenReturn(empty);
    when(orders.searchByStatusAndNumber(any(), any(), any())).thenReturn(empty);

    repo.adminList(null, null, 0, 10);
    repo.adminList("  ", "  ", 0, 10);
    verify(orders, Mockito.times(2)).findAll(any(Pageable.class));

    repo.adminList(null, " 1001 ", 0, 10);
    verify(orders).searchByNumber(ArgumentMatchers.eq("%1001%"), any());

    repo.adminList("PLACED", null, 0, 10);
    repo.adminList("PLACED", "", 0, 10);
    verify(orders, Mockito.times(2)).findByStatus(ArgumentMatchers.eq("PLACED"), any());

    repo.adminList("PLACED", " 77 ", 0, 10);
    verify(orders).searchByStatusAndNumber(ArgumentMatchers.eq("PLACED"), ArgumentMatchers.eq("%77%"), any());
    assertThat(OrderRepository.pattern(" VSM-1_0%1 ")).as("case folded, wildcards removed").isEqualTo("%vsm-101%");
    verify(items, never()).countsFor(any());
  }

  @Test
  void theThreeArgumentAdminListHasNoSearchText() {
    UUID id = UUID.randomUUID();
    OrderEntity e = entity(id, "PLACED", 0L);
    when(orders.findByStatus(ArgumentMatchers.eq("PLACED"), any()))
        .thenReturn(new PageImpl<>(List.of(e), PageRequest.of(1, 5), 11));
    when(items.countsFor(List.of(id))).thenReturn(Collections.singletonList(new Object[] {id, 6L}));
    Page<OrderRepository.SummaryRow> p = repo.adminList("PLACED", 1, 5);
    assertThat(p.total()).isEqualTo(11);
    assertThat(p.page()).isEqualTo(1);
    assertThat(p.size()).isEqualTo(5);
    assertThat(p.items()).singleElement().satisfies(r -> {
      assertThat(r.itemCount()).isEqualTo(6);
      assertThat(r.totalPaise()).isEqualTo(1234);
      assertThat(r.id()).isEqualTo(id);
    });
  }

  @Test
  void anOrderWithoutItemRowsCountsZeroItems() {
    UUID id = UUID.randomUUID();
    when(orders.findByUserId(any(), any())).thenReturn(new PageImpl<>(List.of(entity(id, "PLACED", 0L))));
    when(items.countsFor(List.of(id))).thenReturn(List.of());
    assertThat(repo.list(UUID.randomUUID(), 0, 10).items()).singleElement().satisfies(r -> assertThat(r.itemCount()).isZero());
  }
}
