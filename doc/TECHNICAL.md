# collie-flowable 技术方案

范围来源：[REQUIREMENTS.md](REQUIREMENTS.md)。改实现只动本文；改产品口径先改 REQUIREMENTS（尤其 CF-xx 与 §11）。本文不新增需求编号。

下列选型与默认值按本文实现，不要另选。

## 1. 架构

```text
/qapps/collie-flowable/ProcessList    ── 定义列表（New Process → 点 key 进设计器）
/qapps/collie-flowable/Designer       ── bpmn-js 画布 + Quasar 属性抽屉（无菜单项）
/qapps/collie-flowable/               ── 应用根，默认待办
/qapps/collie-flowable/TaskList       ── 待办 / 已办 / 我发起（W4）
/qapps/collie-flowable/InstanceDetail ── 时间线 + 业务屏，不加载 bpmn-js（W4）
/qapps/collie-flowable/Monitor        ── 管理员实例监控，可加载 bpmn-js（W5）
        │
        ▼
wf.flowable.DesignerServices     lookup：服务名 / 屏幕 / userId / userGroupId
wf.flowable.WorkflowServices     create# / save# / update#ProcessDefinitionMeta / deploy# / start# / get# / claim# / complete#
                                 list#MyTasks / list#CompletedTasks / list#MyStarted
                                 list#TaskComments / list#ProcessInstances
                                 transfer# / withdraw# / cancel# / suspend# / activate#
                                 addSign# / returnTo# / message# / signal#
        │
        ├─► WfProcessDefinition.bpmnXml     （设计器权威源，一行一 key 最新稿）
        ├─► WfProcessLink                   （进行中唯一，结束后可再 start）
        ├─► WfTaskComment                   （节点 outcome + 意见）
        └─► FlowableToolFactory ──► ProcessEngine ──► ACT_* （默认前缀，不映射 entity）
                    │
                    ▼
            MoquiServiceDelegate ──► 既有业务服务 ──► 实体 + StatusFlow
```

- 业务与应用组件只调 `wf.flowable.*`，不直接依赖 Flowable API（CF-02）。
- 内部允许 `ec.getTool("Flowable", ProcessEngine.class)`。
- 不改 `framework/`、`webroot`、`WebrootVue.qvt.js`、mantle、MarbleERP（NFR-01）。

## 2. 设计器选型（已拍）

| 选项 | 结论 | 原因 |
|------|------|------|
| **bpmn-js**（画布） | **采用** | BPMN 2.0 保真度最高；`saveXML()` 可直接给 Flowable `deploy` |
| bpmn-js-properties-panel | **不用** | 与 `/qapps` Vue 2 + Quasar 叠两套属性 UI；Moqui 下拉更好自己做 |
| Flowable UI Modeler | 不用 | 独立 Spring Boot + IDM，违背 CF-05 |
| Camunda Modeler 桌面 | 不用 | 不能进 Moqui 界面 |
| LogicFlow / AntV X6 | 不用 | Flowable `flowable:` 扩展要自写适配 |
| 国内 vue-bpmn 套壳 | 不用 | 内核仍是 bpmn-js（同样水印），依赖旧 |

许可：bpmn-js 为 bpmn.io License（非 Apache）。**设计器页与 W5 管理员监控页右下角必须保留未遮挡的 bpmn.io 水印**（CF-49）。待办、已办、我发起、实例详情与业务表单不加载 bpmn-js。

Vendor：`build.gradle` 的 `downloadBpmnJs` 从 jsDelivr 按版本（当前 **17.11.1**）拉 UMD/CSS 到 `screen/static/bpmn-js/`（已存在则跳过），并去掉 `bpmn.css` 里指向 `../font/` 的 `@font-face`（字体只走 `bpmn-font-fix.css` 的 Asset URL）。不在本组件跑 Vite/npm。自维护一份小 `flowable.json` moddle，不依赖无人维护的 `flowable-bpmn-moddle` npm 包。不把 bpmn-js 拷进 `webroot/libs/`。

画布节点：W2 为 Start、UserTask、Service Task、XOR、AND、End。W5 加 Timer / 多实例。W8 加 Call Activity 与 Message / Signal 捕获。调色板禁止子流程展开、数据对象、Participant。

## 3. 嵌屏

挂载（本组件 `MoquiConf.xml`，重启生效）：

```xml
<screen location="component://webroot/screen/webroot/apps.xml">
    <subscreens-item name="collie-flowable" menu-title="Workflow" menu-index="26"
            location="component://collie-flowable/screen/App.xml"/>
</screen>
```

URL：`/qapps/collie-flowable/`。第一期不挂 Marble。

| 文件 | 波次 | 作用 |
|------|------|------|
| `screen/App.xml` | W2 | 应用根 |
| `screen/ProcessList.xml` | W2 | 定义列表（草稿 / 已部署；W5 加挂起） |
| `screen/ProcessDesigner.xml` | W2 | 必须带已有 `processDefinitionKey`；qvt 画布 + 属性面板；transition：`updateMeta` / `saveDefinition` / `deployDefinition` / lookup |
| `screen/TaskList.xml` | W4 | 待办 / 已办 / 我发起三个列表 |
| `screen/InstanceDetail.xml` | W4 | 时间线 + 该节点 `formKey` 业务屏；不引入 bpmn-js |
| `screen/Monitor.xml` | W5 | 管理员实例列表 + 只读高亮当前节点（橙）与已完成节点/已走 sequenceFlow（绿）；**仅选中实例 / 定义时**加载 bpmn-js |
| `data/AppSeedData.xml` | W2 | `AT_XML_SCREEN` 挂 `App.xml`，`inheritAuthz="Y"`，默认 `ADMIN` |

大 XML 走 POST JSON body + CSRF（对齐 tools `ServiceLoadRunner`），不进 query string。

属性抽屉：选中节点后 Quasar 表单，用 bpmn-js `modeling.updateProperties` + flowable moddle 回写。不是第二套元模型。

| 节点 | 写入 BPMN | 面板 |
|------|-----------|------|
| Process | `id` = `processDefinitionKey`，`isExecutable="true"` | 名称可编，key 创建后只读 |
| Service Task | `flowable:delegateExpression="${moquiServiceDelegate}"` + `flowable:field name="serviceName"` | `list#ServiceNames` |
| User Task | `flowable:formKey` | `list#ScreenLocations` |
| User Task | `flowable:assignee` / `candidateUsers` | `list#Users`；可填 `${var}` |
| User Task | `flowable:candidateGroups` | `list#UserGroups` |
| User Task | 可选完成时同步服务（W4，CF-66） | `flowable:field`：`onApproveService` / `onRejectService` / `onReturnService`（已注册服务全名，可空） |
| User Task | 会签（W5，CF-78） | `multiInstanceLoopCharacteristics`：`collection` / `completionCondition` |
| User Task | 允许加签（W6，CF-81） | `flowable:field`：`allowAddSign`（`true` / 空） |
| User Task | 选人 token（W6，CF-80） | `assignee` / `candidateUsers` 可为 `initiator`、`managerOfInitiator`、`managerOfAssignee`、`role:{roleTypeId}` |
| Call Activity | `calledElement`（W8） | 已部署 `processDefinitionKey` |
| Message / Signal | 捕获名称（W8） | 中间捕获或边界；允许 `${var}` |
| Sequence flow | `conditionExpression` | 文本；读当前 execution 局部 `outcome`（不要写进程级 `outcome`） |

`deploy#` 最小校验（CF-47a）：孤立节点、UserTask 缺 `formKey`、Service Task 缺 `serviceName` → 拒绝。W8：Call Activity 缺 `calledElement`、Message/Signal 捕获缺名称、Call Activity 循环调用 → 拒绝。

## 4. 服务名（写死）

| 服务 | 波次 | 职责 |
|------|------|------|
| `wf.flowable.WorkflowServices.deploy#ProcessDefinition` | W1 | 热部署。资源名必须 `*.bpmn20.xml`。W2 起先过 CF-47a |
| `wf.flowable.WorkflowServices.create#ProcessDefinition` | W2 | 定义列表新建草稿行。必填 key + name；拒绝已存在的 key；写入 Start→End stub BPMN。不部署 |
| `wf.flowable.WorkflowServices.save#ProcessDefinition` | W2 | 写实体草稿 XML；不部署 |
| `wf.flowable.WorkflowServices.update#ProcessDefinitionMeta` | W2 | 只写名称 / 备注，不改 BPMN 与状态 |
| `wf.flowable.WorkflowServices.start#Process` | W1 | `processDefinitionKey` + `businessKey` + variables。W4 起：写 `WfProcessLink`（`WfLinkActive`）；已有进行中同键则拒绝；定义已挂起则拒绝 |
| `wf.flowable.WorkflowServices.get#ProcessInstance` | W1 | 按实例 id 或 `businessKey` 查运行 / 历史摘要（CF-19）。W5 起另出 `completedActivities` / `completedFlows`（已完成节点与已走 sequenceFlow），供监控图高亮 |
| `wf.flowable.WorkflowServices.claim#Task` / `unclaim#Task` | W4 | 候选 → assignee / 退回候选（CF-37） |
| `wf.flowable.WorkflowServices.complete#Task` | W4 | 必收 `taskId` + `outcome`（`approve` / `reject` / `return`），选收 `comment`。须已是 assignee（候选先 `claim#`）。顺序：鉴权 → 若该节点配了对应完成时服务则同步调用（失败则停止、不落意见、不 complete）→ 写 `WfTaskComment` → 写 **execution-local** `outcome` → `task.complete`。**默认不调**业务 `approve#`。禁止写进程级 `outcome`。CF-33：运行期只靠候选组 / assignee + ArtifactAuthz，不另做 `hasPermission` |
| `wf.flowable.WorkflowServices.list#MyTasks` | W4 | 待办：指派 + 候选 |
| `wf.flowable.WorkflowServices.list#CompletedTasks` | W4 | 已办（CF-38） |
| `wf.flowable.WorkflowServices.list#MyStarted` | W4 | 我发起的，`startUserId` = 当前用户 |
| `wf.flowable.WorkflowServices.list#TaskComments` | W4 | 按 `processInstanceId` 或 `businessKey` 查时间线。仅参与者、发起人或 `ADMIN`（CF-63） |
| `wf.flowable.WorkflowServices.transfer#Task` | W5 | `setAssignee`；意见 `outcome=transfer`；不推进 token |
| `wf.flowable.WorkflowServices.withdraw#Process` | W5 | 仅发起人且无已完成 UserTask；业务迁回草稿后删实例；意见 `withdraw` |
| `wf.flowable.WorkflowServices.cancel#Process` | W5 | 先业务作废服务迁终态，再删实例；意见 `cancel` |
| `wf.flowable.WorkflowServices.list#ProcessInstances` | W5 | 管理员监控列表。默认最近 **50** 条（`pageSize` 上限 200）。当前节点一次 `createExecutionQuery` 批量取，不按行 `getActiveActivityIds`。选中但不在本页的 `processInstanceId` 会补进列表 |
| `wf.flowable.WorkflowServices.suspend#ProcessDefinition` / `activate#ProcessDefinition` | W5 | 停用后禁止新 `start#`，旧实例继续 |
| `wf.flowable.WorkflowServices.addSign#Task` | W6 | `taskId` + `toUserId`；选收 `comment`。创建加签子任务，不推进 token |
| `wf.flowable.WorkflowServices.returnTo#Task` | W7 | `taskId` + `targetActivityId`；选收 `comment`。跳到已经过的 UserTask |
| `wf.flowable.WorkflowServices.message#Process` | W8 | `processInstanceId` 或 `businessKey` + `messageName` + 可选 variables |
| `wf.flowable.WorkflowServices.signal#Process` | W8 | `processInstanceId` 或 `businessKey` + `signalName` + 可选 variables |
| `wf.flowable.DesignerServices.list#ServiceNames` | W2 | ServiceFacade 注册表 |
| `wf.flowable.DesignerServices.list#ScreenLocations` | W2 | 已注册 `component://…xml` |
| `wf.flowable.DesignerServices.list#Users` | W2 | `moqui.security.UserAccount` |
| `wf.flowable.DesignerServices.list#UserGroups` | W2 | `moqui.security.UserGroup` |

`deploy#` 要点：

```text
repositoryService.createDeployment()
    .addString(processDefinitionKey + ".bpmn20.xml", bpmnXml)
    .deploy()
```

后缀不是 `.bpmn20.xml` / `.bpmn` 时 Flowable 不注册定义。

### 4.1 变量规则（CF-26）

- `complete#Task` 用 Flowable **local** 变量写入 `outcome`（`taskService.complete(taskId, vars, true)` 或等价 API），值为 `approve` / `reject` / `return`。
- 网关 `conditionExpression` 读当前 execution 的 `outcome`（局部）。不要 `${execution.getVariable('outcome')}` 当进程级用。
- 并行分支各写各的局部 `outcome`，互不覆盖。
- `transfer` / `withdraw` / `cancel` / `addSign` / `returnTo` **不**写 `outcome` 变量，只写 `WfTaskComment`。
- Service Task 入参：按变量名对服务 in-parameters，并注入 `businessKey`。不做字段级映射 UI（CF-10）。

## 5. 实体

包名 `wf.flowable`。`ACT_*` **不** 做成 Moqui entity。

| 实体 | 波次 | 字段要点 |
|------|------|----------|
| `WfProcessDefinition` | W2 | `processDefinitionKey`（唯一）、`processName`、`description`（备注）、`bpmnXml`（`text-very-long`）、`statusId`（草稿 / 已部署；W5 加已挂起）、`deploymentId`。一行一 key 只留最新 XML；引擎 `version` 是运行真相；第一期不做本表历史版本树 |
| `WfProcessLink` | W4（必做） | `entityName` + `pkValue` + `processInstanceId` + `processDefinitionKey` + `statusId`（`WfLinkActive` / `WfLinkEnded`）。进行中的 `(entityName, pkValue, processDefinitionKey)` 至多一条 |
| `WfTaskComment` | W4（必做，CF-61） | `taskCommentId`（PK）、`taskId`（可空：撤回 / 作废可能无当前任务）、`processInstanceId`、`nodeId`（可空）、`userId`、`outcome`（`approve` / `reject` / `return` / `transfer` / `withdraw` / `cancel` / `addSign` / `returnTo`）、`comment`（可空，`text-long`）、`commentDate`、`businessKey`（可空） |

StatusItem 本组件 seed：`WfDefDraft` / `WfDefDeployed` / `WfDefSuspended`（W5）；`WfLinkActive` / `WfLinkEnded`。具体 id 实现时定，保持短、稳定。

启动扫描（CF-03 / CF-43）：`component://**/process/*.bpmn20.xml` 仅当库中无同 key，或库中版本不新于文件时部署。库是设计器权威源。

## 6. 引擎

对齐 `moqui-aws`：

- `build.gradle`：`org.flowable:flowable-engine:7.2.0`（或当时最新 **7.2.x**，Apache 2.0），`copyDependencies` → `lib/`。**不要** Flowable 8.x（默认 Jackson 3 + Spring Boot 4，与 Moqui 叠车）。本仓库 framework 为 Java 21，7.2.x 要求 Java 17+
- `databaseSchemaUpdate=true`（仅本组件 `ProcessEngine` 建/改 `ACT_*`）
- `src/main/groovy/wf/flowable/FlowableToolFactory.groovy`：`init-priority="40"`，`destroy` 关引擎
- `src/main/groovy/wf/flowable/MoquiAssignmentListener.groovy`：W6；TASK_CREATED 时解析选人 token
- `src/main/groovy/wf/flowable/MoquiCallActivityLinkListener.groovy`：W8；子流程启动时写 `WfProcessLink`
- 异步 Job / Service Task 线程：`getExecutionContext()`，结束 `destroyActiveExecutionContext()`（CF-04）

数据源：**跟 Moqui 当前 DataSource**（嵌入 H2 开发、Postgres 部署均可），表前缀 `ACT_`。不要写死「必须 Postgres」，也不做独立 schema（第一期）。`transactionsExternallyManaged=true`，跟当前 Moqui 事务走；Job 线程由拦截器 begin/commit（CF-51）。

## 7. 示例 BPMN 形态

Service Task（Moqui 桥；改状态画在网关之后）：

```xml
<serviceTask id="callApprove" name="Approve"
        flowable:delegateExpression="${moquiServiceDelegate}"
        flowable:async="true">
  <extensionElements>
    <flowable:field name="serviceName">
      <flowable:string><![CDATA[example.ExampleServices.ping]]></flowable:string>
    </flowable:field>
  </extensionElements>
</serviceTask>
```

User Task（可选完成时服务可空）：

```xml
<userTask id="managerReview" name="Manager Review"
        flowable:assignee="${managerUserId}"
        flowable:candidateGroups="ADMIN"
        flowable:formKey="component://example/screen/ExampleApp.xml"/>
```

网关条件读局部 `outcome`：

```xml
<sequenceFlow id="toApprove" sourceRef="managerReviewGateway" targetRef="callApprove">
  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${outcome == 'approve'}]]></conditionExpression>
</sequenceFlow>
```

W1 回归文件：`process/demo-empty.bpmn20.xml`（Start → End）。业务融合仍用 REQUIREMENTS §8 报销故事，不做成组件内置业务。

## 8. REQUIREMENTS §12 仍开放项（本文锁定默认）

编码按此列，不要现场改口径。若产品要改，先改 REQUIREMENTS §12 再改本节。

| 项 | 默认 |
|----|------|
| `WfProcessLink` | **W4 必做**。进行中 `(entityName, pkValue, processDefinitionKey)` 唯一（`WfLinkActive`）；结束后 `WfLinkEnded`，允许再 `start#`。`start#` 自己拒重复。W1–W3 可只用 `businessKey` |
| `WfTaskComment` | **W4 必做**。不用 Flowable `TaskService.addComment`。网关读 **execution-local** `outcome`。`transfer` / `withdraw` / `cancel` 只进意见表 |
| Delegate 身份 | 异步线程先恢复 **`start#` 的发起人 `userId`**（不是当前办理人）；没有则 `_NA_`。`MoquiServiceDelegate` **内部**允许 `disableAuthz`，不得泄漏到业务服务调用方。Service Task 仍受业务服务自己的参数校验 |
| 选人解析 | **TaskListener（TASK_CREATED）**。经理：`PrtManager` 且 `toPartyId`=下属 party、`fromPartyId`=经理 party。角色：`mantle.party.PartyRole`。找不到人则任务保持原 token 字符串并打 warn，不抛错阻断开流 |
| 加签 | **子任务** `parentTaskId`=当前任务，不设 `executionId`。`complete#` 推进父任务（或当前若已是父任务）并删其余子任务 |
| 驳回指定节点 | Flowable **`createChangeActivityStateBuilder().moveActivityIdTo(from, to)`**。多活跃 user-task execution 拒绝 |
| Call Activity link | 子实例 `PROCESS_STARTED` 且存在 `superExecutionId` 时，复制父 `WfProcessLink` 的 `entityName`/`pkValue`，key 用子定义 |
| `ACT_*` | **跟 Moqui 当前 DataSource**，Flowable 默认表前缀 `ACT_`。`databaseSchemaUpdate=true`。`transactionsExternallyManaged=true`（跟 Moqui 事务）。不做独立 schema（第一期） |
| Flowable 版本 | **`org.flowable:flowable-engine:7.2.0`**（或最新 7.2.x）。不上 8.x |

## 9. 水印与静态资源

- 设计器画布与 W5 监控画布容器不得用遮罩、负 margin 或 `overflow: hidden` 裁掉 bpmn.io logo。
- 待办、已办、我发起、实例详情、`form-single`、定义列表不引入 `screen/static/bpmn-js/`。
- 监控列表未选实例时不加载 bpmn-js；字体只走 `Asset/get?file=bpmn.woff2` 等，不请求 `../font/`。
- 不把 bpmn-js 拷进 `webroot/libs/`。

## 10. 验收对照（实现时）

| 波次 | 怎么验 |
|------|--------|
| W1 | ServiceRun：`deploy#` 空 BPMN → `start#` → `get#ProcessInstance` |
| W2 | 浏览器定义列表 New Process → 点 key 进 `/qapps/collie-flowable/Designer`：选服务 / 屏幕 / 用户 / 组 → 保存 → 缺 `formKey` 部署被拒 → 补全后部署；水印可见 |
| W3 | 设计器画「服务任务→结束」，异步线程日志有 `ec`；入参按变量名 + `businessKey`；服务失败可重试 |
| W4 | 候选须 `claim#` 才能 `complete#`；待办打开**该节点** `formKey` 业务屏 + 意见条；已办 / 我发起 / 实例详情可用；至少两个 UserTask 不同屏、同一 `businessKey`（CF-41a）；`complete#` 写局部 `outcome`，并行互不覆盖；改状态来自网关后 Service Task（或可选完成时服务）；`list#TaskComments` 路人不可查；进行中 `WfProcessLink` 唯一，结束后可再 `start#` |
| W5 | 或签 vs 会签（`collection` / `completionCondition`）；Timer 走到升级 UserTask（assignee 不自动改）；`withdraw#`（无已办任务）/ `transfer#`（任务仍开）/ `cancel#`（单据终态 + 实例删）；`suspend#` 后不能新 start；监控页只读图：当前节点橙、已完成路径绿 + 水印可见 |
| W5.1 | 待办列表不能 complete；InstanceDetail 才有意见条；撤回/作废按钮有条件；Groovy 回归 |
| W6 | 选人 token 解析；`addSign#` 后原任务仍开；任一人 complete 推进 |
| W7 | `returnTo#` 只能回已经过的 UserTask；并行拒绝 |
| W8 | Call Activity 循环部署被拒；`message#` 唤醒等待节点 |

日志：`runtime/log/moqui.log`。
