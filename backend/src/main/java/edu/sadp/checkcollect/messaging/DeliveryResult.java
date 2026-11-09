package edu.sadp.checkcollect.messaging;

/** 一次幂等投递的结果；重复消费时 statusChanged 为 false、新增确认人数为 0。 */
public record DeliveryResult(java.util.UUID notificationId, boolean statusChanged,
                             int newlyDeliveredRecipients, int recipients, String status) {
}
