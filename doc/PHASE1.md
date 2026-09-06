# collie-flowable 第一期交付

范围：[REQUIREMENTS.md](REQUIREMENTS.md) §11。实现：[TECHNICAL.md](TECHNICAL.md)。
W0（文档 + `component.xml`）已完成。本文件锁定第一期实现默认值；改产品口径先改 REQUIREMENTS。

## 运行时依赖

1. Java 21（framework 已要求）。Flowable **7.2.x**，不上 8.x。
2. Moqui 当前 DataSource（嵌入 H2 开发 / Postgres 部署均可）。`ACT_*` 同库、默认前缀，不做独立 schema。
3. 无 OpenSearch / MinIO 依赖。
4. W4 起另装本地业务组件 `collie-wf-demo`（本仓库 `runtime/component/collie-wf-demo`），用于单据 + StatusFlow + `submit#` 开流。本组件不内置报销业务。

实体 / MoquiConf / seed / ToolFactory 变更需重启。服务与屏幕约 5s 热更新。`src/` 编译类需 `./gradlew :runtime:component:collie-flowable:jar`（及 demo）后重启。

## 锁定的 id 与鉴权

| 用途 | id |
|------|-----|
| 定义草稿 / 已部署 / 已挂起 | `WfDefDraft` / `WfDefDeployed` / `WfDefSuspended` |
| 进行中 / 已结束 link | `WfLinkActive` / `WfLinkEnded` |
| 通知主题 | `WorkflowTask` |
| Demo 单据状态 | `WfDemoDraft` / `WfDemoInReview` / `WfDemoApproved` / `WfDemoRejected` |
| Demo 组件 | `collie-wf-demo`，实体 `wf.demo.DemoRequest` |

屏幕：

- `/qapps/collie-flowable/` 应用根：`ALL_USERS` 可进（否则待办用户进不了 App）
- 设计器 / 定义列表 / 监控：仅 `ADMIN`
- 待办 / 已办 / 我发起 / 实例详情：`ALL_USERS`，运行期再套指派 / 发起人 / ADMIN（CF-33 / CF-63）

服务：

- `save#` / `deploy#` / 设计器 lookup / `suspend#` / `activate#` / `list#ProcessInstances`：`ADMIN`
- `start#` / `claim#` / `complete#` / `list#MyTasks` 等运行期服务：已登录用户

Delegate：异步线程恢复 `start#` 发起人 `userId`，没有则 `_NA_`。`MoquiServiceDelegate` 内部可 `disableAuthz`。

## 波次

| 波次 | 交付 | 验收 |
|------|------|------|
| W0 | 本文 + REQUIREMENTS + TECHNICAL + `component.xml` | 口径锁定；组件能被 Moqui 识别 |
| W1 | ToolFactory + `deploy#` / `start#` / `get#ProcessInstance` + `ping` | ServiceRun 部署空 BPMN 并 start |
| W2 | 设计器 + `WfProcessDefinition` + `save#` + CF-47a | 浏览器画节点 → 保存 → 缺 formKey 部署被拒 → 补全后部署；水印可见 |
| W3 | `MoquiServiceDelegate` | 异步线程有 `ec`；变量名 + `businessKey`；服务错误可重试 |
| W4 | 待办闭环 + `WfProcessLink` / `WfTaskComment` + `collie-wf-demo` | 候选须认领；两 UserTask 不同屏同一单；意见时间线一致；link 进行中唯一 |
| W5 | 会签 / Timer / 撤回 / 转办 / 作废 / 挂起 / 监控 | 或签 vs 会签；Timer 到升级 UserTask；停用后不能新 start |

## 验收命令

ServiceRun（改 JSON 即可）：

```bash
curl -s -X POST -H "Content-Type: application/json" \
  -u john.doe:moqui \
  -d '{"serviceName":"wf.flowable.WorkflowServices.ping"}' \
  http://localhost:8080/apps/tools/Service/ServiceRun/runJson
```

W1 硬关卡：

```bash
curl -s -X POST -H "Content-Type: application/json" \
  -u john.doe:moqui \
  -d "{\"serviceName\":\"wf.flowable.WorkflowServices.deploy#ProcessDefinition\",\"processDefinitionKey\":\"demoEmpty\",\"bpmnXml\":\"...\"}" \
  http://localhost:8080/apps/tools/Service/ServiceRun/runJson
```

- 屏幕：`http://localhost:8080/qapps/collie-flowable/`
- Demo 单据：`http://localhost:8080/qapps/collie-wf-demo/`
- 演示用户：`john.doe` / `moqui`（ADMIN）
- 日志：`runtime/log/moqui.log`

W1 硬关卡：空流程能 `start#` 后再进 W2。

## 发布说明（W5）

- 本组件不提供「选流程 → 引擎出启动表单」的发起台。开流在业务 `submit#`（demo 组件）里。
- `complete#Task` 默认不调业务 `approve#`。改状态画在网关后 Service Task。
- 设计器页与监控页右下角保留 bpmn.io 水印。待办 / 已办 / 我发起 / 实例详情不加载 bpmn-js。
- `ACT_*` 不映射为 Moqui entity。
- 第一期单节点开一套 Job Executor。多节点用 `flowable_job_executor_enabled` 只开一套。

## 非目标（本期结束时）

加签、驳回指定节点、Call Activity、消息/信号、组织选人解析、WorkEffort 双写、JTA 同提交、Flowable Form、独立 Flowable UI、改 mantle 官方 `submit#`。
