/* collie-flowable designer: bpmn-js + Moqui property drawer */
(function (global) {
  var FLOWABLE_NS = 'http://flowable.org/bpmn';
  var DEFAULT_MODELER_URL = '/apps/collie-flowable/Asset/get?file=bpmn-modeler.production.min.js';
  var ASSIGNEE_TOKENS = [
    { value: 'initiator', label: 'initiator' },
    { value: 'managerOfInitiator', label: 'managerOfInitiator' },
    { value: 'managerOfAssignee', label: 'managerOfAssignee' },
    { value: 'role:', label: 'role:{roleTypeId}' }
  ];

  function xmlAttr(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/"/g, '&quot;')
      .replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function blankXml(key, name) {
    var xmlKey = xmlAttr(key || 'newProcess') || 'newProcess';
    var xmlName = xmlAttr(name || 'New Process');
    return '<?xml version="1.0" encoding="UTF-8"?>' +
      '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"' +
      ' xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"' +
      ' xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI"' +
      ' targetNamespace="http://moqui.org/flowable" id="' + xmlKey + 'Definitions">' +
      '<process id="' + xmlKey + '" name="' + xmlName + '" isExecutable="true">' +
      '<startEvent id="start" name="Start"/></process>' +
      '<bpmndi:BPMNDiagram id="BPMNDiagram_' + xmlKey + '">' +
      '<bpmndi:BPMNPlane id="BPMNPlane_' + xmlKey + '" bpmnElement="' + xmlKey + '">' +
      '<bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="180" y="200" width="36" height="36"/></bpmndi:BPMNShape>' +
      '</bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>';
  }
  function isModelerCtor(Ctor) {
    return !!(Ctor && Ctor.prototype && typeof Ctor.prototype.createDiagram === 'function');
  }
  function loadScriptRaw(url, cb) {
    var s = document.createElement('script');
    s.src = url;
    s.async = false;
    s.onload = function () { cb(null); };
    s.onerror = function () { cb(new Error('Failed to load ' + url)); };
    document.head.appendChild(s);
  }
  function ensureModeler(opts, cb) {
    if (isModelerCtor(global.BpmnModeler)) { cb(global.BpmnModeler); return; }
    if (isModelerCtor(global.BpmnJS)) {
      global.BpmnModeler = global.BpmnJS;
      cb(global.BpmnModeler);
      return;
    }
    var url = (opts && opts.modelerUrl) || DEFAULT_MODELER_URL;
    var sep = url.indexOf('?') >= 0 ? '&' : '?';
    loadScriptRaw(url + sep + '_wfModeler=1', function (err) {
      if (err || !isModelerCtor(global.BpmnJS)) {
        console.error(err || new Error('BpmnModeler failed to load'));
        cb(null);
        return;
      }
      global.BpmnModeler = global.BpmnJS;
      cb(global.BpmnModeler);
    });
  }

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

  var ALLOWED_TYPES = {
    'StartEvent': 1, 'EndEvent': 1, 'UserTask': 1, 'ServiceTask': 1,
    'ExclusiveGateway': 1, 'ParallelGateway': 1, 'InclusiveGateway': 1,
    'SequenceFlow': 1, 'Process': 1, 'Label': 1
  };
  var PALETTE_CREATE_ALLOWED = {
    'create.start-event': 1,
    'create.end-event': 1,
    'create.exclusive-gateway': 1,
    'create.user-task': 1,
    'create.service-task': 1
  };
  var REPLACE_TASK_KEEP = { 'replace-with-user-task': 1, 'replace-with-service-task': 1 };
  var REPLACE_TASK_IDS = {
    'replace-with-user-task': 1, 'replace-with-service-task': 1, 'replace-with-task': 1,
    'replace-with-script-task': 1, 'replace-with-manual-task': 1, 'replace-with-rule-task': 1,
    'replace-with-send-task': 1, 'replace-with-receive-task': 1, 'replace-with-call-activity': 1,
    'replace-with-collapsed-subprocess': 1, 'replace-with-expanded-subprocess': 1,
    'replace-with-subprocess': 1, 'replace-with-event-subprocess': 1, 'replace-with-transaction': 1
  };
  var REPLACE_GATEWAY_HIDE = { 'replace-with-event-based-gateway': 1, 'replace-with-complex-gateway': 1 };
  var REPLACE_MISC_HIDE = { 'toggle-parallel-mi': 1, 'toggle-sequential-mi': 1, 'toggle-loop': 1 };
  var CONTEXT_PAD_HIDE = [
    'append.intermediate-event', 'append.timer-intermediate-event',
    'append.message-intermediate-event', 'append.signal-intermediate-event',
    'append.condition-intermediate-event', 'append.compensation-activity',
    'append.receive-task', 'append.text-annotation'
  ];

  function allowedType(t) {
    t = String(t || '').replace(/^bpmn:/, '');
    return !!ALLOWED_TYPES[t];
  }

  function readEventName(element) {
    var business = bo(element);
    var defs = business && business.eventDefinitions;
    if (!defs || !defs.length) return '';
    var def = defs[0];
    var ref = def.messageRef || def.signalRef;
    if (ref) return (ref.name || ref.id || '') + '';
    return '';
  }

  function setEventName(modeler, element, name) {
    var modeling = modeler.get('modeling');
    var moddle = modeler.get('moddle');
    var business = bo(element);
    var defs = business && business.eventDefinitions;
    if (!defs || !defs.length) return;
    var def = defs[0];
    var dt = String(def.$type || '');
    var canvas = modeler.get('canvas');
    var rootBo = canvas && canvas.getRootElement && bo(canvas.getRootElement());
    var definitions = rootBo && rootBo.$parent;
    function ensureRoot(el) {
      if (!definitions || !el) return;
      var roots = (definitions.rootElements || []).slice();
      if (roots.indexOf(el) >= 0) return;
      roots.push(el);
      try { modeling.updateModdleProperties(canvas.getRootElement(), definitions, { rootElements: roots }); }
      catch (ignored) { definitions.rootElements = roots; }
    }
    if (dt.indexOf('Message') >= 0) {
      var msg = def.messageRef;
      if (!msg || typeof msg === 'string') {
        msg = moddle.create('bpmn:Message', { id: 'Message_' + (business.id || '1'), name: name || 'message' });
        ensureRoot(msg);
        modeling.updateModdleProperties(element, def, { messageRef: msg });
      } else {
        modeling.updateModdleProperties(element, msg, { name: name });
        ensureRoot(msg);
      }
    } else if (dt.indexOf('Signal') >= 0) {
      var sig = def.signalRef;
      if (!sig || typeof sig === 'string') {
        sig = moddle.create('bpmn:Signal', { id: 'Signal_' + (business.id || '1'), name: name || 'signal' });
        ensureRoot(sig);
        modeling.updateModdleProperties(element, def, { signalRef: sig });
      } else {
        modeling.updateModdleProperties(element, sig, { name: name });
        ensureRoot(sig);
      }
    }
  }

  function hideEntry(node) {
    if (!node) return;
    if (node.style) node.style.display = 'none';
    var entry = node.closest && (node.closest('.entry') || node.closest('[class*="entry"]'));
    if (entry && entry.style) entry.style.display = 'none';
  }

  function hideDisallowedPalette(container) {
    if (!container) return;
    var nodes = container.querySelectorAll('.djs-palette [data-action]');
    for (var i = 0; i < nodes.length; i++) {
      var action = nodes[i].getAttribute('data-action') || '';
      if (action.indexOf('create.') !== 0) continue;
      if (PALETTE_CREATE_ALLOWED[action]) continue;
      hideEntry(nodes[i]);
    }
  }

  function createTaskPaletteModule() {
    function WfTaskPalette(palette, create, elementFactory) {
      this._create = create;
      this._elementFactory = elementFactory;
      palette.registerProvider(this);
    }
    WfTaskPalette.$inject = ['palette', 'create', 'elementFactory'];
    WfTaskPalette.prototype.getPaletteEntries = function () {
      var create = this._create;
      var elementFactory = this._elementFactory;
      function start(type) {
        return function (event) {
          create.start(event, elementFactory.createShape({ type: type }));
        };
      }
      return {
        'create.user-task': {
          group: 'activity',
          className: 'bpmn-icon-user-task',
          title: 'Create User Task',
          action: { dragstart: start('bpmn:UserTask'), click: start('bpmn:UserTask') }
        },
        'create.service-task': {
          group: 'activity',
          className: 'bpmn-icon-service-task',
          title: 'Create Service Task',
          action: { dragstart: start('bpmn:ServiceTask'), click: start('bpmn:ServiceTask') }
        }
      };
    };
    return { __init__: ['wfTaskPalette'], wfTaskPalette: ['type', WfTaskPalette] };
  }

  function shouldHideReplaceId(id) {
    id = String(id || '');
    if (REPLACE_GATEWAY_HIDE[id] || REPLACE_MISC_HIDE[id]) return true;
    if (REPLACE_TASK_IDS[id]) return !REPLACE_TASK_KEEP[id];
    return false;
  }

  function padTarget(current) {
    var t = current && (current.target || current.element);
    if (Array.isArray(t)) t = t[0];
    return t;
  }

  function filterReplaceEntryMap(entries) {
    if (!entries) return;
    if (Array.isArray(entries)) {
      for (var i = entries.length - 1; i >= 0; i--) {
        var item = entries[i];
        if (shouldHideReplaceId(item && (item.id || item))) entries.splice(i, 1);
      }
      return;
    }
    Object.keys(entries).forEach(function (key) {
      var item = entries[key];
      if (shouldHideReplaceId(key) || shouldHideReplaceId(item && item.id)) delete entries[key];
    });
  }

  function hideReplacePopupDom() {
    var root = document.querySelector('.djs-popup');
    if (!root) return;
    var nodes = root.querySelectorAll('[data-id]');
    for (var i = 0; i < nodes.length; i++) {
      if (shouldHideReplaceId(nodes[i].getAttribute('data-id'))) hideEntry(nodes[i]);
    }
  }

  function hideContextPadActions(pad, element) {
    if (!pad) return;
    CONTEXT_PAD_HIDE.forEach(function (action) {
      hideEntry(pad.querySelector('[data-action="' + action + '"]'));
    });
    if (is(element, 'StartEvent')) hideEntry(pad.querySelector('[data-action="replace"]'));
  }

  function bindPaletteAndReplace(modeler, container) {
    var eventBus = modeler.get('eventBus');
    hideDisallowedPalette(container);
    eventBus.on('palette.changed', function () { hideDisallowedPalette(container); });
    eventBus.on('contextPad.open', function (e) {
      var padApi = modeler.get('contextPad');
      var current = (e && e.current) || (padApi && padApi._current) || {};
      var pad = (current.html && current.html.parentNode) ||
        container.querySelector('.djs-context-pad') || document.querySelector('.djs-context-pad');
      hideContextPadActions(pad, padTarget(current));
    });
    eventBus.on('popupMenu.open', function () {
      try {
        var popup = modeler.get('popupMenu');
        var current = popup && popup._current;
        if (current) {
          filterReplaceEntryMap(current.entries);
          filterReplaceEntryMap(current.headerEntries);
        }
      } catch (ignored) {}
      setTimeout(hideReplacePopupDom, 0);
    });
  }

  function bindUndoRedo(modeler, canvasEl) {
    if (!canvasEl || canvasEl.querySelector('#wf-undo-redo')) return;
    var bar = document.createElement('div');
    bar.id = 'wf-undo-redo';
    bar.className = 'wf-undo-redo';
    bar.innerHTML =
      '<button type="button" id="wf-undo-btn" class="wf-undo-btn" title="Undo"><i class="fa fa-undo"></i></button>' +
      '<button type="button" id="wf-redo-btn" class="wf-redo-btn" title="Redo"><i class="fa fa-repeat"></i></button>';
    canvasEl.appendChild(bar);
    var stack = modeler.get('commandStack');
    var undoBtn = bar.querySelector('#wf-undo-btn');
    var redoBtn = bar.querySelector('#wf-redo-btn');
    function sync() {
      undoBtn.disabled = !stack.canUndo();
      redoBtn.disabled = !stack.canRedo();
    }
    undoBtn.addEventListener('click', function (ev) {
      ev.preventDefault();
      if (stack.canUndo()) stack.undo();
    });
    redoBtn.addEventListener('click', function (ev) {
      ev.preventDefault();
      if (stack.canRedo()) stack.redo();
    });
    modeler.on('commandStack.changed', sync);
    sync();
  }

  function guardDisallowedShapes(modeler) {
    var eventBus = modeler.get('eventBus');
    var modeling = modeler.get('modeling');
    eventBus.on('commandStack.shape.create.postExecuted', function (e) {
      var shape = e.context && e.context.shape;
      if (shape && !allowedType(shape.type)) {
        try { modeling.removeElements([shape]); } catch (ignored) {}
      }
    });
    eventBus.on('commandStack.shape.replace.postExecuted', function (e) {
      var shape = e.context && (e.context.newShape || e.context.shape);
      if (shape && !allowedType(shape.type)) {
        try { modeling.removeElements([shape]); } catch (ignored) {}
      }
    });
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
    var tok = sessionToken();
    var headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (tok) headers['moquiSessionToken'] = tok;
    fetch(url, { method: 'POST', credentials: 'same-origin', headers: headers, body: JSON.stringify(body || {}) })
      .then(function (r) {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (j) { cb(null, j); })
      .catch(function (e) { cb(e); });
  }

  function itemValue(item) {
    if (item == null) return '';
    if (typeof item === 'string') return item;
    return item.processDefinitionKey || item.userId || item.userGroupId || item.value || '';
  }
  function itemLabel(item) {
    if (item == null) return '';
    if (typeof item === 'string') return item;
    return item.userFullName || item.description || item.processName || item.label || itemValue(item);
  }
  function comboRoot(input) {
    return input && input.closest ? input.closest('.wf-combo') : null;
  }
  function closeAllCombos(except) {
    var lists = document.querySelectorAll('.wf-combo-list');
    for (var i = 0; i < lists.length; i++) {
      if (except && lists[i] === except) continue;
      lists[i].hidden = true;
    }
  }
  function renderCombo(list, items, extra) {
    list.innerHTML = '';
    var all = (extra || []).concat(items || []);
    if (!all.length) {
      var empty = document.createElement('li');
      empty.className = 'wf-combo-empty';
      empty.textContent = 'No matches';
      list.appendChild(empty);
      return;
    }
    all.forEach(function (item) {
      var li = document.createElement('li');
      var value = itemValue(item);
      var label = itemLabel(item);
      li.setAttribute('data-value', value);
      li.className = 'wf-combo-option';
      li.textContent = label && label !== value ? (label + ' (' + value + ')') : value;
      list.appendChild(li);
    });
  }

  function bindLookups(base) {
    function wire(inputId, path, key, extras) {
      var input = byId(inputId);
      if (!input || input.getAttribute('data-wf-lookup') === '1') return;
      input.setAttribute('data-wf-lookup', '1');
      input.setAttribute('autocomplete', 'off');
      var root = comboRoot(input);
      var list = root && root.querySelector ? root.querySelector('.wf-combo-list') : null;
      if (!root || !list) return;
      var btn = root.querySelector('.wf-combo-btn');
      var timer = null;
      function openAndLoad() {
        closeAllCombos(list);
        list.hidden = false;
        lookup(base + path, { query: input.value }, function (err, data) {
          if (err || !data || list.hidden) return;
          renderCombo(list, data[key] || [], extras);
        });
      }
      input.addEventListener('focus', openAndLoad);
      input.addEventListener('input', function () {
        clearTimeout(timer);
        timer = setTimeout(openAndLoad, 250);
      });
      if (btn) {
        btn.addEventListener('mousedown', function (ev) {
          ev.preventDefault();
          if (!list.hidden) { list.hidden = true; return; }
          input.focus();
          openAndLoad();
        });
      }
      list.addEventListener('mousedown', function (ev) {
        var opt = ev.target.closest && ev.target.closest('.wf-combo-option');
        if (!opt) return;
        ev.preventDefault();
        input.value = opt.getAttribute('data-value') || '';
        list.hidden = true;
        var evt = document.createEvent('HTMLEvents');
        evt.initEvent('change', true, true);
        input.dispatchEvent(evt);
      });
    }
    if (document.documentElement.getAttribute('data-wf-combo-doc') !== '1') {
      document.documentElement.setAttribute('data-wf-combo-doc', '1');
      document.addEventListener('mousedown', function (ev) {
        if (ev.target.closest && ev.target.closest('.wf-combo')) return;
        closeAllCombos();
      });
    }
    base = (base || '').replace(/\/$/, '');
    if (base.indexOf('/qapps2') === 0) base = '/apps' + base.substring(7);
    else if (base.indexOf('/qapps') === 0) base = '/apps' + base.substring(6);
    else if (!base) base = appsPath();
    wire('wf-prop-serviceName', '/listServiceNames', 'serviceNameList');
    wire('wf-prop-formKey', '/listScreenLocations', 'screenLocationList');
    wire('wf-prop-assignee', '/listUsers', 'userList', ASSIGNEE_TOKENS);
    wire('wf-prop-candidateUsers', '/listUsers', 'userList');
    wire('wf-prop-candidateGroups', '/listUserGroups', 'userGroupList');
    wire('wf-prop-onApprove', '/listServiceNames', 'serviceNameList');
    wire('wf-prop-onReject', '/listServiceNames', 'serviceNameList');
    wire('wf-prop-onReturn', '/listServiceNames', 'serviceNameList');
    wire('wf-prop-calledElement', '/listProcessDefinitions', 'processDefinitionList');
  }

  function syncProcessMeta(modeler) {
    var registry = modeler.get('elementRegistry');
    var processEl = registry.find(function (e) { return is(e, 'Process'); });
    if (!processEl) return;
    var name = val('wf-meta-name');
    var props = { isExecutable: true };
    if (name) props.name = name;
    setFlowProps(modeler, processEl, props);
  }

  function applyPanel(modeler, element) {
    if (!element) return;
    var business = bo(element);
    var t = typeOf(element);
    setVal('wf-prop-type', t || '');
    setVal('wf-prop-id', business && business.id || '');
    setVal('wf-prop-name', business && business.name || '');
    show('wf-panel-user', t === 'UserTask');
    show('wf-panel-service', t === 'ServiceTask');
    show('wf-panel-flow', t === 'SequenceFlow');
    show('wf-panel-call', t === 'CallActivity');
    show('wf-panel-timer', isTimerEvent(element));
    show('wf-panel-event', isMessageOrSignalEvent(element));
    show('wf-panel-loop', t === 'UserTask'); // collection / completion for 会签
    if (t === 'UserTask') {
      setVal('wf-prop-formKey', flowAttr(business, 'formKey'));
      setVal('wf-prop-assignee', flowAttr(business, 'assignee'));
      setVal('wf-prop-candidateUsers', flowAttr(business, 'candidateUsers'));
      setVal('wf-prop-candidateGroups', flowAttr(business, 'candidateGroups'));
      setVal('wf-prop-onApprove', getField(business, 'onApproveService'));
      setVal('wf-prop-onReject', getField(business, 'onRejectService'));
      setVal('wf-prop-onReturn', getField(business, 'onReturnService'));
      var allow = byId('wf-prop-allowAddSign');
      if (allow) allow.checked = /^(true|Y)$/i.test(getField(business, 'allowAddSign') || '');
      var loop = readLoop(element);
      setVal('wf-prop-collection', loop.collection);
      setVal('wf-prop-completion', loop.completionCondition);
      setVal('wf-prop-elementVar', loop.elementVariable);
    }
    if (t === 'ServiceTask') {
      setVal('wf-prop-serviceName', getField(business, 'serviceName'));
    }
    if (t === 'CallActivity') setVal('wf-prop-calledElement', flowAttr(business, 'calledElement') || business.calledElement || '');
    if (t === 'SequenceFlow') setVal('wf-prop-condition', readCondition(element));
    if (business && business.eventDefinitions) {
      setVal('wf-prop-timer', readTimer(element));
      setVal('wf-prop-eventName', readEventName(element));
    }
    if (t === 'Process') {
      setVal('wf-meta-name', business.name || '');
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
      var allowEl = byId('wf-prop-allowAddSign');
      setField(modeler, element, 'allowAddSign', allowEl && allowEl.checked ? 'true' : '');
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
    if (t === 'CallActivity') {
      props.calledElement = val('wf-prop-calledElement');
      props.inheritBusinessKey = true;
      setFlowProps(modeler, element, props);
      return;
    }
    if (t === 'SequenceFlow') {
      setFlowProps(modeler, element, props);
      setCondition(modeler, element, val('wf-prop-condition'));
      return;
    }
    if (isTimerEvent(element)) {
      setFlowProps(modeler, element, props);
      setTimer(modeler, element, val('wf-prop-timer'));
      return;
    }
    if (isMessageOrSignalEvent(element)) {
      setFlowProps(modeler, element, props);
      setEventName(modeler, element, val('wf-prop-eventName'));
      return;
    }
    setFlowProps(modeler, element, props);
  }

  function eventDefType(element) {
    var business = bo(element);
    var defs = business && business.eventDefinitions;
    if (!defs || !defs.length) return '';
    return String(defs[0].$type || '');
  }
  function isTimerEvent(element) {
    return eventDefType(element).indexOf('Timer') >= 0;
  }
  function isMessageOrSignalEvent(element) {
    var t = eventDefType(element);
    return t.indexOf('Message') >= 0 || t.indexOf('Signal') >= 0;
  }

  function namedRoot(name) {
    return byId(name) || document.querySelector('[name="' + name + '"]') ||
      document.querySelector('form[name="' + name + '"]');
  }
  function vueFields(el) {
    var n = el;
    while (n) {
      var v = n.__vue__;
      if (v && v.fields) return v.fields;
      if (v && v.$parent && v.$parent.fields) return v.$parent.fields;
      n = n.parentElement;
    }
    return null;
  }
  function fieldValue(root, name, fallbackId) {
    var fields = vueFields(root);
    if (fields && fields[name] != null) return fields[name];
    var el = root && root.querySelector ? root.querySelector('[name="' + name + '"]') : null;
    if (el && el.value != null && el.type !== 'hidden') return el.value;
    if (fallbackId) {
      var idEl = byId(fallbackId);
      if (idEl && idEl.value != null) return idEl.value;
    }
    if (el && el.value != null) return el.value;
    return fallbackId ? val(fallbackId) : '';
  }
  function saveButton() {
    var root = namedRoot('DesignerSave');
    var btn = byId('wf-save-btn') || byId('DesignerSave_save');
    if (!btn && root) btn = root.querySelector('button, input[type="submit"], .q-btn');
    if (btn && !btn.id) btn.id = 'wf-save-btn';
    return btn;
  }
  function setSaveDirty(on) {
    var btn = saveButton();
    if (!btn) return;
    if (on) btn.classList.add('wf-save-dirty', 'btn-warning');
    else btn.classList.remove('wf-save-dirty', 'btn-warning');
  }

  function sessionToken() {
    var el = byId('confMoquiSessionToken') || document.querySelector('input[name="moquiSessionToken"]');
    if (el && el.value) return el.value;
    var root = global.moqui && global.moqui.webrootVue;
    return (root && root.moquiSessionToken) || '';
  }
  function ensureHidden(form, name, value) {
    var el = form.querySelector('[name="' + name + '"]');
    if (!el) {
      el = document.createElement('input');
      el.type = 'hidden';
      el.name = name;
      form.appendChild(el);
    }
    el.value = value == null ? '' : value;
    return el;
  }
  function appsPath() {
    var base = (global.location && location.pathname || '').replace(/\/$/, '');
    if (base.indexOf('/qapps2') === 0) return '/apps' + base.substring(7);
    if (base.indexOf('/qapps') === 0) return '/apps' + base.substring(6);
    return base;
  }
  function postTransition(path, data) {
    var base = appsPath();
    var body = new URLSearchParams();
    Object.keys(data).forEach(function (k) {
      if (data[k] != null && data[k] !== '') body.append(k, data[k]);
    });
    var tok = sessionToken();
    if (tok) body.append('moquiSessionToken', tok);
    return fetch(base + path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' },
      body: body.toString()
    });
  }

  function hookForms(modeler) {
    global.wfHookModeler = modeler;
    function reloadAfter() {
      if (global.moqui && moqui.webrootVue && moqui.webrootVue.reloadSubscreens) {
        moqui.webrootVue.reloadSubscreens();
      } else {
        global.location.reload();
      }
    }
    function formNameOf(el) {
      if (!el) return '';
      var n = el;
      while (n && n !== document.documentElement) {
        var id = n.id || n.getAttribute && n.getAttribute('name');
        if (id === 'DesignerMeta' || id === 'DesignerSave' || id === 'DesignerDeploy') return id;
        n = n.parentElement;
      }
      return '';
    }
    function submitMeta(root, ev) {
      if (ev) {
        ev.preventDefault();
        if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
        if (ev.stopPropagation) ev.stopPropagation();
      }
      var key = val('wf-meta-key') || fieldValue(root, 'processDefinitionKey');
      var name = fieldValue(root, 'processName', 'wf-meta-name');
      var description = fieldValue(root, 'description', 'wf-meta-description');
      postTransition('/updateMeta', {
        processDefinitionKey: key,
        processName: name,
        description: description
      }).then(function (resp) {
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        reloadAfter();
      }).catch(function (err) { console.error(err); alert('Update failed: ' + err); });
    }
    function submitDiagram(root, path, ev) {
      if (ev) {
        ev.preventDefault();
        if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
        if (ev.stopPropagation) ev.stopPropagation();
      }
      var active = global.wfHookModeler || modeler;
      syncProcessMeta(active);
      active.saveXML({ format: true }).then(function (r) {
        var key = val('wf-meta-key') || fieldValue(root, 'processDefinitionKey');
        var name = fieldValue(root, 'processName', 'wf-meta-name');
        if (root && root.querySelector) {
          ensureHidden(root, 'processDefinitionKey', key);
          ensureHidden(root, 'processName', name);
          ensureHidden(root, 'bpmnXml', r.xml);
        }
        postTransition(path, {
          processDefinitionKey: key,
          processName: name,
          bpmnXml: r.xml
        }).then(function (resp) {
          if (!resp.ok) throw new Error('HTTP ' + resp.status);
          setSaveDirty(false);
          reloadAfter();
        }).catch(function (err) { console.error(err); alert('Save failed: ' + err); });
      }).catch(function (err) { console.error(err); alert('Cannot serialize BPMN: ' + err); });
    }
    function handleFormEvent(ev) {
      var name = formNameOf(ev.target);
      if (!name) return;
      if (name === 'DesignerMeta') submitMeta(namedRoot(name), ev);
      else if (name === 'DesignerSave') submitDiagram(namedRoot(name), '/saveDefinition', ev);
      else if (name === 'DesignerDeploy') submitDiagram(namedRoot(name), '/deployDefinition', ev);
    }
    if (document.documentElement.getAttribute('data-wf-forms-hooked') === '1') return;
    document.documentElement.setAttribute('data-wf-forms-hooked', '1');
    document.addEventListener('submit', handleFormEvent, true);
    document.addEventListener('click', function (ev) {
      var btn = ev.target.closest && ev.target.closest('button, input[type="submit"], .q-btn');
      if (!btn) return;
      var name = formNameOf(btn);
      if (!name) return;
      handleFormEvent(ev);
    }, true);
  }

  function applyProcessIdName(modeler) {
    var registry = modeler.get('elementRegistry');
    var processEl = registry.find(function (e) { return is(e, 'Process'); });
    if (!processEl) return;
    var key = val('wf-meta-key');
    var name = val('wf-meta-name');
    var props = { isExecutable: true };
    if (key) props.id = key;
    if (name) props.name = name;
    setFlowProps(modeler, processEl, props);
  }
  function loadDiagram(modeler, xml) {
    if (xml && xml.indexOf('<definitions') >= 0) return modeler.importXML(xml);
    if (typeof modeler.createDiagram === 'function') {
      return modeler.createDiagram().then(function () { applyProcessIdName(modeler); });
    }
    return modeler.importXML(blankXml(val('wf-meta-key'), val('wf-meta-name')));
  }
  function bindDeleteKey(modeler, canvasEl) {
    global.wfDeleteModeler = modeler;
    global.wfDeleteCanvas = canvasEl;
    if (document.documentElement.getAttribute('data-wf-delete-bound') === '1') return;
    document.documentElement.setAttribute('data-wf-delete-bound', '1');
    document.addEventListener('keydown', function (ev) {
      if (ev.key !== 'Delete' && ev.key !== 'Backspace') return;
      var t = ev.target;
      var tag = (t && t.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || (t && t.isContentEditable)) return;
      var active = global.wfDeleteModeler;
      var canvas = global.wfDeleteCanvas;
      if (!active || !canvas || !canvas.isConnected) return;
      var selection;
      try { selection = active.get('selection').get(); } catch (ignored) { return; }
      if (!selection || !selection.length) return;
      var removable = selection.filter(function (shape) {
        var ty = typeOf(shape);
        return ty && ty !== 'Process' && ty !== 'Label';
      });
      if (!removable.length) return;
      ev.preventDefault();
      try { active.get('modeling').removeElements(removable); } catch (ignored) {}
    });
  }

  function initDesigner(opts) {
    opts = opts || {};
    var el = byId(opts.canvasId || 'wf-bpmn-canvas');
    if (!el) { setTimeout(function () { initDesigner(opts); }, 80); return; }
    if (el.getAttribute('data-wf-ready') === '1' || el.getAttribute('data-wf-loading') === '1') return;
    el.setAttribute('data-wf-loading', '1');
    ensureModeler(opts, function (Modeler) {
      el = byId(opts.canvasId || 'wf-bpmn-canvas');
      if (!el) return;
      if (!Modeler) { el.removeAttribute('data-wf-loading'); return; }
      if (el.getAttribute('data-wf-ready') === '1') return;
      el.setAttribute('data-wf-ready', '1');

      function start(moddleJson) {
        var modeler = new Modeler({
          container: el,
          additionalModules: [createTaskPaletteModule()],
          moddleExtensions: moddleJson ? { flowable: moddleJson } : {}
        });
        var xml = (opts.existingXml || val('wf-existing-xml') || '').trim();
        if (!xml) {
          var hiddenXml = document.querySelector('form[name="DesignerSave"] [name="bpmnXml"], form[name="DesignerDeploy"] [name="bpmnXml"]');
          if (hiddenXml && hiddenXml.value) xml = hiddenXml.value.trim();
        }
        function afterLoad() {
          try { modeler.get('canvas').resized(); } catch (ignored) {}
          setSaveDirty(false);
          if (el.getAttribute('data-wf-dirty-bound') === '1') return;
          el.setAttribute('data-wf-dirty-bound', '1');
          modeler.on('commandStack.changed', function () { setSaveDirty(true); });
        }
        loadDiagram(modeler, xml).then(afterLoad).catch(function (err) { console.error(err); });
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
        bindUndoRedo(modeler, el);
        bindPaletteAndReplace(modeler, el);
        guardDisallowedShapes(modeler);
        bindDeleteKey(modeler, el);
        if (opts.lockKey !== false) {
          var keyInput = metaKeyEl();
          if (keyInput) keyInput.readOnly = true;
        }
      }

      if (opts.moddle) { start(opts.moddle); return; }
      var moddleUrl = opts.moddleUrl;
      if (!moddleUrl) { start(null); return; }
      fetch(moddleUrl, { credentials: 'same-origin' }).then(function (r) { return r.json(); })
        .then(function (j) { start(j); })
        .catch(function () { start(null); });
    });
  }

  global.wfInitDesigner = initDesigner;
})(window);
