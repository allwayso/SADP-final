package edu.sadp.checkcollect.messaging;

import java.util.List;

public record NotificationPage(List<NotificationView> items, long total, int page, int size, long unread) {
}
