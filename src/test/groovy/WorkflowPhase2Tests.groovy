import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class WorkflowPhase2Tests extends Specification {
    @Shared protected final static Logger logger = LoggerFactory.getLogger(WorkflowPhase2Tests.class)
    @Shared ExecutionContext ec
    @Shared long suffix = System.currentTimeMillis()

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        // Services are authorized; entity finds still need this unless CF_ENT seed is loaded.
        ec.artifactExecution.disableAuthz()
    }

    def cleanupSpec() {
        if (ec != null) {
            if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
            try { ec.artifactExecution.enableAuthz() } catch (Throwable ignored) {}
            ec.destroy()
        }
    }

    def setup() {
        ec.message.clearAll()
        if (ec.transaction.isTransactionInPlace()) {
            try { ec.transaction.rollback("leftover test transaction", null) } catch (Throwable ignored) {}
        }
        restoreJohn()
    }

    protected boolean inNewTx(Closure body) {
        boolean began = false
        try {
            if (!ec.transaction.isTransactionInPlace()) began = ec.transaction.begin(null)
            body.call()
            if (began) ec.transaction.commit()
            return true
        } catch (Throwable t) {
            if (began) try { ec.transaction.rollback(true, t.message, t) } catch (Throwable ignored) {}
            throw t
        }
    }

    protected void restoreJohn() {
        if (ec.user.userId != "EX_JOHN_DOE") {
            try { if (ec.user.userId) ec.user.logoutUser() } catch (Throwable ignored) {}
            ec.user.loginUser("john.doe", "moqui")
        }
        try { ec.artifactExecution.disableAuthz() } catch (Throwable ignored) {}
    }

    protected void loginAs(String username) {
        try { if (ec.user.userId) ec.user.logoutUser() } catch (Throwable ignored) {}
        boolean ok = ec.user.loginUser(username, "moqui")
        assert ok
        ec.artifactExecution.disableAuthz()
    }

    protected Map callWf(String verbNoun, Map params) {
        return ec.service.sync().name("wf.flowable.WorkflowServices." + verbNoun).parameters(params).call()
    }

    protected String twoTaskXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="first"/>
    <userTask id="first" name="First" flowable:assignee="EX_JOHN_DOE" flowable:formKey="component://collie-wf-demo/screen/RequestManager.xml"/>
    <sequenceFlow id="f2" sourceRef="first" targetRef="second"/>
    <userTask id="second" name="Second" flowable:assignee="EX_JOHN_DOE" flowable:formKey="component://collie-wf-demo/screen/RequestFinance.xml"/>
    <sequenceFlow id="f3" sourceRef="second" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String candidateXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="Review" flowable:candidateGroups="ADMIN" flowable:formKey="component://collie-wf-demo/screen/RequestManager.xml"/>
    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String addSignXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="Review" flowable:assignee="EX_JOHN_DOE" flowable:formKey="component://collie-wf-demo/screen/RequestManager.xml">
      <extensionElements><flowable:field name="allowAddSign"><flowable:string>true</flowable:string></flowable:field></extensionElements>
    </userTask>
    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String initiatorXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="Review" flowable:assignee="initiator" flowable:formKey="component://collie-wf-demo/screen/RequestManager.xml"/>
    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String messageXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <message id="paidMsg" name="wfTestPaid"/>
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="waitPaid"/>
    <intermediateCatchEvent id="waitPaid"><messageEventDefinition messageRef="paidMsg"/></intermediateCatchEvent>
    <sequenceFlow id="f2" sourceRef="waitPaid" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String cycleXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="callSelf"/>
    <callActivity id="callSelf" name="Self" calledElement="${key}"/>
    <sequenceFlow id="f2" sourceRef="callSelf" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String managerXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="Manager review" flowable:assignee="managerOfInitiator" flowable:formKey="component://collie-wf-demo/screen/RequestManager.xml"/>
    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String roleXml(String key, String roleTypeId) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="Role review" flowable:candidateUsers="role:${roleTypeId}" flowable:formKey="component://collie-wf-demo/screen/RequestManager.xml"/>
    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String parallelXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="fork"/>
    <parallelGateway id="fork"/>
    <sequenceFlow id="f2" sourceRef="fork" targetRef="taskA"/>
    <sequenceFlow id="f3" sourceRef="fork" targetRef="taskB"/>
    <userTask id="taskA" name="A" flowable:assignee="EX_JOHN_DOE" flowable:formKey="component://collie-wf-demo/screen/RequestManager.xml"/>
    <userTask id="taskB" name="B" flowable:assignee="EX_JOHN_DOE" flowable:formKey="component://collie-wf-demo/screen/RequestFinance.xml"/>
    <sequenceFlow id="f4" sourceRef="taskA" targetRef="join"/>
    <sequenceFlow id="f5" sourceRef="taskB" targetRef="join"/>
    <parallelGateway id="join"/>
    <sequenceFlow id="f6" sourceRef="join" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String countersignXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="Countersign" flowable:assignee="\${approver}" flowable:formKey="component://collie-wf-demo/screen/RequestManager.xml">
      <multiInstanceLoopCharacteristics isSequential="false" flowable:collection="approverList" flowable:elementVariable="approver">
        <completionCondition>\${nrOfCompletedInstances == nrOfInstances}</completionCondition>
      </multiInstanceLoopCharacteristics>
    </userTask>
    <sequenceFlow id="f2" sourceRef="review" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String signalXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <signal id="goSig" name="wfTestGo"/>
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="waitGo"/>
    <intermediateCatchEvent id="waitGo"><signalEventDefinition signalRef="goSig"/></intermediateCatchEvent>
    <sequenceFlow id="f2" sourceRef="waitGo" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String callChildXml(String key) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="childReview"/>
    <userTask id="childReview" name="Child review" flowable:assignee="EX_JOHN_DOE" flowable:formKey="component://collie-wf-demo/screen/RequestFinance.xml"/>
    <sequenceFlow id="f2" sourceRef="childReview" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected String callParentXml(String key, String childKey) {
        return """<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">
  <process id="${key}" name="${key}" isExecutable="true">
    <startEvent id="start"/>
    <sequenceFlow id="f1" sourceRef="start" targetRef="callChild"/>
    <callActivity id="callChild" name="Call child" calledElement="${childKey}" flowable:inheritBusinessKey="true"/>
    <sequenceFlow id="f2" sourceRef="callChild" targetRef="end"/>
    <endEvent id="end"/>
  </process>
</definitions>"""
    }

    protected void ensureManagerRel() {
        inNewTx {
            def john = ec.entity.find("moqui.security.UserAccount").condition("userId", "EX_JOHN_DOE").one()
            String johnParty = (john?.partyId as String) ?: "EX_JOHN_DOE"
            if (!john?.partyId) {
                john.set("partyId", "EX_JOHN_DOE")
                john.update()
            }
            if (ec.entity.find("mantle.party.Party").condition("partyId", johnParty).one() == null) {
                ec.entity.makeValue("mantle.party.Party").setAll([partyId: johnParty, partyTypeEnumId: "PtyPerson"]).create()
            }
            String relId = "WF_MGR_${suffix}"
            if (ec.entity.find("mantle.party.PartyRelationship").condition("partyRelationshipId", relId).one() == null) {
                ec.entity.makeValue("mantle.party.PartyRelationship").setAll([
                        partyRelationshipId: relId, relationshipTypeEnumId: "PrtManager",
                        fromPartyId: "ORG_ZIZI_JD", toPartyId: johnParty, fromDate: ec.user.nowTimestamp
                ]).create()
            }
        }
    }

    def "ping engine"() {
        when:
        Map out = callWf("ping", [:])
        then:
        !ec.message.hasError()
        out.ok == Boolean.TRUE
    }

    def "create process definition writes draft stub and rejects duplicate key"() {
        given:
        String key = "wfNew${suffix}"
        String name = "New Process ${suffix}"
        when:
        Map out = callWf("create#ProcessDefinition", [processDefinitionKey: key, processName: name, description: "note ${suffix}"])
        then:
        !ec.message.hasError()
        out.statusId == "WfDefDraft"
        out.processDefinitionKey == key
        when:
        def ev = ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
        then:
        ev != null
        ev.statusId == "WfDefDraft"
        ev.processName == name
        ev.description == "note ${suffix}"
        ev.deploymentId == null
        (ev.bpmnXml as String).contains("id=\"${key}\"")
        (ev.bpmnXml as String).contains("startEvent")
        (ev.bpmnXml as String).contains("endEvent")
        when:
        ec.message.clearAll()
        callWf("create#ProcessDefinition", [processDefinitionKey: key, processName: name])
        then:
        ec.message.hasError()
        when:
        def afterDup = ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
        def deployed = wf.flowable.WorkflowSupport.engine(ec).repositoryService
                .createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult()
        then:
        afterDup.statusId == "WfDefDraft"
        deployed == null
    }

    def "update process definition meta changes name and remarks only"() {
        given:
        String key = "wfMeta${suffix}"
        callWf("create#ProcessDefinition", [processDefinitionKey: key, processName: "Before ${suffix}", description: "old note"])
        String xmlBefore = ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one().bpmnXml as String
        ec.message.clearAll()
        when:
        Map out = callWf("update#ProcessDefinitionMeta", [processDefinitionKey: key, processName: "After ${suffix}", description: "new note"])
        def ev = ec.entity.find("wf.flowable.WfProcessDefinition").condition("processDefinitionKey", key).one()
        then:
        !ec.message.hasError()
        out.processName == "After ${suffix}"
        out.description == "new note"
        out.statusId == "WfDefDraft"
        ev.processName == "After ${suffix}"
        ev.description == "new note"
        ev.bpmnXml == xmlBefore
        ev.statusId == "WfDefDraft"
    }

    def "start rejects duplicate active link"() {
        given:
        String key = "wfDup${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: candidateXml(key)])
        String bk = "bk-dup-${suffix}"
        when:
        callWf("start#Process", [processDefinitionKey: key, businessKey: bk, entityName: "wf.demo.DemoRequest", pkValue: bk])
        boolean firstOk = !ec.message.hasError()
        String firstErr = ec.message.errorsString
        ec.message.clearAll()
        callWf("start#Process", [processDefinitionKey: key, businessKey: bk, entityName: "wf.demo.DemoRequest", pkValue: bk])
        then:
        firstOk
        firstErr == null || firstErr.isEmpty()
        ec.message.hasError()
    }

    def "unclaimed candidate task cannot complete"() {
        given:
        String key = "wfClaim${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: candidateXml(key)])
        String bk = "bk-claim-${suffix}"
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: bk])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        String taskId = inst.currentTasks[0].taskId
        when:
        callWf("complete#Task", [taskId: taskId, outcome: "approve"])
        then:
        ec.message.hasError()
    }

    def "suspend definition blocks new start"() {
        given:
        String key = "wfSusp${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: candidateXml(key)])
        callWf("suspend#ProcessDefinition", [processDefinitionKey: key])
        when:
        callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-susp-${suffix}"])
        then:
        ec.message.hasError()
        cleanup:
        ec.message.clearAll()
        try { callWf("activate#ProcessDefinition", [processDefinitionKey: key]) } catch (Throwable ignored) {}
    }

    def "withdraw rejected after a user task is completed"() {
        given:
        String key = "wfWd${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: twoTaskXml(key)])
        String bk = "bk-wd-${suffix}"
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: bk])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        callWf("complete#Task", [taskId: inst.currentTasks[0].taskId, outcome: "approve"])
        when:
        ec.message.clearAll()
        callWf("withdraw#Process", [processInstanceId: start.processInstanceId])
        then:
        ec.message.hasError()
    }

    def "initiator token assigns current user"() {
        given:
        String key = "wfInit${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: initiatorXml(key)])
        when:
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-init-${suffix}"])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        then:
        !ec.message.hasError()
        inst.currentTasks[0].assignee == "EX_JOHN_DOE"
    }

    def "addSign keeps original task open"() {
        given:
        String key = "wfAdd${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: addSignXml(key)])
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-add-${suffix}"])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        String taskId = inst.currentTasks[0].taskId
        when:
        callWf("addSign#Task", [taskId: taskId, toUserId: "ORG_ZIZI_JD", comment: "please look"])
        Map after = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        then:
        !ec.message.hasError()
        after.status == "running"
        after.currentTasks.size() >= 2
    }

    def "returnTo moves back to a visited user task"() {
        given:
        String key = "wfRt${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: twoTaskXml(key)])
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-rt-${suffix}"])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        callWf("complete#Task", [taskId: inst.currentTasks[0].taskId, outcome: "approve"])
        Map mid = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        String secondId = mid.currentTasks[0].taskId
        when:
        callWf("returnTo#Task", [taskId: secondId, targetActivityId: "first", comment: "back"])
        Map after = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        then:
        !ec.message.hasError()
        after.currentActivities.contains("first")
    }

    def "message wakes a waiting instance"() {
        given:
        String key = "wfMsg${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: messageXml(key)])
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-msg-${suffix}"])
        when:
        callWf("message#Process", [processInstanceId: start.processInstanceId, messageName: "wfTestPaid"])
        Map after = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        then:
        !ec.message.hasError()
        after.status == "ended"
    }

    def "deploy rejects Call Activity cycle"() {
        given:
        String key = "wfCyc${suffix}"
        when:
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: cycleXml(key)])
        then:
        ec.message.hasError()
    }

    def "addSign child complete advances and appears in completed"() {
        given:
        String key = "wfAddC${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: addSignXml(key)])
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-addc-${suffix}"])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        String parentId = inst.currentTasks[0].taskId
        callWf("addSign#Task", [taskId: parentId, toUserId: "ORG_ZIZI_JD", comment: "please look"])
        Map afterSign = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        String childId = afterSign.currentTasks.find { it.assignee == "ORG_ZIZI_JD" }?.taskId
        when:
        loginAs("joe.developer")
        callWf("complete#Task", [taskId: childId, outcome: "approve", comment: "signed"])
        Map after = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        Map done = callWf("list#CompletedTasks", [:])
        then:
        childId
        !ec.message.hasError()
        after.status == "ended"
        done.taskList.any { it.taskId == childId }
        cleanup:
        restoreJohn()
    }

    def "addSign original complete deletes siblings"() {
        given:
        String key = "wfAddP${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: addSignXml(key)])
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-addp-${suffix}"])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        String parentId = inst.currentTasks[0].taskId
        callWf("addSign#Task", [taskId: parentId, toUserId: "ORG_ZIZI_JD"])
        when:
        callWf("complete#Task", [taskId: parentId, outcome: "approve"])
        Map after = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        then:
        !ec.message.hasError()
        after.status == "ended"
        after.currentTasks == null || after.currentTasks.isEmpty()
    }

    def "addSign rejected on parallel and countersign"() {
        given:
        String parKey = "wfPar${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: parKey, bpmnXml: parallelXml(parKey)])
        Map parStart = callWf("start#Process", [processDefinitionKey: parKey, businessKey: "bk-par-${suffix}"])
        Map parInst = callWf("get#ProcessInstance", [processInstanceId: parStart.processInstanceId])
        String parTask = parInst.currentTasks[0].taskId
        callWf("addSign#Task", [taskId: parTask, toUserId: "ORG_ZIZI_JD"])
        boolean parRejected = ec.message.hasError()
        ec.message.clearAll()
        String csKey = "wfCs${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: csKey, bpmnXml: countersignXml(csKey)])
        Map csStart = callWf("start#Process", [processDefinitionKey: csKey, businessKey: "bk-cs-${suffix}",
                variables: [approverList: ["EX_JOHN_DOE"]]])
        Map csInst = callWf("get#ProcessInstance", [processInstanceId: csStart.processInstanceId])
        when:
        callWf("addSign#Task", [taskId: csInst.currentTasks[0].taskId, toUserId: "ORG_ZIZI_JD"])
        then:
        parRejected
        ec.message.hasError()
    }

    def "managerOfInitiator and role tokens resolve"() {
        given:
        ensureManagerRel()
        String mgrKey = "wfMgr${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: mgrKey, bpmnXml: managerXml(mgrKey)])
        Map mgrStart = callWf("start#Process", [processDefinitionKey: mgrKey, businessKey: "bk-mgr-${suffix}"])
        Map mgrInst = callWf("get#ProcessInstance", [processInstanceId: mgrStart.processInstanceId])
        String roleId = "WfTok${suffix.toString().substring(suffix.toString().length() - 8)}"
        inNewTx {
            if (ec.entity.find("mantle.party.RoleType").condition("roleTypeId", roleId).one() == null) {
                ec.entity.makeValue("mantle.party.RoleType").setAll([roleTypeId: roleId, description: "WF test role"]).create()
            }
            if (ec.entity.find("mantle.party.PartyRole").condition("partyId", "ORG_ZIZI_JD").condition("roleTypeId", roleId).one() == null) {
                ec.entity.makeValue("mantle.party.PartyRole").setAll([partyId: "ORG_ZIZI_JD", roleTypeId: roleId]).create()
            }
        }
        String roleKey = "wfRole${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: roleKey, bpmnXml: roleXml(roleKey, roleId)])
        Map roleStart = callWf("start#Process", [processDefinitionKey: roleKey, businessKey: "bk-role-${suffix}"])
        Map roleInst = callWf("get#ProcessInstance", [processInstanceId: roleStart.processInstanceId])
        String roleTaskId = roleInst.currentTasks[0].taskId
        def pe = ec.getTool("Flowable", org.flowable.engine.ProcessEngine.class)
        def links = pe.taskService.getIdentityLinksForTask(roleTaskId)
        when:
        boolean resolved = links.any { it.userId == "ORG_ZIZI_JD" }
        then:
        mgrInst.currentTasks[0].assignee == "ORG_ZIZI_JD"
        roleInst.currentTasks[0].assignee == null
        resolved
        cleanup:
        restoreJohn()
    }

    def "returnTo rejects unvisited and parallel"() {
        given:
        String key = "wfRtB${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: twoTaskXml(key)])
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-rtb-${suffix}"])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        callWf("returnTo#Task", [taskId: inst.currentTasks[0].taskId, targetActivityId: "second"])
        boolean unvisited = ec.message.hasError()
        ec.message.clearAll()
        String parKey = "wfRtP${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: parKey, bpmnXml: parallelXml(parKey)])
        Map parStart = callWf("start#Process", [processDefinitionKey: parKey, businessKey: "bk-rtp-${suffix}"])
        Map parInst = callWf("get#ProcessInstance", [processInstanceId: parStart.processInstanceId])
        when:
        callWf("returnTo#Task", [taskId: parInst.currentTasks[0].taskId, targetActivityId: "taskA"])
        then:
        unvisited
        ec.message.hasError()
    }

    def "returnTo writes comment and not gateway outcome"() {
        given:
        String key = "wfRtC${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: twoTaskXml(key)])
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-rtc-${suffix}"])
        Map inst = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        callWf("complete#Task", [taskId: inst.currentTasks[0].taskId, outcome: "approve"])
        Map mid = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        when:
        callWf("returnTo#Task", [taskId: mid.currentTasks[0].taskId, targetActivityId: "first", comment: "back"])
        Map comments = callWf("list#TaskComments", [processInstanceId: start.processInstanceId])
        def pe = ec.getTool("Flowable", org.flowable.engine.ProcessEngine.class)
        def vars = pe.runtimeService.getVariables(start.processInstanceId)
        then:
        !ec.message.hasError()
        comments.commentList.any { it.outcome == "returnTo" }
        vars?.outcome != "returnTo"
    }

    def "signal wakes a waiting instance"() {
        given:
        String key = "wfSig${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: signalXml(key)])
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-sig-${suffix}"])
        when:
        callWf("signal#Process", [processInstanceId: start.processInstanceId, signalName: "wfTestGo"])
        Map after = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        then:
        !ec.message.hasError()
        after.status == "ended"
    }

    def "call activity inherits businessKey and parent continues"() {
        given:
        String childKey = "wfCh${suffix}"
        String parentKey = "wfPa${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: childKey, bpmnXml: callChildXml(childKey)])
        callWf("deploy#ProcessDefinition", [processDefinitionKey: parentKey, bpmnXml: callParentXml(parentKey, childKey)])
        String bk = "bk-call-${suffix}"
        Map start = callWf("start#Process", [processDefinitionKey: parentKey, businessKey: bk,
                entityName: "wf.demo.DemoRequest", pkValue: bk])
        def childLink = ec.entity.find("wf.flowable.WfProcessLink")
                .condition("pkValue", bk).condition("processDefinitionKey", childKey).one()
        assert childLink != null : "child WfProcessLink missing for ${childKey} pk=${bk}"
        Map childInst = callWf("get#ProcessInstance", [processInstanceId: childLink.processInstanceId])
        when:
        callWf("complete#Task", [taskId: childInst.currentTasks[0].taskId, outcome: "approve"])
        Map parentAfter = callWf("get#ProcessInstance", [processInstanceId: start.processInstanceId])
        then:
        childLink != null
        childInst.businessKey == bk
        !ec.message.hasError()
        parentAfter.status == "ended"
    }

    def "initiator token notifies real userId on InstanceDetail"() {
        given:
        String key = "wfNtf${suffix}"
        callWf("deploy#ProcessDefinition", [processDefinitionKey: key, bpmnXml: initiatorXml(key)])
        when:
        Map start = callWf("start#Process", [processDefinitionKey: key, businessKey: "bk-ntf-${suffix}"])
        def notes = ec.entity.find("moqui.security.user.NotificationMessageByUser")
                .condition("userId", "EX_JOHN_DOE").condition("topic", "WorkflowTask")
                .orderBy("-sentDate").limit(20).list()
        def hit = notes.find { String json = it.messageJson as String
            json && json.contains(start.processInstanceId as String) }
        then:
        !ec.message.hasError()
        hit != null
        String link = hit.linkText as String
        link.contains("/qapps/collie-flowable/InstanceDetail")
        link.contains("taskId=")
        !link.contains("/TaskList?")
    }

    def "filterSortPaginate filters contains, sorts, and paginates"() {
        given:
        ["taskListCount", "taskListPageIndex", "taskListPageSize", "taskListPageMaxIndex",
         "taskListPageRangeLow", "taskListPageRangeHigh", "taskListAlreadyPaginated",
         "pageNoLimit", "orderByField", "businessKey", "businessKey_op"].each { ec.context.remove(it) }
        List rows = [
                [name: "Alpha Task", businessKey: "bk-1"],
                [name: "Beta Other", businessKey: "bk-2"],
                [name: "alpha extra", businessKey: "bk-3"]
        ]
        ec.context.taskList = new ArrayList(rows)
        ec.context.name = "alpha"
        ec.context.name_op = "contains"
        ec.context.name_ic = "Y"
        ec.context.pageIndex = "0"
        ec.context.pageSize = "1"
        when:
        wf.flowable.WorkflowSupport.filterSortPaginate(ec, "taskList", ["name", "businessKey"])
        then:
        ec.context.taskListCount == 2
        ec.context.taskList.size() == 1
        ((Map) ec.context.taskList[0]).name.toString().toLowerCase().contains("alpha")
        when:
        ec.context.taskList = new ArrayList(rows)
        ec.context.pageNoLimit = "true"
        ec.context.orderByField = "-name"
        wf.flowable.WorkflowSupport.filterSortPaginate(ec, "taskList", ["name", "businessKey"])
        then:
        ec.context.taskList.size() == 2
        ec.context.taskListAlreadyPaginated == true
        ((Map) ec.context.taskList[0]).name == "alpha extra"
    }
}
