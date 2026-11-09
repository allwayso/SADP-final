package edu.sadp.checkcollect.poi;

/**
 * 通知发送接口，对应表二中的 {@code notify(type, target, reference, text)}。
 * M4 实现该接口后由 Spring 注入，M2 不直接接触 RabbitMQ，也不因通知失败回滚已提交的采集结果。
 * M4 未落地时列表为空，采集流程照常可用。
 */
public interface PoiNotifier {
    void notify(String type, String target, String reference, String text);
}
