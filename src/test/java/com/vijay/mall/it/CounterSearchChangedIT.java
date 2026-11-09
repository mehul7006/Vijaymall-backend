package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.catalog.infra.ProductRepository;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The counter's "changed since" search decides what changed inside the database. Tens of thousands of changed products must not
 * become tens of thousands of bound parameters, and paging through equal names must be stable.
 */
class CounterSearchChangedIT extends IntegrationTestBase {
  @Autowired ProductRepository products;

  @AfterEach
  void clean() {
    jdbc.update("delete from product_barcode where product_id in (select id from product where name_en like 'zzbulk%')");
    jdbc.update("delete from product_pos where product_id in (select id from product where name_en like 'zzbulk%')");
    jdbc.update("delete from product where name_en like 'zzbulk%'");
  }

  @Test
  void fortyOneThousandChangedProductsNeedNoIdListAndPagingIsStable() {
    jdbc.update("insert into product (id, category_id, name_en, name_gu, mrp_paise, price_paise, created_at, updated_at, product_group_id) "
        + "select gen_random_uuid(), (select id from category limit 1), 'zzbulk same name', 'x', 100, 100, '2000-01-01', '2000-01-01', gen_random_uuid() "
        + "from generate_series(1, 41000)");
    jdbc.update("insert into product_pos (product_id, updated_at) select id, now() from product where name_en like 'zzbulk%' "
        + "and id::text < 'a'");
    long changed = jdbc.queryForObject("select count(*) from product_pos pp join product p on p.id = pp.product_id where p.name_en like 'zzbulk%'", Long.class);
    assertThat(changed).isGreaterThan(10_000);
    Instant since = Instant.now().minusSeconds(3600);

    var first = products.searchForCounter("zzbulk", Set.of(), since, 0, 50);
    var again = products.searchForCounter("zzbulk", Set.of(), since, 0, 50);
    var second = products.searchForCounter("zzbulk", Set.of(), since, 1, 50);

    assertThat(first.items()).hasSize(50);
    assertThat(first.hasMore()).isTrue();
    assertThat(first.total()).isEqualTo(10_000);
    assertThat(first.totalExact()).isFalse();
    assertThat(first.items()).as("same order every time (id breaks the tie between equal names)").isEqualTo(again.items());
    assertThat(second.items()).extracting(p -> p.id()).doesNotContainAnyElementsOf(first.items().stream().map(p -> p.id()).toList());
  }

  @Test
  void aBarcodeAddedAfterTheInstantCountsAsAChange() {
    jdbc.update("insert into product (id, category_id, name_en, name_gu, mrp_paise, price_paise, created_at, updated_at, product_group_id) "
        + "values ('00000000-0000-0000-0000-00000000b001', (select id from category limit 1), 'zzbulk barcode', 'x', 100, 100, '2000-01-01', '2000-01-01', gen_random_uuid()), "
        + "('00000000-0000-0000-0000-00000000b002', (select id from category limit 1), 'zzbulk untouched', 'x', 100, 100, '2000-01-01', '2000-01-01', gen_random_uuid())");
    jdbc.update("insert into product_barcode (code, product_id, created_at) values ('ZZBULK-1', '00000000-0000-0000-0000-00000000b001', now())");
    var found = products.searchForCounter("zzbulk", Set.of(), Instant.now().minusSeconds(3600), 0, 20);
    assertThat(found.items()).extracting(p -> p.nameEn()).containsExactly("zzbulk barcode");
  }
}
