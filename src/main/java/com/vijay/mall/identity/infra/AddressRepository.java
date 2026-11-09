package com.vijay.mall.identity.infra;

import com.vijay.mall.identity.domain.Address;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores customers' delivery addresses. Every method is scoped to one user so nobody can read or change another person's address.
 */
@Repository
public class AddressRepository {
  private final AddressJpa jpa;

  AddressRepository(AddressJpa jpa) { this.jpa = jpa; }

  private static Address toDomain(AddressEntity e) {
    return new Address(e.id, e.label, e.line1, e.area, e.pincode, e.defaultAddress, e.phone);
  }

  /** Addresses of the user, default first, then oldest first. */
  public List<Address> list(UUID userId) {
    return jpa.findByUserIdOrderByDefaultAddressDescCreatedAtAsc(userId).stream().map(AddressRepository::toDomain).toList();
  }

  /** One address of the user, or empty when it does not exist or belongs to someone else. */
  public Optional<Address> find(UUID userId, UUID id) { return jpa.findByIdAndUserId(id, userId).map(AddressRepository::toDomain); }

  /** Number of addresses the user has saved. */
  public int count(UUID userId) { return (int) jpa.countByUserId(userId); }

  /** Saves a new address and returns it with its generated id. */
  @Transactional
  public Address insert(UUID userId, Address a, Instant now) {
    AddressEntity e = new AddressEntity();
    e.id = UUID.randomUUID();
    e.userId = userId;
    e.createdAt = now;
    copy(a, e);
    return toDomain(jpa.save(e));
  }

  /** Overwrites the fields of one of the user's addresses; does nothing if it is not theirs. */
  @Transactional
  public void update(UUID userId, UUID id, Address a) { jpa.findByIdAndUserId(id, userId).ifPresent(e -> copy(a, e)); }

  /** Unmarks every default address of the user (call before marking another one). */
  @Transactional
  public void clearDefault(UUID userId) { jpa.clearDefault(userId); }

  /** Deletes one address if it belongs to the user. */
  @Transactional
  public void delete(UUID userId, UUID id) { jpa.deleteOwned(id, userId); }

  /** Deletes all of the user's addresses (account deletion). */
  @Transactional
  public void deleteAll(UUID userId) { jpa.deleteAllOf(userId); }

  private static void copy(Address a, AddressEntity e) {
    e.label = a.label();
    e.line1 = a.line1();
    e.area = a.area();
    e.pincode = a.pincode();
    e.phone = a.phone();
    e.defaultAddress = a.isDefault();
  }
}
