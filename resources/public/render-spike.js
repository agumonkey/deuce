/*jslint browser: true regexp: true */

document.addEventListener('DOMContentLoaded', function () {
    'use strict';

    function bufferLines(s) {
        return s.match(/^.*((\r\n|\n|\r)|$)/gm);
    }

    var scrollBuffer = document.querySelector('.scroll-buffer'),
        win = document.querySelector('.window'),
        display = document.querySelector('.display'),
        clipboard = document.querySelector('.clipboard'),
        point = document.querySelector('.point'),
        pendingRedraw = false,
        fontHeight,
        fontWidth,
        width = 132,
        height = 43,
        file = new Array(1000).join(document.querySelector('[data-filename=TUTORIAL]').textContent + '\n'),
        linesInFile = bufferLines(file),
        offset = 0,
        visibleStart = 0;

    function offsetOfLine(idx) {
        var i, acc = 0;
        for (i = 0; i < idx; i += 1) {
            acc += linesInFile[i].length;
        }
        return acc;
    }

    function setCssRule(selector, css) {
        var styleSheet = document.styleSheets[0],
            i;
        for (i = 0; i < styleSheet.cssRules.length; i += 1) {
            if (styleSheet.cssRules[i].selectorText === selector) {
                styleSheet.deleteRule(i);
                break;
            }
        }
        styleSheet.insertRule(selector + ' ' + css, 0);
    }

    function alignLineNumbers() {
        var gutterWidth = ((linesInFile.length.toString().length + 1) * fontWidth);
        setCssRule('.window.linum-mode .display .line:before', '{ width: ' + gutterWidth + 'px; }');
        setCssRule('.window.linum-mode .point', '{ margin-left: ' + gutterWidth + 'px; }');
    }

    function alignDisplay() {
        display.style.top = (win.offsetTop + win.clientTop) + 'px';
        display.style.left = (win.offsetLeft + win.clentLeft) + 'px';
    }

    function calculateFontSize() {
        var temp = document.createElement('span');
        temp.style.position = 'absolute';
        temp.textContent = ' ';
        display.appendChild(temp);
        function setCalculatedFontSize() {
            var style = window.getComputedStyle(temp),
                parsedWidth = parseFloat(style.width),
                parsedHeight = parseFloat(style.height);
            if (!(parsedWidth && parsedHeight)) {
                return window.requestAnimationFrame(setCalculatedFontSize);
            }
            fontWidth = parsedWidth;
            fontHeight = parsedHeight;
            temp.remove();

            scrollBuffer.style.height = (fontHeight * linesInFile.length) + 'px';
            win.style.width = (width * fontWidth) + 'px';
            win.style.height = (height * fontHeight) + 'px';

            point.style.width = fontWidth + 'px';
            point.style.height = fontHeight + 'px';
        }
        window.requestAnimationFrame(setCalculatedFontSize);
    }

    function renderLine(idx) {
        var line = document.createElement('span');
        line.dataset.line = idx + 1;
        line.classList.add('line');
        line.innerHTML = linesInFile[idx];
        return line;
    }

    function render() {
        var t = Date.now(),
            newStart = Math.floor(win.scrollTop / fontHeight),
            newEnd = newStart + height,
            diff = newStart - visibleStart,
            fragment = document.createDocumentFragment(),
            useDeltas = true,
            i;
        offset = offsetOfLine(newStart);

        console.log('line:', newStart, 'offset:', offset);

        if (useDeltas && Math.abs(diff) < height && diff !== 0) {
            console.log('diff:', diff);
            if (diff > 0) {
                for (i = diff; i > 0; i -= 1) {
                    display.firstChild.remove();
                    fragment.appendChild(renderLine(newEnd - i));
                }
                display.appendChild(fragment);
            } else {
                for (i = 0; i < -diff; i += 1) {
                    display.lastChild.remove();
                    fragment.appendChild(renderLine(newStart + i));
                }
                display.insertBefore(fragment, display.firstChild);
            }
        } else {
            console.log('full redraw');
            for (i = newStart; i < newEnd; i += 1) {
                fragment.appendChild(renderLine(i));
            }
            display.innerHTML = '';
            display.appendChild(fragment);
        }

        visibleStart = newStart;
        pendingRedraw = false;

        console.log((Date.now() - t) + 'ms');
    }

    win.addEventListener('scroll', function () {
        if (!pendingRedraw) {
            pendingRedraw = true;
            window.requestAnimationFrame(render);
        }
    });

    document.querySelector('[name=linum-mode]').addEventListener('click', function (e) {
        document.querySelector('.window').classList.toggle(e.target.name);
    });

    function resize() {
        calculateFontSize();
        window.requestAnimationFrame(function () {
            alignLineNumbers();
            alignDisplay();
            render();
            win.focus();
        });
    }

    window.addEventListener('resize', resize);
    resize();

    window.addEventListener('paste', function (e) {
        e.preventDefault();
        console.log('paste', e.clipboardData.getData('text/plain'));
    });

    function handleCopyAndCut(type, text) {
        console.log(type, text);
        clipboard.value = text;
        clipboard.select();
        setTimeout(function () {
            clipboard.blur();
            clipboard.value = '';
        });
    }

    window.addEventListener('cut', function () {
        handleCopyAndCut('cut', 'Cut from Deuce' + new Date());
    });

    window.addEventListener('copy', function () {
        handleCopyAndCut('copy', 'Copied from Deuce ' + new Date());
    });

    console.log('lines:', linesInFile.length, (Math.round(file.length / (1024 * 1024) * 100) / 100), 'Mb');
});