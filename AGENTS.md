# AGENTS — collie-flowable

Scoped rules for this component. This file overrides the repository root
`AGENTS.md` when you are working in this directory.

## Role

Embed the Flowable BPMN engine in Moqui and bridge it to existing
services, StatusFlow, users, and screens. The engine orchestrates
process instances; Moqui remains the source of business truth.

Depends on (from `component.xml`):

- `mantle-udm`
- `mantle-usl`

## Find things here

- 范围与融合约定：`doc/REQUIREMENTS.md`（改产品口径先改本文）
- 选型与实现：`doc/TECHNICAL.md`（改实现只动本文；服务名、实体、嵌屏、§12 默认值写死处）
- 第一期交付：`doc/PHASE1.md`（StatusItem id、鉴权、demo 组件名写死处）
- Entities: `entity/WorkflowEntities.xml` — 包名 `wf.flowable`
- Services: `service/wf/flowable/WorkflowServices.xml`、`DesignerServices.xml`
- Screens: `screen/App.xml` — `/qapps/collie-flowable/`
- REST: 第一期不做
- Seed: `data/StatusSeedData.xml`、`ServiceSeedData.xml`、`AppSeedData.xml`、
  `data/WfL10nData.xml`
- Demo 业务组件：`runtime/component/collie-wf-demo`（W4）

## Conventions

- Default `userGroupId` for new authz: `ADMIN` unless this app defines another
- Do not redefine mantle entities; extend with `<extend-entity>`,
  view-entity, SECA/EECA, or this component's own package
- New services stay under `wf.flowable.*`
- Mount screens from this component's `MoquiConf.xml`
- Do not edit `framework/`, `mantle-udm`, `mantle-usl`, SimpleScreens,
  MarbleERP, or `webroot`
- Flowable must not write business tables except by calling Moqui services
- Assignee / candidateUsers use `UserAccount.userId`; candidateGroups
  use `UserGroup.userGroupId`
- Do not map Flowable `ACT_*` tables as Moqui entities
- UI original text stays English; Chinese only in `data/WfL10nData.xml`
- User-facing service errors call `ec.l10n.localize` (or `ec.resource.expand`) first

## Verify

After services exist, use ServiceRun `wf.flowable.*` (names in
`doc/TECHNICAL.md`). W1 hard gate: deploy empty BPMN then `start#`.
Do not declare success from XML or compile output alone.

## Do not

- Edit entities, services, or screens in a dependency or catalog
  component (`mantle-udm`, `mantle-usl`, `SimpleScreens`, `MarbleERP`,
  `webroot`). Use `<extend-entity>`, SECA/EECA, or this component's
  `MoquiConf.xml` instead
- Mount screens by editing `webroot` files; use this component's `MoquiConf.xml`
- Skip seed ArtifactAuthz for a new app root screen or REST root path
- Replace StatusFlow, WorkEffort, or ServiceJob with Flowable
- Enable Flowable's identity tables as the account source
- Start Camunda 8, a Flowable commercial suite, or a custom BPMN engine
