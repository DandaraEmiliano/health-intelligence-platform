package dev.healthintelligence.core.notifications;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.PersonalDataExport;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.stereotype.Component;

@Component
class NotificationsDataExport implements PersonalDataExport {

    static final int MAX = 100_000;

    private final NotificationController.Notifications notifications;

    NotificationsDataExport(NotificationController.Notifications notifications) {
        this.notifications = notifications;
    }

    @Override
    public String section() {
        return "notifications";
    }

    @Override
    public Object export(UserId user, PatientId patient) {
        return notifications.page(user, null, null, MAX).stream().map(notifications::view).toList();
    }
}
