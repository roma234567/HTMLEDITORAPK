package com.glassbrowser.app

import org.json.JSONObject

/**
 * Мост инспектора: JS выполняется в Kotlin-строках и общается с нативной
 * стороной через prompt("__GLASS__" + payload) → WebViewClient.onJsPrompt.
 *
 * Правила: в JS-исходниках НЕТ символов '$' и '`' — только одинарные
 * кавычки и явные экранирования, чтобы Kotlin-интерполяция не ломала код.
 */
object InspectorBridge {

    const val PREFIX = "__GLASS__"
    const val SEP = "\u001F" // разделитель полей в payload

    /** Сообщения Kotlin → JS (id событий в payload после PREFIX). */
    const val EV_HTML = "1"      // html: path \u001F outerHTML
    const val EV_CSS = "2"       // css: path \u001F cssText
    const val EV_PICK = "3"      // выбор завершён: path \u001F innerHTML \u001F cssText
    const val EV_CSS_RESULT = "4301" // getCss: \u001F + JSON строк (полный ответ)

    /** Подписчики событий (устанавливает SheetInspector, снимает при закрытии). */
    @Volatile
    var onEvent: ((String, Array<String>) -> Unit)? = null

    /**
     * Разбирает входящее prompt-сообщение.
     * @return true, если сообщение наше (обработано).
     */
    fun handlePrompt(message: String, url: String?): Boolean {
        if (!message.startsWith(PREFIX)) return false
        val rest = message.substring(PREFIX.length)
        val ev: String
        val payload: String
        val cut = rest.indexOf(SEP)
        if (cut >= 0) {
            ev = rest.substring(0, cut)
            payload = rest.substring(cut + SEP.length)
        } else {
            ev = rest
            payload = ""
        }
        val fields = payload.split(SEP).toTypedArray()
        when (ev) {
            EV_CSS_RESULT -> {
                // полный ответ уже в payload (одно поле — JSON)
                val json = if (fields.isNotEmpty()) fields[0] else ""
                onEvent?.invoke(ev, arrayOf(json, url ?: ""))
            }
            else -> onEvent?.invoke(ev, fields + url.orEmpty())
        }
        return true
    }

    // ------------------------------------------------------------------
    // JS-исходники
    // ------------------------------------------------------------------

    /** Инжект фонового модуля (cssPath + подсветка ховера). Возвращает "OK" или ошибку. */
    fun injectModule(): String = wrap(
        """
        if (!window.__glassMod) {
          var CSSP = function (el) {
            if (el === document.documentElement) return 'html';
            if (!el || !el.parentNode) return 'html';
            var path = [];
            while (el && el.nodeType === 1 && el !== document.documentElement) {
              var tag = el.tagName.toLowerCase();
              if (el.id) { path.push(tag + '#' + el.id); break; }
              var par = el.parentNode;
              var same = 0, nth = 1;
              var kids = par.children || [];
              for (var i = 0; i < kids.length; i++) {
                var k = kids[i];
                if (k.tagName === el.tagName) {
                  same++;
                  if (k === el) nth = same;
                }
              }
              if (same > 1) path.push(tag + ':nth-of-type(' + nth + ')');
              else path.push(tag);
              el = el.parentNode;
            }
            path.reverse();
            var s = path.join(' > ');
            return s;
          };
          window.__glassCssPath = CSSP;
          var st = document.createElement('style');
          st.id = '__glass_hl';
          st.textContent = '.__glass_hover{outline:2px solid #2F6FED !important;outline-offset:2px !important;}';
          (document.head || document.documentElement).appendChild(st);
          window.__glassMod = 1;
        }
        """
    )

    /** Включает режим выбора элемента (после предыдущего — снимает). */
    fun pickScript(): String = wrap(
        """
        if (window.__glassStop) window.__glassStop();
        if (!(window.__glassMod && window.__glassCssPath)) { return 'NO_MOD'; }
        var S = String.fromCharCode(31);
        var stop = null, armed = true;
        var lastHover = null;
        function hi(el) { if (lastHover) lastHover.classList.remove('__glass_hover'); el.classList.add('__glass_hover'); lastHover = el; }
        function unhi() { if (lastHover) { lastHover.classList.remove('__glass_hover'); lastHover = null; } }
        function onMove(e) {
          if (!armed) return;
          var t = e.target;
          if (t && t.nodeType === 1) hi(t);
        }
        function onClick(e) {
          if (!armed) return;
          e.preventDefault(); e.stopPropagation();
          armed = false;
          var el = e.target;
          unhi();
          stop();
          var path = window.__glassCssPath(el);
          var html = '';
          try { html = el.outerHTML || ''; } catch (x) { html = ''; }
          var css = '';
          try { css = el.style && el.style.cssText ? el.style.cssText : ''; } catch (x2) { css = ''; }
          prompt('__GLASS__3' + S + path + S + html + S + css, '');
          return false;
        }
        stop = function () {
          document.removeEventListener('mousemove', onMove, true);
          document.removeEventListener('click', onClick, true);
          document.removeEventListener('keydown', onKey, true);
          unhi();
          window.__glassStop = null;
          armed = false;
        };
        function onKey(e) {
          if (e.key === 'Escape') { stop(); }
        }
        window.__glassStop = stop;
        document.addEventListener('mousemove', onMove, true);
        document.addEventListener('click', onClick, true);
        document.addEventListener('keydown', onKey, true);
        """
    )

    /** Остановка выбора (мигает — безопасно вызывать повторно). */
    fun stopPickScript(): String = wrap(
        """
        if (window.__glassStop) window.__glassStop();
        """
    )

    /** Снимок элемента: prompt → EV_CSS_RESULT с JSON {css, html}. */
    fun snapshotScript(cssPath: String): String = wrap(
        """
        var S = String.fromCharCode(31);
        var path = '""" + jsEsc(cssPath) + """';
        var el = null;
        try { el = document.querySelector(path); } catch (x) {}
        if (!el) { prompt('__GLASS__4301' + S + '', ''); return; }
        var css = '', html = '';
        try { css = el.style && el.style.cssText ? el.style.cssText : ''; } catch (x2) {}
        try { html = el.outerHTML || ''; } catch (x3) {}
        var obj = {css: css, html: html};
        prompt('__GLASS__4301' + S + JSON.stringify(obj), '');
        """
    )

    /** Замена outerHTML элемента. */
    fun applyHtmlScript(cssPath: String, html: String): String = wrap(
        """
        var path = '""" + jsEsc(cssPath) + """';
        var htmlVal = '""" + jsEsc(html) + """';
        var el = null;
        try { el = document.querySelector(path); } catch (x) {}
        if (!el) { return 'NOT_FOUND'; }
        var tmp = document.createElement('div');
        tmp.innerHTML = htmlVal;
        if (!tmp.firstChild) { return 'BAD_HTML'; }
        var newNode = tmp.firstChild;
        if (el.parentNode) {
          el.parentNode.replaceChild(newNode, el);
          return 'OK';
        }
        return 'NO_PARENT';
        """
    )

    /** Применение CSS-правил к элементу через CSSOM (без replaceAll с '$'). */
    fun applyCssScript(cssPath: String, cssText: String): String = wrap(
        """
        var path = '""" + jsEsc(cssPath) + """';
        var css = '""" + jsEsc(cssText) + """';
        var el = null;
        try { el = document.querySelector(path); } catch (x) {}
        if (!el) { return 'NOT_FOUND'; }
        var parts = css.split(';');
        for (var i = 0; i < parts.length; i++) {
          var p = parts[i];
          if (!p) continue;
          var c = p.indexOf(':');
          if (c < 0) continue;
          var prop = p.substring(0, c).trim();
          var val = p.substring(c + 1).trim();
          if (!prop) continue;
          if (val === '' || val === 'initial' || val === 'unset' || val === 'inherit') {
            try { el.style.removeProperty(prop); } catch (x2) {}
          } else {
            try { el.style.setProperty(prop, val); } catch (x3) {}
          }
        }
        return 'OK';
        """
    )

    /** Обёртка: try/catch, возвращает "OK" или "ERR: ...". */
    private fun wrap(body: String): String {
        val b = body.trim('\n', ' ', '\t')
        return "(function(){try{\n" + b + "\nreturn 'OK';}catch(e){return 'ERR: '+String(e);}})();"
    }

    /** Экранирование строки для вставки в одинарные кавычки JS. */
    fun jsEsc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u0000' -> { /* пропускаем NUL */ }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Обёртка произвольного пользовательского JS: возвращает JSON-строку
     * {ok:1,v:...} либо {ok:0,e:...}. Через new Function — можно return.
     */
    fun runUserCode(code: String): String {
        val escaped = jsEsc(code)
        return (
            "(function(){try{" +
                "var __f=new Function('return (function(){\\n" + escaped + "\\n})()');" +
                "var __v=__f();" +
                "return JSON.stringify({ok:1,v:(__v===undefined?'undefined':String(__v))});" +
                "}catch(e){return JSON.stringify({ok:0,e:String(e)});}})();"
            )
    }

    /** Проба WebSQL (доступность openDatabase). */
    fun webSqlProbe(): String = runUserCode(
        "var out = 'openDatabase: ';\n" +
            "try {\n" +
            "  if (typeof openDatabase !== 'function') { out += 'отсутствует (отключено в WebView)'; }\n" +
            "  else {\n" +
            "    var db = openDatabase('probe_db', '1.0', 'probe', 1024);\n" +
            "    db.transaction(function(t){ t.executeSql('CREATE TABLE IF NOT EXISTS p (id INTEGER)'); });\n" +
            "    out += 'доступна, база создана';\n" +
            "  }\n" +
            "} catch(e) { out += 'ошибка: ' + e; }\n" +
            "return out;"
    )

    /** JSON для внедрения SELECT-результата в страницу. */
    fun injectSqlScript(json: String): String {
        val safe = json.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", " ").replace("\r", " ").replace("\u0000", "")
        return "(function(){try{window.__glassSql = JSON.parse('" + safe + "');" +
            "return 'OK';}catch(e){return String(e);}})();"
    }

    /** Проверка, что страница вообще умеет в скрипты (для pick). */
    fun probe(): String = "document.title"

    fun debugEvent(ev: String, fields: Array<String>): String = ev + " → " + fields.joinToString("|")

    fun jsonLen(json: String): Int = try { JSONObject(json).length() } catch (e: Exception) { -1 }
}
