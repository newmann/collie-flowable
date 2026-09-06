# collie-flowable

在 Moqui 中嵌入 Flowable 工作流引擎的本地组件。引擎负责流程实例与待办编排，业务状态、服务、用户和屏幕仍由 Moqui 负责。

范围：[doc/REQUIREMENTS.md](doc/REQUIREMENTS.md)。选型：[doc/TECHNICAL.md](doc/TECHNICAL.md)。第一期波次：[doc/PHASE1.md](doc/PHASE1.md)。

## 第一期入口

| 谁 | URL / 服务 |
|----|------------|
| 待办 / 已办 / 我发起 | `/qapps/collie-flowable/TaskList` |
| 设计器 | `/qapps/collie-flowable/Designer` |
| 定义列表 | `/qapps/collie-flowable/ProcessList` |
| 监控 | `/qapps/collie-flowable/Monitor` |
| 演示单据 | `/qapps/collie-wf-demo/` |
| 引擎封装 | `wf.flowable.WorkflowServices.*` |

W1 验收：ServiceRun `deploy#ProcessDefinition` → `start#Process` → `get#ProcessInstance`。先 `ping`。

保存不会自动部署。开流在业务 `submit#`（`collie-wf-demo`）里，先 StatusFlow 再 `start#`。
