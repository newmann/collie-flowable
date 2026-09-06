/* collie-flowable designer: bpmn-js + Moqui property drawer */
(function (global) {
  var FLOWABLE_NS = 'http://flowable.org/bpmn';
  var EMPTY_XML = '<?xml version="1.0" encoding="UTF-8"?>' +
    '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="http://moqui.org/flowable">' +
    '<process id="newProcess" name="New Process" isExecutable="true">' +
    '<startEvent id="start" name="Start"/>' +
    '<sequenceFlow id="toEnd" sourceRef="start" targetRef="end"/>' +
    '<endEvent id="end" name="End"/>' +
    '</process></definitions>';

  function byId(id) { return document.getElementById(id); }
  function formField(formName, name) {
    return document.querySelector('form[name="' + formName + '"] [name="' + name + '"]');
  }
  function metaKeyEl() { return byId('wf-meta-key') || formField('DesignerMeta', 'processDefinitionKey'); }
  function metaNameEl() { return byId('wf-meta-name') || formField('DesignerMeta', 'processName'); }
  function val(id) {
    var el = byId(id);
    if (!el && id === 'wf-meta-key') el = metaKeyEl();
    if (!el && id === 'wf-meta-name') el = metaNameEl();
    return el ? el.value : '';
  }
  function setVal(id, v) {
    var el = byId(id);
    if (!el && id === 'wf-meta-key') el = metaKeyEl();
    if (!el && id === 'wf-meta-name') el = metaNameEl();
    if (el) el.value = v == null ? '' : v;
  }
  function show(id, on) { var el = byId(id); if (el) el.style.display = on ? '' : 'none'; }
  function bo(el) { return el && (el.businessObject || el); }
  function typeOf(el) {
    var t = el && (el.type || (el.businessObject && el.businessObject.$type));
    return t ? String(t).replace(/^bpmn:/, '') : '';
  }
  function is(el, name) { return typeOf(el) === name; }

  function getField(business, name) {
    var ext = business && business.extensionElements;
    var values = ext && ext.values ? ext.values : [];
    for (var i = 0; i < values.length; i++) {
      var n = values[i];
      if ((n.$type === 'flowable:Field' || (n.name && n.$type && String(n.$type).indexOf('Field') >= 0)) && n.name === name) {
        return (n.string && (n.string.body || n.string)) || n.stringValue || '';
      }
    }
    return '';
  }

  function setField(modeler, element, name, value) {
    var modeling = modeler.get('modeling');
    var moddle = modeler.get('moddle');
    var business = bo(element);
    var ext = business.extensionElements;
    if (!ext) {
      ext = moddle.create('bpmn:ExtensionElements', { values: [] });
      modeling.updateModdleProperties(element, business, { extensionElements: ext });
      ext = business.extensionElements;
    }
    var values = (ext.values || []).slice();
    var found = null;
    for (var i = 0; i < values.length; i++) {
      if (values[i].name === name) { found = values[i]; break; }
    }
    if (!value) {
      if (found) {
        values = values.filter(function (v) { return v !== found; });
        modeling.updateModdleProperties(element, ext, { values: values });
      }
      return;
    }
    if (!found) {
      found = moddle.create('flowable:Field', { name: name });
      values.push(found);
      modeling.updateModdleProperties(element, ext, { values: values });
    }
    found.stringValue = value;
    try { found.string = value; } catch (ignored) {}
  }

  function flowAttr(business, name) {
    if (!business) return '';
    return business.get ? (business.get(name) || business.get('flowable:' + name) || '') : (business[name] || '');
  }

  function setFlowProps(modeler, element, props) {
    modeler.get('modeling').updateProperties(element, props);
  }

  function readCondition(element) {
    var business = bo(element);
    var expr = business && business.conditionExpression;
    if (!expr) return '';
    return expr.body || expr.get && expr.get('body') || '';
  }

  function setCondition(modeler, element, text) {
    var modeling = modeler.get('modeling');
    var moddle = modeler.get('moddle');
    var business = bo(element);
    if (!text) {
      modeling.updateProperties(element, { conditionExpression: undefined });
      return;
    }
    var expr = moddle.create('bpmn:FormalExpression', { body: text });
    modeling.updateModdleProperties(element, business, { conditionExpression: expr });
  }

  function readTimer(element) {
    var business = bo(element);
    var defs = business && business.eventDefinitions;
    if (!defs || !defs.length) return '';
    var timer = defs[0];
    var td = timer.timeDuration;
    if (!td) return '';
    return td.body || td.get && td.get('body') || String(td);
  }

  function setTimer(modeler, element, duration) {
    var modeling = modeler.get('modeling');
    var moddle = modeler.get('moddle');
    var business = bo(element);
    var defs = business.eventDefinitions;
    if (!defs || !defs.length) return;
    var timer = defs[0];
    if (!duration) {
      modeling.updateModdleProperties(element, timer, { timeDuration: undefined });
      return;
    }
    var expr = moddle.create('bpmn:FormalExpression', { body: duration });
    modeling.updateModdleProperties(element, timer, { timeDuration: expr });
  }

  function readLoop(element) {
    var business = bo(element);
    var loop = business && business.loopCharacteristics;
    if (!loop) return { collection: '', completionCondition: '', elementVariable: '' };
    var cond = loop.completionCondition;
    return {
      collection: flowAttr(loop, 'collection') || loop.collection || '',
      elementVariable: flowAttr(loop, 'elementVariable') || loop.elementVariable || '',
      completionCondition: cond ? (cond.body || '') : ''
    };
  }

  function setLoop(modeler, element, collection, completionCondition, elementVariable) {
    var modeling = modeler.get('modeling');
    var moddle = modeler.get('moddle');
    if (!collection && !completionCondition) {
      modeling.updateProperties(element, { loopCharacteristics: undefined });
      return;
    }
    var props = { isSequential: false };
    if (collection) props.collection = collection;
    if (elementVariable) props.elementVariable = elementVariable;
    var loop = moddle.create('bpmn:MultiInstanceLoopCharacteristics', props);
    if (completionCondition) {
      loop.completionCondition = moddle.create('bpmn:FormalExpression', { body: completionCondition });
    }
    modeling.updateProperties(element, { loopCharacteristics: loop });
  }

  function lookup(url, body, cb) {
    var tokenEl = document.querySelector('input[name="moquiSessionToken"]');
    var headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (tokenEl && tokenEl.value) headers['moquiSessionToken'] = tokenEl.value;
    fetch(url, { method: 'POST', credentials: 'same-origin', headers: headers, body: JSON.stringify(body || {}) })
      .then(function (r) { return r.json(); })
      .then(function (j) { cb(null, j); })
      .catch(function (e) { cb(e); });
  }

  function fillDatalist(listId, items) {
    var list = byId(listId);
    if (!list) return;
    list.innerHTML = '';
    (items || []).forEach(function (item) {
      var opt = document.createElement('option');
      opt.value = typeof item === 'string' ? item : (item.userId || item.userGroupId || item);
      if (item && item.userFullName) opt.label = item.userFullName;
      if (item && item.description) opt.label = item.description;
      list.appendChild(opt);
    });
  }

  function bindLookups(base) {
    function wire(inputId, listId, path, key) {
      var input = byId(inputId);
      if (!input || input.getAttribute('data-wf-lookup') === '1') return;
      input.setAttribute('data-wf-lookup', '1');
      var timer = null;
      input.addEventListener('input', function () {
        clearTimeout(timer);
        timer = setTimeout(function () {
          lookup(base + path, { query: input.value }, function (err, data) {
            if (err || !data) return;
            fillDatalist(listId, data[key] || []);
          });
        }, 250);
      });
    }
    wire('wf-prop-serviceName', 'wf-dl-services', '/listServiceNames', 'serviceNameList');
    wire('wf-prop-formKey', 'wf-dl-screens', '/listScreenLocations', 'screenLocationList');
    wire('wf-prop-assignee', 'wf-dl-users', '/listUsers', 'userList');
    wire('wf-prop-candidateUsers', 'wf-dl-users', '/listUsers', 'userList');
    wire('wf-prop-candidateGroups', 'wf-dl-groups', '/listUserGroups', 'userGroupList');
    wire('wf-prop-onApprove', 'wf-dl-services', '/listServiceNames', 'serviceNameList');
    wire('wf-prop-onReject', 'wf-dl-services', '/listServiceNames', 'serviceNameList');
    wire('wf-prop-onReturn', 'wf-dl-services', '/listServiceNames', 'serviceNameList');
  }

  function syncProcessMeta(modeler) {
    var registry = modeler.get('elementRegistry');
    var processEl = registry.find(function (e) { return is(e, 'Process'); });
    if (!processEl) return;
    var key = val('wf-meta-key');
    var name = val('wf-meta-name');
    var props = { isExecutable: true };
    if (name) props.name = name;
    var keyEl = metaKeyEl();
    if (key && keyEl && !keyEl.readOnly) props.id = key;
    setFlowProps(modeler, processEl, props);
  }

  function applyPanel(modeler, element) {
    var business = bo(element);
    var t = typeOf(element);
    setVal('wf-prop-type', t || '');
    setVal('wf-prop-id', business && business.id || '');
    setVal('wf-prop-name', business && business.name || '');
    show('wf-panel-user', t === 'UserTask');
    show('wf-panel-service', t === 'ServiceTask');
    show('wf-panel-flow', t === 'SequenceFlow');
    show('wf-panel-timer', t.indexOf('Event') >= 0 && readTimer(element) !== undefined && business && business.eventDefinitions);
    show('wf-panel-loop', t === 'UserTask'); // collection / completion for 会签
    if (t === 'UserTask') {
      setVal('wf-prop-formKey', flowAttr(business, 'formKey'));
      setVal('wf-prop-assignee', flowAttr(business, 'assignee'));
      setVal('wf-prop-candidateUsers', flowAttr(business, 'candidateUsers'));
      setVal('wf-prop-candidateGroups', flowAttr(business, 'candidateGroups'));
      setVal('wf-prop-onApprove', getField(business, 'onApproveService'));
      setVal('wf-prop-onReject', getField(business, 'onRejectService'));
      setVal('wf-prop-onReturn', getField(business, 'onReturnService'));
      var loop = readLoop(element);
      setVal('wf-prop-collection', loop.collection);
      setVal('wf-prop-completion', loop.completionCondition);
      setVal('wf-prop-elementVar', loop.elementVariable);
    }
    if (t === 'ServiceTask') {
      setVal('wf-prop-serviceName', getField(business, 'serviceName'));
    }
    if (t === 'SequenceFlow') setVal('wf-prop-condition', readCondition(element));
    if (business && business.eventDefinitions) setVal('wf-prop-timer', readTimer(element));
    if (t === 'Process') {
      setVal('wf-meta-name', business.name || '');
      var keyEl = metaKeyEl();
      if (keyEl && !keyEl.readOnly) setVal('wf-meta-key', business.id || '');
    }
  }

  function writePanel(modeler, element) {
    if (!element) return;
    var t = typeOf(element);
    var props = { name: val('wf-prop-name') };
    if (t === 'UserTask') {
      props.formKey = val('wf-prop-formKey');
      props.assignee = val('wf-prop-assignee');
      props.candidateUsers = val('wf-prop-candidateUsers');
      props.candidateGroups = val('wf-prop-candidateGroups');
      setFlowProps(modeler, element, props);
      setField(modeler, element, 'onApproveService', val('wf-prop-onApprove'));
      setField(modeler, element, 'onRejectService', val('wf-prop-onReject'));
      setField(modeler, element, 'onReturnService', val('wf-prop-onReturn'));
      setLoop(modeler, element, val('wf-prop-collection'), val('wf-prop-completion'), val('wf-prop-elementVar'));
      return;
    }
    if (t === 'ServiceTask') {
      props.delegateExpression = '${moquiServiceDelegate}';
      props.async = true;
      setFlowProps(modeler, element, props);
      setField(modeler, element, 'serviceName', val('wf-prop-serviceName'));
      return;
    }
    if (t === 'SequenceFlow') {
      setFlowProps(modeler, element, props);
      setCondition(modeler, element, val('wf-prop-condition'));
      return;
    }
    if (businessHasTimer(element)) {
      setFlowProps(modeler, element, props);
      setTimer(modeler, element, val('wf-prop-timer'));
      return;
    }
    setFlowProps(modeler, element, props);
  }

  function businessHasTimer(element) {
    var business = bo(element);
    return !!(business && business.eventDefinitions && business.eventDefinitions.length);
  }

  function hookForms(modeler) {
    function beforeSubmit(form) {
      return function (ev) {
        ev.preventDefault();
        syncProcessMeta(modeler);
        modeler.saveXML({ format: true }).then(function (r) {
          var xmlField = form.querySelector('textarea[name="bpmnXml"], input[name="bpmnXml"]');
          if (xmlField) xmlField.value = r.xml;
          var keyField = form.querySelector('input[name="processDefinitionKey"]');
          var nameField = form.querySelector('input[name="processName"]');
          if (keyField && val('wf-meta-key')) keyField.value = val('wf-meta-key');
          if (nameField && val('wf-meta-name')) nameField.value = val('wf-meta-name');
          form.submit();
        }).catch(function (err) { console.error(err); alert('Cannot serialize BPMN: ' + err); });
      };
    }
    ['DesignerMeta', 'DesignerSave', 'DesignerDeploy'].forEach(function (name) {
      var form = document.querySelector('form[name="' + name + '"]');
      if (form && form.getAttribute('data-wf-hook') !== '1') {
        form.setAttribute('data-wf-hook', '1');
        form.addEventListener('submit', beforeSubmit(form));
      }
    });
  }

  function initDesigner(opts) {
    opts = opts || {};
    if (typeof BpmnJS === 'undefined') { setTimeout(function () { initDesigner(opts); }, 80); return; }
    var el = byId(opts.canvasId || 'wf-bpmn-canvas');
    if (!el) { setTimeout(function () { initDesigner(opts); }, 80); return; }
    if (el.getAttribute('data-wf-ready') === '1') return;
    el.setAttribute('data-wf-ready', '1');

    function start(moddleJson) {
      var modeler = new BpmnJS({
        container: el,
        moddleExtensions: moddleJson ? { flowable: moddleJson } : {}
      });
      var xml = (opts.existingXml || val('wf-existing-xml') || '').trim();
      if (!xml) {
        var hiddenXml = document.querySelector('form[name="DesignerMeta"] [name="bpmnXml"]');
        if (hiddenXml && hiddenXml.value) xml = hiddenXml.value.trim();
      }
      var load = xml && xml.indexOf('<definitions') >= 0 ? modeler.importXML(xml) : modeler.createDiagram();
      load.catch(function () { return modeler.createDiagram(); }).catch(function (err) { console.error(err); });
      global.wfBpmnModeler = modeler;
      global.wfBpmnSaveXml = function (cb) {
        modeler.saveXML({ format: true }).then(function (r) { cb(null, r.xml); }).catch(cb);
      };

      var selected = null;
      modeler.on('selection.changed', function (e) {
        selected = (e.newSelection && e.newSelection[0]) || null;
        if (selected) applyPanel(modeler, selected);
      });
      var panel = byId('wf-prop-panel');
      if (panel && panel.getAttribute('data-wf-bound') !== '1') {
        panel.setAttribute('data-wf-bound', '1');
        panel.addEventListener('change', function () { if (selected) writePanel(modeler, selected); });
        panel.addEventListener('blur', function () { if (selected) writePanel(modeler, selected); }, true);
      }
      hookForms(modeler);
      bindLookups(opts.lookupBase || (global.location && location.pathname) || '');
      if (opts.lockKey || val('wf-existing-xml')) {
        var keyInput = metaKeyEl();
        if (keyInput && keyInput.value) keyInput.readOnly = true;
      }
    }

    if (opts.moddle) { start(opts.moddle); return; }
    var moddleUrl = opts.moddleUrl;
    if (!moddleUrl) { start(null); return; }
    fetch(moddleUrl, { credentials: 'same-origin' }).then(function (r) { return r.json(); })
      .then(function (j) { start(j); })
      .catch(function () { start(null); });
  }

  global.wfInitDesigner = initDesigner;
})(window);
