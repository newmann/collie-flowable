package wf.flowable

import org.flowable.bpmn.model.BpmnModel
import org.flowable.bpmn.model.UserTask as BpmnUserTask
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType
import org.flowable.common.engine.api.delegate.event.FlowableEvent
import org.flowable.common.engine.api.delegate.event.FlowableEventListener
import org.flowable.engine.ProcessEngine
import org.flowable.engine.history.HistoricProcessInstance
import org.flowable.task.api.history.HistoricTaskInstance
import org.flowable.engine.runtime.ProcessInstance
import org.flowable.identitylink.api.IdentityLinkType
import org.flowable.task.api.Task
import org.flowable.task.service.impl.persistence.entity.TaskEntity
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Resolve CF-80 assignment tokens when a UserTask is created. */
class MoquiAssignmentListener implements FlowableEventListener {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiAssignmentListener.class)
    static final String TOKEN_INITIATOR = "initiator"
    static final String TOKEN_MGR_INITIATOR = "managerOfInitiator"
    static final String TOKEN_MGR_ASSIGNEE = "managerOfAssignee"

    @Override
    void onEvent(FlowableEvent event) {
        if (event.type != FlowableEngineEventType.TASK_CREATED) return
        if (!(event instanceof FlowableEngineEntityEvent)) return
        Object entity = ((FlowableEngineEntityEvent) event).entity
        if (!(entity instanceof TaskEntity)) return
        TaskEntity task = (TaskEntity) entity
        ExecutionContext ec = Moqui.getExecutionContext()
        if (ec == null) return
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ProcessEngine pe = (ProcessEngine) ec.getTool("Flowable", ProcessEngine.class)
            String initiator = resolveInitiator(ec, pe, task.processInstanceId)
            String assignee = task.assignee
            if (assignee && isToken(assignee)) {
                String resolved = resolveToken(ec, pe, task, assignee.trim(), initiator)
                if (resolved) {
                    task.setAssignee(resolved)
                    pe.taskService.setAssignee(task.id, resolved)
                } else logger.warn("Could not resolve assignee token ${assignee} for task ${task.id}")
            }
            List<String> candidateUsers = splitCandidates(pe, task)
            boolean replacedCandidates = false
            List<String> newUsers = new ArrayList<>()
            for (String raw : candidateUsers) {
                if (!raw) continue
                if (isToken(raw) || raw.startsWith("role:")) {
                    replacedCandidates = true
                    newUsers.addAll(resolveTokenList(ec, pe, task, raw, initiator))
                } else {
                    newUsers.add(raw)
                }
            }
            if (replacedCandidates) {
                clearCandidateUsers(pe, task)
                Set<String> unique = new LinkedHashSet<>(newUsers)
                for (String userId : unique) {
                    if (userId) pe.taskService.addCandidateUser(task.id, userId)
                }
            }
        } catch (Throwable t) {
            logger.warn("Assignment token resolve failed for task ${task.id}: ${t.message}", t)
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    static boolean isToken(String value) {
        if (!value) return false
        String v = value.trim()
        return TOKEN_INITIATOR.equals(v) || TOKEN_MGR_INITIATOR.equals(v) || TOKEN_MGR_ASSIGNEE.equals(v) ||
                v.startsWith("role:")
    }

    protected static String resolveToken(ExecutionContext ec, ProcessEngine pe, TaskEntity task, String token, String initiator) {
        List<String> list = resolveTokenList(ec, pe, task, token, initiator)
        return list ? list[0] : null
    }

    protected static List<String> resolveTokenList(ExecutionContext ec, ProcessEngine pe, TaskEntity task,
                                                  String token, String initiator) {
        String v = token?.trim()
        if (!v) return []
        if (TOKEN_INITIATOR.equals(v)) return initiator ? [initiator] : []
        if (TOKEN_MGR_INITIATOR.equals(v)) {
            String mgr = managerOf(ec, initiator)
            return mgr ? [mgr] : []
        }
        if (TOKEN_MGR_ASSIGNEE.equals(v)) {
            String base = previousAssignee(ec, pe, task, initiator)
            String mgr = managerOf(ec, base)
            return mgr ? [mgr] : []
        }
        if (v.startsWith("role:")) {
            return usersForRole(ec, v.substring(5).trim())
        }
        return [v]
    }

    protected static String resolveInitiator(ExecutionContext ec, ProcessEngine pe, String instanceId) {
        if (!instanceId) return null
        try {
            ProcessInstance pi = pe.runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult()
            if (pi?.startUserId) return pi.startUserId
            HistoricProcessInstance h = pe.historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(instanceId).singleResult()
            return h?.startUserId
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static String previousAssignee(ExecutionContext ec, ProcessEngine pe, TaskEntity task, String initiator) {
        try {
            Object var = pe.runtimeService.getVariable(task.processInstanceId, "assigneeUserId")
            if (var) return var.toString()
        } catch (Throwable ignored) { }
        try {
            List<HistoricTaskInstance> hist = pe.historyService.createHistoricTaskInstanceQuery()
                    .processInstanceId(task.processInstanceId).finished()
                    .orderByHistoricTaskInstanceEndTime().desc().list()
            HistoricTaskInstance last = null
            for (HistoricTaskInstance t : hist) {
                if (t.assignee && t.id != task.id) { last = t; break }
            }
            if (last?.assignee) return last.assignee
        } catch (Throwable ignored) { }
        return initiator
    }

    /** PrtManager: fromPartyId = manager, toPartyId = subordinate (mantle convention). */
    protected static String managerOf(ExecutionContext ec, String userId) {
        if (!userId) return null
        def account = ec.entity.find("moqui.security.UserAccount").condition("userId", userId).one()
        String partyId = account?.get("partyId") as String
        if (!partyId) partyId = userId
        def rels = ec.entity.find("mantle.party.PartyRelationship")
                .condition("relationshipTypeEnumId", "PrtManager")
                .condition("toPartyId", partyId)
                .orderBy("-fromDate").list()
        if (rels) rels = rels.filterByDate("fromDate", "thruDate", null)
        def rel = rels ? rels[0] : null
        String managerParty = rel?.fromPartyId as String
        if (!managerParty) return null
        def mgrUser = ec.entity.find("moqui.security.UserAccount").condition("partyId", managerParty).one()
        if (mgrUser?.userId) return mgrUser.userId as String
        def byId = ec.entity.find("moqui.security.UserAccount").condition("userId", managerParty).one()
        return byId?.userId as String
    }

    protected static List<String> usersForRole(ExecutionContext ec, String roleTypeId) {
        if (!roleTypeId) return []
        List<String> userIds = []
        def roles = ec.entity.find("mantle.party.PartyRole").condition("roleTypeId", roleTypeId).list()
        for (def role : roles) {
            String partyId = role.partyId as String
            def acct = ec.entity.find("moqui.security.UserAccount").condition("partyId", partyId).one()
            if (acct?.userId) userIds.add(acct.userId as String)
        }
        return userIds
    }

    protected static List<String> splitCandidates(ProcessEngine pe, TaskEntity task) {
        LinkedHashSet<String> users = new LinkedHashSet<>()
        def links = task.identityLinks
        if (links != null) {
            for (def link : links) {
                if (link.type == IdentityLinkType.CANDIDATE && link.userId) users.add(link.userId as String)
            }
        }
        try {
            def persisted = pe.taskService.getIdentityLinksForTask(task.id)
            for (def link : persisted) {
                if (link.type == IdentityLinkType.CANDIDATE && link.userId) users.add(link.userId as String)
            }
        } catch (Throwable ignored) { }
        try {
            BpmnModel model = pe.repositoryService.getBpmnModel(task.processDefinitionId)
            def el = model?.getFlowElement(task.taskDefinitionKey)
            if (el instanceof BpmnUserTask) {
                BpmnUserTask ut = (BpmnUserTask) el
                for (String u : (ut.candidateUsers ?: [])) {
                    if (!u) continue
                    u.split(",").each { String part -> if (part?.trim()) users.add(part.trim()) }
                }
            }
        } catch (Throwable ignored) { }
        try {
            def defn = pe.repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(task.processDefinitionId).singleResult()
            if (defn?.deploymentId && defn.resourceName) {
                def stream = pe.repositoryService.getResourceAsStream(defn.deploymentId, defn.resourceName)
                if (stream != null) {
                    String xml = stream.getText("UTF-8")
                    def matcher = (xml =~ /<userTask[^>]*\sid="${task.taskDefinitionKey}"[^>]*candidateUsers="([^"]+)"/)
                    if (matcher.find()) matcher.group(1).split(",").each { String part -> if (part?.trim()) users.add(part.trim()) }
                    matcher = (xml =~ /<userTask[^>]*candidateUsers="([^"]+)"[^>]*\sid="${task.taskDefinitionKey}"/)
                    if (matcher.find()) matcher.group(1).split(",").each { String part -> if (part?.trim()) users.add(part.trim()) }
                }
            }
        } catch (Throwable ignored) { }
        return new ArrayList<>(users)
    }

    protected static void clearCandidateUsers(ProcessEngine pe, TaskEntity task) {
        List<Task> found = pe.taskService.createTaskQuery().taskId(task.id).list()
        if (!found) return
        List links = new ArrayList(pe.taskService.getIdentityLinksForTask(task.id) ?: [])
        for (def link : links) {
            if (link.type == IdentityLinkType.CANDIDATE && link.userId) {
                pe.taskService.deleteCandidateUser(task.id, link.userId)
            }
        }
    }

    @Override boolean isFailOnException() { return false }
    @Override boolean isFireOnTransactionLifecycleEvent() { return false }
    @Override String getOnTransaction() { return null }
}
