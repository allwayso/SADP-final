# check@collect 架构设计

本目录包含 POI 数据采集及核验移动应用的架构、实现、部署和验收资料，需求原文见 [desc.txt](../desc.txt)。最终提交入口见 [提交与验收清单](submission-checklist.md)，现场演示见 [演示脚本](demo-script.md)。

- [架构设计](architecture.md)：架构选择、UML 组件图、组件职责表、连接件与接口表、业务流程和需求核对。
- [四人分工说明](work-allocation.md)：模块边界、交付内容、预计比例与集成顺序。
- [成员 A 实现与联调](member-a.md)：启动步骤、账号与身份接口、微信配置、联调说明。
- [成员 B 实现与联调](member-b.md)：采集与版本接口、地图查询与导航入口、OCR 与地图适配器、给 C/D 的接口及验收检查。
- [成员 C 实现与联调](member-c.md)：核验与异议处理的状态机、接口、与 M2/M4 的端口约定、演示步骤与验证记录。
- [成员 D 实现与联调](member-d.md)：通知群发/点发、已读、错误单私聊、角色群聊、RabbitMQ 投递与重试去重、消息桩和验收脚本。
- [UML 组件图](assets/architecture.svg)：架构图的独立查看版本；[PlantUML 源文件](assets/architecture.puml)用于后续修改。

设计原则：一个响应式前端、一个模块化后端、一个关系数据库，按业务拆为四个模块；仅引入题目要求的消息中间件及地图、微信、OCR 外部平台。先完成端到端业务闭环，再考虑规模扩大后的部署优化。

“一图二表”在本设计中采用“UML 组件图 + 组件职责表 + 连接件与接口表”的表达方式。课程需求未另行规定两张表的具体模板。

- [Linux 部署与验收](deployment-linux.md)：Docker、真实服务集成、高德、PaddleOCR、HTTPS 和持久化。
- [四模块集成记录](integration-status.md)：修复对应、测试结果和部署待验收项。
