package com.vijay.mall.common.persistence;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Base for entities whose id is a UUID made by the application. Telling Spring Data that a fresh object is new makes
 * save() run a plain INSERT instead of "SELECT first, then INSERT" (which is what merge() does for assigned ids).
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {
  /** The primary key, assigned by the application. */
  @Id
  public UUID id;

  @Transient
  private boolean fresh = true;

  @Override
  public UUID getId() { return id; }

  @Override
  public boolean isNew() { return fresh; }

  @PostPersist
  @PostLoad
  void markPersisted() { fresh = false; }
}
