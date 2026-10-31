package com.vijay.mall.identity.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DeviceJpa extends JpaRepository<DeviceTokenEntity, UUID> {
  Optional<DeviceTokenEntity> findByFcmToken(String fcmToken);

  @Query("select d.fcmToken from DeviceTokenEntity d where d.userId = :user")
  List<String> tokensOf(@Param("user") UUID user);
}
