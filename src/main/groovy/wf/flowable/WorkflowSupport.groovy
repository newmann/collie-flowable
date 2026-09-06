package wf.flowable

import groovy.util.logging.Slf4j
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import org.flowable.bpmn.model.BpmnModel
import org.flowable.bpmn.model.FieldExtension
import org.flowable.bpmn.model.ParallelGateway
import org.flowable.bpmn.model.UserTask
import org.flowable.common.engine.impl.identity.Authentication
import org.flowable.engine.ProcessEngine
import org.flowable.engine.history.HistoricProcessInstance
import org.flowable.engine.repository.ProcessDefinition
import org.flowable.engine.runtime.ProcessInstance
import org.flowable.task.api.Task
import org.flowable.task.api.history.HistoricTaskInstance
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.resource.ResourceReference

@Slf4j
class WorkflowSupport {
    static final String OUTCOME_APPROVE = "approve"
    static final String OUTCOME_REJECT = "reject"
    static final String OUTCOME_RETURN = "return"
    static final String OUTCOME_TRANSFER = "transfer"
    static final String OUTCOME_WITHDRAW = "withdraw"
    static final String OUTCOME_CANCEL = "cancel"
    static final Set<String> COMPLETE_OUTCOMES = [OUTCOME_APPROVE, OUTCOME_REJECT, OUTCOME_RETURN] as Set<String>

    static ProcessEngine engine(ExecutionContext ec) {
        return (ProcessEngine) ec.getTool("Flowable", ProcessEngine.class)
    }

    static Object runEngine(ExecutionContext ec, Closure body) {
        return body.call(engine(ec))
    }

    protected static void addError(ExecutionContext ec, String original) {
        ec.message.addError(ec.l10n.localize(original))
    }

    protected static void addError(ExecutionContext ec, String original, Map extra) {
        ec.message.addError(ec.resource.expand(original, "", extra))
    }

    static void ping(ExecutionContext ec, Map context) {
        context.put("ok", Boolean.TRUE)
        context.put("engineName", engine(ec).name)
        log.info("wf.flowable.WorkflowServices.ping engine=${engine(ec).name}")
    }

    static void deployProcessDefinition(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        String bpmnXml = context.bpmnXml as String
        if (!key || !bpmnXml) {
            addError(ec, "processDefinitionKey and bpmnXml are required")
            return
        }
        List<String> errors = validateBpmn(ec, bpmnXml)
        if (errors) {
            errors.each { ec.message.addError(it) }
            return
        }
        String resourceName = key.endsWith(".bpmn20.xml") || key.endsWith(".bpmn") ? key : (key + ".bpmn20.xml")
        def deployment = runEngine(ec) { ProcessEngine pe ->
            pe.repositoryService.createDeployment()
                    .addString(resourceName, bpmnXml)
                    .name(context.processName as String ?: key)
                    .deploy()
        }
        ProcessDefinition defn = runEngine(ec) { ProcessEngine pe ->
            pe.repositoryService.createProcessDefinitionQuery().deploymentId(deployment.id).singleResult()
        }
        context.deploymentId = deployment.id
        context.processDefinitionId = defn?.id
        upsertDefinitionEntity(ec, key, context.processName as String, bpmnXml, "WfDefDeployed", deployment.id)
    }

    static void saveProcessDefinition(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        String bpmnXml = context.bpmnXml as String
        if (!key || !bpmnXml) {
            addError(ec, "processDefinitionKey and bpmnXml are required")
            return
        }
        upsertDefinitionEntity(ec, key, context.processName as String, bpmnXml, "WfDefDraft", null)
        context.statusId = "WfDefDraft"
    }

    static void getProcessDefinition(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        EntityValue ev = ec.entity.find("wf.flowable.WfProcessDefinition")
                .condition("processDefinitionKey", key).one()
        if (ev == null) {
            addError(ec, "Process definition \${key} not found", [key: key])
            return
        }
        context.putAll(ev.getMap())
    }

    static void listProcessDefinitions(ExecutionContext ec, Map context) {
        context.processDefinitionList = ec.entity.find("wf.flowable.WfProcessDefinition")
                .orderBy("processName").list().getValueMapList()
    }

    static void startProcess(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        String businessKey = context.businessKey as String
        if (!key || !businessKey) {
            addError(ec, "processDefinitionKey and businessKey are required")
            return
        }
        ProcessDefinition defn = runEngine(ec) { ProcessEngine pe ->
            pe.repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult()
        }
        if (defn == null) {
            addError(ec, "No deployed process definition for key \${key}", [key: key])
            return
        }
        if (defn.isSuspended()) {
            addError(ec, "Process definition \${key} is suspended", [key: key])
            return
        }
        String entityName = context.entityName as String
        String pkValue = (context.pkValue as String) ?: businessKey
        if (hasActiveLink(ec, pkValue, key)) {
            if (!ec.message.hasError()) {
                addError(ec, "An active process already exists for \${entityName}:\${pkValue} key=\${key}",
                        [entityName: entityName ?: "businessKey", pkValue: pkValue, key: key])
            }
            return
        }

        Map<String, Object> variables = new LinkedHashMap<>()
        Object incoming = context.variables
        if (incoming instanceof Map) variables.putAll((Map) incoming)
        String userId = ec.user.userId ?: "_NA_"
        variables.put("initiatorUserId", userId)
        if (context.businessWithdrawService) variables.put("businessWithdrawService", context.businessWithdrawService)
        if (context.businessCancelService) variables.put("businessCancelService", context.businessCancelService)

        Authentication.setAuthenticatedUserId(userId)
        ProcessInstance instance
        try {
            instance = runEngine(ec) { ProcessEngine pe ->
                pe.runtimeService.startProcessInstanceByKey(key, businessKey, variables)
            }
        } finally {
            Authentication.setAuthenticatedUserId(null)
        }
        try {
            writeProcessLink(ec, entityName, pkValue, instance.id, key)
        } catch (Throwable t) {
            try { engine(ec).runtimeService.deleteProcessInstance(instance.id, "link write failed") } catch (Throwable ignored) {}
            addError(ec, "Failed to write WfProcessLink: \${message}", [message: t.message])
            return
        }
        if (ec.message.hasError()) {
            try { engine(ec).runtimeService.deleteProcessInstance(instance.id, "link write failed") } catch (Throwable ignored) {}
            return
        }
        // Empty / sync processes finish during start, before PROCESS_COMPLETED can see the new link.
        ProcessInstance stillRunning = engine(ec).runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.id).singleResult()
        if (stillRunning == null) markLinkEnded(ec, instance.id)
        context.processInstanceId = instance.id
    }

    static void getProcessInstance(ExecutionContext ec, Map context) {
        String instanceId = context.processInstanceId as String
        String businessKey = context.businessKey as String
        ProcessInstance running = null
        if (instanceId) {
            running = engine(ec).runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult()
        } else if (businessKey) {
            List<ProcessInstance> runList = engine(ec).runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey).list()
            running = runList ? runList[0] : null
        }
        if (running != null) {
            fillRunningInstance(ec, context, running)
            fillBusinessServices(ec, context, running.id)
            denyInstanceIfUnauthorized(ec, context, running.id)
            return
        }
        HistoricProcessInstance historic = null
        if (instanceId) {
            historic = engine(ec).historyService.createHistoricProcessInstanceQuery().processInstanceId(instanceId).singleResult()
        } else if (businessKey) {
            List<HistoricProcessInstance> hist = engine(ec).historyService.createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey).orderByProcessInstanceStartTime().desc().list()
            historic = hist ? hist[0] : null
        }
        if (historic == null) {
            addError(ec, "Process instance not found")
            return
        }
        context.processInstanceId = historic.id
        context.processDefinitionKey = historic.processDefinitionKey
        context.processDefinitionVersion = historic.processDefinitionVersion
        context.businessKey = historic.businessKey
        context.status = historic.endTime != null ? "ended" : "historic"
        context.startUserId = historic.startUserId
        context.endTime = historic.endTime
        context.currentActivities = []
        context.currentTasks = []
        context.latestFormKey = latestHistoricFormKey(ec, historic.id)
        fillBusinessServices(ec, context, historic.id)
        denyInstanceIfUnauthorized(ec, context, historic.id)
    }

    static void claimTask(ExecutionContext ec, Map context) {
        Task task = requireTask(ec, context)
        if (task == null) return
        String userId = ec.user.userId
        if (task.assignee) {
            if (task.assignee != userId) addError(ec, "Task already assigned")
            return
        }
        if (!isCandidate(ec, task, userId)) {
            addError(ec, "Current user is not a candidate for this task")
            return
        }
        engine(ec).taskService.claim(task.id, userId)
    }

    static void unclaimTask(ExecutionContext ec, Map context) {
        Task task = requireTask(ec, context)
        if (task == null) return
        if (task.assignee != ec.user.userId && !ec.user.isInGroup("ADMIN")) {
            addError(ec, "Only the assignee or ADMIN may unclaim")
            return
        }
        engine(ec).taskService.unclaim(task.id)
    }

    static void completeTask(ExecutionContext ec, Map context) {
        Task task = requireTask(ec, context)
        if (task == null) return
        String outcome = context.outcome as String
        if (!COMPLETE_OUTCOMES.contains(outcome)) {
            addError(ec, "outcome must be approve, reject, or return")
            return
        }
        if (task.assignee == null) {
            addError(ec, "Unclaimed candidate tasks cannot be completed; claim first")
            return
        }
        if (task.assignee != ec.user.userId) {
            addError(ec, "Current user must be the assignee")
            return
        }
        String syncService = skipOnCompleteService(ec, task) ? null : findOnCompleteService(ec, task, outcome)
        if (syncService) {
            Map<String, Object> params = new LinkedHashMap<>()
            params.putAll(engine(ec).runtimeService.getVariables(task.executionId))
            params.businessKey = findBusinessKey(ec, task.processInstanceId)
            params.outcome = outcome
            params.taskId = task.id
            Map result = ec.service.sync().name(syncService).parameters(params).call()
            if (ec.message.hasError()) return
            if (result != null) {
                engine(ec).runtimeService.setVariables(task.executionId, result)
            }
        }
        writeTaskComment(ec, task, outcome, context.comment as String)
        // execution-local so the following XOR sees outcome (CF-26); task-local is gone after complete
        engine(ec).runtimeService.setVariableLocal(task.executionId, "outcome", outcome)
        engine(ec).taskService.complete(task.id)
    }

    static void listMyTasks(ExecutionContext ec, Map context) {
        String userId = ec.user.userId
        Set<String> groups = ec.user.userGroupIdSet ?: ([] as Set<String>)
        List<Task> assigned = engine(ec).taskService.createTaskQuery().taskAssignee(userId).list()
        List<Task> candidate = engine(ec).taskService.createTaskQuery().taskCandidateUser(userId).list()
        List<Task> groupTasks = groups ? engine(ec).taskService.createTaskQuery().taskCandidateGroupIn(new ArrayList<String>(groups)).list() : []
        Map<String, Map> byId = new LinkedHashMap<>()
        (assigned + candidate + groupTasks).each { Task t ->
            byId.put(t.id, taskToMap(ec, t))
        }
        context.taskList = new ArrayList(byId.values())
    }

    static void listCompletedTasks(ExecutionContext ec, Map context) {
        List<HistoricTaskInstance> tasks = engine(ec).historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(ec.user.userId).finished().orderByHistoricTaskInstanceEndTime().desc().list()
        context.taskList = tasks.collect { HistoricTaskInstance t ->
            [taskId: t.id, name: t.name, processInstanceId: t.processInstanceId, processDefinitionId: t.processDefinitionId,
             endTime: t.endTime, formKey: t.formKey, businessKey: findBusinessKey(ec, t.processInstanceId)] as Map
        }
    }

    static void listMyStarted(ExecutionContext ec, Map context) {
        List<HistoricProcessInstance> list = engine(ec).historyService.createHistoricProcessInstanceQuery()
                .startedBy(ec.user.userId).orderByProcessInstanceStartTime().desc().list()
        context.instanceList = list.collect { HistoricProcessInstance p ->
            [processInstanceId: p.id, processDefinitionKey: p.processDefinitionKey, businessKey: p.businessKey,
             startTime: p.startTime, endTime: p.endTime, status: p.endTime != null ? "ended" : "running"] as Map
        }
    }

    static void listTaskComments(ExecutionContext ec, Map context) {
        String instanceId = context.processInstanceId as String
        String businessKey = context.businessKey as String
        if (!instanceId && !businessKey) {
            addError(ec, "processInstanceId or businessKey is required")
            return
        }
        if (businessKey) {
            if (!canViewByBusinessKey(ec, businessKey)) {
                addError(ec, "Not allowed to list comments for this instance")
                return
            }
            context.commentList = ec.entity.find("wf.flowable.WfTaskComment")
                    .condition("businessKey", businessKey).orderBy("commentDate").list().getValueMapList()
            return
        }
        if (!canViewInstance(ec, instanceId)) {
            addError(ec, "Not allowed to list comments for this instance")
            return
        }
        String bk = findBusinessKey(ec, instanceId)
        if (bk) {
            context.commentList = ec.entity.find("wf.flowable.WfTaskComment")
                    .condition("businessKey", bk).orderBy("commentDate").list().getValueMapList()
        } else {
            context.commentList = ec.entity.find("wf.flowable.WfTaskComment")
                    .condition("processInstanceId", instanceId).orderBy("commentDate").list().getValueMapList()
        }
    }

    static void transferTask(ExecutionContext ec, Map context) {
        Task task = requireTask(ec, context)
        if (task == null) return
        String toUserId = context.toUserId as String
        if (!toUserId) {
            addError(ec, "toUserId is required")
            return
        }
        if (task.assignee != ec.user.userId && !ec.user.isInGroup("ADMIN")) {
            addError(ec, "Only the assignee or ADMIN may transfer")
            return
        }
        writeTaskComment(ec, task, OUTCOME_TRANSFER, context.comment as String)
        engine(ec).taskService.setAssignee(task.id, toUserId)
    }

    static void withdrawProcess(ExecutionContext ec, Map context) {
        ProcessInstance instance = requireRunning(ec, context)
        if (instance == null) return
        if (instance.startUserId != ec.user.userId) {
            addError(ec, "Only the initiator may withdraw")
            return
        }
        long finished = engine(ec).historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instance.id).finished().count()
        if (finished > 0) {
            addError(ec, "Cannot withdraw after a UserTask has been completed")
            return
        }
        String businessService = (context.businessWithdrawService as String) ?:
                (engine(ec).runtimeService.getVariable(instance.id, "businessWithdrawService") as String)
        if (businessService) {
            ec.service.sync().name(businessService).parameter("businessKey", instance.businessKey)
                    .parameter("processInstanceId", instance.id).call()
            if (ec.message.hasError()) return
        }
        writeProcessComment(ec, instance, OUTCOME_WITHDRAW, context.comment as String, null, null)
        engine(ec).runtimeService.deleteProcessInstance(instance.id, "withdraw")
    }

    static void cancelProcess(ExecutionContext ec, Map context) {
        ProcessInstance instance = requireRunning(ec, context)
        if (instance == null) return
        if (!ec.user.isInGroup("ADMIN") && instance.startUserId != ec.user.userId) {
            addError(ec, "Only ADMIN or the initiator may cancel")
            return
        }
        String businessService = (context.businessCancelService as String) ?:
                (engine(ec).runtimeService.getVariable(instance.id, "businessCancelService") as String)
        if (businessService) {
            ec.service.sync().name(businessService).parameter("businessKey", instance.businessKey)
                    .parameter("processInstanceId", instance.id).call()
            if (ec.message.hasError()) return
        }
        writeProcessComment(ec, instance, OUTCOME_CANCEL, context.comment as String, null, null)
        engine(ec).runtimeService.deleteProcessInstance(instance.id, "cancel")
    }

    static void listProcessInstances(ExecutionContext ec, Map context) {
        if (!ec.user.isInGroup("ADMIN")) {
            addError(ec, "ADMIN required")
            return
        }
        List<HistoricProcessInstance> list = engine(ec).historyService.createHistoricProcessInstanceQuery()
                .orderByProcessInstanceStartTime().desc().list()
        context.instanceList = list.collect { HistoricProcessInstance p ->
            List<String> activities = []
            if (p.endTime == null) {
                try { activities = engine(ec).runtimeService.getActiveActivityIds(p.id) } catch (Throwable ignored) {}
            }
            [processInstanceId: p.id, processDefinitionKey: p.processDefinitionKey, businessKey: p.businessKey,
             startUserId: p.startUserId, startTime: p.startTime, endTime: p.endTime,
             status: p.endTime != null ? "ended" : "running", currentActivities: activities] as Map
        }
    }

    static void suspendProcessDefinition(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        engine(ec).repositoryService.suspendProcessDefinitionByKey(key)
        EntityValue ev = ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
        if (ev != null) { ev.set("statusId", "WfDefSuspended"); ev.update() }
    }

    static void activateProcessDefinition(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        engine(ec).repositoryService.activateProcessDefinitionByKey(key)
        EntityValue ev = ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
        if (ev != null) { ev.set("statusId", "WfDefDeployed"); ev.update() }
    }

    static void listServiceNames(ExecutionContext ec, Map context) {
        ServiceFacadeImpl sfi = (ServiceFacadeImpl) ec.service
        String query = (context.query as String)?.toLowerCase()
        List<String> names = new ArrayList<>(sfi.getKnownServiceNames())
        if (query) names = names.findAll { it.toLowerCase().contains(query) } as List<String>
        context.serviceNameList = names.take(200)
    }

    static void listScreenLocations(ExecutionContext ec, Map context) {
        String query = (context.query as String)?.toLowerCase()
        List<String> locations = []
        for (String base : ec.factory.componentBaseLocations.values()) {
            collectXmlFiles(ec.resource.getLocationReference(base + "/screen"), locations)
        }
        if (query) locations = locations.findAll { it.toLowerCase().contains(query) } as List<String>
        context.screenLocationList = locations.take(200)
    }

    static void listUsers(ExecutionContext ec, Map context) {
        String query = context.query as String
        def find = ec.entity.find("moqui.security.UserAccount").selectField("userId")
                .selectField("username").selectField("userFullName")
        if (query) {
            String like = "%" + query + "%"
            def cf = ec.entity.conditionFactory
            find.condition(cf.makeCondition([
                    cf.makeCondition("userId", EntityCondition.LIKE, like),
                    cf.makeCondition("username", EntityCondition.LIKE, like),
                    cf.makeCondition("userFullName", EntityCondition.LIKE, like)
            ], EntityCondition.OR))
        }
        context.userList = find.limit(50).list().getValueMapList()
    }

    static void listUserGroups(ExecutionContext ec, Map context) {
        String query = context.query as String
        def find = ec.entity.find("moqui.security.UserGroup").selectField("userGroupId").selectField("description")
        if (query) {
            String like = "%" + query + "%"
            def cf = ec.entity.conditionFactory
            find.condition(cf.makeCondition([
                    cf.makeCondition("userGroupId", EntityCondition.LIKE, like),
                    cf.makeCondition("description", EntityCondition.LIKE, like)
            ], EntityCondition.OR))
        }
        context.userGroupList = find.limit(50).list().getValueMapList()
    }

    static List<String> validateBpmn(ExecutionContext ec, String bpmnXml) {
        List<String> errors = []
        try {
            def xml = new XmlSlurper().parseText(bpmnXml)
            xml.process.each { process ->
                Set<String> incoming = [] as Set<String>
                Set<String> outgoing = [] as Set<String>
                process.sequenceFlow.each { flow ->
                    outgoing.add(flow.'@sourceRef'.text())
                    incoming.add(flow.'@targetRef'.text())
                    String cond = flow.conditionExpression?.text()?.trim()
                    if (cond && !(cond.startsWith('${') && cond.endsWith('}'))) {
                        errors.add(ec.resource.expand(
                                "Sequence flow \${id} condition must be a dollar-brace expression (e.g. outcome == 'approve')",
                                "", [id: flow.'@id'.text()]))
                    }
                }
                process.'*'.each { node ->
                    String local = localName(node)
                    if (local == "sequenceFlow" || local == "laneSet" || local == "documentation") return
                    String id = node.'@id'.text()
                    if (!id) return
                    boolean isStart = local.toLowerCase().contains("startevent")
                    boolean isEnd = local.toLowerCase().contains("endevent")
                    boolean isBoundary = local.equalsIgnoreCase("boundaryEvent")
                    if (!isStart && !isBoundary && !incoming.contains(id)) {
                        errors.add(ec.resource.expand("Isolated or missing incoming flow: \${id}", "", [id: id]))
                    }
                    if (!isEnd && !isBoundary && !outgoing.contains(id)) {
                        errors.add(ec.resource.expand("Isolated or missing outgoing flow: \${id}", "", [id: id]))
                    }
                    if (isBoundary && !outgoing.contains(id)) {
                        errors.add(ec.resource.expand("Boundary event missing outgoing flow: \${id}", "", [id: id]))
                    }
                    if (local.equalsIgnoreCase("userTask")) {
                        String formKey = node.'@formKey'.text() ?: attr(node, "formKey")
                        if (!formKey) errors.add(ec.resource.expand("UserTask \${id} missing formKey", "", [id: id]))
                    }
                    if (local.equalsIgnoreCase("serviceTask")) {
                        String serviceName = null
                        node.extensionElements.'*'.each { field ->
                            if (localName(field) == "field" && field.'@name'.text() == "serviceName") {
                                serviceName = field.string.text() ?: field.'@stringValue'.text()
                            }
                        }
                        if (!serviceName) {
                            errors.add(ec.resource.expand("Service Task \${id} missing serviceName", "", [id: id]))
                        }
                    }
                }
            }
        } catch (Throwable t) {
            errors.add(ec.resource.expand("Invalid BPMN XML: \${message}", "", [message: t.message]))
        }
        return errors
    }

    static void scanAndDeploySeedBpmn(ExecutionContextFactory ecf, ProcessEngine engine) {
        ExecutionContext ec = ecf.getExecutionContext()
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            for (Map.Entry<String, String> entry : ecf.componentBaseLocations.entrySet()) {
                ResourceReference dir = ecf.resource.getLocationReference(entry.value + "/process")
                if (dir == null || !dir.exists || !dir.isDirectory()) continue
                for (ResourceReference file : dir.directoryEntries) {
                    String name = file.fileName
                    if (!(name.endsWith(".bpmn20.xml") || name.endsWith(".bpmn"))) continue
                    String xml = file.getText()
                    String key = extractProcessKey(xml)
                    if (!key) continue
                    if (shouldSkipSeedDeploy(ec, engine, key, file)) continue
                    try {
                        def deployment = engine.repositoryService.createDeployment()
                                .addString(key + ".bpmn20.xml", xml)
                                .name("seed:" + name)
                                .deploy()
                        String processName = extractProcessName(xml) ?: key
                        upsertDefinitionEntity(ec, key, processName, xml, "WfDefDeployed", deployment.id)
                        log.info("Deployed seed BPMN ${name} key=${key}")
                    } catch (Throwable t) {
                        log.warn("Failed to deploy seed BPMN ${name}: ${t.message}")
                    }
                }
            }
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    protected static boolean shouldSkipSeedDeploy(ExecutionContext ec, ProcessEngine engine, String key, ResourceReference ignoredFile) {
        try {
            EntityValue ev = ec.entity.find("wf.flowable.WfProcessDefinition")
                    .condition("processDefinitionKey", key).one()
            if (ev != null) return true
            ProcessDefinition existing = engine.repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(key).latestVersion().singleResult()
            if (existing != null) return true
        } catch (Throwable ignored) { }
        return false
    }

    protected static String extractProcessKey(String xml) {
        def matcher = (xml =~ /<process[^>]*\sid="([^"]+)"/)
        return matcher.find() ? matcher.group(1) : null
    }

    protected static String extractProcessName(String xml) {
        def matcher = (xml =~ /<process[^>]*\sname="([^"]+)"/)
        return matcher.find() ? matcher.group(1) : null
    }

    protected static void upsertDefinitionEntity(ExecutionContext ec, String key, String name, String xml,
                                                String statusId, String deploymentId) {
        EntityValue ev = ec.entity.find("wf.flowable.WfProcessDefinition")
                .condition("processDefinitionKey", key).one()
        if (ev == null) {
            ev = ec.entity.makeValue("wf.flowable.WfProcessDefinition")
            ev.processDefinitionKey = key
            ev.processName = name ?: key
            ev.bpmnXml = xml
            ev.statusId = statusId
            if (deploymentId) ev.deploymentId = deploymentId
            ev.create()
        } else {
            ev.processName = name ?: ev.processName
            ev.bpmnXml = xml
            ev.statusId = statusId
            if (deploymentId) ev.deploymentId = deploymentId
            ev.update()
        }
    }

    protected static boolean hasActiveLink(ExecutionContext ec, String pkValue, String key) {
        if (!pkValue) return false
        try {
            long count = ec.entity.find("wf.flowable.WfProcessLink")
                    .condition("pkValue", pkValue)
                    .condition("processDefinitionKey", key)
                    .condition("statusId", "WfLinkActive")
                    .count()
            return count > 0
        } catch (Throwable t) {
            addError(ec, "Failed to check WfProcessLink: \${message}", [message: t.message])
            return true
        }
    }

    protected static void writeProcessLink(ExecutionContext ec, String entityName, String pkValue,
                                          String instanceId, String key) {
        if (!pkValue) {
            addError(ec, "pkValue or businessKey is required to write WfProcessLink")
            return
        }
        EntityValue ev = ec.entity.makeValue("wf.flowable.WfProcessLink")
        ev.linkId = ec.entity.sequencedIdPrimary("wf.flowable.WfProcessLink", null, null)
        ev.entityName = entityName ?: "businessKey"
        ev.pkValue = pkValue
        ev.processInstanceId = instanceId
        ev.processDefinitionKey = key
        ev.statusId = "WfLinkActive"
        ev.create()
    }

    protected static void markLinkEnded(ExecutionContext ec, String instanceId) {
        if (!instanceId) return
        def links = ec.entity.find("wf.flowable.WfProcessLink")
                .condition("processInstanceId", instanceId)
                .condition("statusId", "WfLinkActive").list()
        for (def link : links) {
            link.set("statusId", "WfLinkEnded")
            link.update()
        }
    }

    protected static Task requireTask(ExecutionContext ec, Map context) {
        String taskId = context.taskId as String
        if (!taskId) {
            addError(ec, "taskId is required")
            return null
        }
        Task task = engine(ec).taskService.createTaskQuery().taskId(taskId).singleResult()
        if (task == null) addError(ec, "Task \${taskId} not found", [taskId: taskId])
        return task
    }

    protected static ProcessInstance requireRunning(ExecutionContext ec, Map context) {
        String instanceId = context.processInstanceId as String
        String businessKey = context.businessKey as String
        ProcessInstance instance = null
        if (instanceId) instance = engine(ec).runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult()
        else if (businessKey) {
            List<ProcessInstance> runList = engine(ec).runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(businessKey).list()
            instance = runList ? runList[0] : null
        }
        if (instance == null) addError(ec, "Running process instance not found")
        return instance
    }

    protected static boolean isCandidate(ExecutionContext ec, Task task, String userId) {
        List<Task> matches = engine(ec).taskService.createTaskQuery().taskId(task.id).taskCandidateUser(userId).list()
        if (matches) return true
        Set<String> groups = ec.user.userGroupIdSet
        if (groups) {
            List<Task> groupMatches = engine(ec).taskService.createTaskQuery()
                    .taskId(task.id).taskCandidateGroupIn(new ArrayList<String>(groups)).list()
            if (groupMatches) return true
        }
        return false
    }

    protected static Map taskToMap(ExecutionContext ec, Task t) {
        String businessKey = findBusinessKey(ec, t.processInstanceId)
        return [taskId: t.id, name: t.name, assignee: t.assignee, processInstanceId: t.processInstanceId,
                processDefinitionId: t.processDefinitionId, formKey: t.formKey, createTime: t.createTime,
                businessKey: businessKey] as Map
    }

    protected static String findBusinessKey(ExecutionContext ec, String instanceId) {
        ProcessInstance pi = engine(ec).runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult()
        if (pi?.businessKey) return pi.businessKey
        HistoricProcessInstance h = engine(ec).historyService.createHistoricProcessInstanceQuery().processInstanceId(instanceId).singleResult()
        return h?.businessKey
    }

    protected static boolean skipOnCompleteService(ExecutionContext ec, Task task) {
        try {
            BpmnModel model = engine(ec).repositoryService.getBpmnModel(task.processDefinitionId)
            def el = model?.getFlowElement(task.taskDefinitionKey)
            if (!(el instanceof UserTask)) return false
            UserTask ut = (UserTask) el
            if (ut.loopCharacteristics != null) return true
            for (def flow : ut.incomingFlows ?: []) {
                if (flow.sourceFlowElement instanceof ParallelGateway) return true
            }
        } catch (Throwable ignored) { }
        return false
    }

    protected static String findOnCompleteService(ExecutionContext ec, Task task, String outcome) {
        String field = outcome == OUTCOME_APPROVE ? "onApproveService" :
                (outcome == OUTCOME_REJECT ? "onRejectService" : "onReturnService")
        try {
            Object val = task.getTaskLocalVariables()?.get(field)
            if (val) return val.toString()
        } catch (Throwable ignored) { }
        try {
            BpmnModel model = engine(ec).repositoryService.getBpmnModel(task.processDefinitionId)
            def el = model?.getFlowElement(task.taskDefinitionKey)
            if (el instanceof UserTask) {
                for (FieldExtension fe : ((UserTask) el).fieldExtensions ?: []) {
                    if (field.equals(fe.fieldName) && (fe.stringValue || fe.expression)) {
                        return (fe.stringValue ?: fe.expression) as String
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null
    }

    protected static void fillRunningInstance(ExecutionContext ec, Map context, ProcessInstance running) {
        context.processInstanceId = running.id
        context.processDefinitionKey = running.processDefinitionKey
        context.processDefinitionVersion = running.processDefinitionVersion
        context.businessKey = running.businessKey
        context.status = "running"
        context.startUserId = running.startUserId
        context.currentActivities = engine(ec).runtimeService.getActiveActivityIds(running.id)
        List<Task> open = engine(ec).taskService.createTaskQuery().processInstanceId(running.id).list()
        context.currentTasks = open.collect { Task t -> taskToMap(ec, t) }
        context.latestFormKey = open ? open[0].formKey : latestHistoricFormKey(ec, running.id)
    }

    protected static String latestHistoricFormKey(ExecutionContext ec, String instanceId) {
        List<HistoricTaskInstance> hist = engine(ec).historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instanceId).orderByHistoricTaskInstanceEndTime().desc().list()
        HistoricTaskInstance last = hist.find { it.formKey }
        return last?.formKey
    }

    protected static void writeTaskComment(ExecutionContext ec, Task task, String outcome, String comment) {
        writeProcessComment(ec, null, outcome, comment, task, findBusinessKey(ec, task.processInstanceId))
    }

    protected static void writeProcessComment(ExecutionContext ec, ProcessInstance instance, String outcome,
                                             String comment, Task task, String businessKey) {
        EntityValue ev = ec.entity.makeValue("wf.flowable.WfTaskComment")
        ev.taskCommentId = ec.entity.sequencedIdPrimary("wf.flowable.WfTaskComment", null, null)
        ev.taskId = task?.id
        ev.processInstanceId = task?.processInstanceId ?: instance?.id
        ev.nodeId = task?.taskDefinitionKey
        ev.userId = ec.user.userId
        ev.outcome = outcome
        ev.comment = comment
        ev.commentDate = ec.user.nowTimestamp
        ev.businessKey = businessKey ?: instance?.businessKey
        ev.create()
    }

    protected static void denyInstanceIfUnauthorized(ExecutionContext ec, Map context, String instanceId) {
        if (canViewInstance(ec, instanceId)) return
        addError(ec, "Not allowed to view this instance")
        context.latestFormKey = null
        context.currentTasks = []
        context.currentActivities = []
    }

    protected static void fillBusinessServices(ExecutionContext ec, Map context, String instanceId) {
        if (!instanceId) return
        try {
            Object withdraw = engine(ec).runtimeService.getVariable(instanceId, "businessWithdrawService")
            Object cancel = engine(ec).runtimeService.getVariable(instanceId, "businessCancelService")
            if (withdraw) context.businessWithdrawService = withdraw.toString()
            if (cancel) context.businessCancelService = cancel.toString()
        } catch (Throwable ignored) {
            try {
                def vars = engine(ec).historyService.createHistoricVariableInstanceQuery()
                        .processInstanceId(instanceId).list()
                vars.each { v ->
                    if (v.variableName == "businessWithdrawService" && v.value) {
                        context.businessWithdrawService = v.value.toString()
                    }
                    if (v.variableName == "businessCancelService" && v.value) {
                        context.businessCancelService = v.value.toString()
                    }
                }
            } catch (Throwable ignored2) { }
        }
    }

    protected static boolean canViewByBusinessKey(ExecutionContext ec, String businessKey) {
        if (ec.user.isInGroup("ADMIN")) return true
        List<HistoricProcessInstance> hist = engine(ec).historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).list()
        for (HistoricProcessInstance h : hist) {
            if (canViewInstance(ec, h.id)) return true
        }
        return false
    }

    protected static boolean canViewComments(ExecutionContext ec, String instanceId) {
        return canViewInstance(ec, instanceId)
    }

    protected static boolean canViewInstance(ExecutionContext ec, String instanceId) {
        if (!instanceId) return false
        if (ec.user.isInGroup("ADMIN")) return true
        HistoricProcessInstance h = engine(ec).historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instanceId).singleResult()
        if (h != null && h.startUserId == ec.user.userId) return true
        long participated = engine(ec).historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instanceId).taskAssignee(ec.user.userId).count()
        if (participated > 0) return true
        List<Task> open = engine(ec).taskService.createTaskQuery().processInstanceId(instanceId).taskAssignee(ec.user.userId).list()
        if (open) return true
        List<Task> cand = engine(ec).taskService.createTaskQuery().processInstanceId(instanceId).taskCandidateUser(ec.user.userId).list()
        if (cand) return true
        Set<String> groups = ec.user.userGroupIdSet
        if (groups) {
            List<Task> groupCand = engine(ec).taskService.createTaskQuery()
                    .processInstanceId(instanceId).taskCandidateGroupIn(new ArrayList<String>(groups)).list()
            if (groupCand) return true
        }
        return false
    }

    protected static void collectXmlFiles(ResourceReference dir, List<String> out) {
        if (dir == null || !dir.exists || !dir.isDirectory()) return
        for (ResourceReference child : dir.directoryEntries) {
            if (child.isDirectory()) collectXmlFiles(child, out)
            else if (child.fileName?.endsWith(".xml")) out.add(child.location)
        }
    }

    protected static String localName(Object node) {
        def n = node?.name()
        if (n == null) return ""
        String s = n.toString()
        int brace = s.lastIndexOf('}')
        if (brace >= 0 && brace < s.length() - 1) return s.substring(brace + 1)
        int colon = s.lastIndexOf(':')
        if (colon >= 0 && colon < s.length() - 1) return s.substring(colon + 1)
        try {
            if (n.hasProperty("localPart")) return n.localPart?.toString() ?: s
        } catch (Throwable ignored) { }
        return s
    }

    protected static String attr(Object node, String name) {
        try {
            def n = node as GPathResult
            def v = n.attributes()?.get("{" + "http://flowable.org/bpmn}" + name)
            if (v) return v.toString()
            v = n.attributes()?.get(name)
            return v?.toString()
        } catch (Throwable ignored) {
            return null
        }
    }
}
