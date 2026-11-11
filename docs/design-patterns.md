# 现有设计模式与代码对应

本说明解释现有实现；课程“指定设计模式”的具体名称尚待确认，不能据此直接认定该项 5% 已满足。面馆应用的代码与类图是独立个人成果。

## 适配器模式

业务通过统一接口访问平台，具体实现负责外部协议和数据转换。

| 角色 | 代码位置（相对仓库根目录） | 职责 |
| --- | --- | --- |
| 目标接口 | backend/src/main/java/edu/sadp/checkcollect/poi/MapAdapter.java | 接收 WGS84 起终点和模式，返回导航 URL |
| 具体适配器 | backend/src/main/java/edu/sadp/checkcollect/poi/AmapAdapter.java | 转 GCJ-02 并生成高德 URI |
| OCR 目标接口 | backend/src/main/java/edu/sadp/checkcollect/poi/OcrAdapter.java | 将图片识别统一为文字、建议分类和模拟标志 |
| OCR 具体适配器 | backend/src/main/java/edu/sadp/checkcollect/poi/PaddleOcrAdapter.java | 封装 OCR HTTP 服务 |
| 核验端口适配器 | backend/src/main/java/edu/sadp/checkcollect/review/adapter/PoiRevisionAdapter.java | 将核验版本端口接入真实 POI 服务 |

具体地图实现由 Spring 配置选择，业务无需拼接供应商 URL。导航测试验证 URL、坐标转换及输入检查；平台实际导航另需实机验收。

## 可替换的消息传输策略

NotificationTransport 定义 send(UUID notificationId)，RabbitNotificationTransport 与 InProcessNotificationTransport 提供不同传输实现。NotificationDeliveryService 使用抽象传输接口，配置决定注入对象。真实提交部署使用 RabbitMQ；进程内实现供本地联调使用，不作为中间件接入证明。

## 评分对应待确认

请将教师指定模式与上述实际代码逐项比对。如指定的是其他模式，应依据真实业务补实现及验证；不能仅修改类名或文档名称。若教师要求模式类图，应补充与最终代码一致的 UML 类图。
