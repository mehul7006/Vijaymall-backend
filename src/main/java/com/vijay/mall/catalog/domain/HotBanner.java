package com.vijay.mall.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A promotional banner shown at the top of the hot-sale screen.
 * <p>A banner is live when it is {@code active} and the current time is inside the optional {@code startAt} / {@code endAt}
 * window; a null end means no limit on that side.
 * @param id banner id
 * @param title headline text
 * @param subtitle optional second line
 * @param imageUrl optional picture path
 * @param startAt first moment the banner is shown, or null for no start limit
 * @param endAt last moment the banner is shown, or null for no end limit
 * @param active admin on/off switch
 */
public record HotBanner(UUID id, String title, String subtitle, String imageUrl, Instant startAt, Instant endAt,
                        boolean active) {}
