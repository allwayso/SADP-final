package edu.sadp.checkcollect.review.adapter;

import edu.sadp.checkcollect.messaging.*;
import edu.sadp.checkcollect.review.port.NotificationPort;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "app.review.notification-stub",
    havingValue = "false",
    matchIfMissing = true)
public class ReviewMessagingAdapter implements NotificationPort {
  private final MessagingService messages;

  public ReviewMessagingAdapter(MessagingService messages) {
    this.messages = messages;
  }

  public String notify(
      String type,
      edu.sadp.checkcollect.review.port.NotificationTarget target,
      edu.sadp.checkcollect.review.port.NotificationReference reference,
      String text) {
    NotificationKind kind =
        switch (type) {
          case "REVIEW_ISSUE_REPORTED" -> NotificationKind.ISSUE_RAISED;
          case "REVIEW_DISPUTE_SUBMITTED" -> NotificationKind.DISPUTE_OPENED;
          case "REVIEW_FIELD_TASK_ASSIGNED", "REVIEW_FIELD_TASK_UNASSIGNED" ->
              NotificationKind.FIELD_TASK;
          case "REVIEW_DISPUTE_DECIDED" -> NotificationKind.FINAL_RESULT;
          default -> NotificationKind.ISSUE_RESPONSE;
        };
    NotificationReference ref =
        NotificationReference.of(
            reference.type().equals("FIELD_TASK") ? "TASK" : reference.type(), reference.id());
    UUID id =
        target.userId() != null
            ? messages.notifyUser(kind, target.userId(), ref, "核验进展通知", text)
            : messages.notifyRole(kind, target.role(), ref, "核验进展通知", text);
    return id.toString();
  }

  public String openConversation(String issueId, UUID collector, UUID reviewer) {
    return messages.openIssueConversation(issueId, "错误单讨论", collector, reviewer).toString();
  }

  public String conversationId(String issueId) {
    return messages.issueConversationId(issueId);
  }
}
