package com.vijay.mall.identity.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code address} rows; every query is scoped to the owning user. */
interface AddressJpa extends JpaRepository<AddressEntity, UUID> {
  List<AddressEntity> findByUserIdOrderByDefaultAddressDescCreatedAtAsc(UUID userId);

  Optional<AddressEntity> findByIdAndUserId(UUID id, UUID userId);

  long countByUserId(UUID userId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update AddressEntity a set a.defaultAddress = false where a.userId = :user")
  int clearDefault(@Param("user") UUID user);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from AddressEntity a where a.id = :id and a.userId = :user")
  int deleteOwned(@Param("id") UUID id, @Param("user") UUID user);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from AddressEntity a where a.userId = :user")
  int deleteAllOf(@Param("user") UUID user);
}
