package edu.sadp.checkcollect.review.stub;

import edu.sadp.checkcollect.review.port.NotificationPort;
import edu.sadp.checkcollect.review.port.PoiRevisionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按配置装配 M2/M4 桩。成员 B、D 提供真实的 {@link PoiRevisionPort}、{@link NotificationPort}
 * Bean 后，把对应开关设为 {@code false} 即完成替换，M3 业务代码无需修改。
 */
@Configuration
public class ReviewStubConfig {

    @Bean
    @ConditionalOnProperty(name = "app.review.poi-stub", havingValue = "true")
    InMemoryPoiRevisionStub poiRevisionStub() {
        return new InMemoryPoiRevisionStub();
    }

    @Bean
    @ConditionalOnProperty(name = "app.review.notification-stub", havingValue = "true")
    RecordingNotificationStub notificationStub() {
        return new RecordingNotificationStub();
    }
}
