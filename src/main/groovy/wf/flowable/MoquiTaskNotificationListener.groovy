package wf.flowable

import groovy.transform.CompileStatic
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType
import org.flowable.common.engine.api.delegate.event.FlowableEvent
import org.flowable.common.engine.api.delegate.event.FlowableEventListener
import org.flowable.engine.ProcessEngine
import org.flowable.engine.runtime.ProcessInstance
import org.flowable.identitylink.api.IdentityLinkType
import org.flowable.task.service.impl.persistence.entity.TaskEntity
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.context.NotificationMessage
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** UserTask created → NotificationTopic WorkflowTask (CF-36). */
@CompileStatic
class MoquiTaskNotificationListener implements FlowableEventListener {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiTaskNotificationListener.class)

    @Override
    void onEvent(FlowableEvent event) {
        if (event.type != FlowableEngineEventType.TASK_CREATED) return
        if (!(event instanceof FlowableEngineEntityEvent)) return
        Object entity = ((FlowableEngineEntityEvent) event).entity
        if (!(entity instanceof TaskEntity)) return
        TaskEntity task = (TaskEntity) entity
        ExecutionContext ec = Moqui.getExecutionContext()
        if (ec == null) return
        try {
            String businessKey = resolveBusinessKey(ec, task.processInstanceId)
            Map<String, Object> payload = [taskId: task.id, taskName: task.name,
                    processInstanceId: task.processInstanceId, businessKey: businessKey] as Map<String, Object>
            String assignee = task.assignee
            if (assignee) {
                sendOne(ec, task, payload, assignee, null)
                return
            }
            Set<String> users = new LinkedHashSet<>()
            Set<String> groups = new LinkedHashSet<>()
            def links = task.identityLinks
            if (links != null) {
                for (def link : links) {
                    if (link.type != IdentityLinkType.CANDIDATE) continue
                    if (link.userId) users.add(link.userId as String)
                    if (link.groupId) groups.add(link.groupId as String)
                }
            }
            if (!users && !groups) groups.add("ADMIN")
            for (String userId : users) sendOne(ec, task, payload, userId, null)
            for (String groupId : groups) sendOne(ec, task, payload, null, groupId)
        } catch (Throwable t) {
            logger.warn("Failed to send WorkflowTask notification for task ${task.id}: ${t.message}")
        }
    }

    protected static String resolveBusinessKey(ExecutionContext ec, String instanceId) {
        if (!instanceId) return null
        try {
            ProcessEngine pe = (ProcessEngine) ec.getTool("Flowable", ProcessEngine.class)
            ProcessInstance pi = pe.runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult()
            return pi?.businessKey
        } catch (Throwable ignored) {
            return null
        }
    }

    protected static void sendOne(ExecutionContext ec, TaskEntity task, Map<String, Object> payload,
                                 String userId, String groupId) {
        NotificationMessage nm = ec.makeNotificationMessage()
        nm.topic("WorkflowTask")
        nm.type(NotificationMessage.NotificationType.info)
        nm.title("Workflow task: ${task.name ?: task.id}")
        nm.link("/qapps/collie-flowable/TaskList?taskId=${task.id}")
        nm.message(payload)
        if (userId) nm.userId(userId)
        if (groupId) nm.userGroupId(groupId)
        nm.send(true)
    }

    @Override boolean isFailOnException() { return false }
    @Override boolean isFireOnTransactionLifecycleEvent() { return false }
    @Override String getOnTransaction() { return null }
}
