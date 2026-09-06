# collie-flowable 需求分析

本文汇总本仓库工作流调研与 Flowable 融合讨论，作为 `collie-flowable` 组件的范围与约定。实现未开始；改产品口径先改本文。选型、嵌屏、服务名与实体字段见 [TECHNICAL.md](TECHNICAL.md)。

## 1. 背景

Moqui 是业务自动化 / ERP 框架，用 XML DSL 描述实体、服务和屏幕。本 git 树是 **moqui-framework**；应用代码放在 `runtime/component/` 的本地组件。本环境已装 `mantle-udm`、`mantle-usl` 等。

仓库内**没有** Flowable、Camunda、jBPM、Activiti 依赖；[addons.xml](../../../../addons.xml) 也没有官方工作流组件。官网旧文档提到的 jBPM 对应可选工具组件 `moqui-kie`（本环境未装），实际用法是 **Drools 规则**（如定价），不是流程引擎。[ReleaseNotes.md](../../../../ReleaseNotes.md) 里「活动绑定屏幕/服务」的 Bonita 式工作流是**未实现**的未来项。

简单单据生命周期继续用 StatusFlow，不进本组件。

## 2. 问题：Moqui 有没有工作流

有编排能力，**没有** BPMN 流程引擎。容易把三件事混成「工作流」：

| 说法 | 本仓库实际是什么 | 典型用途 |
|------|------------------|----------|
| 状态机 | `StatusItem` / `StatusFlow` / `StatusFlowTransition` | 单据生命周期（草稿→提交→完成） |
| 任务指派 | `WorkEffort` + `WorkEffortParty`，或 `Request` | 项目任务、工时、制造、工单 |
| 流程引擎 | **不存在** | 实例、待办 inbox、并行网关、会签、流程版本 |

### 2.1 StatusFlow（记录级有限状态机）

定义在 `framework/entity/BasicEntities.xml`。实体 `update` 改 `statusId` 时，`EntityAutoServiceRunner.checkStatus` 校验是否存在合法 `StatusFlowTransition`，并可按 `userPermissionId` 鉴权。`conditionExpression` **当前不支持**。状态历史需 audit-log 或 SECA。

这是**一张单据一个当前状态**，不是 token、并行分支或流程实例。

查询下拉：`org.moqui.impl.BasicServices.find#StatusFlowTransitionToDetail`。屏幕控件：`statusTransitionWithFlowDropDown`。

### 2.2 服务 + SECA / EECA

`service-eca-3.xsd` 写明：Service ECA 用于 **triggering business processes**。业务写在 `service/*.xml`，副作用用 SECA/EECA。无流程图、无实例、无待办引擎。另有 Email ECA、`SystemMessage` 状态机（集成收发）。

WorkEffort 已有副作用挂钩（不是引擎）：`mantle-usl` 的 `WorkEffort.secas.xml`（状态进入进行中/完成时写实际时间）、`Work.eecas.xml`（工时回写任务汇总）。

### 2.3 ServiceJob

`moqui.service.job.ServiceJob` 是 cron / 异步作业，不是人工审批。

### 2.4 WorkEffort / Request

`mantle.work.effort.WorkEffort`：Project / Milestone / Task / Event / 制造路由 / 发运拣货。层级用 `parentWorkEffortId` / `rootWorkEffortId`。指派用 `WorkEffortParty`。任务状态例如 `WeInPlanning → WeApproved → WeInProgress → WeComplete → WeClosed`。服务在 `mantle.work.*`（`ProjectServices`、`TaskServices` 等）。

`mantle.request.Request` 是工单，可挂 WorkEffort，仍是单据 + 指派。MarbleERP / SimpleScreens 的 Task、Project、「我的任务」建在这套模型上。`knowledge-base` 的 `ParseTask` 是领域异步任务，不是审批引擎。

## 3. 与 Flowable / Camunda / jBPM

| 能力 | Moqui 原生 | Flowable / Camunda 7 / jBPM |
|------|------------|------------------------------|
| 过程表示 | 实体状态图 + 服务代码 | BPMN 2.0（Flowable 另有 CMMN、DMN） |
| 运行对象 | 业务行的 `statusId` | Process instance + token |
| 人工任务 | WorkEffort / Request 指派，无通用 inbox | UserTask、候选组、委派、待办 |
| 网关 | 服务里的条件 | XOR / AND / inclusive |
| 定时 / 消息 / 信号 | ServiceJob、通知 | Timer / message / signal / error |
| 会签 / 或签 | 自己写 | Multi-instance、条件完成 |
| 流程版本 | 改 XML / seed 即改行为 | 定义版本，旧实例可跑旧版 |
| 可视化建模 | 无 | 嵌入式设计器（本组件 Moqui 屏，第一期要上）；不上独立 Flowable / Camunda Modeler |
| 与 Moqui 集成 | 原生 | 需桥接 |

**一句话：** Moqui 管「这张单现在什么状态、谁在做」；BPMN 引擎管「这个实例走到哪一步、下一步谁做、如何分叉汇合」。

引擎选型（若必须引入）：

- **Flowable（本组件选择）：** Activiti 分支；核心引擎 Apache 2.0；可嵌入同一 JVM；与 Moqui 嵌入式 Jetty + 服务层合拍。包版本写死在 [TECHNICAL.md](TECHNICAL.md)（7.2.x，不要 8.x）。
- **Camunda 7：** 同源、嵌入式类似；Community 基本停更，官方推 Camunda 8（Zeebe，独立集群、非嵌入）。新项目不选。
- **jBPM：** 与 `moqui-kie` 历史路径一致，组件重、社区弱。只为流程不要选它。

社区 [netvariant/moqui-workflow](https://github.com/netvariant/moqui-workflow) 不在官方 addons，不能当标准方案。

## 4. 何时用本组件

**默认仍不把普通单据生命周期送进引擎。** 先 StatusFlow + 服务；要人工任务再用 WorkEffort / Request。这是 Mantle 既定做法。

**进入 Flowable 的充分条件（满足多条；下列是立项理由，不是第一期承诺清单）：**

- 业务要改流程图，且不靠发版改服务
- 并行审批、会签、或签（W2 可画 AND；W4 可跑并行汇合；会签多实例在 W5）
- 跨单据类型的统一待办
- 合规要流程实例审计（谁在哪一节点何时通过）
- 已有 BPMN 资产要复用（可粘贴 XML；不做桌面工程一键转换）

**后续波次才覆盖、不要读成第一期承诺：** 加签、驳回到指定节点、子流程（Call Activity）、消息/信号等待外部系统、组织选人解析（上级/角色）。超时升级在 W5 用 Timer 走出另一条 BPMN 路径（可到升级 UserTask），不做「自动改 assignee」。

**不够单独立项的：** 「以后可能要审批」「希望看起来专业」、知识库发布/归档、项目任务与工时。

简单审批若坚持不用引擎：实体 `statusId` + `StatusFlowTransition` + `submit#` / `approve#` / `reject#` + 可选 WorkEffort 指派 + SECA 通知 + ServiceJob 催办。

## 5. 本组件目标

在 **不改** `framework/`、`mantle-udm`、`mantle-usl`、SimpleScreens、MarbleERP、`webroot` 的前提下，用本地组件嵌入 Flowable，并把 Moqui 已有能力接上：

- 业务服务（Service Task → `ec.service`）
- StatusFlow（单据 `statusId` 仍是业务真相）
- 用户与用户组（待办指派）
- 屏幕（各 UserTask 独立 `formKey`，打开既有 `form-single`，展示内容可以不同，见 CF-41a；节点意见不改业务屏，见 §7.8）
- 节点任务动作与审批意见（统一 `outcome` + `comment`，见 §7.8）
- 管理员可视化设计器（本组件屏幕内嵌第三方画布，见 §7.7）
- 运行期操作面（认领、已办/我发起、实例详情在 W4；撤回/转办/作废/监控在 W5，见 §7.10）
- 通知（`NotificationTopic`）
- ArtifactAuthz

组件名：`collie-flowable`。识别文件：本目录 `component.xml`。

## 6. 融合原则

**Flowable 只负责「实例走到哪、谁待办、如何分叉」；业务真相仍在 Moqui。**

| 仍由 Moqui 管 | 交给 Flowable | 禁止 |
|---------------|---------------|------|
| 实体、`statusId`、StatusFlow | BPMN 定义与版本 | 引擎直接 SQL 改业务表 |
| 业务服务、SECA / EECA | Process instance / token | BPMN 里写 entity-auto |
| UserAccount / UserGroup / ArtifactAuthz | UserTask 待办、候选组 | 用 Flowable 身份表当账号源 |
| 业务表单字段（既有 `form-single`） | | 把 UserTask 意见做成各业务实体字段 |
| 节点 `outcome` / 审批意见（本组件实体，见 §7.8） | 并行网关、Timer、会签 | 用 Flowable `TaskService.addComment` 当产品意见库 |
| 屏幕宿主、通知、设计器宿主 | | 用引擎替换 StatusFlow |

```text
设计器 / 待办·已办·我发起 / 实例详情 / 监控 / SECA
        │
        ▼
wf.flowable.* 服务 ──► FlowableToolFactory ──► ProcessEngine ──► ACT_* 表
        │                      │
        │                      ▼
        │              MoquiServiceDelegate
        ▼                      ▼
  WfProcessDefinition   既有业务服务 ──► 实体 + StatusFlow
  WfProcessLink
  WfTaskComment
  User / UserGroup
        ▲
        └──── list#MyTasks / claim# / complete# / list#TaskComments
```

## 7. 功能需求

编号 CF-xx，实现时保持稳定。未实现方法或未列范围须在实现方案里显式延期，不得 silently 改口径。

### 7.1 引擎生命周期

| ID | 需求 |
|----|------|
| CF-01 | 以 Moqui `ToolFactory` 管理 `ProcessEngine`（`init` 创建、`destroy` 关闭），注册在本组件 `MoquiConf.xml`，`init-priority` 与 `moqui-aws` / `moqui-fop` 同级（约 40） |
| CF-02 | 业务代码通过封装服务访问引擎；允许内部用 `ec.getTool("Flowable", ProcessEngine.class)`，应用组件不要直接依赖 Flowable API |
| CF-03 | 启动时扫描并部署本仓库约定路径下的 BPMN（如 `component://**/process/*.bpmn20.xml`）；支持服务 `deploy#ProcessDefinition` 做热部署 |
| CF-04 | Flowable Job Executor / 异步 Service Task 所在线程必须 `getExecutionContext()`，结束后 `destroyActiveExecutionContext()`；缺 `ec` 视为缺陷 |
| CF-05 | 引擎依赖仅为嵌入式 `flowable-engine`（或等价 BOM）。不上 Flowable UI 全家桶、不上独立 Modeler / IDM / Task 应用。允许本组件在 Moqui 屏幕内嵌第三方画布（见 §7.7），画布库本身不是引擎依赖。包版本与 schema 策略见 [TECHNICAL.md](TECHNICAL.md) |

### 7.2 服务桥

| ID | 需求 |
|----|------|
| CF-10 | Service Task 只调 Moqui 服务，不调实体。提供 `JavaDelegate`（如 `MoquiServiceDelegate`）：`serviceName` + **默认按变量名对服务入参，并注入 `businessKey`**。不做字段级映射 UI（W3） |
| CF-11 | 服务出参写回 variables，供网关条件使用 |
| CF-12 | `ec.message.hasError()` 时抛错，由 Flowable 重试或 error boundary 处理 |
| CF-13 | `start#Process`：`processDefinitionKey` + `businessKey`（业务主键）+ variables。组件内写 `WfProcessLink`（W4）；发现进行中的同键 link 则拒绝，不靠业务自己判断（CF-24） |
| CF-14 | `signal#Process` / `message#Process`：等待外部系统或线下结果。挂到 **以后**（W5 之后），第一期不实现 |
| CF-15 | `complete#Task` **只做**：鉴权（当前用户必须是 **assignee**；候选须先 `claim#`，见 CF-37）→ 写 `WfTaskComment` → 写**节点局部** outcome（CF-26）→ `task.complete`。必收 `outcome`（`approve` / `reject` / `return`），选收 `comment`。**默认不调**业务 `approve#` / `reject#`。业务失败要任务保持打开时，用该 UserTask 上**可选**的「完成时同步服务」（按 outcome 各绑一个，CF-46 / CF-66）；会签 / 并行节点不绑。改 `statusId` 画在网关之后的 Service Task（CF-22 / CF-62） |
| CF-16 | `list#MyTasks`：按 `ec.user.userId` 与 `ec.user.userGroupIdSet` 查指派与候选任务 |
| CF-17 | 启动流程优先由业务服务或 SECA（如 `tx-commit`）调用 `start#Process`，不在 entity-auto 里塞引擎 |
| CF-18 | **单据先行、提交即开流。** 「启动即出单」（如点「报销申请」马上出申请单）只跳转既有业务 `form-single`（草稿），此时**不**调 `start#Process`。用户填完后由业务 `submit#` 按 CF-21 改状态，再按 CF-13 / CF-17 启动。申请人填单不是 UserTask，不进 `list#MyTasks`。不为 Start Event 配 `formKey`，不用 Flowable Form 当启动表单 |
| CF-19 | `get#ProcessInstance`：按实例 id 或 `businessKey` 查运行 / 历史摘要（定义 key、版本、状态、当前节点、`startUserId`）。W1 即可 ServiceRun 验收；W4 起供实例详情 |

服务命名空间写死在 [TECHNICAL.md](TECHNICAL.md)：`wf.flowable.WorkflowServices`、`wf.flowable.DesignerServices`。

### 7.3 与 StatusFlow 对齐

| ID | 需求 |
|----|------|
| CF-20 | 单据 `statusId` 仍是对内对外状态；Flowable 实例只做编排 |
| CF-21 | 启动流程的业务服务先把单据迁到「已提交 / 审批中」，且必须是合法 `StatusFlowTransition` |
| CF-22 | 关键节点通过 **已有** `approve#` / `reject#` / `update#` 改状态，走 `EntityAutoServiceRunner` 校验。这些服务画在 **网关之后的 Service Task**，不由 `complete#Task` 默认调用 |
| CF-23 | 流程正常结束 / 驳回结束再迁到终态（同样用 End 前的 Service Task，或结束监听调既有服务） |
| CF-24 | **W4 必做**薄实体 `WfProcessLink`：`entityName` + `pkValue` + `processInstanceId` + `processDefinitionKey` + `statusId`。进行中的 `(entityName, pkValue, processDefinitionKey)` **至多一条**（`WfLinkActive`）。实例结束后 link 留作历史（`WfLinkEnded`），允许再 `start#`（驳回结束 → 改草稿 → 再提交）。`start#` 发现进行中 link 则拒绝 |
| CF-25 | 网关条件读 **当前 execution 的局部** outcome（CF-26），不在 BPMN 里绕过 StatusFlow 改库 |
| CF-26 | **禁止**只写一条进程级 `outcome`。`complete#Task` 写 execution-local（或 `outcome_<activityId>`）；网关条件读当前 execution 局部变量。会签用多实例局部变量 + 完成条件（W5，CF-78） |

### 7.4 身份与待办

| ID | 需求 |
|----|------|
| CF-30 | 不启用 Flowable 自带用户库作为账号源 |
| CF-31 | `assignee` / `candidateUsers` = `UserAccount.userId`（全程 userId，不用 username） |
| CF-32 | `candidateGroups` = `UserGroup.userGroupId` |
| CF-33 | 认领 / 完成另受 `ec.user.isInGroup`、`hasPermission` 与 ArtifactAuthz 约束 |
| CF-34 | 待办是本组件 Moqui screen，数据来自 Flowable `TaskService`，不查 WorkEffort |
| CF-35 | 第一期 **不** 与 WorkEffort 双写。SimpleScreens「我的任务」继续只管项目任务 |
| CF-36 | UserTask 创建时用 `TaskListener` 发已有 `NotificationTopic`（如 `WorkflowTask`），接收人为 assignee 或候选组成员；不另做站内信。催办不另做按钮，W5 可用 Timer 路径 + 本通知 |
| CF-37 | W4：`claim#Task` / `unclaim#Task`。候选用户或候选组成员认领后成为 assignee。**未认领的候选任务不能 `complete#`**。已是 assignee 的任务无需再认领 |
| CF-38 | W4：`list#CompletedTasks`（我已办）、`list#MyStarted`（我发起的，按 `startUserId` = `ec.user.userId`） |

第一期**不做**组织选人解析器（上级、角色、部门经理）。`${initiatorUserId}` / `${managerUserId}` 等由业务 `start#` 传入 variables。

### 7.5 屏幕与表单

| ID | 需求 |
|----|------|
| CF-40 | UserTask `formKey` = Moqui 屏幕路径（`component://...xml`）。每个 UserTask **独立**配 `formKey`，互不继承 |
| CF-41 | 待办打开时带 `taskId` 与 `businessKey`；表单用该节点 `formKey` 指向的既有 `form-single`。节点意见用本组件统一意见条（§7.8），不改业务屏来收 `outcome` / `comment` |
| CF-41a | **同一张单、各节点展示可以不同。** 同一流程实例、同一 `businessKey`（及 `WfProcessLink`）下，各 UserTask 可绑不同 `formKey`（不同既有 `form-single`），例如经理看费用摘要、财务看科目与发票。差异只来自各节点选的屏幕。第一期引擎**不**按 `nodeId` 做同一 `form-single` 的字段显隐 / 只读切换。允许业务侧为审批节点另做只读、精简或角色视角屏；这些屏仍不收 `outcome` / `comment`（CF-60）。各节点绑同一屏也合法 |
| CF-42 | UserTask **运行时**不用 Flowable Form；待办打开该节点 `formKey` 的 Moqui `form-single`。管理员设计流程用 §7.7，不是外置 Modeler |
| CF-43 | 改流程 = 设计器保存 + `deploy#ProcessDefinition`（热部署）。`component://**/process/*.bpmn20.xml` 只作 seed / 回归；启动扫描不得覆盖库中更新的同 key。旧实例按 Flowable 版本继续跑旧定义。`WfProcessDefinition` 一行一 key 只留最新 XML（设计器权威源）；运行版本以引擎 `version` 为准；第一期不做本表历史版本树 |
| CF-44 | 待办 / 已办 / 我发起 / 实例详情应用 `AT_XML_SCREEN` ArtifactAuthz；`start#Process` / `complete#Task` / `claim#Task` 走服务权限。引擎内授权不能替代 Moqui 授权 |

挂 `/qapps/collie-flowable/`：

| 波次 | 屏 |
|------|----|
| W2 | 设计器 + 定义列表 |
| W4 | 待办 + 已办 + 我发起 + 实例详情（时间线 + 业务屏，**不**加载 bpmn-js） |
| W5 | 管理员实例监控（只读图可加载 bpmn-js，CF-49） |

服务仍可用 ServiceRun 验收。**没有**「选流程定义 → 引擎弹出启动表单」的发起台；发起入口在业务应用菜单，见 CF-18 与 §8。

**发起即填单（谁填、何时开流）：**

| 谁 | 在哪填 | 是不是 UserTask |
|----|--------|-----------------|
| 申请人 | 业务应用里的既有 `form-single`（草稿）；申请备注是单据字段，不是节点意见 | 否，流程尚未 `start#` |
| 经理 / 财务等 | `/qapps/collie-flowable/` 待办：该节点 `formKey` 打开对应业务屏（可与其它节点不同；同一 `businessKey`），**旁边**本组件意见条（`outcome` + `comment`），带 `taskId` + `businessKey` | 是 |

菜单可以叫「启动报销流程」，实现上只是跳到业务屏；真正的 `start#Process` 藏在 `submit#`（或它的 `tx-commit` SECA）里。

不要用下面两种做法实现「一点启动就出单」（要改须先改本节与 CF-18，不能在编码时改回）：

1. **Start 后第一个 UserTask = 申请人填单。** 启动时还没有完整单据，`businessKey`（CF-13）与「先迁到审批中」（CF-21）都落空；申请人也会进待办，和「填完即提交审核」不一致。
2. **Flowable Start Event 表单 / Flowable Form。** 与 CF-42 冲突；设计器也不给 Start 配 `formKey`。

### 7.6 数据、事务、集群

| ID | 需求 |
|----|------|
| CF-50 | Flowable `ACT_*` **不** 映射为 Moqui entity。跟 Moqui **当前 DataSource**（H2 开发 / Postgres 部署均可），默认表前缀 `ACT_`。不做独立 schema（第一期） |
| CF-51 | 第一期：引擎自行提交；Service Task 内 Moqui 服务走 `TransactionFacade`；Moqui 失败抛错，由 Flowable async job 重试。不上 JTA 双 XA |
| CF-52 | 若后续必须「改状态 + 推进 token」同提交，再用 Moqui `TransactionFacade.getTransactionManager()` 配 Flowable JTA。须另开一期，不混进第一期 |
| CF-53 | 集群只开一套 Job Executor（Flowable 对作业表有锁） |
| CF-54 | 人工超时用 BPMN Timer；批处理仍用 `ServiceJob`。二者不互相替代。W5：Timer = 走另一条 BPMN 路径（可到升级 UserTask）；**不做**「到期自动改 assignee」的引擎魔法 |

### 7.7 可视化设计器

管理员在本组件屏幕画流程；画布是嵌入的第三方库，不是自研引擎，也不是独立 Flowable UI。选型与嵌法见 [TECHNICAL.md](TECHNICAL.md)。

| ID | 需求 |
|----|------|
| CF-45 | 管理员在本组件 Moqui 屏幕画 BPMN。W2 节点：Start / UserTask / Service Task / XOR / AND / End（W2 可画 AND，W4 即可跑并行汇合）。W5 加 Timer 与 multi-instance（会签）。产出可被 Flowable 部署的 BPMN 2.0 XML。不自研画布引擎，不嵌入 Flowable UI |
| CF-46 | 属性面板绑定 Moqui 真相，写入 `flowable:` 扩展：Service Task → 已注册服务全名；UserTask `formKey` → `component://` 屏幕路径（每个 UserTask 独立选，允许互不相同，CF-41a）；`assignee` / `candidateUsers` → `userId`；`candidateGroups` → `userGroupId`。允许 `${var}`。W4：UserTask **可选**「完成时同步服务」（按 `approve` / `reject` / `return` 各选一个已注册服务全名）；不填则 `complete#` 不调业务服务。W5：会签节点可配 `collection` / `completionCondition` |
| CF-47 | 设计稿存本组件实体（草稿 / 已部署）。保存不自动部署；部署走 `deploy#ProcessDefinition` |
| CF-47a | W2 起 `deploy#` 做最小校验：孤立节点、UserTask 缺 `formKey`、Service Task 缺 `serviceName` 则拒绝部署 |
| CF-48 | 应用根 `/qapps/collie-flowable/`（`AT_XML_SCREEN`，默认 `ADMIN`）。设计器、定义列表、W5 监控仅管理员；待办 / 已办 / 我发起 / 实例详情另受任务指派或发起人约束（CF-33 / CF-63）。第一期不挂 Marble |
| CF-49 | 第三方画布若许可要求署名 / 水印，允许出现在**设计器页**和 **W5 管理员监控页**；不得出现在待办、已办、我发起、实例详情或业务表单 |

### 7.8 任务动作与审批意见

**单据字段继续用既有 Moqui 实体和 `form-single`（各节点可用不同屏看同一行单据，CF-41a）；UserTask 上的通过 / 不通过 / 意见由本组件统一提供。** 二者不是一类数据。申请人草稿备注跟单据走；节点意见跟任务实例走。

| | 业务表单 | 节点审批意见 |
|--|----------|----------------|
| 生命周期 | 跟单据走 | 跟任务实例走（会签则每人一条） |
| 粒度 | 一行单据一份 | 每个 UserTask 一条；撤回 / 作废 / 转办另记一行 |
| 跨单据 | 各实体各写字段 | 待办、实例时间线同一套 |
| 谁写 | 申请人在草稿屏写（还不是 UserTask） | 办理人在待办意见条写；转办 / 撤回 / 作废由对应服务写 |

| ID | 需求 |
|----|------|
| CF-60 | 不把 UserTask `outcome` / `comment` 做成各业务实体字段，也不改业务 `form-single` 来收意见。业务 `approve#` / `reject#` / `update#` 只改单据状态与业务副作用（CF-22） |
| CF-61 | 薄实体（如 `WfTaskComment`）：`taskId` + `processInstanceId` + `nodeId` + `userId` + `outcome` + `comment` + 时间，并可挂 `businessKey`。字段写死在 [TECHNICAL.md](TECHNICAL.md)。W4 必做。`outcome` 枚举：`approve` / `reject` / `return` / `transfer` / `withdraw` / `cancel`。后三个只进意见表，**不**写网关变量（W5 操作面才产生） |
| CF-62 | `complete#Task` 是任务收口：记意见 + 写局部 outcome + 推进 token。**不**在 complete 内默认调 `approve#`。不另提供一套与单据无关、替代 `approve#` 的「通用审批业务服务」。改状态用网关后 Service Task（或 UserTask 可选完成时同步服务，CF-15 / CF-66） |
| CF-63 | `list#TaskComments`：按 `processInstanceId` 或 `businessKey` 查时间线。仅实例参与者、发起人（`startUserId`）或 `ADMIN` 可查，供待办与实例详情只读展示 |
| CF-64 | 不用 Flowable `TaskService.addComment` / 引擎评论表当产品意见库（与 CF-50 一致：`ACT_*` 不映射 entity，也不当审计意见源） |
| CF-65 | 待办与实例详情的意见条 / 时间线是本组件屏幕，叠在业务 `form-single` **旁边**，不加载 bpmn-js（CF-49）。W4 的 `return` = 只写意见 + 局部变量，**靠 BPMN 画回退边**（XOR → 上一 UserTask 或结束）；引擎不做 `moveActivity`。加签、驳回指定节点另开范围 |
| CF-66 | UserTask **可选**完成时同步服务（设计器 W4 可配）：`complete#` 在写意见之后、`task.complete` 之前按 outcome 调用。服务失败则不推进 token、不落意见（或回滚该意见），任务保持打开。会签 / 并行节点不配此项 |

### 7.9 创建定义、发布定义、业务开流

保存、发布、开实例是三步，不要混成「创建流程」。本组件提供定义与引擎封装；**每个单据类型的开流接线是业务侧定制**，不是本组件内置发起台（CF-17 / CF-18 / CF-47）。

| 说法 | 谁做 | 做什么 | 服务 |
|------|------|--------|------|
| **创建 / 保存** | 管理员 | 画 BPMN，写成草稿 | `wf.flowable.WorkflowServices.save#ProcessDefinition` |
| **发布 / 部署** | 管理员 | 把 BPMN 交给 Flowable，之后才能被启动 | `wf.flowable.WorkflowServices.deploy#ProcessDefinition` |
| **启动实例** | 业务 `submit#` 或它的 SECA | 单据已有主键且已迁到审批中之后，开一条运行中的实例 | `wf.flowable.WorkflowServices.start#Process`（组件内写 `WfProcessLink`） |

保存**不会**自动发布（CF-47）。改已发布定义 = 设计器再保存 + 再 `deploy#`（CF-43）。旧实例按 Flowable 版本继续跑旧定义。

**管理员如何创建并发布（本组件屏）：**

1. 打开 `/qapps/collie-flowable/`（默认 `ADMIN`）。设计器：`/qapps/collie-flowable/Designer`（W2）。
2. 画 W2 节点（Start / UserTask / Service Task / XOR / AND / End）。属性面板绑 Moqui 真相（CF-46）：Process `id` = `processDefinitionKey`（创建后只读）；UserTask 独立选 `formKey`、`assignee` / `candidateUsers`（`userId`）、`candidateGroups`（`userGroupId`）；Service Task 选已注册服务全名。W4 起 UserTask 可选完成时同步服务。
3. 保存 → `save#ProcessDefinition`，只写 `WfProcessDefinition`（草稿）。**不部署。**
4. 再点部署 → `deploy#ProcessDefinition`（先过 CF-47a 校验）。资源名必须是 `*.bpmn20.xml`（或 `.bpmn`），否则引擎不注册定义。发布后 `statusId` 为已部署，记下 `deploymentId`。

权威源是库里的 `WfProcessDefinition`，不是磁盘 XML。`component://**/process/*.bpmn20.xml` 只作 seed / 回归；启动扫描不得覆盖库中已更新的同 key（CF-43）。W1 可用 ServiceRun 直接 `deploy#` 一条空 BPMN 验收，不经过设计器。

**业务用户如何开实例（本地业务组件定制，不是本组件功能）：**

本组件**不**替业务单据开流，也**不**改 mantle-usl / SimpleScreens / MarbleERP 去感知引擎（NFR-01 / §10）。没有「选流程定义 → 引擎弹出启动表单」的发起台。业务菜单可以叫「启动报销流程」，实现上只跳转既有草稿 `form-single`；真正的 `start#Process` 藏在该单据的 `submit#`（或它的 `tx-commit` SECA）里。

| 本组件提供 | 每个单据类型要自己做 |
|------------|----------------------|
| `start#Process`（`processDefinitionKey` + `businessKey` + variables；组件写 link、拒重复） | 既有实体、`StatusFlow`、草稿 `form-single`、`submit#` |
| `WfProcessLink`（防重复启动，W4） | 选定哪个 key、`businessKey` 用哪列主键、何时允许启动 |
| 待办、`complete#Task`、设计器 | 在本地组件的 `submit#` 或对本组件可依赖的业务服务挂 SECA；并把 `approve#` / `reject#` 画成网关后 Service Task |

单据屏本身**不用改成工作流屏**，也不必出现 Flowable。申请人仍填原来的 `form-single`。要接的是提交服务，不是画布。优先业务服务或 SECA，**不要**塞进 entity-auto（CF-17）。两种接法都合法：

```text
写法 A（写在业务 submit# 里）
  submit#Expense
    1. 合法 StatusFlow：草稿 → 审批中          （CF-21，必须先改状态）
    2. start#Process(key, businessKey=expenseId)
       （组件写 WfProcessLink；已有 WfLinkActive 则拒绝）

写法 B（submit# 只改状态，SECA 开流）
  submit# 仍只做单据 + StatusFlow
  tx-commit SECA → start#Process（组件写 link）
```

每个**新的单据类型**都要配一次。这不是引擎能力，是业务绑定。报销融合是 §8 验收故事，不是本组件内置业务。

对称的另一处业务接线：审批节点上的 `approve#` / `reject#` 仍是业务服务，画在网关之后的 Service Task；`complete#Task` **默认不调**它们（CF-15 / CF-22 / CF-62）。不要为开流去做这些：

- 不要把「申请人填写」画成 Start 后第一个 UserTask，也不要给 Start 配 `formKey` / Flowable Form（CF-18 / CF-42）
- 不要改业务 `form-single` 去收 `outcome` / `comment`（CF-60）
- 不要改官方 catalog 组件；接线放在 `runtime/component/` 的本地业务组件
- 不要在每个 UserTask 的 complete 里调 `approve#`（会签 / 并行会抢状态）

### 7.10 运行期操作面

W4 补齐待办闭环；W5 补撤回 / 转办 / 作废 / 监控。`transfer` / `withdraw` / `cancel` 只写 `WfTaskComment`，不写网关 outcome 变量。

| ID | 波次 | 需求 |
|----|------|------|
| CF-70 | W4 | 实例详情屏 `InstanceDetail`：按实例或 `businessKey` 展示时间线 + 该节点（或最近节点）`formKey` 业务屏；**不**加载 bpmn-js |
| CF-71 | W5 | `cancel#Process`（作废）：先调业务作废服务迁 StatusFlow 终态，再 `deleteProcessInstance`；意见 `outcome=cancel`。管理员可作废；业务是否允许申请人作废由业务服务鉴权 |
| CF-72 | W5 | `withdraw#Process`（撤回）：仅发起人，且**尚无任何 UserTask 完成**；先调业务服务迁回草稿，再删实例；意见 `outcome=withdraw` |
| CF-73 | W5 | `transfer#Task`（转办）：`setAssignee`；意见 `outcome=transfer`；任务保持打开，不推进 token |
| CF-74 | W5 | `list#ProcessInstances` + 管理员监控列表；只读高亮当前节点，可加载 bpmn-js（CF-49） |
| CF-75 | W5 | `suspend#ProcessDefinition` / `activate#ProcessDefinition`。停用后禁止新 `start#`，旧实例继续 |
| CF-78 | W5 | **或签** = 候选用户/组 + 一人认领完成。**会签** = multi-instance + 完成条件（全过 / 一票否决）。设计器要能配 `collection` / `completionCondition` |

## 8. 与现有能力的边界（用谁）

| 场景 | 用谁 |
|------|------|
| 简单单据生命周期（含知识库发布/解析状态） | StatusFlow，不进本组件 |
| 项目 / 工时 / 制造任务 | WorkEffort |
| 提交后副作用（过账、邮件、改关联单） | SECA / EECA；若该步属于流程，做成 Service Task 调**同一个**服务 |
| 定时批处理 | ServiceJob |
| 跨系统收发 | SystemMessage |
| 定价等规则 | 可选 `moqui-kie` / Drools，不是本组件 |
| 多级 / 并行审批、会签、统一待办 | 本组件 + Flowable |
| 节点通过 / 不通过、审批意见 | 本组件 `complete#Task` + `WfTaskComment`；业务 `approve#` / `reject#` 只改单据状态（网关后 Service Task） |
| 单据上的申请备注、附件、业务字段 | 既有业务实体 + 各节点 `formKey` 指向的 `form-single`（可不同屏、同一张单），不进本组件 |
| 可视化改流程图（不靠发版改服务 XML） | 本组件设计器 + `deploy#` |
| 业务单据提交后开流程实例 | 本地业务组件的 `submit#` 或 SECA 调本组件 `start#`（§7.9）；不是本组件内置发起台，也不改 mantle |
| 认领、已办、我发起、实例详情 | 本组件 W4 |
| 撤回、转办、作废、定义挂起、实例监控 | 本组件 W5 |

报销融合示例（实现时的验收故事，不是本组件内置业务）。顺序是 **先有单据主键，再有流程实例**（CF-18）：

```text
菜单「发起报销」
    → 打开既有费用 form-single（草稿；不调 start#Process）
    → 用户填写
    → 提交 → submit#Expense
         ├─ 合法 StatusFlow：草稿 → 审批中
         └─ start#Process(...)     （组件写 WfProcessLink）
    → 流程进入第一个 UserTask（经理审核）
    → 申请人这边结束；经理去待办认领（若是候选）并打开经理视角费用屏 + 统一意见条
```

1. 申请人在业务屏填草稿（不是待办，不是引擎启动表单；申请备注是单据字段）
2. `submit#Expense` → StatusFlow「审批中」→ `start#Process`（`businessKey=expenseId`；组件写 link）
3. 经理 UserTask（`formKey`=费用经理屏）→ 待办打开该屏 + 意见条 → `complete#Task(outcome=approve, comment=…)` → 写 `WfTaskComment` + 局部 outcome → 推进 token（**不**在 complete 里调 `approve#Expense`）
4. XOR 读当前 execution 局部 outcome：`approve` 往下、`reject` / `return` 走回退边或驳回结束
5. 并行：财务 UserTask（`formKey`=费用财务屏，同一 `expenseId`）+ 合规 Service Task（既有校验服务）；财务同样走意见条。各分支局部 outcome 互不覆盖（CF-26）
6. 汇合后 Service Task 调 `approve#Expense`（或过账）；避免提交时 SECA 就过账，也避免每个 UserTask complete 都改状态
7. End → 状态「已批准」；`WfProcessLink` 置 `WfLinkEnded`；实例时间线可按 `businessKey` 列出各节点意见

## 9. 非功能

| ID | 需求 |
|----|------|
| NFR-01 | 不改 framework、catalog 组件、MarbleERP、SimpleScreens、webroot |
| NFR-02 | 许可：Flowable 引擎 Apache 2.0；本组件随仓库惯例。第三方画布按其自身许可（设计器页与 W5 管理员监控页可含水印）；引擎仍为 Apache 2.0 |
| NFR-03 | 日志打 `runtime/log/moqui.log`；异步线程异常必须可见 |
| NFR-04 | 验证不能只靠编译。ServiceRun 调封装服务；有待办屏则浏览器走完「认领（若候选）→ 打开该节点 `formKey` 业务屏 → 填意见条 → 完成 → 网关后 Service Task 改单据状态、意见时间线与引擎历史一致」，并抽查两个 UserTask 不同屏（CF-41a）。有设计器则再走完「画节点 → 填 Moqui 属性（含各 UserTask 独立选屏）→ 保存 → 部署（含 CF-47a 校验）→ start」 |
| NFR-05 | 第一期不做多租户引擎隔离（沿用 Moqui 当前实例模型） |

## 10. 明确不做（第一期及默认）

- 不把 Flowable 推进 framework，不改 mantle-usl 订单等服务去「感知」引擎
- 不把所有 StatusFlow 迁进 BPMN
- 不上 Camunda 8、不上 Flowable 商业套件；不上 Flowable 8.x（Jackson 3 / Spring Boot 4，与 Moqui 叠车）
- 不上独立 Flowable UI / Camunda 桌面 Modeler、不上 CMMN、不上 DMN
- 不自研 BPMN 画布（嵌入第三方画布，见 §7.7）
- 第一期设计器不做协同编辑、不要求导入桌面工程一键转换（可粘贴 XML）
- 不在 BPMN 里复制一份权限模型
- 不自研 BPMN 引擎
- 不与 WorkEffort 双写（除非后续单独开一期，且已有统一 inbox 的产品理由）
- 不用 Flowable Start Event 表单、不为 Start 配 `formKey` 来实现「启动即出单」（CF-18 / CF-42）
- 不把「申请人填写」画成 Start 后第一个 UserTask 来凑启动表单（须先有单据再 `start#`）
- 不把 UserTask 审批意见做成各业务实体字段，也不改业务 `form-single` 来收 `outcome` / `comment`（CF-60 / §7.8）
- 第一期引擎不按节点做同一 `form-single` 的字段显隐 / 只读切换；节点展示差异只靠各 UserTask 的 `formKey`（CF-41a）。不为「换屏」去改引擎身份或另起一套 Flowable Form
- 不用 Flowable `TaskService.addComment` / 引擎评论表当产品意见库（CF-64）
- 不做组织选人解析器（上级 / 角色 / 部门经理）；`${var}` 由业务 `start#` 传入
- 不加签、减签、委派、抄送、催办按钮、自由跳转、驳回指定节点（`moveActivity`）、实例迁移、Call Activity、消息/信号（CF-14）
- 不把「选任意实体绑流程」做成发起台（§7.9）
- `complete#Task` 不默认调业务 `approve#` / `reject#`（CF-15 / CF-62）

## 11. 分期

改实现只动 [TECHNICAL.md](TECHNICAL.md)；改范围先改本节。

| 波次 | 交付 | 验收要点 |
|------|------|----------|
| W0 | 本文 + [TECHNICAL.md](TECHNICAL.md) + `component.xml` | 口径与选型锁定；组件能被 Moqui 识别 |
| W1 | ToolFactory + `deploy#` / `start#` / `get#ProcessInstance` | ServiceRun 部署一条空 BPMN 并 start |
| W2 | 设计器屏 + 完整属性面板 + 保存 / 部署 + CF-47a 校验 | 浏览器画出 Service Task（选服务名）+ User Task（选屏幕 / 用户 / 组）→ 缺 `formKey` 的部署被拒 → 补全后部署成功 |
| W3 | `MoquiServiceDelegate` +「服务任务→结束」 | 异步线程有 `ec`；变量名对入参 + `businessKey`；服务错误可重试；可用设计器画的图跑通 |
| W4 | UserTask + 认领 + `list#MyTasks` / `list#CompletedTasks` / `list#MyStarted` + `complete#Task`（局部 outcome + `comment`）+ `formKey` 开业务屏 + 统一意见条 / 实例详情 + `NotificationTopic` + `WfProcessLink` + `WfTaskComment` + UserTask 可选完成时服务 | 候选须认领才能完成；待办点开**该节点** `formKey` 的 `form-single` 与意见条；至少两个 UserTask 绑不同屏、同一 `businessKey`（CF-41a）；`complete#` 后意见时间线与实例步骤一致；改状态来自网关后 Service Task（或可选完成时服务）；进行中 link 唯一，结束后可再 `start#` |
| W5 | 会签多实例 + Timer + 撤回 / 转办 / 作废 + 定义挂起 + 管理员监控 | 或签 vs 会签路径；Timer 走出升级 UserTask（不自动改 assignee）；撤回（无已完成任务）/ 转办（任务仍打开）/ 作废（单据终态 + 实例删除）；停用定义后不能新 start |
| 以后 | 加签、驳回指定节点、Call Activity、消息/信号、组织选人解析、WorkEffort 双写、JTA 同提交 | 须单独改本文范围 |

## 12. 开放问题

**已拍板（写入本文，不要在编码时改回）：**

1. 挂 `/qapps/collie-flowable/`：W2 设计器 + 定义列表；W4 待办 / 已办 / 我发起 / 实例详情；W5 管理员监控。不只用 ServiceRun。无「选流程 → 引擎出启动表单」发起台。
2. 第一条示范 BPMN 用组件内定义，不绑报销 / 订单（报销仍是 §8 验收故事）。
3. 设计器权威源在本组件实体；`process/*.bpmn20.xml` 只作 seed / 回归。实体一行一 key 只留最新 XML；运行版本以引擎为准。
4. **单据先行、提交即开流**（CF-18）：「启动即出单」= 跳转既有业务 `form-single`；`start#Process` 只在业务 `submit#` 改完 StatusFlow 之后。申请人填单不是 UserTask。若要改成「先起实例、再填启动表单」，先改 CF-13 / CF-18 / CF-21 / §8。
5. **节点审批意见由本组件统一提供**（§7.8 / CF-60–CF-66）：业务 `form-single` 只管单据字段；UserTask 的 `outcome` + `comment` 不做到各业务实体上，不用 Flowable Task comment。`complete#Task` 收口记意见、写局部 outcome、推进 token，**默认不调**业务服务。W4 必做。若要改成「意见跟单据字段走」，先改 CF-15 / CF-41 / §7.8 / §8。
6. **各 UserTask 单据展示可以不同**（CF-41a）：同一 `businessKey`，各节点独立 `formKey`。第一期不做引擎侧字段显隐。若要改成「同屏按节点藏字段」或「各节点打开不同单据」，先改 CF-40 / CF-41 / CF-41a / §8。
7. **开流是业务侧定制**（§7.9）：本组件只提供 `save#` / `deploy#` / `start#`；每个单据类型在本地组件的 `submit#` 或 SECA 里自己接线（先 StatusFlow 再 `start#`）。不做通用「绑任意实体到流程定义」的发起台，不改 mantle 服务感知引擎。若要改成组件内置发起或改官方 `submit#`，先改 CF-17 / CF-18 / §7.9 / §8 / NFR-01。
8. **`complete#` 不默认调 `approve#`**（CF-15 / CF-22 / CF-62 / CF-66）：改状态用网关后 Service Task；单人节点可选用完成时同步服务。若要改回「每个 complete 都调业务服务」，先改这些条目与 §8。
9. **outcome 必须是 execution-local**（CF-26）。禁止只写进程级 `outcome`。
10. **W4 的 `return` 靠 BPMN 回退边**（CF-65），不是引擎跳转。驳回指定节点仍属以后。
11. **`WfProcessLink` W4 必做**（CF-24）：进行中唯一；结束后可再 `start#`。`start#` 自己拒重复。
12. **操作面分期**：认领 / 已办 / 我发起 / 实例详情在 W4；撤回 / 转办 / 作废 / 定义挂起 / 监控在 W5。

**仍开放（默认建议已写入 [TECHNICAL.md](TECHNICAL.md)，编码前按该文，不要另选）：**

1. 系统用户 / 后台委托用哪个 `userId`，以及是否允许 `disableAuthz`（仅限 Delegate 内部）。
2. `ACT_*` 跟 Moqui 当前 DataSource 加前缀，还是以后再拆独立 schema。

## 13. 参考

- 实现与选型：[TECHNICAL.md](TECHNICAL.md)（包版本、嵌屏、服务名写死处）
- 框架：`framework/entity/BasicEntities.xml`（StatusFlow）、`EntityAutoServiceRunner.checkStatus`、`ToolFactory`、`TransactionFacade`、`UserFacade`
- 工具组件范例：`runtime/component/moqui-aws/MoquiConf.xml`、`runtime/component/moqui-fop`
- 任务域：`runtime/component/mantle-udm/entity/WorkEffortEntities.xml`、`mantle-usl/service/mantle/work/`
- 组件脚手架约定：仓库 `.agents/skills/create-moqui-component/SKILL.md`
- Flowable：嵌入式 `ProcessEngine`、`TaskService`、`JavaDelegate`（Apache 2.0）；本组件钉 7.2.x
- 画布实现引用（不绑定需求包版本）：bpmn-js
