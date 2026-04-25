package uk.nktnet.webviewkiosk.utils.webview.scripts

import org.json.JSONObject
import uk.nktnet.webviewkiosk.utils.webview.wrapJsInIIFE

fun generateInsertTextIntoFocusedElementScript(text: String): String {
    val quotedText = JSONObject.quote(text)
    return wrapJsInIIFE(
        """
            const text = $quotedText;

            function activeElement(root) {
                let element = root.activeElement;
                while (element && element.shadowRoot && element.shadowRoot.activeElement) {
                    element = element.shadowRoot.activeElement;
                }
                return element;
            }

            function dispatchInput(element, data) {
                let event;
                try {
                    event = new InputEvent('input', {
                        bubbles: true,
                        cancelable: false,
                        inputType: 'insertText',
                        data: data
                    });
                } catch (error) {
                    event = new Event('input', { bubbles: true });
                }
                element.dispatchEvent(event);
            }

            function setNativeValue(element, value) {
                const prototype = element instanceof HTMLTextAreaElement
                    ? HTMLTextAreaElement.prototype
                    : HTMLInputElement.prototype;
                const descriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
                if (descriptor && descriptor.set) {
                    descriptor.set.call(element, value);
                } else {
                    element.value = value;
                }
            }

            const element = activeElement(document);
            if (!element) {
                return false;
            }

            const tagName = (element.tagName || '').toLowerCase();
            if (tagName === 'input' || tagName === 'textarea') {
                const value = element.value || '';
                const start = Number.isInteger(element.selectionStart)
                    ? element.selectionStart
                    : value.length;
                const end = Number.isInteger(element.selectionEnd)
                    ? element.selectionEnd
                    : start;
                const nextValue = value.slice(0, start) + text + value.slice(end);
                const nextPosition = start + text.length;
                setNativeValue(element, nextValue);
                element.setSelectionRange(nextPosition, nextPosition);
                dispatchInput(element, text);
                return true;
            }

            if (element.isContentEditable) {
                element.focus();
                if (document.execCommand('insertText', false, text)) {
                    dispatchInput(element, text);
                    return true;
                }
                const selection = window.getSelection();
                if (selection && selection.rangeCount > 0) {
                    const range = selection.getRangeAt(0);
                    range.deleteContents();
                    range.insertNode(document.createTextNode(text));
                    range.collapse(false);
                    dispatchInput(element, text);
                    return true;
                }
            }

            window.dispatchEvent(new CustomEvent('doorplateTextInput', {
                detail: { text: text }
            }));
            return false;
        """.trimIndent()
    )
}
