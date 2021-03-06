/*eslint-env browser */
/*globals virtualDom m DeuceVDom DeuceFrame DeuceWindow DeuceBuffer DeuceElement */

'use strict';

let DEBUG = false,
    h = DeuceVDom.e;

function debug() {
    if (DEBUG) {
        console.debug.apply(console, [].slice.call(arguments));
    }
}

function time(label) {
    if (DEBUG) {
        time(label);
    }
}

function timeEnd(label) {
    if (DEBUG) {
        timeEnd(label);
    }
}

let renderers = new Map([[m, 'mithril'],
                         [DeuceVDom.e, 'deuce-vdom'],
                         [virtualDom.h, 'virtual-dom']]);

function usedRenderer() {
    return renderers.get(h);
}

function attrs(properties) {
    if (usedRenderer() === 'virtual-dom') {
        return properties;
    }
    let attributes = properties.attributes;
    if (attributes) {
        Object.keys(attributes).forEach((k) => {
            properties[k] = attributes[k];
        });
    }
    delete properties.attributes;
    return properties;
}

function attributeFromModel(value) {
    return Array.isArray(value) ? value.join(' ') : String(value);
}

function lineFromModel(bufferName, lineNumberAtStart, line, idx) {
    return h('line-d', attrs({key: 'line-' + bufferName + '-' + idx,
                              attributes: {number: (idx + lineNumberAtStart)}}), line);
}

function bufferFromModel(buffer, lineNumberAtStart, lineNumberAtEnd) {
    let properties = {key: 'buffer-' + buffer.name, attributes: {}},
        children;
    lineNumberAtStart = lineNumberAtStart || 1;
    Object.keys(buffer).forEach((a) => {
        if (a === 'text') {
            let lines = buffer[a];
            if (lines.length === buffer['line-number-at-point-max']) {
                lines = lines.slice(lineNumberAtStart - 1, lineNumberAtEnd);
            }
            children = lines.map((line, idx) => lineFromModel(buffer.name, lineNumberAtStart, line, idx));
        } else if (buffer[a] !== false) {
            properties.attributes[a] = attributeFromModel(buffer[a]);
        }
    });
    return h('buffer-d', attrs(properties), children);
}

function windowFromModel(win) {
    let properties = {key: 'window-' + win['sequence-number'], attributes: {}},
        children = [],
        lineNumberAtStart = parseInt(win['line-number-at-start'], 10),
        lineNumberAtEnd = parseInt(win['line-number-at-end'], 10);
    Object.keys(win).forEach((a) => {
        if (a === 'buffer') {
            children.push(bufferFromModel(win[a], lineNumberAtStart, lineNumberAtEnd));
        } else if (a === 'mode-line') {
            children.push(h('mode-line-d', attrs({key: 'mode-line-' + properties.key, innerHTML: win[a]})));
        } else if (win[a] !== false) {
            properties.attributes[a] = attributeFromModel(win[a]);
        }
    });
    return h('window-d', attrs(properties), children);
}

function frameFromModel(frame) {
    if (frame.closed) {
        document.body.classList.remove('keydown');
        window.location = 'about:blank';
    }
    let properties = {key: 'frame-' + frame.name, attributes: {}},
        children = [];
    Object.keys(frame).forEach((a) => {
        if (a === 'menu-bar') {
            children.push(h('menu-bar-d', attrs({key: 'menu-bar-' + properties.key}),
                            frame[a].map((menu) => h('menu-d', {key: 'menu-' + menu}, menu))));
        } else if (a === 'root-window' || a === 'minibuffer-window') {
            children.push(windowFromModel(frame[a]));
        } else if (frame[a] !== false) {
            properties.attributes[a] = attributeFromModel(frame[a]);
        }
    });
    return h('frame-d', attrs(properties), children);
}

function applySimpleCharDiffs(ds, s) {
    let acc = '';
    for (var i = 0, idx = 0; i < ds.length; i += 1) {
        let d = ds[i];
        if (typeof d === 'string') {
            acc += d;
        } else if (d > 0) {
            acc += s.slice(idx, idx + d);
            idx += d;
        } else if (d < 0) {
            idx -= d;
        }
    }
    return acc;
}

let state,
    serializedState,
    revision,
    rootNode,
    vdomTree,
    pendingRefresh;

function onrefresh(newRevision, newSerializedState) {
    serializedState = newSerializedState;
    state = JSON.parse(serializedState);

    requestAnimationFrame(() => {
        if (usedRenderer() === 'mithril') {
            rootNode = document.body;
        } else if (usedRenderer() === 'deuce-vdom') {
            document.body.innerHTML = '';
            rootNode = DeuceVDom.redraw(() => frameFromModel(state.frame), true).element;
            document.body.appendChild(rootNode);
        } else {
            vdomTree = frameFromModel(state.frame);
            rootNode = virtualDom.create(vdomTree);
            document.body.innerHTML = '';
            document.body.appendChild(rootNode);
        }
    });

    revision = newRevision;
}

function patchCommon(oldRevision) {
    if (revision === undefined) {
        console.error('got patch before full refresh, ignoring.');
        return false;
    }
    if (oldRevision !== revision) {
        console.error('out of sync with server, requesting refresh:', oldRevision, revision);
        revision = undefined;
        send('r');
        return false;
    }
    return true;
}

function onpatch(oldRevision, diffs) {
    if (patchCommon(oldRevision)) {
        serializedState = applySimpleCharDiffs(diffs, serializedState);
        state = JSON.parse(serializedState);
        revision = oldRevision + 1;
    }
}

function render(serverTime) {
    if (!pendingRefresh) {
        pendingRefresh = true;
        time('frame waiting time');
        requestAnimationFrame(() => {
            timeEnd('frame waiting time');
            time('redraw');
            pendingRefresh = false;

            if (state.frame) {
                if (usedRenderer() === 'mithril') {
                    m.render(rootNode, frameFromModel(state.frame));
                } else if (usedRenderer() === 'deuce-vdom') {
                    DeuceVDom.redraw(() => frameFromModel(state.frame));
                } else {
                    let newTree = frameFromModel(state.frame),
                        patches = virtualDom.diff(vdomTree, newTree);
                    rootNode = virtualDom.patch(rootNode, patches);
                    vdomTree = newTree;
                }
            }

            timeEnd('redraw');
            timeEnd('client time');
            debug('latency:', Date.now() - serverTime, 'ms', usedRenderer());
        });
    }
}

let handlers = {r: onrefresh, p: onpatch};

function onmessage(data) {
    debug('client received:', data.data.length, data.data);
    time('client time');
    let message = JSON.parse(data.data),
        serverTime = message[message.length - 1];
    debug('server time:', Date.now() - serverTime, 'ms');
    handlers[message[0]].apply(null, message.slice(1));
    render(serverTime);
}

let ws,
    url = 'ws://127.0.0.1:8080',
    initialReconnectInterval = 1000,
    maxReconnectInterval = initialReconnectInterval * 5,
    reconnectInterval = initialReconnectInterval,
    reconnectBackoffRatio = 1.2;

function send() {
    if (ws) {
        let args = [].constructor(arguments.length);
        for (let i = 0; i < arguments.length; i += 1) {
            args[i] = arguments[i];
        }
        ws.send(JSON.stringify(args));
    }
}

function connect() {
    if (ws) {
        return;
    }
    debug('connecting to', url);

    ws = new WebSocket(url);
    ws.onmessage = onmessage;
    ws.onopen = (e) => {
        debug('connection opened:', e);
        reconnectInterval = initialReconnectInterval;
    };
    ws.onerror = (e) => {
        console.error('connection error:', e);
        document.body.classList.remove('keydown');
        ws.close();
    };
    ws.onclose = (e) => {
        debug('connection closed:', e);
        debug('retrying in:', reconnectInterval, 'ms.');
        ws = undefined;

        let minibuffer = document.querySelector('window-d[mini-p] buffer-d line-d');
        (minibuffer || document.body).innerHTML = '<span style=\'color: red;\'>NO CONNECTION</span>';

        window.setTimeout(connect, reconnectInterval);
        reconnectInterval *= reconnectBackoffRatio;
        reconnectInterval = Math.min(maxReconnectInterval, reconnectInterval);
    };
}

window.addEventListener('error', (e) => {
    if (ws) {
        console.error('error, reloading app:', e);
        if (ws.close) {
            ws.close();
        }
        ws = {};
        setTimeout(() => window.location.reload(), maxReconnectInterval);
    }
});

// Based on https://github.com/ccampbell/mousetrap/blob/master/mousetrap.js
let keyCodeToEmacs = {8: 'backspace', 9: 'tab', 13: 'return',
                      19: 'pause',
                      27: 'escape',
                      33: 'prior', 34: 'next',
                      35: 'end', 36: 'home',
                      37: 'left', 38: 'up', 39: 'right', 40: 'down',
                      45: 'insert', 46: 'delete',
                      112: 'f1', 113: 'f2', 114: 'f3', 115: 'f4', 116: 'f5', 117: 'f6',
                      118: 'f7', 119: 'f8', 120: 'f9', 121: 'f10', 122: 'f11', 123: 'f12'},
    specialKeys = {106: '*', 107: '+', 109: '-',
                   110: '.', 111: '/',
                   186: ';', 187: '=', 188: ',', 189: '-',
                   190: '.', 191: '/', 192: '`',
                   219: '[', 220: '\\', 221: ']', 222: '\''},
    shiftMap = {'`': '~', '1': '!', '2': '@', '3': '#', '4': '$', '5': '%', '6': '^', '7': '&', '8': '*', '9': '(', '0': ')',
                '-': '_', '=': '+',
                '[': '{', ']': '}', '\\': '|',
                ';': ':', '\'': '\"',
                ',': '<', '.': '>', '/': '?'},
    modifierKeyCodes = {16: 'shift', 17: 'ctrl', 18: 'alt'},
    keyUpTimer,
    keyUpDelay = 400,
    ctrlDown;

function modifiers(e, noShift) {
    let mods = [];
    if (e.ctrlKey) {
        mods.push('C');
    }
    if (e.altKey) {
        mods.push('M');
    }
    if (e.shiftKey && !noShift) {
        mods.push('S');
    }
    return mods;
}

function keyEventToString(mods, key) {
    return mods.concat([key]).join('-');
}

function sendKeyEvent(event) {
    debug('key:', event);
    send('k', event);
}

window.addEventListener('keydown', (e) => {
    document.body.classList.add('keydown');
    ctrlDown = modifierKeyCodes[e.keyCode] === 'ctrl';
    if (keyUpTimer) {
        clearTimeout(keyUpTimer);
    }
    if (keyCodeToEmacs[e.keyCode]) {
        e.preventDefault();
        sendKeyEvent(keyEventToString(modifiers(e), keyCodeToEmacs[e.keyCode]));
    } else if ((e.ctrlKey || e.altKey) && !modifierKeyCodes[e.keyCode]) {
        e.preventDefault();
        let key = String.fromCharCode(e.keyCode),
            noShift = false;

        if (specialKeys[e.keyCode]) {
            key = specialKeys[e.keyCode];
        }
        if (e.shiftKey && shiftMap[key]) {
            key = shiftMap[key];
            noShift = true;
        }
        sendKeyEvent(keyEventToString(modifiers(e, noShift), key.toLowerCase()));
    }
});

window.addEventListener('keypress', (e) => {
    e.preventDefault();
    sendKeyEvent(keyEventToString(modifiers(e, true), String.fromCharCode(e.charCode)));
});

window.addEventListener('keyup', (e) => ctrlDown = ctrlDown && modifierKeyCodes[e.keyCode] !== 'ctrl');

['keyup', 'blur'].forEach((e) => window.addEventListener(e, () => {
    keyUpTimer = setTimeout(() => document.body.classList.remove('keydown'), keyUpDelay);
}));

window.addEventListener('mousedown', (e) => {
    document.body.classList.add('mousedown');
    e.preventDefault();
});

window.addEventListener('mouseup', (e) => {
    document.body.classList.remove('mousedown');
    e.preventDefault();
});

let wheelTimer,
    wheelDelay = 250;

window.addEventListener('mousewheel', () => {
    clearTimeout(wheelTimer);
    document.body.classList.add('mousewheel');
    wheelTimer = setTimeout(() => document.body.classList.remove('mousewheel'), wheelDelay);
});


window.oncontextmenu = (e) => e.preventDefault();

window.onbeforeunload = () => {
    if (ctrlDown && ws) {
        setTimeout(() => sendKeyEvent('C-w'));
        return 'Staying on this page will execute kill-region.';
    }
};

document.addEventListener('DOMContentLoaded', connect);

function sendSizeEvent(type, idAttr, element) {
    send(type, element.getAttribute(idAttr),
         element.intAttribute('width'), element.intAttribute('height'));
}

DeuceFrame.resize = () => {
    DeuceElement.resize.call(this);
    sendSizeEvent('zf', 'name', this);
};

DeuceWindow.resize = () => {
    DeuceElement.resize.call(this);
    sendSizeEvent('zw', 'sequence-number', this);
};

let bufferAttachedCallback = DeuceBuffer.attachedCallback,
    bufferDetachedCallback = DeuceBuffer.detachedCallback;

DeuceBuffer.scroll = () => {
    this.scrollPane.lastScrollTop = this.scrollPane.scrollTop;
    let newLineNumberAtStart = Math.floor(this.scrollPane.lastScrollTop / this.point.charHeight) + 1;
    if (newLineNumberAtStart !== this.win.lineNumberAtStart && !document.body.classList.contains('keydown')) {
        send('s', this.win.sequenceNumber, newLineNumberAtStart);
    }
};

DeuceBuffer.attachedCallback = () => {
    bufferAttachedCallback.call(this);
    this.scroll = this.scroll.bind(this);
    this.scrollPane.addEventListener('scroll', this.scroll);
};

DeuceBuffer.detachedCallback = () => {
    bufferDetachedCallback.call(this);
    this.scrollPane.removeEventListener('scroll', this.scroll);
};
