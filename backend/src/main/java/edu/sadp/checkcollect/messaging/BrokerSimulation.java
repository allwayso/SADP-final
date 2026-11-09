package edu.sadp.checkcollect.messaging;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * 消息桩的故障开关：模拟中间件短暂不可用，用于演示失败后由重试任务补投。
 * 只影响通知发布，不影响其他业务；默认可用。
 */
@Component
public class BrokerSimulation {
    private final AtomicBoolean available = new AtomicBoolean(true);

    public boolean isAvailable() {
        return available.get();
    }

    public void setAvailable(boolean available) {
        this.available.set(available);
    }

    public void assertAvailable() {
        if (!available.get()) {
            throw new IllegalStateException("模拟消息中间件不可用");
        }
    }
}
