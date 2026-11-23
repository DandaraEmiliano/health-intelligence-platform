package dev.healthintelligence.core.notifications;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.healthintelligence.core.identity.CurrentUser;
import dev.healthintelligence.core.shared.FieldViolation;
import dev.healthintelligence.core.shared.ProblemException;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final CurrentUser currentUser;
    private final Notifications notifications;

    NotificationController(CurrentUser currentUser, Notifications notifications) {
        this.currentUser = currentUser;
        this.notifications = notifications;
    }

    record Resource(String type, UUID id) {
    }

    record NotificationView(UUID id, String type, String status, Resource resource, Instant createdAt, Instant readAt) {
    }

    record NotificationPage(List<NotificationView> items, String nextCursor) {
    }

    @GetMapping
    ResponseEntity<NotificationPage> listNotifications(@RequestParam(name = "status", required = false) Notification.Status status,
                                                       @RequestParam(name = "cursor", required = false) String cursor,
                                                       @RequestParam(name = "limit", required = false) Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw ProblemException.validation(List.of(new FieldViolation("limit", "OUT_OF_RANGE", "limit must be between 1 and 100")));
        }
        List<Notification> rows = notifications.page(currentUser.id(), status, decode(cursor), size);
        boolean more = rows.size() > size;
        List<Notification> items = more ? rows.subList(0, size) : rows;
        String next = more ? encode(items.getLast()) : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new NotificationPage(items.stream().map(notifications::view).toList(), next));
    }

    @PostMapping("/{notificationId}/read")
    ResponseEntity<Void> markNotificationRead(@PathVariable("notificationId") UUID id) {
        notifications.markRead(currentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    private static String encode(Notification last) {
        String raw = last.createdAt() + "|" + last.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Object[] decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new Object[] {Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1))};
        } catch (RuntimeException invalid) {
            throw ProblemException.validation(List.of(new FieldViolation("cursor", "INVALID_FORMAT", "cursor is not valid")));
        }
    }

    /** Use cases, scoped to the account from the token. */
    @Service
    static class Notifications {

        static final String NOT_FOUND = "NOTIFICATION_NOT_FOUND";

        private final NotificationRepository repository;
        private final JsonMapper json;
        private final Clock clock;

        Notifications(NotificationRepository repository, JsonMapper json, Clock clock) {
            this.repository = repository;
            this.json = json;
            this.clock = clock;
        }

        @Transactional(readOnly = true)
        List<Notification> page(UserId user, Notification.Status status, Object[] after, int limit) {
            return repository.page(user, status, after == null ? null : (Instant) after[0],
                    after == null ? null : (UUID) after[1], org.springframework.data.domain.Limit.of(limit + 1));
        }

        @Transactional
        void markRead(UserId user, UUID id) {
            Notification notification = repository.findOwned(id, user)
                    .orElseThrow(() -> ProblemException.notFound(NOT_FOUND));
            notification.markRead(Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
            repository.save(notification);
        }

        NotificationView view(Notification n) {
            Map<?, ?> payload = json.readValue(n.payload(), Map.class);
            Resource resource = payload.get("resourceId") instanceof String id
                    ? new Resource(String.valueOf(payload.get("resourceType")), UUID.fromString(id)) : null;
            return new NotificationView(n.id(), n.type().name(), n.status().name(), resource, n.createdAt(), n.readAt());
        }
    }
}
