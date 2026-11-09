package edu.sadp.checkcollect.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** M4 通知投递配置。默认使用 RabbitMQ，可通过 MESSAGING_TRANSPORT=in-process 在无中间件环境联调。 */
@ConfigurationProperties("app.messaging")
public record MessagingProperties(
        String transport,
        String exchange,
        String queue,
        String routingKey,
        long retryIntervalMs,
        int maxAttempts,
        boolean stubEnabled) {

    public MessagingProperties {
        transport = transport == null || transport.isBlank() ? "rabbit" : transport.trim();
        exchange = exchange == null || exchange.isBlank() ? "checkcollect.notifications" : exchange.trim();
        queue = queue == null || queue.isBlank() ? "checkcollect.notifications.delivery" : queue.trim();
        routingKey = routingKey == null || routingKey.isBlank() ? "notification" : routingKey.trim();
        retryIntervalMs = retryIntervalMs <= 0 ? 15_000 : retryIntervalMs;
        maxAttempts = maxAttempts <= 0 ? 20 : maxAttempts;
    }

    public boolean inProcess() {
        return "in-process".equals(transport);
    }
}
