package wf.flowable

import groovy.util.logging.Slf4j
import groovy.xml.XmlSlurper
import groovy.xml.slurpersupport.GPathResult
import org.flowable.bpmn.model.BpmnModel
import org.flowable.bpmn.model.ParallelGateway
import org.flowable.bpmn.model.UserTask
import org.flowable.common.engine.impl.identity.Authentication
import org.flowable.engine.ProcessEngine
import org.flowable.engine.history.HistoricActivityInstance
import org.flowable.engine.history.HistoricProcessInstance
import org.flowable.engine.repository.ProcessDefinition
import org.flowable.engine.runtime.Execution
import org.flowable.engine.runtime.ProcessInstance
import org.flowable.identitylink.api.IdentityLinkType
import org.flowable.task.api.Task
import org.flowable.task.api.history.HistoricTaskInstance
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.resource.ResourceReference
import org.moqui.util.CollectionUtilities
import org.moqui.util.ObjectUtilities

import java.sql.Timestamp

@Slf4j
class WorkflowSupport {
    static final String OUTCOME_APPROVE = "approve"
    static final String OUTCOME_REJECT = "reject"
    static final String OUTCOME_RETURN = "return"
    static final String OUTCOME_TRANSFER = "transfer"
    static final String OUTCOME_WITHDRAW = "withdraw"
    static final String OUTCOME_CANCEL = "cancel"
    static final String OUTCOME_ADD_SIGN = "addSign"
    static final String OUTCOME_RETURN_TO = "returnTo"
    static final Set<String> COMPLETE_OUTCOMES = [OUTCOME_APPROVE, OUTCOME_REJECT, OUTCOME_RETURN] as Set<String>
    static final int DEFAULT_INSTANCE_PAGE_SIZE = 50
    static final int MAX_INSTANCE_PAGE_SIZE = 200

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

    protected static <T> T noAuthz(ExecutionContext ec, Closure<T> body) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            return body.call()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
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

    static void createProcessDefinition(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        String name = context.processName as String
        if (!key || !name) {
            addError(ec, "processDefinitionKey and processName are required")
            return
        }
        if (!(key ==~ /^[A-Za-z_][A-Za-z0-9_]*$/)) {
            addError(ec, "processDefinitionKey must start with a letter or underscore and contain only letters, digits, and underscores")
            return
        }
        EntityValue existing = noAuthz(ec) {
            ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
        }
        if (existing != null) {
            addError(ec, "Process definition \${key} already exists", [key: key])
            return
        }
        String bpmnXml = stubBpmnXml(key, name)
        upsertDefinitionEntity(ec, key, name, bpmnXml, "WfDefDraft", null, context.description as String)
        context.processDefinitionKey = key
        context.statusId = "WfDefDraft"
    }

    static void updateProcessDefinitionMeta(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        if (!key) {
            addError(ec, "processDefinitionKey is required")
            return
        }
        EntityValue ev = noAuthz(ec) {
            ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
        }
        if (ev == null) {
            addError(ec, "Process definition \${key} not found", [key: key])
            return
        }
        if (context.processName) ev.processName = context.processName as String
        ev.description = context.description
        ev.update()
        context.processDefinitionKey = key
        context.processName = ev.processName
        context.description = ev.description
        context.statusId = ev.statusId
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
        EntityValue ev = noAuthz(ec) {
            ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
        }
        if (ev == null) {
            addError(ec, "Process definition \${key} not found", [key: key])
            return
        }
        context.putAll(ev.getMap())
    }

    static void listProcessDefinitions(ExecutionContext ec, Map context) {
        context.processDefinitionList = noAuthz(ec) {
            ec.entity.find("wf.flowable.WfProcessDefinition").orderBy("processName").list().getValueMapList()
        }
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
            linkCalledChildren(ec, instance.id)
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
        if (!instanceId && !businessKey && context.taskId) {
            Task byTask = engine(ec).taskService.createTaskQuery().taskId(context.taskId as String).singleResult()
            if (byTask?.processInstanceId) instanceId = byTask.processInstanceId
            else {
                HistoricTaskInstance ht = engine(ec).historyService.createHistoricTaskInstanceQuery()
                        .taskId(context.taskId as String).singleResult()
                if (ht?.processInstanceId) instanceId = ht.processInstanceId
            }
        }
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
        fillCompletedPath(ec, context, historic.id)
        fillVisitAndFlags(ec, context, historic.id, historic.startUserId,
                historic.endTime != null ? "ended" : "historic")
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
        Task engineTask = resolveEngineTask(ec, task)
        if (engineTask == null) return
        String syncService = skipOnCompleteService(ec, engineTask) ? null : findOnCompleteService(ec, engineTask, outcome)
        if (syncService) {
            Map<String, Object> params = new LinkedHashMap<>()
            params.putAll(engine(ec).runtimeService.getVariables(engineTask.executionId ?: engineTask.processInstanceId))
            params.businessKey = findBusinessKey(ec, engineTask.processInstanceId)
            params.outcome = outcome
            params.taskId = task.id
            Map result = ec.service.sync().name(syncService).parameters(params).call()
            if (ec.message.hasError()) return
            if (result != null && engineTask.executionId) {
                engine(ec).runtimeService.setVariables(engineTask.executionId, result)
            }
        }

        boolean beganHere = false
        if (!ec.transaction.isTransactionInPlace()) {
            ec.transaction.begin(null)
            beganHere = true
        }
        String commentId = null
        try {
            commentId = writeTaskComment(ec, task, outcome, context.comment as String)
            if (engineTask.executionId) {
                engine(ec).runtimeService.setVariableLocal(engineTask.executionId, "outcome", outcome)
            }
            boolean addSignChild = task.parentTaskId != null
            deleteAddSignSiblings(ec, engineTask, addSignChild ? task.id : null)
            if (addSignChild) {
                try {
                    engine(ec).taskService.complete(task.id)
                } catch (Throwable ignored) {
                    try { engine(ec).taskService.deleteTask(task.id, false) } catch (Throwable ignored2) { }
                }
            }
            engine(ec).taskService.complete(engineTask.id)
            if (beganHere) ec.transaction.commit()
        } catch (Throwable t) {
            if (beganHere) {
                try { ec.transaction.rollback(true, "complete#Task failed", t) } catch (Throwable ignored) {}
            } else if (commentId) {
                try {
                    EntityValue ev = noAuthz(ec) {
                        ec.entity.find("wf.flowable.WfTaskComment").condition("taskCommentId", commentId).one()
                    }
                    if (ev != null) noAuthz(ec) { ev.delete(); null }
                } catch (Throwable ignored) {}
            }
            addError(ec, "Failed to complete task: \${message}", [message: t.message])
        }
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
        try {
            List<Task> unassigned = engine(ec).taskService.createTaskQuery().taskUnassigned().list()
            for (Task t : unassigned) {
                if (byId.containsKey(t.id)) continue
                if (isCandidate(ec, t, userId)) byId.put(t.id, taskToMap(ec, t))
            }
        } catch (Throwable ignored) { }
        context.taskList = new ArrayList(byId.values())
    }

    static void listCompletedTasks(ExecutionContext ec, Map context) {
        String userId = ec.user.userId
        List<HistoricTaskInstance> tasks = engine(ec).historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(userId).finished().orderByHistoricTaskInstanceEndTime().desc().list()
        Map<String, Map> byId = new LinkedHashMap<>()
        tasks.each { HistoricTaskInstance t ->
            byId.put(t.id, [taskId: t.id, name: t.name, processInstanceId: t.processInstanceId,
                    processDefinitionId: t.processDefinitionId, endTime: t.endTime, formKey: t.formKey,
                    businessKey: findBusinessKey(ec, t.processInstanceId)] as Map)
        }
        noAuthz(ec) {
            ec.entity.find("wf.flowable.WfTaskComment")
                    .condition("userId", userId)
                    .condition("outcome", EntityCondition.IN, COMPLETE_OUTCOMES)
                    .orderBy("-commentDate").list().each { EntityValue ev ->
                String taskId = ev.taskId as String
                if (!taskId || byId.containsKey(taskId)) return
                byId.put(taskId, [taskId: taskId, name: ev.nodeId, processInstanceId: ev.processInstanceId,
                        endTime: ev.commentDate, formKey: null, businessKey: ev.businessKey] as Map)
            }
            null
        }
        context.taskList = new ArrayList(byId.values())
    }

    static void listMyStarted(ExecutionContext ec, Map context) {
        List<HistoricProcessInstance> list = engine(ec).historyService.createHistoricProcessInstanceQuery()
                .startedBy(ec.user.userId).orderByProcessInstanceStartTime().desc().list()
        context.instanceList = list.collect { HistoricProcessInstance p ->
            [processInstanceId: p.id, processDefinitionKey: p.processDefinitionKey, businessKey: p.businessKey,
             startTime: p.startTime, endTime: p.endTime, status: p.endTime != null ? "ended" : "running"] as Map
        }
    }

    /** Filter, sort, and paginate a form-list backed by a List of Maps (Flowable, not entity-find). */
    static void filterSortPaginate(ExecutionContext ec, String listName, List<String> fields) {
        Map ctx = ec.context
        List raw = (ctx.get(listName) as List) ?: []
        ArrayList<Map<String, Object>> maps = new ArrayList<>()
        for (Object item : raw) {
            if (item instanceof Map) maps.add((Map<String, Object>) item)
        }
        ArrayList<Map<String, Object>> filtered = new ArrayList<>()
        for (Map<String, Object> row : maps) {
            boolean keep = true
            for (String fn : fields) {
                if (!rowMatchesFormInput(ec, row, fn)) { keep = false; break }
            }
            if (keep) filtered.add(row)
        }
        Object orderBy = ctx.orderByField
        List<String> orderFields = []
        if (orderBy instanceof Collection) {
            for (Object ob : (Collection) orderBy) {
                if (!ObjectUtilities.isEmpty(ob)) orderFields.add(ob.toString())
            }
        } else if (!ObjectUtilities.isEmpty(orderBy)) {
            orderFields.add(orderBy.toString())
        }
        if (orderFields) CollectionUtilities.orderMapList(filtered, orderFields)
        ctx.put(listName, filtered)

        boolean noLimit = ctx.pageNoLimit == true || ctx.pageNoLimit == "true"
        if (noLimit) {
            int n = filtered.size()
            ctx.put(listName + "Count", n)
            ctx.put(listName + "PageIndex", 0)
            ctx.put(listName + "PageSize", n > 0 ? n : 20)
            ctx.put(listName + "PageMaxIndex", 0)
            ctx.put(listName + "PageRangeLow", n > 0 ? 1 : 0)
            ctx.put(listName + "PageRangeHigh", n)
            ctx.put(listName + "AlreadyPaginated", true)
        } else {
            CollectionUtilities.paginateList(listName, listName, ctx)
        }
    }

    protected static boolean rowMatchesFormInput(ExecutionContext ec, Map row, String fn) {
        Map ctx = ec.context
        if (ctx.containsKey(fn) || ctx.containsKey(fn + "_op")) {
            Object value = ctx.get(fn)
            boolean valueEmpty = ObjectUtilities.isEmpty(value)
            String op = (ctx.get(fn + "_op") ?: "equals") as String
            boolean not = ctx.get(fn + "_not") in ["Y", "true", true]
            boolean ic = ctx.get(fn + "_ic") in ["Y", "true", true]
            Object field = row.get(fn)
            Boolean match = null
            switch (op) {
                case "equals":
                    if (valueEmpty) return true
                    match = stringEquals(field, value, ic)
                    break
                case "like":
                    if (valueEmpty) return true
                    match = likeMatch(field, value.toString(), ic)
                    break
                case "contains":
                    if (valueEmpty) return true
                    match = containsMatch(field, value.toString(), ic)
                    break
                case "begins":
                    if (valueEmpty) return true
                    match = beginsMatch(field, value.toString(), ic)
                    break
                case "empty":
                    match = ObjectUtilities.isEmpty(field)
                    break
                case "in":
                    if (valueEmpty) return true
                    Collection vals = value instanceof Collection ? (Collection) value : value.toString().split(",") as List
                    String fs = field == null ? "" : field.toString()
                    match = ic ? vals.any { it?.toString()?.equalsIgnoreCase(fs) } :
                            vals.any { Objects.equals(it?.toString()?.trim(), fs) }
                    break
                default:
                    if (valueEmpty) return true
                    match = stringEquals(field, value, ic)
            }
            return not ? !match : match
        }
        if (!ObjectUtilities.isEmpty(ctx.get(fn + "_period")) ||
                !ObjectUtilities.isEmpty(ctx.get(fn + "_from")) ||
                !ObjectUtilities.isEmpty(ctx.get(fn + "_thru"))) {
            Object field = row.get(fn)
            Long t = toMillis(field)
            if (t == null) return false
            if (!ObjectUtilities.isEmpty(ctx.get(fn + "_period"))) {
                ArrayList range = ec.user.getPeriodRange(fn, ctx)
                Timestamp fromTs = range ? (Timestamp) range.get(0) : null
                Timestamp thruTs = range && range.size() > 1 ? (Timestamp) range.get(1) : null
                if (fromTs != null && t < fromTs.time) return false
                if (thruTs != null && t >= thruTs.time) return false
                return true
            }
            ArrayList range = ec.user.getPeriodRange(fn, ctx)
            Timestamp fromTs = range ? (Timestamp) range.get(0) : null
            Timestamp thruTs = range && range.size() > 1 ? (Timestamp) range.get(1) : null
            if (fromTs != null && t < fromTs.time) return false
            if (thruTs != null && t > thruTs.time) return false
            return true
        }
        return true
    }

    protected static boolean stringEquals(Object field, Object value, boolean ic) {
        String fs = field == null ? "" : field.toString()
        String vs = value == null ? "" : value.toString()
        return ic ? fs.equalsIgnoreCase(vs) : fs == vs
    }

    protected static boolean containsMatch(Object field, String value, boolean ic) {
        String fs = field == null ? "" : field.toString()
        return ic ? fs.toLowerCase().contains(value.toLowerCase()) : fs.contains(value)
    }

    protected static boolean beginsMatch(Object field, String value, boolean ic) {
        String fs = field == null ? "" : field.toString()
        return ic ? fs.toLowerCase().startsWith(value.toLowerCase()) : fs.startsWith(value)
    }

    protected static boolean likeMatch(Object field, String pattern, boolean ignored) {
        if (field == null) return false
        return ObjectUtilities.compareLike(field.toString(), pattern)
    }

    protected static Long toMillis(Object v) {
        if (v == null) return null
        if (v instanceof Date) return ((Date) v).time
        if (v instanceof Number) return ((Number) v).longValue()
        String s = v.toString()?.trim()
        if (!s) return null
        try {
            if (s.length() <= 10) return Timestamp.valueOf(s + " 00:00:00").time
            return Timestamp.valueOf(s.replace('T', ' ')).time
        } catch (Throwable ignored) {
            return null
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
            context.commentList = noAuthz(ec) {
                ec.entity.find("wf.flowable.WfTaskComment")
                        .condition("businessKey", businessKey).orderBy("commentDate").list().getValueMapList()
            }
            return
        }
        if (!canViewInstance(ec, instanceId)) {
            addError(ec, "Not allowed to list comments for this instance")
            return
        }
        String bk = findBusinessKey(ec, instanceId)
        if (bk) {
            context.commentList = noAuthz(ec) {
                ec.entity.find("wf.flowable.WfTaskComment")
                        .condition("businessKey", bk).orderBy("commentDate").list().getValueMapList()
            }
        } else {
            context.commentList = noAuthz(ec) {
                ec.entity.find("wf.flowable.WfTaskComment")
                        .condition("processInstanceId", instanceId).orderBy("commentDate").list().getValueMapList()
            }
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

    static void addSignTask(ExecutionContext ec, Map context) {
        Task task = requireTask(ec, context)
        if (task == null) return
        String toUserId = context.toUserId as String
        if (!toUserId) {
            addError(ec, "toUserId is required")
            return
        }
        if (task.assignee != ec.user.userId) {
            addError(ec, "Current user must be the assignee")
            return
        }
        Task engineTask = resolveEngineTask(ec, task)
        if (engineTask == null) return
        if (isParallelOrCountersign(ec, engineTask)) {
            addError(ec, "Cannot add sign on a countersign or parallel task")
            return
        }
        if (!isAllowAddSign(ec, engineTask)) {
            addError(ec, "Add sign is not allowed on this task")
            return
        }
        EntityValue user = noAuthz(ec) {
            ec.entity.find("moqui.security.UserAccount").condition("userId", toUserId).one()
        }
        if (user == null) {
            addError(ec, "User \${userId} not found", [userId: toUserId])
            return
        }
        Task sibling = engine(ec).taskService.newTask()
        sibling.name = (engineTask.name ?: "Task") + " (add-sign)"
        sibling.assignee = toUserId
        sibling.owner = engineTask.assignee
        sibling.parentTaskId = engineTask.id
        sibling.processInstanceId = engineTask.processInstanceId
        sibling.processDefinitionId = engineTask.processDefinitionId
        sibling.taskDefinitionKey = engineTask.taskDefinitionKey
        sibling.formKey = engineTask.formKey
        engine(ec).taskService.saveTask(sibling)
        writeTaskComment(ec, task, OUTCOME_ADD_SIGN, context.comment as String)
    }

    static void returnToTask(ExecutionContext ec, Map context) {
        Task task = requireTask(ec, context)
        if (task == null) return
        String target = context.targetActivityId as String
        if (!target) {
            addError(ec, "targetActivityId is required")
            return
        }
        if (task.assignee != ec.user.userId) {
            addError(ec, "Current user must be the assignee")
            return
        }
        Task engineTask = resolveEngineTask(ec, task)
        if (engineTask == null) return
        if (isParallelOrCountersign(ec, engineTask)) {
            addError(ec, "Cannot return to a node on a parallel or countersign instance; use a BPMN return edge")
            return
        }
        List<Map> visited = listVisitedUserTasks(ec, engineTask.processInstanceId)
        if (!visited.any { it.activityId == target }) {
            addError(ec, "targetActivityId must be a UserTask already visited on this instance")
            return
        }
        writeTaskComment(ec, task, OUTCOME_RETURN_TO, context.comment as String)
        deleteAddSignSiblings(ec, engineTask, null)
        engine(ec).runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(engineTask.processInstanceId)
                .moveActivityIdTo(engineTask.taskDefinitionKey, target)
                .changeState()
    }

    static void messageProcess(ExecutionContext ec, Map context) {
        deliverWaitingEvent(ec, context, "message")
    }

    static void signalProcess(ExecutionContext ec, Map context) {
        deliverWaitingEvent(ec, context, "signal")
    }

    protected static void deliverWaitingEvent(ExecutionContext ec, Map context, String kind) {
        ProcessInstance instance = requireRunning(ec, context)
        if (instance == null) return
        String name = (kind == "message" ? context.messageName : context.signalName) as String
        if (!name) {
            addError(ec, kind == "message" ? "messageName is required" : "signalName is required")
            return
        }
        Map<String, Object> vars = new LinkedHashMap<>()
        if (context.variables instanceof Map) vars.putAll((Map) context.variables)
        def q = engine(ec).runtimeService.createExecutionQuery().processInstanceId(instance.id)
        List<Execution> waiting = kind == "message" ?
                q.messageEventSubscriptionName(name).list() :
                q.signalEventSubscriptionName(name).list()
        if (!waiting) {
            addError(ec, "No waiting \${kind} subscription named \${name} on this instance",
                    [kind: kind, name: name])
            return
        }
        for (Execution ex : waiting) {
            if (kind == "message") engine(ec).runtimeService.messageEventReceived(name, ex.id, vars)
            else engine(ec).runtimeService.signalEventReceived(name, ex.id, vars)
        }
    }

    static void listProcessInstances(ExecutionContext ec, Map context) {
        if (!ec.user.isInGroup("ADMIN")) {
            addError(ec, "ADMIN required")
            return
        }
        // Cap is independent of form-list pageSize/pageIndex (those are applied by filterSortPaginate).
        int cap = DEFAULT_INSTANCE_PAGE_SIZE
        Object rawCap = context.pageSize
        if (rawCap != null) {
            try { cap = Math.max(1, Math.min(MAX_INSTANCE_PAGE_SIZE, rawCap as int)) } catch (Throwable ignored) { }
        }
        List<HistoricProcessInstance> list = engine(ec).historyService.createHistoricProcessInstanceQuery()
                .orderByProcessInstanceStartTime().desc().listPage(0, cap)
        String selectedId = context.processInstanceId as String
        if (selectedId && !list.any { it.id == selectedId }) {
            HistoricProcessInstance selected = engine(ec).historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(selectedId).singleResult()
            if (selected != null) list = [selected] + list
        }
        Set<String> runningIds = list.findAll { it.endTime == null }.collect { it.id } as Set<String>
        Map<String, List<String>> activitiesByInstance = [:]
        if (runningIds) {
            List<Execution> executions = engine(ec).runtimeService.createExecutionQuery().list()
            for (Execution e : executions) {
                if (!e.activityId || !runningIds.contains(e.processInstanceId)) continue
                List<String> ids = activitiesByInstance.get(e.processInstanceId)
                if (ids == null) {
                    ids = new ArrayList<>()
                    activitiesByInstance.put(e.processInstanceId, ids)
                }
                if (!ids.contains(e.activityId)) ids.add(e.activityId)
            }
        }
        context.instanceList = list.collect { HistoricProcessInstance p ->
            List<String> activities = p.endTime == null ? (activitiesByInstance.get(p.id) ?: []) : []
            [processInstanceId: p.id, processDefinitionKey: p.processDefinitionKey,
             processDefinitionVersion: p.processDefinitionVersion, businessKey: p.businessKey,
             startUserId: p.startUserId, startTime: p.startTime, endTime: p.endTime,
             status: p.endTime != null ? "ended" : "running", currentActivities: activities] as Map
        }
    }

    static void suspendProcessDefinition(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        engine(ec).repositoryService.suspendProcessDefinitionByKey(key)
        noAuthz(ec) {
            EntityValue ev = ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
            if (ev != null) { ev.set("statusId", "WfDefSuspended"); ev.update() }
            null
        }
    }

    static void activateProcessDefinition(ExecutionContext ec, Map context) {
        String key = context.processDefinitionKey as String
        try {
            engine(ec).repositoryService.activateProcessDefinitionByKey(key)
        } catch (Throwable t) {
            String msg = (t.message ?: "").toLowerCase()
            if (!msg.contains("already") && !msg.contains("not suspended")) {
                addError(ec, "Failed to activate process: \${message}", [message: t.message])
                return
            }
        }
        noAuthz(ec) {
            EntityValue ev = ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
            if (ev != null) { ev.set("statusId", "WfDefDeployed"); ev.update() }
            null
        }
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
        for (String componentName : ec.factory.componentBaseLocations.keySet()) {
            collectXmlFiles(ec.resource.getLocationReference("component://" + componentName + "/screen"), locations)
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
                    if (local.equalsIgnoreCase("callActivity")) {
                        String called = node.'@calledElement'.text() ?: attr(node, "calledElement")
                        if (!called) {
                            errors.add(ec.resource.expand("Call Activity \${id} missing calledElement", "", [id: id]))
                        }
                    }
                    if (local.toLowerCase().contains("event") && node.messageEventDefinition.size()) {
                        String msgName = node.messageEventDefinition.'@messageRef'.text() ?:
                                node.messageEventDefinition.message.'@name'.text()
                        if (!msgName) msgName = attr(node.messageEventDefinition, "messageRef")
                        if (!msgName) {
                            errors.add(ec.resource.expand("Message catch \${id} missing message name", "", [id: id]))
                        }
                    }
                    if (local.toLowerCase().contains("event") && node.signalEventDefinition.size()) {
                        String sigName = node.signalEventDefinition.'@signalRef'.text() ?:
                                node.signalEventDefinition.signal.'@name'.text()
                        if (!sigName) sigName = attr(node.signalEventDefinition, "signalRef")
                        if (!sigName) {
                            errors.add(ec.resource.expand("Signal catch \${id} missing signal name", "", [id: id]))
                        }
                    }
                }
            }
            errors.addAll(findCallActivityCycles(ec, bpmnXml))
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

    protected static String stubBpmnXml(String key, String name) {
        String xmlKey = xmlEscape(key)
        String xmlName = xmlEscape(name)
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xmlns:flowable="http://flowable.org/bpmn"
        xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
        xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
        xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
        targetNamespace="http://moqui.org/flowable"
        id="${xmlKey}Definitions">
    <process id="${xmlKey}" name="${xmlName}" isExecutable="true">
        <startEvent id="start" name="Start"/>
        <sequenceFlow id="toEnd" sourceRef="start" targetRef="end"/>
        <endEvent id="end" name="End"/>
    </process>
    <bpmndi:BPMNDiagram id="BPMNDiagram_${xmlKey}">
        <bpmndi:BPMNPlane id="BPMNPlane_${xmlKey}" bpmnElement="${xmlKey}">
            <bpmndi:BPMNShape id="start_di" bpmnElement="start">
                <dc:Bounds x="180" y="200" width="36" height="36"/>
            </bpmndi:BPMNShape>
            <bpmndi:BPMNShape id="end_di" bpmnElement="end">
                <dc:Bounds x="330" y="200" width="36" height="36"/>
            </bpmndi:BPMNShape>
            <bpmndi:BPMNEdge id="toEnd_di" bpmnElement="toEnd">
                <di:waypoint x="216" y="218"/>
                <di:waypoint x="330" y="218"/>
            </bpmndi:BPMNEdge>
        </bpmndi:BPMNPlane>
    </bpmndi:BPMNDiagram>
</definitions>"""
    }

    protected static String xmlEscape(String value) {
        if (value == null) return ""
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;")
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
                                                String statusId, String deploymentId, String description = null) {
        noAuthz(ec) {
            EntityValue ev = ec.entity.find("wf.flowable.WfProcessDefinition")
                    .condition("processDefinitionKey", key).one()
            if (ev == null) {
                ev = ec.entity.makeValue("wf.flowable.WfProcessDefinition")
                ev.processDefinitionKey = key
                ev.processName = name ?: key
                ev.description = description
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
            null
        }
    }

    protected static boolean hasActiveLink(ExecutionContext ec, String pkValue, String key) {
        if (!pkValue) return false
        try {
            long count = noAuthz(ec) {
                ec.entity.find("wf.flowable.WfProcessLink")
                        .condition("pkValue", pkValue)
                        .condition("processDefinitionKey", key)
                        .condition("statusId", "WfLinkActive")
                        .count()
            }
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
        noAuthz(ec) {
            EntityValue ev = ec.entity.makeValue("wf.flowable.WfProcessLink")
            ev.linkId = ec.entity.sequencedIdPrimary("wf.flowable.WfProcessLink", null, null)
            ev.entityName = entityName ?: "businessKey"
            ev.pkValue = pkValue
            ev.processInstanceId = instanceId
            ev.processDefinitionKey = key
            ev.statusId = "WfLinkActive"
            ev.create()
            null
        }
    }

    protected static void linkCalledChildren(ExecutionContext ec, String parentInstanceId) {
        if (!parentInstanceId) return
        ProcessEngine pe = engine(ec)
        String parentBk = findBusinessKey(ec, parentInstanceId)
        Set<String> childIds = new LinkedHashSet<>()
        try {
            pe.runtimeService.createProcessInstanceQuery().superProcessInstanceId(parentInstanceId).list()
                    .each { childIds.add(it.id) }
        } catch (Throwable ignored) { }
        try {
            pe.historyService.createHistoricProcessInstanceQuery().superProcessInstanceId(parentInstanceId).list()
                    .each { HistoricProcessInstance h -> if (h.id) childIds.add(h.id) }
        } catch (Throwable ignored) { }
        EntityValue parentLink = noAuthz(ec) {
            EntityValue active = ec.entity.find("wf.flowable.WfProcessLink")
                    .condition("processInstanceId", parentInstanceId).condition("statusId", "WfLinkActive").one()
            (EntityValue) (active ?: ec.entity.find("wf.flowable.WfProcessLink")
                    .condition("processInstanceId", parentInstanceId).orderBy("-linkId").one())
        }
        for (String childId : childIds) {
            if (parentBk) {
                try {
                    ProcessInstance child = pe.runtimeService.createProcessInstanceQuery()
                            .processInstanceId(childId).singleResult()
                    if (child != null && child.businessKey != parentBk) {
                        pe.runtimeService.updateBusinessKey(childId, parentBk)
                    }
                } catch (Throwable ignored) { }
            }
            if (parentLink == null) continue
            Long existing = noAuthz(ec) {
                ec.entity.find("wf.flowable.WfProcessLink").condition("processInstanceId", childId).count()
            }
            if (existing > 0) continue
            HistoricProcessInstance hist = pe.historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(childId).singleResult()
            String childKey = hist?.processDefinitionKey
            if (!childKey) continue
            noAuthz(ec) {
                EntityValue ev = ec.entity.makeValue("wf.flowable.WfProcessLink")
                ev.linkId = ec.entity.sequencedIdPrimary("wf.flowable.WfProcessLink", null, null)
                ev.entityName = parentLink.entityName
                ev.pkValue = parentLink.pkValue
                ev.processInstanceId = childId
                ev.processDefinitionKey = childKey
                ev.statusId = hist.endTime == null ? "WfLinkActive" : "WfLinkEnded"
                ev.create()
                null
            }
        }
    }

    protected static void markLinkEnded(ExecutionContext ec, String instanceId) {
        if (!instanceId) return
        noAuthz(ec) {
            def links = ec.entity.find("wf.flowable.WfProcessLink")
                    .condition("processInstanceId", instanceId)
                    .condition("statusId", "WfLinkActive").list()
            for (def link : links) {
                link.set("statusId", "WfLinkEnded")
                link.update()
            }
            null
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
        if (!userId || task == null) return false
        List<Task> matches = engine(ec).taskService.createTaskQuery().taskId(task.id).taskCandidateUser(userId).list()
        if (matches) return true
        try {
            def links = engine(ec).taskService.getIdentityLinksForTask(task.id)
            for (def link : links) {
                String linkType = link.type?.toString()
                String linkUser = link.userId?.toString()?.trim()
                if (IdentityLinkType.CANDIDATE.equals(linkType) && userId.equals(linkUser)) return true
            }
        } catch (Throwable ignored) { }
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
                businessKey: businessKey, parentTaskId: t.parentTaskId,
                allowAddSign: isAllowAddSign(ec, t)] as Map
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

    protected static boolean isParallelOrCountersign(ExecutionContext ec, Task task) {
        if (skipOnCompleteService(ec, task)) return true
        return countActiveUserTaskExecutions(ec, task.processInstanceId) > 1
    }

    protected static String findOnCompleteService(ExecutionContext ec, Task task, String outcome) {
        String field = outcome == OUTCOME_APPROVE ? "onApproveService" :
                (outcome == OUTCOME_REJECT ? "onRejectService" : "onReturnService")
        String v = userTaskField(ec, task, field)
        return v ? v.trim() : null
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
        fillCompletedPath(ec, context, running.id)
        fillVisitAndFlags(ec, context, running.id, running.startUserId, "running")
    }

    protected static void fillCompletedPath(ExecutionContext ec, Map context, String instanceId) {
        List<String> completedActivities = new ArrayList<>()
        List<String> completedFlows = new ArrayList<>()
        context.completedActivities = completedActivities
        context.completedFlows = completedFlows
        if (!instanceId) return
        try {
            List<HistoricActivityInstance> histActs = engine(ec).historyService
                    .createHistoricActivityInstanceQuery()
                    .processInstanceId(instanceId)
                    .finished()
                    .list()
            Set<String> seenActs = new LinkedHashSet<>()
            Set<String> seenFlows = new LinkedHashSet<>()
            for (HistoricActivityInstance hai : histActs) {
                String id = hai.activityId
                if (!id) continue
                if ("sequenceFlow".equals(hai.activityType)) {
                    seenFlows.add(id)
                } else {
                    seenActs.add(id)
                }
            }
            completedActivities.addAll(seenActs)
            completedFlows.addAll(seenFlows)
        } catch (Throwable ignored) { }
    }

    protected static String latestHistoricFormKey(ExecutionContext ec, String instanceId) {
        List<HistoricTaskInstance> hist = engine(ec).historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instanceId).orderByHistoricTaskInstanceEndTime().desc().list()
        HistoricTaskInstance last = hist.find { it.formKey }
        return last?.formKey
    }

    protected static String writeTaskComment(ExecutionContext ec, Task task, String outcome, String comment) {
        return writeProcessComment(ec, null, outcome, comment, task, findBusinessKey(ec, task.processInstanceId))
    }

    protected static String writeProcessComment(ExecutionContext ec, ProcessInstance instance, String outcome,
                                             String comment, Task task, String businessKey) {
        return noAuthz(ec) {
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
            ev.taskCommentId as String
        }
    }

    protected static void denyInstanceIfUnauthorized(ExecutionContext ec, Map context, String instanceId) {
        if (canViewInstance(ec, instanceId)) return
        addError(ec, "Not allowed to view this instance")
        context.latestFormKey = null
        context.currentTasks = []
        context.currentActivities = []
        context.completedActivities = []
        context.completedFlows = []
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
        try {
            List<Task> openAll = engine(ec).taskService.createTaskQuery().processInstanceId(instanceId).list()
            for (Task t : openAll) {
                if (isCandidate(ec, t, ec.user.userId)) return true
            }
        } catch (Throwable ignored) { }
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

    protected static Task resolveEngineTask(ExecutionContext ec, Task task) {
        if (!task.parentTaskId) return task
        Task parent = engine(ec).taskService.createTaskQuery().taskId(task.parentTaskId).singleResult()
        if (parent == null) {
            addError(ec, "Parent task \${taskId} not found", [taskId: task.parentTaskId])
            return null
        }
        return parent
    }

    protected static void deleteAddSignSiblings(ExecutionContext ec, Task engineTask, String keepTaskId) {
        if (!engineTask?.id) return
        try {
            List<Task> subs = engine(ec).taskService.getSubTasks(engineTask.id)
            for (Task sub : subs) {
                if (keepTaskId && sub.id == keepTaskId) continue
                try { engine(ec).taskService.deleteTask(sub.id, true) } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    /** Flowable FieldExtensionParser only attaches fields on ServiceTask; UserTask fields stay as extension elements or raw XML. */
    protected static String userTaskField(ExecutionContext ec, Task task, String fieldName) {
        if (!task?.processDefinitionId || !task.taskDefinitionKey || !fieldName) return null
        try {
            Object val = task.getTaskLocalVariables()?.get(fieldName)
            if (val) return val.toString()
        } catch (Throwable ignored) { }
        try {
            BpmnModel model = engine(ec).repositoryService.getBpmnModel(task.processDefinitionId)
            def el = model?.getFlowElement(task.taskDefinitionKey)
            Map exts = el?.extensionElements
            if (exts) {
                def list = exts.get("field") ?: exts.get("{http://flowable.org/bpmn}field")
                for (def ext : (list ?: [])) {
                    String n = ext.getAttributeValue(null, "name") ?:
                            ext.getAttributeValue("http://flowable.org/bpmn", "name")
                    if (!fieldName.equals(n)) continue
                    String sv = ext.getAttributeValue(null, "stringValue")
                    if (sv) return sv
                    Map kids = ext.childElements
                    def strings = kids?.get("string") ?: kids?.get("{http://flowable.org/bpmn}string")
                    if (strings) {
                        String text = strings[0].elementText
                        if (text) return text.trim()
                    }
                    String expr = ext.getAttributeValue(null, "expression")
                    if (expr) return expr
                }
            }
        } catch (Throwable ignored) { }
        return readUserTaskFieldFromXml(ec, task, fieldName)
    }

    protected static String readUserTaskFieldFromXml(ExecutionContext ec, Task task, String fieldName) {
        try {
            ProcessDefinition defn = engine(ec).repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(task.processDefinitionId).singleResult()
            if (defn == null || !defn.deploymentId || !defn.resourceName) return null
            def stream = engine(ec).repositoryService.getResourceAsStream(defn.deploymentId, defn.resourceName)
            if (stream == null) return null
            String xml = stream.getText("UTF-8")
            def parsed = new XmlSlurper().parseText(xml)
            def ut = parsed.depthFirst().find { localName(it) == "userTask" && it.'@id'.text() == task.taskDefinitionKey }
            if (ut == null) return null
            def field = ut.extensionElements.'*'.find { localName(it) == "field" && it.'@name'.text() == fieldName }
            if (field == null) return null
            String sv = field.'@stringValue'.text()
            if (sv) return sv
            String nested = field.string.text()
            if (nested) return nested.trim()
            String expr = field.'@expression'.text()
            return expr ?: null
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static boolean isAllowAddSign(ExecutionContext ec, Task task) {
        String v = userTaskField(ec, task, "allowAddSign")
        if (!v) return false
        String t = v.trim()
        return "true".equalsIgnoreCase(t) || "Y".equalsIgnoreCase(t)
    }

    protected static int countActiveUserTaskExecutions(ExecutionContext ec, String instanceId) {
        if (!instanceId) return 0
        List<Task> open = engine(ec).taskService.createTaskQuery().processInstanceId(instanceId).list()
        return open.findAll { it.executionId && !it.parentTaskId }.collect { it.executionId }.unique().size()
    }

    protected static List<Map> listVisitedUserTasks(ExecutionContext ec, String instanceId) {
        if (!instanceId) return []
        List<HistoricTaskInstance> hist = engine(ec).historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instanceId).finished().orderByHistoricTaskInstanceEndTime().asc().list()
        Map<String, Map> byKey = new LinkedHashMap<>()
        for (HistoricTaskInstance t : hist) {
            if (!t.taskDefinitionKey || t.parentTaskId) continue
            byKey.put(t.taskDefinitionKey, [activityId: t.taskDefinitionKey, name: t.name, endTime: t.endTime] as Map)
        }
        return new ArrayList(byKey.values())
    }

    protected static void fillVisitAndFlags(ExecutionContext ec, Map context, String instanceId,
                                           String startUserId, String status) {
        List<Map> visited = listVisitedUserTasks(ec, instanceId)
        context.visitedUserTasks = visited
        long finished = engine(ec).historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instanceId).finished().count()
        context.finishedUserTaskCount = finished as Integer
        boolean running = "running".equals(status)
        String userId = ec.user.userId
        context.canWithdraw = running && userId && userId == startUserId && finished == 0
        context.canCancel = running && userId && (ec.user.isInGroup("ADMIN") || userId == startUserId)
    }

    protected static List<String> findCallActivityCycles(ExecutionContext ec, String bpmnXml) {
        List<String> errors = []
        String thisKey = extractProcessKey(bpmnXml)
        if (!thisKey) return errors
        Map<String, Set<String>> graph = new LinkedHashMap<>()
        graph.put(thisKey, extractCalledElements(bpmnXml))
        try {
            noAuthz(ec) {
                ec.entity.find("wf.flowable.WfProcessDefinition").list().each { EntityValue ev ->
                    String key = ev.processDefinitionKey as String
                    if (!key || key == thisKey) return
                    String xml = ev.bpmnXml as String
                    if (xml) graph.put(key, extractCalledElements(xml))
                }
                null
            }
        } catch (Throwable ignored) { }
        Set<String> stack = new LinkedHashSet<>()
        Set<String> seen = new LinkedHashSet<>()
        if (hasCycle(graph, thisKey, stack, seen)) {
            errors.add(ec.l10n.localize("Call Activity cycle detected"))
        }
        return errors
    }

    protected static Set<String> extractCalledElements(String xml) {
        Set<String> calls = new LinkedHashSet<>()
        if (!xml) return calls
        def matcher = (xml =~ /<callActivity[^>]*\scalledElement="([^"]+)"/)
        while (matcher.find()) {
            String v = matcher.group(1)
            if (v && !v.contains('${')) calls.add(v)
        }
        return calls
    }

    protected static boolean hasCycle(Map<String, Set<String>> graph, String node, Set<String> stack, Set<String> seen) {
        if (stack.contains(node)) return true
        if (seen.contains(node)) return false
        stack.add(node)
        for (String next : graph.get(node) ?: []) {
            if (hasCycle(graph, next, stack, seen)) return true
        }
        stack.remove(node)
        seen.add(node)
        return false
    }
}
