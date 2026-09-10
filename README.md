# collie-flowable

在 Moqui 中嵌入 Flowable 工作流引擎的本地组件。引擎负责流程实例与待办编排，业务状态、服务、用户和屏幕仍由 Moqui 负责。

## FAQ：流程定义与业务对象的对应关系在哪里维护？

**没有单独的「流程定义 ↔ 业务实体类型」注册表。** `WfProcessDefinition` 只存 key、名称、BPMN、部署状态，不绑实体。对应关系分两层：业务侧写死用哪个 key，运行时再落到 `WfProcessLink`。

### 类型级：写在业务 `submit#`（或 SECA）里

选哪个 `processDefinitionKey`、`businessKey` 用哪列主键，由每个单据类型自己接，不是本组件发起台（见 `doc/REQUIREMENTS.md` §7.9）。

Demo：`wf.demo.DemoServices.submit#DemoRequest` 默认 key=`demoRequest`，并把实体名和主键传给 `start#Process`（`entityName=wf.demo.DemoRequest`，`pkValue` / `businessKey`=`requestId`）。换一种单据就要再接一次 `submit#` / SECA。

### 实例级：实体 `wf.flowable.WfProcessLink`

真正落库的是「这一行业务对象 ↔ 这一条流程实例」：`entityName` + `pkValue` + `processInstanceId` + `processDefinitionKey` + `statusId`。

写入点是 `wf.flowable.WorkflowServices.start#Process` → `WorkflowSupport.startProcess()`：引擎 `startProcessInstanceByKey` 之后立刻 `writeProcessLink`。进行中的 `(entityName, pkValue, processDefinitionKey)` 至多一条（`WfLinkActive`）；结束后改 `WfLinkEnded`，允许再 `start#`。

子流程不另选实体：`MoquiCallActivityLinkListener` 从父实例复制 `entityName` / `pkValue`，只换子流程的 `processDefinitionKey`。

### 引擎侧的 `businessKey`

`start#` 的必填参数是 `processDefinitionKey` + `businessKey`（通常等于主键）。Flowable 实例自己带着 `businessKey`；`get#ProcessInstance`、意见时间线、待办打开表单都靠它回查单据。`entityName` / `pkValue` 可选；不传时 `pkValue` 回退为 `businessKey`。

### 和「业务对象」相关、但不是实体映射的部分

UserTask 的 `flowable:formKey` 绑的是**业务屏幕**，不是实体。例如 demo 经理节点指向 `component://collie-wf-demo/screen/RequestManager.xml`。同一 `businessKey` 下各节点可以绑不同 `form-single`。

**一句话：** 定义级对应关系在业务组件的 `submit#`（demo 里是 `wf.demo.DemoServices.submit#DemoRequest`）；实例级对应关系在 `wf.flowable.WfProcessLink`，由 `start#Process` 维护。
