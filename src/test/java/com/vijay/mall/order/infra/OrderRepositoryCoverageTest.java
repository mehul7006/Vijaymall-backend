package com.vijay.mall.order.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.ShopIdentity;
import com.vijay.mall.identity.infra.UserRepository;
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
    when(orders.findPageBy(any(Pageable.class))).thenReturn(List.of());
    when(orders.listByNumber(any(), any())).thenReturn(List.of());
    when(orders.findPageByStatus(any(), any())).thenReturn(List.of());
    when(orders.listByStatusAndNumber(any(), any(), any())).thenReturn(List.of());

    repo.adminList(null, null, 0, 10);
    repo.adminList("  ", "  ", 0, 10);
    verify(orders, org.mockito.Mockito.times(2)).findPageBy(any(Pageable.class));

    repo.adminList(null, " 1001 ", 0, 10);
    verify(orders).listByNumber(org.mockito.ArgumentMatchers.eq("%1001%"), any());

    repo.adminList("PLACED", null, 0, 10);
    repo.adminList("PLACED", "", 0, 10);
    verify(orders, org.mockito.Mockito.times(2)).findPageByStatus(org.mockito.ArgumentMatchers.eq("PLACED"), any());

    repo.adminList("PLACED", " 77 ", 0, 10);
    verify(orders).listByStatusAndNumber(org.mockito.ArgumentMatchers.eq("PLACED"), org.mockito.ArgumentMatchers.eq("%77%"), any());
    assertThat(OrderRepository.pattern(" VSM-1_0%1 ")).as("case folded, wildcards removed").isEqualTo("%vsm-101%");
    verify(items, never()).countsFor(any());
    verify(orders, never()).count();
  }

  @Test
  void aSearchAlsoFindsOrdersOfCustomersMatchingTheText() {
    UserRepository users = mock(UserRepository.class);
    repo.setUsers(users);
    UUID c = UUID.randomUUID();
    when(users.idsMatching("%9876%", OrderRepository.MAX_CUSTOMER_MATCHES)).thenReturn(List.of(c));
    when(orders.listByNumberOrUsers(any(), any(), any())).thenReturn(List.of());
    when(orders.listByStatusAndNumberOrUsers(any(), any(), any(), any())).thenReturn(List.of());
    repo.adminList(null, "9876", 0, 10);
    verify(orders).listByNumberOrUsers(ArgumentMatchers.eq("%9876%"), ArgumentMatchers.eq(List.of(c)), any());
    repo.adminList("PLACED", "9876", 0, 10);
    verify(orders).listByStatusAndNumberOrUsers(ArgumentMatchers.eq("PLACED"), ArgumentMatchers.eq("%9876%"),
        ArgumentMatchers.eq(List.of(c)), any());
    verify(orders, never()).listByNumber(any(), any());
    // no text, no customer lookup
    repo.adminList(null, null, 0, 10);
    verify(users, Mockito.times(2)).idsMatching(any(), ArgumentMatchers.anyInt());
  }

  @Test
  void aSearchWithNoMatchingCustomerFallsBackToTheOrderNumberQueries() {
    UserRepository users = mock(UserRepository.class);
    repo.setUsers(users);
    when(users.idsMatching(any(), ArgumentMatchers.anyInt())).thenReturn(List.of());
    when(orders.listByNumber(any(), any())).thenReturn(List.of());
    repo.adminList(null, "5", 0, 10);
    verify(orders).listByNumber(ArgumentMatchers.eq("%5%"), any());
    verify(orders, never()).listByNumberOrUsers(any(), any(), any());
  }

  @Test
  void cancelInfoAndItemPictureAreStoredAndRead() {
    UUID id = UUID.randomUUID();
    OrderEntity e = entity(id, "CANCELLED", 1L);
    when(orders.findById(id)).thenReturn(Optional.of(e));
    repo.setCancelInfo(id, "SYSTEM", "PAYMENT_TIMEOUT", null);
    assertThat(e.cancelledBy).isEqualTo("SYSTEM");
    assertThat(e.cancelCode).isEqualTo("PAYMENT_TIMEOUT");
    assertThat(e.cancelReason).isNull();
    var row = repo.find(id).orElseThrow();
    assertThat(row.cancelledBy()).isEqualTo("SYSTEM");
    assertThat(row.cancelCode()).isEqualTo("PAYMENT_TIMEOUT");
    UUID gone = UUID.randomUUID();
    when(orders.findById(gone)).thenReturn(Optional.empty());
    repo.setCancelInfo(gone, "SHOP", "X", "y");

    var line = new OrderRepository.NewItem(UUID.randomUUID(), "Rice", 50, 2, 100, true, true, "R", "kg", 0, "/rice.png");
    repo.insertItem(id, line);
    ArgumentCaptor<OrderItemEntity> saved = ArgumentCaptor.forClass(OrderItemEntity.class);
    verify(items).save(saved.capture());
    assertThat(saved.getValue().imageUrl).isEqualTo("/rice.png");
    when(items.findByOrderIdOrderByName(id)).thenReturn(List.of(saved.getValue()));
    assertThat(repo.items(id)).singleElement().satisfies(i -> assertThat(i.imageUrl()).isEqualTo("/rice.png"));
    assertThat(new OrderRepository.SummaryRow(id, id, "N", "PLACED", "PICKUP", 1, 1, NOW, 1, null, 0, 0, 0, 0, "COD", "NONE").cancelCode()).isNull();
    assertThat(new OrderRepository.Row(id, "N", id, "PICKUP", "PLACED", 1, null, 0, 0, 0, 0, 1, 0, false, "COD", "NONE", null, null, NOW, 5,
        null, null, true).cancelledBy()).isNull();
  }

  private Query countReturns(long n) {
    Query q = mock(Query.class);
    when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(q);
    when(q.getSingleResult()).thenReturn(n);
    return q;
  }

  @Test
  void aShortPageSettlesTheTotalWithoutAnyCount() {
    OrderEntity e = entity(UUID.randomUUID(), "PLACED", 0L);
    when(items.countsFor(any())).thenReturn(List.of());
    when(orders.findPageBy(any(Pageable.class))).thenReturn(List.of(e));
    var page = repo.adminList(null, null, 0, 10);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.totalExact()).isTrue();
    assertThat(page.hasMore()).isFalse();
    verify(em, never()).createNativeQuery(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void aFullPageIsCountedExactlyUpToTheCapWithoutCachingAnything() {
    OrderEntity e = entity(UUID.randomUUID(), "PLACED", 0L);
    when(items.countsFor(any())).thenReturn(List.of());
    when(orders.findPageBy(any(Pageable.class))).thenReturn(List.of(e, e, e)); // 3 read for a page of 2: another page follows
    Query q = countReturns(183_243L);
    var page = repo.adminList(null, null, 0, 2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(10_000);
    assertThat(page.totalExact()).as("more than the cap: honest about it").isFalse();
    assertThat(page.hasMore()).isTrue();
    verify(em).createNativeQuery("select count(*) from (select 1 from orders o where 1 = 1 limit 10001) bounded");
    countReturns(4_321L);
    assertThat(repo.adminList(null, null, 0, 2).total()).as("asked again, counted again: never stale").isEqualTo(4_321);
    assertThat(repo.adminList(null, null, 0, 2).totalExact()).isTrue();
  }

  @Test
  void theCountFollowsTheSameFiltersAsThePage() {
    OrderEntity e = entity(UUID.randomUUID(), "PLACED", 0L);
    when(items.countsFor(any())).thenReturn(List.of());
    when(orders.findPageByStatus(any(), any())).thenReturn(List.of(e, e, e));
    when(orders.listByNumber(any(), any())).thenReturn(List.of(e, e, e));
    when(orders.listByStatusAndNumber(any(), any(), any())).thenReturn(List.of(e, e, e));
    Query q = countReturns(44_794L);
    repo.adminList("PLACED", null, 0, 2);
    verify(em).createNativeQuery("select count(*) from (select 1 from orders o where 1 = 1 and o.status = :status limit 10001) bounded");
    verify(q).setParameter("status", "PLACED");
    repo.adminList(null, "77", 0, 2);
    verify(em).createNativeQuery("select count(*) from (select 1 from orders o where 1 = 1 and lower(o.order_no) like :pattern limit 10001) bounded");
    verify(q).setParameter("pattern", "%77%");
    repo.adminList("PLACED", "77", 0, 2);
    verify(em).createNativeQuery("select count(*) from (select 1 from orders o where 1 = 1 and o.status = :status and lower(o.order_no) like :pattern limit 10001) bounded");
  }

  @Test
  void aSearchThatAlsoMatchesCustomersCountsTheirOrdersToo() {
    UserRepository users = mock(UserRepository.class);
    repo.setUsers(users);
    UUID c = UUID.randomUUID();
    OrderEntity e = entity(UUID.randomUUID(), "PLACED", 0L);
    when(items.countsFor(any())).thenReturn(List.of());
    when(users.idsMatching(any(), ArgumentMatchers.anyInt())).thenReturn(List.of(c));
    when(orders.listByNumberOrUsers(any(), any(), any())).thenReturn(List.of(e, e, e));
    Query q = countReturns(12L);
    var page = repo.adminList(null, "7", 0, 2);
    assertThat(page.total()).isEqualTo(12);
    verify(em).createNativeQuery("select count(*) from (select 1 from orders o where 1 = 1 and (lower(o.order_no) like :pattern or o.user_id in (:users)) limit 10001) bounded");
    verify(q).setParameter("users", List.of(c));
  }

  @Test
  void theThreeArgumentAdminListHasNoSearchText() {
    UUID id = UUID.randomUUID();
    OrderEntity e = entity(id, "PLACED", 0L);
    when(orders.findPageByStatus(org.mockito.ArgumentMatchers.eq("PLACED"), any())).thenReturn(List.of(e));
    when(items.countsFor(List.of(id))).thenReturn(java.util.Collections.singletonList(new Object[] {id, 6L}));
    Page<OrderRepository.SummaryRow> p = repo.adminList("PLACED", 1, 5);
    assertThat(p.total()).as("page 2 of a short result: the offset plus what was returned").isEqualTo(6);
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
