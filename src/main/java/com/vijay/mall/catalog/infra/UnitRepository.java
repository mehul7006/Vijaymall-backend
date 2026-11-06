package com.vijay.mall.catalog.infra;

import com.vijay.mall.catalog.domain.UnitOfMeasure;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UnitRepository {
  private final UnitJpa jpa;

  @PersistenceContext
  private EntityManager em;

  UnitRepository(UnitJpa jpa) { this.jpa = jpa; }

  private static UnitOfMeasure toDomain(UnitEntity e) {
    return new UnitOfMeasure(e.code, e.nameEn, e.nameGu, e.soldBy, e.sortOrder, e.active);
  }

  public List<UnitOfMeasure> all() { return jpa.findAllByOrderBySortOrderAscCodeAsc().stream().map(UnitRepository::toDomain).toList(); }

  public List<UnitOfMeasure> allActive() {
    return jpa.findByActiveTrueOrderBySortOrderAscCodeAsc().stream().map(UnitRepository::toDomain).toList();
  }

  /** Finds a unit ignoring case, so "KG", "Kg" and "kg" are one unit. */
  public Optional<UnitOfMeasure> findIgnoreCase(String code) { return jpa.findByCodeIgnoreCase(code).map(UnitRepository::toDomain); }

  @Transactional
  public UnitOfMeasure insert(UnitOfMeasure u) {
    UnitEntity e = new UnitEntity();
    e.code = u.code();
    apply(e, u);
    return toDomain(jpa.save(e));
  }

  /** Updates the names, kind, order and active flag of the unit with this code (the code itself never changes here). */
  @Transactional
  public Optional<UnitOfMeasure> update(String code, UnitOfMeasure u) {
    return jpa.findById(code).map(e -> {
      apply(e, u);
      return toDomain(e);
    });
  }

  private static void apply(UnitEntity e, UnitOfMeasure u) {
    e.nameEn = u.nameEn();
    e.nameGu = u.nameGu() == null ? "" : u.nameGu();
    e.soldBy = u.soldBy();
    e.sortOrder = u.sortOrder();
    e.active = u.active();
  }

  /** Renames a unit's code and every product that uses it; returns the products changed. */
  @Transactional
  public int rename(String from, String to) {
    jpa.flush();
    int products = em.createQuery("update ProductEntity p set p.unit = :to where p.unit = :from")
        .setParameter("to", to).setParameter("from", from).executeUpdate();
    em.createNativeQuery("update unit_of_measure set code = :to where code = :from")
        .setParameter("to", to).setParameter("from", from).executeUpdate();
    em.clear();
    return products;
  }

  public long productsUsing(String code) {
    return em.createQuery("select count(p) from ProductEntity p where p.unit = :c", Long.class)
        .setParameter("c", code).getSingleResult();
  }

  @Transactional
  public void delete(String code) { jpa.deleteById(code); }
}
