package com.vijay.mall.identity.infra;

import com.vijay.mall.identity.domain.Address;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AddressRepository {
  private final AddressJpa jpa;

  AddressRepository(AddressJpa jpa) { this.jpa = jpa; }

  private static Address toDomain(AddressEntity e) {
    return new Address(e.id, e.label, e.line1, e.area, e.pincode, e.defaultAddress);
  }

  public List<Address> list(UUID userId) {
    return jpa.findByUserIdOrderByDefaultAddressDescCreatedAtAsc(userId).stream().map(AddressRepository::toDomain).toList();
  }

  public Optional<Address> find(UUID userId, UUID id) { return jpa.findByIdAndUserId(id, userId).map(AddressRepository::toDomain); }

  public int count(UUID userId) { return (int) jpa.countByUserId(userId); }

  @Transactional
  public Address insert(UUID userId, Address a, Instant now) {
    AddressEntity e = new AddressEntity();
    e.id = UUID.randomUUID();
    e.userId = userId;
    e.createdAt = now;
    copy(a, e);
    return toDomain(jpa.save(e));
  }

  @Transactional
  public void update(UUID userId, UUID id, Address a) { jpa.findByIdAndUserId(id, userId).ifPresent(e -> copy(a, e)); }

  @Transactional
  public void clearDefault(UUID userId) { jpa.clearDefault(userId); }

  @Transactional
  public void delete(UUID userId, UUID id) { jpa.deleteOwned(id, userId); }

  @Transactional
  public void deleteAll(UUID userId) { jpa.deleteAllOf(userId); }

  private static void copy(Address a, AddressEntity e) {
    e.label = a.label();
    e.line1 = a.line1();
    e.area = a.area();
    e.pincode = a.pincode();
    e.defaultAddress = a.isDefault();
  }
}
