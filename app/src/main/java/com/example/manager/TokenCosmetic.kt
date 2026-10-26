package com.example.manager

import java.util.Base64

data class TokenCosmeticPayload(
    val classes: Set<String>,
    val ids: Set<String>,
    val complex: List<String>,
    val encoded: String,
) {
    val selectorCount: Int get() = classes.size + ids.size + complex.size

    companion object {
        const val MAX_SELECTORS = 15_000
        const val MAX_COMPLEX_SELECTORS = 750
        const val MAX_ENCODED_BYTES = 384 * 1_024

        private val simpleSelector = Regex("[.#][A-Za-z_][A-Za-z0-9_-]*")

        fun create(selectors: Collection<String>): TokenCosmeticPayload {
            val ordered = selectors.distinct().sorted()
            require(ordered.size <= MAX_SELECTORS) { "Too many generic cosmetic selectors" }
            val classes = mutableSetOf<String>()
            val ids = mutableSetOf<String>()
            val complex = mutableListOf<String>()
            for (selector in ordered) {
                when {
                    simpleSelector.matches(selector) && selector.startsWith('.') ->
                        classes += selector.drop(1)
                    simpleSelector.matches(selector) && selector.startsWith('#') ->
                        ids += selector.drop(1)
                    else -> complex += selector
                }
            }
            require(complex.size <= MAX_COMPLEX_SELECTORS) {
                "Too many complex generic cosmetic selectors"
            }
            val encoded = listOf(
                encode(classes.joinToString("\n")),
                encode(ids.joinToString("\n")),
                encode(complex.joinToString("\n")),
            ).joinToString(".")
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
                "Generic cosmetic payload too large"
            }
            return TokenCosmeticPayload(classes, ids, complex, encoded)
        }

        private fun encode(value: String): String =
            Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    }
}

data class TokenCosmeticPolicy(
    val disabled: Boolean = false,
    val deniedSelectors: Set<String> = emptySet(),
) {
    companion object {
        const val MAX_DENIED_SELECTORS = 128
    }

    init {
        require(deniedSelectors.size <= MAX_DENIED_SELECTORS) {
            "Too many generic cosmetic exceptions"
        }
    }
}

val tokenScannerScript: String = """
(function () {
  if (window.__tokenCosmeticActive) return;
  window.__tokenCosmeticActive = true;
  var host = location.hostname.toLowerCase().replace(/\.$/, '');
  var payloadText = '';
  try { payloadText = window.__tokenCosmeticBridge.payload(host); } catch (e) { return; }
  if (!payloadText) return;
  var parts = payloadText.split('.');
  if (parts.length !== 3) return;
  function decodePart(value) {
    if (!value) return [];
    try { return atob(value).split('\n').filter(Boolean); } catch (e) { return []; }
  }
  var classSet = new Set(decodePart(parts[0]));
  var idSet = new Set(decodePart(parts[1]));
  var complexList = decodePart(parts[2]);
  if (complexList.length) {
    var complexStyle = document.createElement('style');
    complexStyle.textContent = complexList.map(function (s) {
      return s + '{display:none!important}';
    }).join('');
    (document.head || document.documentElement).appendChild(complexStyle);
  }
  var simpleStyle = null;
  var queuedSelectors = [];
  var activeSelectors = new Set();
  var activeBytes = 0;
  var MAX_SELECTORS = 1024;
  var MAX_SELECTOR_BYTES = 96 * 1024;
  var MAX_PENDING_NODES = 8192;
  var queue = [];
  var queueIndex = 0;
  var scheduled = false;
  var seen = new WeakSet();
  function flushSelectors() {
    if (!queuedSelectors.length) return;
    if (!simpleStyle) {
      simpleStyle = document.createElement('style');
      (document.head || document.documentElement).appendChild(simpleStyle);
    }
    try {
      simpleStyle.sheet.insertRule(
        queuedSelectors.join(',') + '{display:none!important}',
        simpleStyle.sheet.cssRules.length
      );
    } catch (e) {}
    queuedSelectors = [];
  }
  function activate(selector) {
    if (activeSelectors.has(selector)) return;
    if (activeSelectors.size >= MAX_SELECTORS) return;
    if (activeBytes + selector.length > MAX_SELECTOR_BYTES) return;
    activeSelectors.add(selector);
    activeBytes += selector.length;
    queuedSelectors.push(selector);
    if (queuedSelectors.length >= 256) flushSelectors();
  }
  function inspect(el) {
    if (!el || el.nodeType !== 1) return;
    if (el.id && idSet.has(el.id)) activate('#' + el.id);
    try {
      el.classList.forEach(function (name) {
        if (classSet.has(name)) activate('.' + name);
      });
    } catch (e) {}
  }
  function drain() {
    scheduled = false;
    var started = performance.now();
    var processed = 0;
    while (queueIndex < queue.length && processed < 512 &&
           performance.now() - started < 4) {
      var node = queue[queueIndex++];
      for (var child = node.firstElementChild; child; child = child.nextElementSibling) {
        enqueue(child);
      }
      processed++;
    }
    flushSelectors();
    if (queueIndex >= queue.length) { queue = []; queueIndex = 0; }
    else {
      if (queueIndex > 4096) { queue = queue.slice(queueIndex); queueIndex = 0; }
      schedule();
    }
  }
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    (window.requestAnimationFrame || function (cb) { setTimeout(cb, 0); })(drain);
  }
  function enqueue(node) {
    if (!node || node.nodeType !== 1 || seen.has(node)) return;
    if (queue.length - queueIndex >= MAX_PENDING_NODES) return;
    seen.add(node);
    inspect(node);
    queue.push(node);
    schedule();
  }
  var observer = new MutationObserver(function (records) {
    records.forEach(function (record) {
      if (record.type === 'attributes') inspect(record.target);
      else record.addedNodes.forEach(enqueue);
    });
    flushSelectors();
  });
  observer.observe(document, {
    subtree: true, childList: true, attributes: true,
    attributeFilter: ['id', 'class']
  });
  enqueue(document.documentElement);
})();
""".trimIndent()
