package com.vijay.mall.identity.infra;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code device_token} rows: lookup by push token, a user's tokens, and bulk deletes. */
interface DeviceJpa extends JpaRepository<DeviceTokenEntity, UUID> {
  Optional<DeviceTokenEntity> findByFcmToken(String fcmToken);

  @Query("select d.fcmToken from DeviceTokenEntity d where d.userId = :user")
  List<String> tokensOf(@Param("user") UUID user);

  @Query("select d.fcmToken from DeviceTokenEntity d where d.userId in :users")
  List<String> tokensOfAll(@Param("users") Collection<UUID> users);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from DeviceTokenEntity d where d.userId = :user")
  int deleteAllOf(@Param("user") UUID user);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from DeviceTokenEntity d where d.fcmToken = :token and d.userId = :user")
  int deleteOwnedToken(@Param("user") UUID user, @Param("token") String token);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from DeviceTokenEntity d where d.fcmToken = :token")
  int deleteByToken(@Param("token") String token);
}
