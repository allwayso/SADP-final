package edu.sadp.checkcollect.poi;

import edu.sadp.checkcollect.identity.Role;
import edu.sadp.checkcollect.messaging.*;
import org.springframework.stereotype.Component;

@Component
public class PoiMessagingAdapter implements PoiNotifier {
  private final MessagingService messages;

  public PoiMessagingAdapter(MessagingService messages) {
    this.messages = messages;
  }

  public void notify(String type, String target, String reference, String text) {
    messages.notifyRole(
        NotificationKind.NEW_DATA,
        Role.valueOf(target),
        NotificationReference.of("POI", reference),
        "采集数据待核验",
        text);
  }
}
