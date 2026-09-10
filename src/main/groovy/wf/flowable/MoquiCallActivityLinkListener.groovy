package wf.flowable

import groovy.transform.CompileStatic
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType
import org.flowable.common.engine.api.delegate.event.FlowableEvent
import org.flowable.common.engine.api.delegate.event.FlowableEventListener
import org.flowable.engine.ProcessEngine
import org.flowable.engine.delegate.event.FlowableProcessEngineEvent
import org.flowable.engine.runtime.Execution
import org.flowable.engine.runtime.ProcessInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** When a Call Activity starts a child instance, copy WfProcessLink keys from the parent (CF-83). */
@CompileStatic
class MoquiCallActivityLinkListener implements FlowableEventListener {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiCallActivityLinkListener.class)

    @Override
    void onEvent(FlowableEvent event) {
        if (event.type != FlowableEngineEventType.PROCESS_STARTED) return
        if (!(event instanceof FlowableProcessEngineEvent)) return
        String instanceId = ((FlowableProcessEngineEvent) event).processInstanceId
        if (!instanceId) return
        ExecutionContext ec = Moqui.getExecutionContext()
        if (ec == null) return
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ProcessEngine pe = (ProcessEngine) ec.getTool("Flowable", ProcessEngine.class)
            ProcessInstance child = pe.runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instanceId).singleResult()
            if (child == null || !child.superExecutionId) return
            Execution superEx = pe.runtimeService.createExecutionQuery()
                    .executionId(child.superExecutionId).singleResult()
            String parentInstanceId = superEx?.processInstanceId
            if (!parentInstanceId) return
            ProcessInstance parentPi = pe.runtimeService.createProcessInstanceQuery()
                    .processInstanceId(parentInstanceId).singleResult()
            String parentBk = parentPi?.businessKey
            if (parentBk && child.businessKey != parentBk) {
                pe.runtimeService.updateBusinessKey(instanceId, parentBk)
                child = pe.runtimeService.createProcessInstanceQuery()
                        .processInstanceId(instanceId).singleResult() ?: child
            }
            def parentLink = ec.entity.find("wf.flowable.WfProcessLink")
                    .condition("processInstanceId", parentInstanceId)
                    .condition("statusId", "WfLinkActive").one()
            if (parentLink == null) {
                parentLink = ec.entity.find("wf.flowable.WfProcessLink")
                        .condition("processInstanceId", parentInstanceId).orderBy("-linkId").one()
            }
            if (parentLink == null) return
            if (ec.entity.find("wf.flowable.WfProcessLink").condition("processInstanceId", instanceId).count() > 0) return
            def ev = ec.entity.makeValue("wf.flowable.WfProcessLink")
            ev.linkId = ec.entity.sequencedIdPrimary("wf.flowable.WfProcessLink", null, null)
            ev.entityName = parentLink.entityName
            ev.pkValue = parentLink.pkValue
            ev.processInstanceId = instanceId
            ev.processDefinitionKey = child.processDefinitionKey
            ev.statusId = "WfLinkActive"
            ev.create()
        } catch (Throwable t) {
            logger.warn("Could not write child WfProcessLink for ${instanceId}: ${t.message}")
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @Override boolean isFailOnException() { return false }
    @Override boolean isFireOnTransactionLifecycleEvent() { return false }
    @Override String getOnTransaction() { return null }
}
