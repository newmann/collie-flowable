# collie-flowable 第二期交付

范围：[REQUIREMENTS.md](REQUIREMENTS.md) §11 W5.1–W8。实现：[TECHNICAL.md](TECHNICAL.md)。
第一期见 [PHASE1.md](PHASE1.md)。改产品口径先改 REQUIREMENTS。

## 相对第一期

W0–W5 已交付。本期不重做引擎嵌入、设计器宿主、待办闭环。

运行时依赖与 PHASE1 相同（Java 21、Flowable 7.2.x、当前 DataSource、`collie-wf-demo`）。

屏幕验收以 **`/qapps/collie-flowable/`** 为准。`/qapps2` 的 qvue 设计器不作为本期关卡。

## 锁定的 id 与鉴权

沿用 PHASE1 的 StatusItem / 通知 / demo 实体。新增意见 `outcome`：`addSign` / `returnTo`（只进 `WfTaskComment`，不写网关变量）。

服务：

- `addSign#` / `returnTo#` / `message#` / `signal#`：已登录用户（与 `complete#` 同组）
- 选人解析在引擎 TaskListener 内，可 `disableAuthz` 读 Party 关系；不得泄漏到业务调用方

## 波次

| 波次 | 交付 | 验收 |
|------|------|------|
| W5.1 | 待办完成只在 InstanceDetail；已办标题；撤回/作废按钮条件；设计器节点白名单；Groovy 回归 | 列表不能 complete；未认领不能 complete；suspend 后不能 start |
| W6 | 选人 token + `addSign#Task` | `initiator` / `managerOfInitiator` / `role:{roleTypeId}` 解析成 userId；加签后任务仍开；任一人 complete 推进 |
| W7 | `returnTo#Task` | 只能回到已经过的 UserTask；并行/会签拒绝 |
| W8 | Call Activity + `message#` / `signal#` | 循环调用部署被拒；message 唤醒等待节点 |

## 选人 token（CF-80）

写在 UserTask `assignee` / `candidateUsers`（可逗号分隔），创建任务时解析：

| token | 含义 |
|-------|------|
| `initiator` | 实例 `startUserId` |
| `managerOfInitiator` | 发起人的经理：`UserAccount.partyId` → `PartyRelationship`（`PrtManager`，`toPartyId`=下属，`fromPartyId`=经理）→ 经理的 `UserAccount.userId` |
| `managerOfAssignee` | 进程变量 `assigneeUserId`，否则最近已完成 UserTask 的 assignee，否则发起人；再按上表找经理 |
| `role:{roleTypeId}` | `mantle.party.PartyRole` 该角色下所有 party 对应的 `UserAccount.userId`，写入 candidateUsers |

仍允许 `${var}`（Flowable 表达式，本监听器不改已是真实 userId 的值）。

## 加签（CF-81）

`addSign#Task(taskId, toUserId, comment)`：

- 当前用户必须是 assignee；节点 `allowAddSign=true`；非会签、非并行
- 创建 `parentTaskId`=当前任务的子任务，assignee=`toUserId`，不推进 token
- 意见 `addSign`
- 原任务或任一加签子任务 `complete#`：写意见、推进父任务 token、删除其余子任务

## 驳回指定节点（CF-82）

`returnTo#Task(taskId, targetActivityId, comment)` 使用 `runtimeService.createChangeActivityStateBuilder()`。
目标必须出现在本实例历史 UserTask 中。多活跃 execution 拒绝。

## 子流程与消息（CF-83 / CF-14）

- Call Activity `calledElement` = key；子实例继承 `businessKey`；另写 `WfProcessLink`
- `message#Process` / `signal#Process`：按 `processInstanceId` 或 `businessKey` 投递到该实例上等待的订阅，不配 correlation key UI

## 非目标（本期结束时）

减签、委派、抄送、催办按钮、自由跳转、实例迁移、WorkEffort 双写、JTA 同提交、独立 schema、Flowable Form、改 mantle / `collie-fasm`。
