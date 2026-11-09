package edu.sadp.checkcollect.messaging;

import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {

    public MessagingConfig(MessagingProperties properties) {
        if (!Set.of("rabbit", "in-process").contains(properties.transport())) {
            throw new IllegalStateException("app.messaging.transport 只能是 rabbit 或 in-process，当前为：" + properties.transport());
        }
    }
}
