(() => {
  const $ = (id) => document.getElementById(id);
  const state = {
    sessionId: null,
    meta: null,
    running: false,
    pendingInteraction: null,
    streamingEl: null,
    streamingRaw: "",
    thinkingEl: null,
    view: "chat",
    sessionsCache: [],
    sessionFilter: "",
    traceEvents: [],
    traceFilter: "",
    pendingRestoreEventId: null,
    pendingRestoreForce: false,
    undoAvailable: false,
    editingProviderId: null,
    settingsTab: "general",
    mention: {
      open: false,
      start: -1,
      end: -1,
      query: "",
      items: [],
      index: 0,
      timer: null,
    },
  };

  const transcript = $("transcript");
  const scrollport = $("scrollport");
  const input = $("input");
  const btnSend = $("btnSend");
  const hero = $("hero");
  const traceView = $("traceView");
  const composerSeat = document.querySelector(".composer-seat");

  function shorten(path, n = 42) {
    if (!path) return "";
    const s = String(path).replace(/\\/g, "/");
    return s.length <= n ? s : "…" + s.slice(-(n - 1));
  }

  function escapeHtml(s) {
    return String(s ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  const ICO = {
    plus: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>`,
    search: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>`,
    chat: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h14v10H8l-3 3V5z"/></svg>`,
    eye: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/></svg>`,
    pencil: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>`,
    zap: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M13 2L4 14h7l-1 8 10-14h-7l1-6z"/></svg>`,
    shield: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l8 3v6c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6l8-3z"/></svg>`,
    trash: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V5h6v2M8 7l1 12h6l1-12"/></svg>`,
    more: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><circle cx="6" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="18" cy="12" r="1.5"/></svg>`,
    send: `<svg class="ico send-ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5M6 11l6-6 6 6"/></svg>`,
    stop: `<svg class="ico send-ico" viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="1.5"/></svg>`,
    terminal: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16v12H4z"/><path d="M7 10l3 2-3 2M12 14h5"/></svg>`,
    file: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3h7l5 5v13H7V3z"/><path d="M14 3v5h5"/></svg>`,
    folder: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h6l2 2h10v10H3V7z"/></svg>`,
    globe: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18"/></svg>`,
    list: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 7h11M9 12h11M9 17h11M5 7h.01M5 12h.01M5 17h.01"/></svg>`,
    help: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.8.4-1.5 1-1.5 2.2M12 17h.01"/></svg>`,
    tool: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M14.5 5.5l4 4-8.5 8.5H6v-4L14.5 5.5z"/><path d="M12 8l4 4"/></svg>`,
    undo: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M9 14l-4-4 4-4"/><path d="M5 10h7a6 6 0 0 1 0 12h-1"/></svg>`,
    copy: `<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="M4 16V6a2 2 0 0 1 2-2h10"/></svg>`,
  };

  function modeIcon(id) {
    switch (id) {
      case "chat":
        return ICO.chat;
      case "read-only":
        return ICO.eye;
      case "default":
        return ICO.pencil;
      case "auto-confirm":
        return ICO.zap;
      default:
        return ICO.shield;
    }
  }

  function kindIcon(kind) {
    return (
      {
        bash: ICO.terminal,
        edit: ICO.pencil,
        delete: ICO.trash,
        read: ICO.file,
        web: ICO.globe,
        ask: ICO.help,
        todo: ICO.list,
        generic: ICO.tool,
      }[kind] || ICO.tool
    );
  }

  function scrollBottom() {
    scrollport.scrollTop = scrollport.scrollHeight;
  }

  function hideHero() {
    if (hero) hero.style.display = "none";
  }

  function relativeTime(ms) {
    if (!ms) return "";
    const d = Date.now() - ms;
    if (d < 45_000) return "刚刚";
    if (d < 3_600_000) return `${Math.max(1, Math.round(d / 60_000))} 分钟前`;
    if (d < 86_400_000) return `${Math.max(1, Math.round(d / 3_600_000))} 小时前`;
    if (d < 7 * 86_400_000) return `${Math.max(1, Math.round(d / 86_400_000))} 天前`;
    return new Date(ms).toLocaleDateString();
  }

  function setView(view) {
    state.view = view === "trace" ? "trace" : "chat";
    $("tabChat").classList.toggle("active", state.view === "chat");
    $("tabTrace").classList.toggle("active", state.view === "trace");
    transcript.classList.toggle("hidden", state.view !== "chat");
    traceView.classList.toggle("hidden", state.view !== "trace");
    if (composerSeat) composerSeat.classList.toggle("trace-mode", state.view === "trace");
    if (state.view === "trace") loadTrace();
  }

  /** Minimal safe markdown → HTML (fences, inline code, headings, lists, emphasis). */
  function renderMarkdown(src) {
    const text = String(src ?? "").replace(/\r\n/g, "\n");
    const blocks = [];
    let i = 0;
    const fenceRe = /```([\w+-]*)\n([\s\S]*?)```/g;
    let m;
    let last = 0;
    while ((m = fenceRe.exec(text))) {
      if (m.index > last) blocks.push({ type: "text", value: text.slice(last, m.index) });
      blocks.push({ type: "code", lang: m[1] || "", value: m[2].replace(/\n$/, "") });
      last = m.index + m[0].length;
    }
    if (last < text.length) blocks.push({ type: "text", value: text.slice(last) });

    return blocks
      .map((b) => {
        if (b.type === "code") {
          if (b.lang === "diff" || looksLikeDiff(b.value)) {
            return `<pre class="md-diff">${renderDiffLines(b.value)}</pre>`;
          }
          return `<pre class="md-code"><code>${escapeHtml(b.value)}</code></pre>`;
        }
        return renderMdBlocks(b.value);
      })
      .join("");
  }

  function looksLikeDiff(s) {
    return /^(\+\+\+|---|@@|[+-])/m.test(s);
  }

  function renderDiffLines(raw) {
    return String(raw)
      .split("\n")
      .map((line) => {
        let cls = "diff-ctx";
        if (line.startsWith("+") && !line.startsWith("+++")) cls = "diff-add";
        else if (line.startsWith("-") && !line.startsWith("---")) cls = "diff-del";
        else if (line.startsWith("@@") || line.startsWith("---") || line.startsWith("+++")) cls = "diff-meta";
        return `<span class="${cls}">${escapeHtml(line) || " "}</span>`;
      })
      .join("\n");
  }

  function renderMdInline(s) {
    let t = escapeHtml(s);
    t = t.replace(/`([^`]+)`/g, "<code>$1</code>");
    t = t.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    t = t.replace(/(^|[^*\w])\*([^*\n]+)\*(?!\*)/g, "$1<em>$2</em>");
    t = t.replace(
      /\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
      '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'
    );
    return t;
  }

  function renderMdBlocks(text) {
    const lines = text.split("\n");
    const out = [];
    let para = [];
    let listType = null;
    let listItems = [];

    const flushPara = () => {
      if (!para.length) return;
      out.push(`<p>${renderMdInline(para.join(" "))}</p>`);
      para = [];
    };
    const flushList = () => {
      if (!listType) return;
      const tag = listType === "ol" ? "ol" : "ul";
      out.push(`<${tag}>${listItems.map((x) => `<li>${renderMdInline(x)}</li>`).join("")}</${tag}>`);
      listType = null;
      listItems = [];
    };

    lines.forEach((line) => {
      const heading = /^(#{1,3})\s+(.+)$/.exec(line);
      const ul = /^[-*]\s+(.+)$/.exec(line);
      const ol = /^(\d+)\.\s+(.+)$/.exec(line);
      if (heading) {
        flushPara();
        flushList();
        const n = heading[1].length;
        out.push(`<h${n}>${renderMdInline(heading[2])}</h${n}>`);
      } else if (ul) {
        flushPara();
        if (listType && listType !== "ul") flushList();
        listType = "ul";
        listItems.push(ul[1]);
      } else if (ol) {
        flushPara();
        if (listType && listType !== "ol") flushList();
        listType = "ol";
        listItems.push(ol[2]);
      } else if (!line.trim()) {
        flushPara();
        flushList();
      } else {
        flushList();
        para.push(line);
      }
    });
    flushPara();
    flushList();
    return out.join("");
  }

  function toolKind(name) {
    const n = (name || "").toLowerCase();
    if (n === "bash") return "bash";
    if (n === "edit" || n === "write") return "edit";
    if (n === "delete") return "delete";
    if (n === "read" || n === "grep" || n === "glob") return "read";
    if (n === "websearch" || n === "webfetch") return "web";
    if (n === "ask_user") return "ask";
    if (n === "todowrite") return "todo";
    return "generic";
  }

  function toolSummaryLine(data, running) {
    const name = data.name || "tool";
    const kind = toolKind(name);
    if (running) {
      if (kind === "bash") return data.command || data.inputPreview || "running…";
      if (kind === "edit" || kind === "write" || kind === "delete") return data.path || data.inputPreview || "running…";
      if (kind === "read") return data.path || data.pattern || data.inputPreview || "running…";
      return data.inputPreview || "running…";
    }
    const bits = [];
    if (data.ok === false) bits.push("失败");
    else bits.push("完成");
    if (data.latencyMs != null) bits.push(`${data.latencyMs}ms`);
    if (data.path) bits.push(shorten(data.path, 36));
    else if (data.command) bits.push(shorten(data.command, 40));
    return bits.join(" · ");
  }

  function fillToolBody(body, data) {
    body.innerHTML = "";
    const kind = toolKind(data.name);
    const output = data.output || data.outputPreview || "";

    if (kind === "edit" && (data.oldString != null || data.newString != null)) {
      const path = data.path ? `<div class="tool-path">${escapeHtml(data.path)}</div>` : "";
      const diff = renderDiffLines(
        [
          data.path ? `--- a/${data.path}` : "---",
          data.path ? `+++ b/${data.path}` : "+++",
          ...(String(data.oldString || "").split("\n").map((l) => "-" + l)),
          ...(String(data.newString || data.content || "").split("\n").map((l) => "+" + l)),
        ].join("\n")
      );
      body.innerHTML = `${path}<pre class="md-diff">${diff}</pre>`;
      return;
    }
    if (kind === "write" && data.content != null) {
      const path = data.path ? `<div class="tool-path">${escapeHtml(data.path)}</div>` : "";
      const diff = renderDiffLines(
        [`+++ ${data.path || "file"}`, ...String(data.content).split("\n").map((l) => "+" + l)].join("\n")
      );
      body.innerHTML = `${path}<pre class="md-diff">${diff}</pre>`;
      return;
    }
    if (kind === "bash") {
      const cmd = data.command || data.inputPreview || "";
      body.innerHTML = `<pre class="term"><span class="term-prompt">$</span> ${escapeHtml(cmd)}\n${escapeHtml(output)}</pre>`;
      return;
    }
    if (output.includes("```diff") || looksLikeDiff(output)) {
      body.innerHTML = renderMarkdown(output);
      return;
    }
    body.innerHTML = `<pre class="tool-plain">${escapeHtml(output || data.inputPreview || "")}</pre>`;
  }

  function classifyEvent(type) {
    if (!type) return { kind: "system", label: "系统" };
    if (type === "request.header") return { kind: "context", label: "上下文" };
    if (type === "user.message" || type === "turn.start") return { kind: "user", label: "用户" };
    if (type === "assistant.message" || type === "turn.end") return { kind: "assistant", label: "回答" };
    if (type.startsWith("tool.") || type === "tool.result") return { kind: "tool", label: "工具" };
    if (type === "file.mutate") return { kind: "tool", label: "文件" };
    if (type.startsWith("model.")) return { kind: "assistant", label: "助手" };
    if (type.startsWith("session.")) return { kind: "user", label: "会话" };
    if (type === "error") return { kind: "error", label: "错误" };
    if (type === "compact" || type === "summary" || type === "permission.mode") {
      return { kind: "system", label: "系统" };
    }
    return { kind: "system", label: "系统" };
  }

  function formatPayload(type, payload) {
    const p = payload || {};
    if (type === "request.header" && p.system) {
      const sys = String(p.system);
      return (
        `messageCount=${p.messageCount ?? "—"}\nhasSummary=${p.hasSummary ?? "—"}\n\n` +
        (sys.length > 4000 ? sys.slice(0, 4000) + "\n…(truncated)" : sys)
      );
    }
    if ((type === "user.message" || type === "assistant.message" || type === "tool.result") && p.content != null) {
      const c = String(p.content);
      const bits = [];
      if (p.toolUseId) bits.push(`toolUseId=${p.toolUseId}`);
      if (p.toolCalls) bits.push(`toolCalls=${Array.isArray(p.toolCalls) ? p.toolCalls.length : 1}`);
      bits.push(c.length > 4000 ? c.slice(0, 4000) + "\n…" : c);
      return bits.join("\n");
    }
    const bits = [];
    const keys = [
      "name",
      "ok",
      "latencyMs",
      "stopReason",
      "reason",
      "model",
      "messageCount",
      "toolCallNames",
      "userPreview",
      "assistantPreview",
      "textPreview",
      "inputPreview",
      "outputPreview",
      "output",
      "message",
      "where",
      "mode",
      "path",
      "command",
      "pattern",
      "query",
      "url",
      "oldString",
      "newString",
      "content",
      "hasSummary",
    ];
    keys.forEach((k) => {
      if (p[k] === undefined || p[k] === null || p[k] === "") return;
      let v = p[k];
      if (Array.isArray(v)) v = v.join(", ");
      if (typeof v === "string" && v.length > 600) v = v.slice(0, 599) + "…";
      bits.push(`${k}=${v}`);
    });
    return bits.join("\n");
  }

  async function loadTrace() {
    const turnsEl = $("traceTurns");
    if (!state.sessionId) {
      turnsEl.innerHTML = "";
      $("traceStats").innerHTML = "";
      $("traceEmpty").style.display = "";
      $("traceMeta").textContent = "runtime events";
      return;
    }
    const events = await api(`/api/sessions/${state.sessionId}/events?limit=0`);
    state.traceEvents = events || [];
    renderTrace();
  }

  function renderTrace() {
    const turnsEl = $("traceTurns");
    turnsEl.innerHTML = "";
    const q = (state.traceFilter || "").trim().toLowerCase();
    let events = state.traceEvents || [];
    if (q) {
      events = events.filter((e) => {
        const blob = `${e.type || ""} ${e.turnId || ""} ${formatPayload(e.type, e.payload)}`.toLowerCase();
        return blob.includes(q);
      });
    }
    $("traceMeta").textContent = `${events.length} / ${state.traceEvents.length} events`;
    if (!events.length) {
      $("traceEmpty").style.display = "";
      $("traceStats").innerHTML = "";
      return;
    }
    $("traceEmpty").style.display = "none";

    const groups = new Map();
    const order = [];
    events.forEach((e) => {
      const key = e.turnId || "_session";
      if (!groups.has(key)) {
        groups.set(key, []);
        order.push(key);
      }
      groups.get(key).push(e);
    });

    let toolMs = 0;
    let toolCount = 0;
    let turnCount = 0;
    order.forEach((key) => {
      const list = groups.get(key);
      const start = list.find((x) => x.type === "turn.start");
      const end = [...list].reverse().find((x) => x.type === "turn.end");
      if (start) turnCount++;
      const dur =
        start && end && end.ts && start.ts ? Math.max(0, end.ts - start.ts) : null;
      list.forEach((e) => {
        if (e.type === "tool.end" && e.payload && e.payload.latencyMs != null) {
          toolMs += Number(e.payload.latencyMs) || 0;
          toolCount++;
        }
      });

      const card = document.createElement("section");
      card.className = "trace-turn";
      const head = document.createElement("header");
      head.className = "trace-turn-head";
      const title = key === "_session" ? "会话事件" : `Turn · ${key.slice(0, 8)}`;
      const preview =
        (start && start.payload && start.payload.userPreview) ||
        (end && end.payload && end.payload.assistantPreview) ||
        "";
      head.innerHTML = `<div class="trace-turn-title"></div><div class="trace-turn-meta"></div>`;
      head.querySelector(".trace-turn-title").textContent = title;
      head.querySelector(".trace-turn-meta").textContent = [
        dur != null ? `${dur}ms` : null,
        `${list.length} events`,
        preview ? shorten(preview, 48) : null,
      ]
        .filter(Boolean)
        .join(" · ");
      card.appendChild(head);

      const rail = document.createElement("div");
      rail.className = "trace-rail-bar";
      if (dur != null) {
        const toolsInTurn = list.filter((x) => x.type === "tool.end");
        const tms = toolsInTurn.reduce((a, x) => a + (Number(x.payload?.latencyMs) || 0), 0);
        const pct = dur > 0 ? Math.min(100, Math.round((tms / dur) * 100)) : 0;
        rail.innerHTML = `<span class="rail-llm" style="width:${100 - pct}%"></span><span class="rail-tool" style="width:${pct}%"></span>`;
      }
      card.appendChild(rail);

      const listEl = document.createElement("div");
      listEl.className = "trace-event-list";
      list.forEach((e) => {
        const cls = classifyEvent(e.type);
        const row = document.createElement("div");
        row.className = `trace-event ${cls.kind}`;
        row.innerHTML = `
          <div class="te-left"><span class="dot"></span><span class="te-type"></span></div>
          <div class="te-body"><div class="te-title"></div><pre class="te-content"></pre></div>
          <div class="te-time"></div>`;
        row.querySelector(".te-type").textContent = cls.label;
        row.querySelector(".te-title").textContent = e.type || "?";
        row.querySelector(".te-content").textContent = formatPayload(e.type, e.payload);
        row.querySelector(".te-time").textContent = e.ts
          ? new Date(e.ts).toLocaleTimeString()
          : "";
        listEl.appendChild(row);
      });
      card.appendChild(listEl);
      turnsEl.appendChild(card);
    });

    $("traceStats").innerHTML = `
      <div class="stat"><span class="stat-k">回合</span><span class="stat-v">${turnCount}</span></div>
      <div class="stat"><span class="stat-k">工具调用</span><span class="stat-v">${toolCount}</span></div>
      <div class="stat"><span class="stat-k">工具耗时</span><span class="stat-v">${toolMs}ms</span></div>
      <div class="stat"><span class="stat-k">事件</span><span class="stat-v">${events.length}</span></div>`;
  }

  function addUser(text, eventId) {
    hideHero();
    const wrap = document.createElement("div");
    wrap.className = "msg user";
    wrap.innerHTML = `
      <div class="role">you</div>
      <div class="bubble"></div>
      <div class="msg-actions">
        <button type="button" class="msg-act copy-btn" title="复制提示词" aria-label="复制提示词">${ICO.copy}</button>
      </div>`;
    wrap.querySelector(".bubble").textContent = text;
    wrap.querySelector(".copy-btn").onclick = async (e) => {
      e.stopPropagation();
      await copyUserPrompt(wrap);
    };
    transcript.appendChild(wrap);
    if (eventId) attachCheckpointBtn(wrap, eventId);
    scrollBottom();
    return wrap;
  }

  async function copyUserPrompt(wrap) {
    const text = wrap?.querySelector(".bubble")?.textContent ?? "";
    const btn = wrap?.querySelector(".copy-btn");
    try {
      await navigator.clipboard.writeText(text);
      if (btn) {
        btn.title = "已复制";
        btn.classList.add("copied");
        setTimeout(() => {
          btn.title = "复制提示词";
          btn.classList.remove("copied");
        }, 1200);
      }
    } catch {
      addStatus("复制失败");
    }
  }

  function attachCheckpointBtn(wrap, eventId) {
    if (!wrap || !eventId) return;
    wrap.dataset.eventId = eventId;
    const actions = wrap.querySelector(".msg-actions");
    if (!actions) return;
    let btn = actions.querySelector(".ckpt-btn");
    if (!btn) {
      btn = document.createElement("button");
      btn.type = "button";
      btn.className = "msg-act ckpt-btn";
      btn.title = "恢复到此处";
      btn.setAttribute("aria-label", "恢复到此处");
      btn.innerHTML = ICO.undo;
      actions.appendChild(btn);
    }
    btn.onclick = (e) => {
      e.stopPropagation();
      requestRestore(eventId);
    };
  }

  /** Bind checkpoint id to the latest user bubble that still lacks one (live send). */
  function bindLatestUserCheckpoint(eventId) {
    if (!eventId) return;
    const users = transcript.querySelectorAll(".msg.user");
    for (let i = users.length - 1; i >= 0; i--) {
      const el = users[i];
      if (!el.dataset.eventId) {
        attachCheckpointBtn(el, eventId);
        return;
      }
    }
  }

  const RESTORE_SKIP_KEY = "zcode.restore.dontAsk";

  function setUndoBar(available) {
    state.undoAvailable = !!available;
    const bar = $("undoBar");
    if (bar) bar.classList.toggle("hidden", !available);
  }

  async function refreshUndoBar() {
    if (!state.sessionId) {
      setUndoBar(false);
      return;
    }
    try {
      const st = await api(`/api/sessions/${state.sessionId}/restore/undo`);
      setUndoBar(!!st.available);
    } catch {
      setUndoBar(false);
    }
  }

  function hideRestoreModal() {
    state.pendingRestoreEventId = null;
    state.pendingRestoreForce = false;
    $("restoreMask").classList.add("hidden");
  }

  function showRestoreModal(eventId, conflicts) {
    state.pendingRestoreEventId = eventId;
    state.pendingRestoreForce = true;
    $("restoreDontAsk").checked = false;
    const list = $("restoreConflicts");
    const title = $("restoreTitle");
    const sub = $("restoreSub");
    const rows = Array.isArray(conflicts) ? conflicts : [];
    if (list) {
      list.innerHTML = "";
      if (rows.length) {
        list.classList.remove("hidden");
        rows.forEach((c) => {
          const li = document.createElement("li");
          const path = c && c.path ? c.path : "";
          const reason = c && c.reason ? c.reason : "本地状态不一致";
          li.innerHTML = `<code></code><span></span>`;
          li.querySelector("code").textContent = path;
          li.querySelector("span").textContent = reason;
          list.appendChild(li);
        });
        if (title) title.textContent = `将覆盖 ${rows.length} 处本地改动，仍要回滚？`;
        if (sub) sub.textContent = "以下路径既不是检查点状态，也不是 AI 改完后的状态（可能被手改过）。回滚后仍可撤销。";
      } else {
        list.classList.add("hidden");
        if (title) title.textContent = "丢弃此检查点之后的所有更改？";
        if (sub) sub.textContent = "之后仍可撤销";
      }
    }
    $("restoreMask").classList.remove("hidden");
  }

  async function requestRestore(eventId) {
    if (!eventId || !state.sessionId || state.running) return;
    try {
      const preview = await api(`/api/sessions/${state.sessionId}/restore`, {
        method: "POST",
        body: JSON.stringify({ eventId, force: false }),
      });
      const conflicts = (preview && preview.conflicts) || [];
      if (conflicts.length) {
        showRestoreModal(eventId, conflicts);
        return;
      }
      if (localStorage.getItem(RESTORE_SKIP_KEY) === "1") {
        await doRestore(eventId, true);
        return;
      }
      showRestoreModal(eventId, []);
    } catch (e) {
      addStatus("回滚预览失败 · " + (e.message || e));
    }
  }

  async function doRestore(eventId, force) {
    if (!eventId || !state.sessionId) return;
    try {
      const res = await api(`/api/sessions/${state.sessionId}/restore`, {
        method: "POST",
        body: JSON.stringify({ eventId, force: !!force }),
      });
      if (res && res.needsConfirm && (res.conflicts || []).length) {
        showRestoreModal(eventId, res.conflicts);
        return;
      }
      hideRestoreModal();
      setUndoBar(!!res.undoAvailable);
      await openSession(state.sessionId);
      if (res.undoAvailable) setUndoBar(true);
      const n = (res.conflicts || []).length;
      addStatus(
        n
          ? `已回滚 · 还原 ${res.filesRestored ?? 0} 个文件（覆盖 ${n} 处本地改动）`
          : `已回滚 · 还原 ${res.filesRestored ?? 0} 个文件`
      );
    } catch (e) {
      hideRestoreModal();
      addStatus("回滚失败 · " + (e.message || e));
    }
  }

  async function doUndoRestore() {
    if (!state.sessionId || state.running) return;
    try {
      await api(`/api/sessions/${state.sessionId}/restore/undo`, { method: "POST", body: "{}" });
      setUndoBar(false);
      await openSession(state.sessionId);
      addStatus("已撤销回滚");
    } catch (e) {
      addStatus("撤销失败 · " + (e.message || e));
    }
  }

  function activeProviderLabel() {
    const llm = state.meta && state.meta.llm;
    if (!llm) return "";
    const providers = llm.providers || [];
    const active = providers.find((p) => p.id === llm.activeId) || providers[0];
    if (!active) return "";
    return String(active.name || active.model || "").trim();
  }

  function assistantRoleHtml() {
    const name = activeProviderLabel();
    if (!name) return `<div class="role">assistant</div>`;
    return `<div class="role">assistant<span class="role-provider"> · ${escapeHtml(name)}</span></div>`;
  }

  function addAssistantMarkdown(text) {
    hideHero();
    const wrap = document.createElement("div");
    wrap.className = "msg assistant";
    wrap.innerHTML = `${assistantRoleHtml()}<div class="bubble md"></div>`;
    wrap.querySelector(".bubble").innerHTML = renderMarkdown(text || "");
    transcript.appendChild(wrap);
    scrollBottom();
  }

  function ensureAssistantStream() {
    hideThinking();
    if (state.streamingEl) return state.streamingEl;
    hideHero();
    const wrap = document.createElement("div");
    wrap.className = "msg assistant";
    wrap.innerHTML = `${assistantRoleHtml()}<div class="bubble streaming"></div>`;
    transcript.appendChild(wrap);
    state.streamingEl = wrap.querySelector(".bubble");
    state.streamingRaw = "";
    return state.streamingEl;
  }

  function endAssistantStream() {
    if (state.streamingEl) {
      const raw = state.streamingRaw || state.streamingEl.textContent || "";
      state.streamingEl.classList.remove("streaming");
      state.streamingEl.classList.add("md");
      state.streamingEl.innerHTML = renderMarkdown(raw);
      state.streamingEl = null;
      state.streamingRaw = "";
    }
  }

  function showThinking() {
    hideHero();
    if (state.thinkingEl && state.thinkingEl.isConnected) return;
    const el = document.createElement("div");
    el.className = "thinking-line";
    el.innerHTML = `<span class="think-spinner" aria-hidden="true"></span><span>思考中</span>`;
    transcript.appendChild(el);
    state.thinkingEl = el;
    scrollBottom();
  }

  function hideThinking() {
    if (state.thinkingEl) {
      state.thinkingEl.remove();
      state.thinkingEl = null;
    }
  }

  function addStatus(text) {
    hideThinking();
    const el = document.createElement("div");
    el.className = "status-line";
    el.textContent = text;
    transcript.appendChild(el);
    scrollBottom();
  }

  function addToolCard(data, running = true) {
    hideThinking();
    hideHero();
    const payload = typeof data === "string" ? { name: data } : { ...(data || {}) };
    const name = payload.name || "tool";
    payload.name = name;
    const kind = toolKind(name);
    const card = document.createElement("div");
    card.className = `tool-card kind-${kind}${running ? "" : " open"}`;
    card.dataset.name = name;
    card.innerHTML = `
      <div class="tool-head">
        <span class="tool-dot ${running ? "run" : "ok"}"></span>
        <span class="tool-kind-ico">${kindIcon(kind)}</span>
        <span class="tool-badge"></span>
        <span class="tool-title"></span>
        <span class="tool-summary"></span>
        <svg class="ico tool-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>
      </div>
      <div class="tool-body"></div>`;
    card.querySelector(".tool-badge").textContent = name;
    card.querySelector(".tool-title").textContent = kindLabel(kind);
    card.querySelector(".tool-summary").textContent = toolSummaryLine(payload, running);
    fillToolBody(card.querySelector(".tool-body"), payload);
    card.querySelector(".tool-head").onclick = () => card.classList.toggle("open");
    transcript.appendChild(card);
    scrollBottom();
    return card;
  }

  function kindLabel(kind) {
    return (
      {
        bash: "终端",
        edit: "编辑",
        read: "读取",
        web: "网络",
        ask: "提问",
        todo: "待办",
        generic: "工具",
      }[kind] || "工具"
    );
  }

  function finishToolCard(data) {
    const name = data.name || "";
    const cards = [...transcript.querySelectorAll(".tool-card")].reverse();
    let card = cards.find((c) => c.dataset.name === name && c.querySelector(".tool-dot.run"));
    if (!card) {
      card = addToolCard(data, false);
    }
    const ok = data.ok !== false;
    const dot = card.querySelector(".tool-dot");
    dot.classList.remove("run");
    dot.classList.add(ok ? "ok" : "fail");
    card.classList.toggle("fail", !ok);
    card.querySelector(".tool-summary").textContent = toolSummaryLine(data, false);
    fillToolBody(card.querySelector(".tool-body"), data);
    if (!ok) card.classList.add("open");
    if (state.running) showThinking();
  }

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      headers: { "Content-Type": "application/json", ...(opts.headers || {}) },
      ...opts,
    });
    const text = await res.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = { raw: text };
    }
    if (!res.ok) throw new Error((data && data.error) || res.statusText || "request failed");
    return data;
  }

  async function loadMeta() {
    state.meta = await api("/api/meta");
    renderCfg();
    if (state.sessionId && state.sessionsCache) {
      const info = state.sessionsCache.find((s) => s.id === state.sessionId);
      applyWorkspaceToChrome(effectiveWorkspace(info));
    } else {
      applyWorkspaceToChrome(
        (state.meta && state.meta.defaultWorkspace) || (state.meta && state.meta.workspace)
      );
    }
    renderGeneralSettings();
    renderSkillsSettings();
    renderLlmForm(state.meta.llm || {});
  }

  function renderGeneralSettings() {
    const modeSel = $("settingsMode");
    if (!modeSel) return;
    const mode = (state.meta && state.meta.permissionMode) || "";
    modeSel.innerHTML = "";
    (state.meta.modes || []).forEach((m) => {
      const opt = document.createElement("option");
      opt.value = m.id;
      opt.textContent = `${m.id} — ${m.description || ""}`;
      if (m.id === mode) opt.selected = true;
      modeSel.appendChild(opt);
    });
    $("settingsWorkspace").textContent = (state.meta && state.meta.workspace) || "—";
    $("settingsModelLabel").textContent = (state.meta && state.meta.model) || "—";
    $("settingsTools").textContent = ((state.meta && state.meta.tools) || []).join(", ") || "—";
  }

  function normalizeSkills(raw) {
    if (!Array.isArray(raw)) return [];
    return raw
      .map((s) => {
        if (typeof s === "string") return { name: s, description: "", path: "" };
        if (s && typeof s === "object") {
          return {
            name: s.name || "",
            description: s.description || "",
            path: s.path || "",
          };
        }
        return null;
      })
      .filter((s) => s && s.name);
  }

  function renderSkillsSettings() {
    const list = $("settingsSkillList");
    const empty = $("settingsSkillEmpty");
    if (!list) return;
    const skills = normalizeSkills(state.meta && state.meta.skills);
    list.innerHTML = "";
    if (empty) empty.hidden = skills.length > 0;
    skills.forEach((s) => {
      const row = document.createElement("div");
      row.className = "skill-item";
      const title = document.createElement("div");
      title.className = "skill-item-name";
      title.textContent = s.name;
      row.appendChild(title);
      if (s.description) {
        const desc = document.createElement("div");
        desc.className = "skill-item-desc";
        desc.textContent = s.description;
        row.appendChild(desc);
      }
      if (s.path) {
        const path = document.createElement("div");
        path.className = "skill-item-path";
        path.textContent = s.path;
        path.title = s.path;
        row.appendChild(path);
      }
      list.appendChild(row);
    });
  }

  function setSettingsTab(tab) {
    state.settingsTab = tab || "general";
    document.querySelectorAll(".settings-nav-item").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.settingsTab === tab);
    });
    document.querySelectorAll(".settings-panel").forEach((panel) => {
      panel.classList.toggle("active", panel.dataset.settingsPanel === tab);
    });
    updateSettingsHeadAction();
    if (tab === "skills") {
      renderSkillsSettings();
    }
  }

  function updateSettingsHeadAction() {
    const btn = $("btnOpenLlmFile");
    if (!btn) return;
    if (state.settingsTab === "skills") {
      btn.textContent = "打开 skills 文件夹";
      btn.title = "在资源管理器中打开 .zcode/skills";
    } else {
      btn.textContent = "打开配置文件";
      btn.title = "复制配置文件路径";
    }
  }

  function renderLlmForm(llm) {
    const providers = llm.providers || [];
    const activeId = llm.activeId || (providers[0] && providers[0].id) || "";
    if (!state.editingProviderId || !providers.some((p) => p.id === state.editingProviderId)) {
      state.editingProviderId = activeId;
    }
    const editing = providers.find((p) => p.id === state.editingProviderId) || providers[0] || null;

    const list = $("providerList");
    if (list) {
      list.innerHTML = "";
      providers.forEach((p) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "provider-chip" + (p.id === state.editingProviderId ? " active" : "");
        const label = p.name || p.model || p.id;
        btn.textContent = (p.active ? "● " : "") + label;
        btn.title = p.active ? "当前使用" : "编辑此提供方";
        btn.onclick = () => {
          state.editingProviderId = p.id;
          renderLlmForm(state.meta.llm || llm);
        };
        list.appendChild(btn);
      });
    }

    const idEl = $("llmProviderId");
    const nameEl = $("llmName");
    const apiEl = $("llmApi");
    const baseEl = $("llmBaseUrl");
    const keyEl = $("llmApiKey");
    const modelEl = $("llmModel");
    const hint = $("llmKeyHint");
    const status = $("llmStatus");
    if (!apiEl || !nameEl || !baseEl || !keyEl || !modelEl || !editing) return;

    if (idEl) idEl.value = editing.id || "";
    nameEl.value = editing.name || "";
    apiEl.value = editing.api === "openai" ? "openai" : "anthropic";
    baseEl.value = editing.baseUrl || "";
    modelEl.value = editing.model || "";
    keyEl.value = "";
    keyEl.placeholder = editing.apiKeyConfigured
      ? (editing.apiKeyMasked ? `已配置 ${editing.apiKeyMasked}` : "已配置（留空则保留）")
      : "输入 API Key";

    const isActive = editing.id === activeId;
    apiEl.disabled = !!llm.apiLocked && isActive;
    baseEl.disabled = !!llm.baseUrlLocked && isActive;
    keyEl.disabled = !!llm.apiKeyLocked && isActive;
    modelEl.disabled = !!llm.modelLocked && isActive;

    const locks = [];
    if (llm.apiKeyLocked && isActive) locks.push("由启动环境提供（只读）");
    else locks.push("密钥不会回显；留空则保留已有配置");
    if (llm.baseUrlLocked && isActive) locks.push("地址由环境变量锁定");
    if (llm.modelLocked && isActive) locks.push("模型由环境变量锁定");
    if (hint) hint.textContent = locks.join(" · ");

    const configured = !!editing.apiKeyConfigured;
    if (status) {
      status.className = "llm-status " + (configured ? "ok" : "warn");
      if (configured && isActive && llm.apiKeyLocked) {
        status.textContent = "API Key 由环境变量提供";
      } else {
        status.textContent = configured ? "API Key 已配置" : "API Key 未配置";
      }
    }

    const del = $("btnDeleteProvider");
    if (del) del.disabled = providers.length <= 1;
    const useBtn = $("btnUseProvider");
    if (useBtn) {
      useBtn.disabled = isActive;
      useBtn.textContent = isActive ? "当前使用中" : "设为当前";
    }
  }

  async function saveLlm() {
    const status = $("llmStatus");
    try {
      status.className = "llm-status";
      status.textContent = "保存中…";
      const body = {
        id: $("llmProviderId").value,
        name: $("llmName").value,
        api: $("llmApi").value,
        baseUrl: $("llmBaseUrl").value,
        model: $("llmModel").value,
        apiKey: $("llmApiKey").value,
      };
      const llm = await api("/api/llm", { method: "PUT", body: JSON.stringify(body) });
      applyLlmMeta(llm);
      status.className = "llm-status ok";
      status.textContent = "已保存";
      setTimeout(() => renderLlmForm(llm), 800);
    } catch (e) {
      status.className = "llm-status err";
      status.textContent = "保存失败 · " + (e.message || e);
    }
  }

  function applyLlmMeta(llm) {
    if (state.meta) {
      state.meta.llm = llm;
      state.meta.model = llm.model;
    }
    renderLlmForm(llm);
    renderCfg();
    renderGeneralSettings();
  }

  async function addProvider() {
    try {
      const llm = await api("/api/llm/providers", {
        method: "POST",
        body: JSON.stringify({ name: "新提供方", api: "anthropic" }),
      });
      state.editingProviderId = llm.createdId || llm.activeId;
      applyLlmMeta(llm);
    } catch (e) {
      addStatus("添加失败 · " + (e.message || e));
    }
  }

  async function deleteProvider() {
    const id = $("llmProviderId").value;
    if (!id) return;
    if (!confirm("删除该提供方？")) return;
    try {
      const llm = await api(`/api/llm/providers/${encodeURIComponent(id)}`, { method: "DELETE" });
      state.editingProviderId = llm.activeId;
      applyLlmMeta(llm);
    } catch (e) {
      addStatus("删除失败 · " + (e.message || e));
    }
  }

  async function useProvider() {
    const id = $("llmProviderId").value;
    if (!id) return;
    try {
      const llm = await api("/api/llm/active", {
        method: "PUT",
        body: JSON.stringify({ id }),
      });
      applyLlmMeta(llm);
    } catch (e) {
      addStatus("切换失败 · " + (e.message || e));
    }
  }

  async function selectProvider(id) {
    const llm = await api("/api/llm/active", {
      method: "PUT",
      body: JSON.stringify({ id }),
    });
    applyLlmMeta(llm);
  }

  async function copyLlmSettingsPath() {
    const path = (state.meta && state.meta.llm && state.meta.llm.settingsFile) || "";
    if (!path) {
      addStatus("未找到配置文件路径");
      return;
    }
    try {
      await navigator.clipboard.writeText(path);
      addStatus("已复制配置路径 · " + path);
    } catch {
      addStatus("配置文件 · " + path);
    }
  }

  async function openSkillsFolder() {
    addStatus("正在打开 skills 文件夹…");
    try {
      const res = await api("/api/skills/open-dir", { method: "POST", body: "{}" });
      const path = (res && res.path) || (state.meta && state.meta.skillsDir) || ".zcode/skills";
      addStatus("已打开 skills 文件夹 · " + path);
    } catch (e) {
      const path = (state.meta && state.meta.skillsDir) || "";
      if (path) {
        try {
          await navigator.clipboard.writeText(path);
          addStatus("无法直接打开，已复制路径 · " + path);
          return;
        } catch {
          /* ignore */
        }
      }
      addStatus("打开 skills 文件夹失败 · " + (e.message || e));
    }
  }

  function onSettingsHeadAction() {
    if (state.settingsTab === "skills") {
      openSkillsFolder();
    } else {
      copyLlmSettingsPath();
    }
  }

  function renderCfg() {
    const llm = (state.meta && state.meta.llm) || {};
    const providers = llm.providers || [];
    const active = providers.find((p) => p.id === llm.activeId) || providers[0];
    const modelLabel = (active && (active.name || active.model)) || (state.meta && state.meta.model) || "model";
    const mode = (state.meta && state.meta.permissionMode) || "—";
    const modelPillText = $("modelPillText");
    const modePillText = $("modePillText");
    const modePillIcon = $("modePillIcon");
    if (modelPillText) modelPillText.textContent = modelLabel;
    if (modePillText) modePillText.textContent = mode;
    if (modePillIcon) modePillIcon.innerHTML = modeIcon(mode);

    const providerList = $("cfgProviderList");
    if (providerList) {
      providerList.innerHTML = "";
      providers.forEach((p) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "cfg-mode-item" + (p.id === llm.activeId ? " active" : "");
        const title = p.name || p.model || p.id;
        const desc = [p.api, p.model].filter(Boolean).join(" · ") || (p.apiKeyConfigured ? "已配置 Key" : "未配置 Key");
        btn.innerHTML = `<span class="cfg-mode-ico">${ICO.zap}</span><span class="cfg-mode-copy"><span class="cfg-mode-id"></span><span class="cfg-mode-desc"></span></span>`;
        btn.querySelector(".cfg-mode-id").textContent = title;
        btn.querySelector(".cfg-mode-desc").textContent = desc;
        btn.onclick = async () => {
          try {
            await selectProvider(p.id);
            closeAllCfg();
          } catch (e) {
            addStatus("切换失败 · " + (e.message || e));
          }
        };
        providerList.appendChild(btn);
      });
      if (!providers.length) {
        const empty = document.createElement("div");
        empty.className = "cfg-row static";
        empty.textContent = "还没有提供方，去设置 → 模型 添加";
        providerList.appendChild(empty);
      }
    }

    const list = $("cfgModeList");
    if (!list) return;
    list.innerHTML = "";
    ((state.meta && state.meta.modes) || []).forEach((m) => {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "cfg-mode-item" + (m.id === mode ? " active" : "");
      btn.innerHTML = `<span class="cfg-mode-ico">${modeIcon(m.id)}</span><span class="cfg-mode-copy"><span class="cfg-mode-id"></span><span class="cfg-mode-desc"></span></span>`;
      btn.querySelector(".cfg-mode-id").textContent = m.id;
      btn.querySelector(".cfg-mode-desc").textContent = m.description || "";
      btn.onclick = async () => {
        await api("/api/permission/mode", {
          method: "PUT",
          body: JSON.stringify({ mode: m.id }),
        });
        await loadMeta();
        closeAllCfg();
      };
      list.appendChild(btn);
    });
  }

  function closeAllCfg() {
    ["model", "mode"].forEach((key) => {
      const pill = $(`${key}Pill`);
      const pop = $(`${key}Popover`);
      if (pop) pop.classList.add("hidden");
      if (pill) {
        pill.classList.remove("open");
        pill.setAttribute("aria-expanded", "false");
      }
    });
  }

  function toggleCfg(key) {
    const pill = $(`${key}Pill`);
    const pop = $(`${key}Popover`);
    const open = !pop.classList.contains("hidden");
    closeAllCfg();
    if (!open) {
      pop.classList.remove("hidden");
      pill.classList.add("open");
      pill.setAttribute("aria-expanded", "true");
    }
  }

  function workspaceLabel(path) {
    if (!path) return "未指定工作区";
    const s = String(path).replace(/\\/g, "/");
    const parts = s.split("/").filter(Boolean);
    return parts.length ? parts[parts.length - 1] : s;
  }

  function effectiveWorkspace(session) {
    return (session && session.workspace)
      || (state.meta && state.meta.defaultWorkspace)
      || (state.meta && state.meta.workspace)
      || "";
  }

  function applyWorkspaceToChrome(workspacePath) {
    const path = workspacePath || (state.meta && state.meta.defaultWorkspace) || "";
    const chip = $("workspaceChipText");
    if (chip) chip.textContent = shorten(path, 48) || "workspace";
    const sub = $("sessionSub");
    if (sub) sub.textContent = path ? shorten(path, 64) : "";
    if (state.meta) state.meta.workspace = path;
  }

  async function refreshSessions() {
    const list = await api("/api/sessions");
    state.sessionsCache = list || [];
    renderSessionList();
  }

  function renderSessionList() {
    const box = $("sessionList");
    if (!box) return;
    box.innerHTML = "";
    closeAllSessionMenus();
    const q = (state.sessionFilter || "").trim().toLowerCase();
    const list = (state.sessionsCache || []).filter((s) => {
      if (!q) return true;
      const title = (s.displayName || s.title || s.id || "").toLowerCase();
      const ws = (s.workspace || "").toLowerCase();
      return title.includes(q) || String(s.id).toLowerCase().includes(q) || ws.includes(q);
    });

    const groups = new Map();
    list.forEach((s) => {
      const key = effectiveWorkspace(s) || "__none__";
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(s);
    });

    const def = (state.meta && state.meta.defaultWorkspace) || "";
    const keys = Array.from(groups.keys()).sort((a, b) => {
      if (a === def) return -1;
      if (b === def) return 1;
      return workspaceLabel(a).localeCompare(workspaceLabel(b), "zh");
    });

    keys.forEach((wsKey) => {
      const sessions = groups.get(wsKey) || [];
      const group = document.createElement("div");
      group.className = "session-group";
      const head = document.createElement("div");
      head.className = "session-group-head";
      head.title = wsKey === "__none__" ? "" : wsKey;
      head.innerHTML = `<span class="ws-name"></span><span class="ws-count"></span>`;
      head.querySelector(".ws-name").textContent =
        wsKey === "__none__" ? "未指定工作区" : workspaceLabel(wsKey);
      head.querySelector(".ws-count").textContent = String(sessions.length);
      group.appendChild(head);

      sessions.forEach((s) => {
        const row = document.createElement("div");
        row.className = "session-item" + (s.id === state.sessionId ? " active" : "");
        row.dataset.id = s.id;

        const main = document.createElement("button");
        main.type = "button";
        main.className = "session-item-main";
        const title = s.displayName || s.title || s.id;
        const isCustom = !!(s.title && String(s.title).trim());
        main.innerHTML = `<span class="sid${isCustom ? "" : " mono"}"></span><span class="meta"></span>`;
        main.querySelector(".sid").textContent = title;
        main.querySelector(".meta").textContent = `${s.messageCount ?? 0} 问 · ${relativeTime(s.mtimeMs)}`;
        main.onclick = () => openSession(s.id);

        const more = document.createElement("button");
        more.type = "button";
        more.className = "session-more";
        more.title = "更多";
        more.innerHTML = ICO.more;
        more.onclick = (e) => {
          e.stopPropagation();
          toggleSessionMenu(row, more, menu);
        };

        const menu = document.createElement("div");
        menu.className = "session-menu";
        const renameBtn = document.createElement("button");
        renameBtn.type = "button";
        renameBtn.innerHTML = `${ICO.pencil}<span>重命名</span>`;
        renameBtn.onclick = async (e) => {
          e.stopPropagation();
          closeAllSessionMenus();
          const current = s.title || "";
          const next = window.prompt("会话名称", current || title);
          if (next === null) return;
          const updated = await api(`/api/sessions/${s.id}`, {
            method: "PUT",
            body: JSON.stringify({ title: next.trim() }),
          });
          if (state.sessionId === s.id) {
            $("sessionTitle").textContent = (updated && (updated.displayName || updated.title)) || s.id;
          }
          await refreshSessions();
        };

        const wsBtn = document.createElement("button");
        wsBtn.type = "button";
        wsBtn.innerHTML = `${ICO.folder}<span>切换工作区</span>`;
        wsBtn.onclick = async (e) => {
          e.stopPropagation();
          closeAllSessionMenus();
          await changeSessionWorkspace(s);
        };

        const deleteBtn = document.createElement("button");
        deleteBtn.type = "button";
        deleteBtn.className = "danger";
        deleteBtn.innerHTML = `${ICO.trash}<span>删除</span>`;
        deleteBtn.onclick = async (e) => {
          e.stopPropagation();
          closeAllSessionMenus();
          const label = s.displayName || s.title || s.id;
          if (!window.confirm(`删除会话「${label}」？此操作不可恢复。`)) return;
          try {
            await api(`/api/sessions/${s.id}`, { method: "DELETE" });
            if (state.sessionId === s.id) {
              state.sessionId = null;
              $("sessionTitle").textContent = "—";
              transcript.innerHTML = "";
              transcript.appendChild(hero);
              hero.style.display = "";
              endAssistantStream();
              $("traceTurns").innerHTML = "";
              $("traceStats").innerHTML = "";
              applyWorkspaceToChrome(state.meta && state.meta.defaultWorkspace);
            }
            const remaining = await api("/api/sessions");
            await refreshSessions();
            if (state.sessionId) return;
            if (remaining.length) await openSession(remaining[0].id);
            else {
              const created = await api("/api/sessions", {
                method: "POST",
                body: JSON.stringify({ resume: false }),
              });
              await openSession(created.sessionId);
            }
          } catch (err) {
            addStatus("删除失败 · " + (err.message || err));
          }
        };
        menu.append(renameBtn, wsBtn, deleteBtn);
        row.append(main, more, menu);
        group.appendChild(row);
      });
      box.appendChild(group);
    });
  }

  async function changeSessionWorkspace(session) {
    const def = (state.meta && state.meta.defaultWorkspace) || "";
    const current = effectiveWorkspace(session) || def;
    let picked;
    try {
      picked = await api("/api/workspace/pick-dir", {
        method: "POST",
        body: JSON.stringify({ initial: current }),
      });
    } catch (e) {
      addStatus("打开文件夹对话框失败 · " + (e.message || e));
      return;
    }
    if (!picked || picked.cancelled || !picked.path) {
      return;
    }
    const next = String(picked.path).trim();
    if (!next) return;
    try {
      const updated = await api(`/api/sessions/${session.id}/workspace`, {
        method: "PUT",
        body: JSON.stringify({ workspace: next }),
      });
      if (state.sessionId === session.id) {
        applyWorkspaceToChrome(updated.workspace);
      }
      await refreshSessions();
      addStatus("工作区已切换 · " + (updated.workspace || next));
    } catch (e) {
      addStatus("切换工作区失败 · " + (e.message || e));
    }
  }

  function closeAllSessionMenus() {
    document.querySelectorAll(".session-menu.open").forEach((el) => el.classList.remove("open"));
    document.querySelectorAll(".session-more.open").forEach((el) => el.classList.remove("open"));
  }

  function toggleSessionMenu(row, moreBtn, menu) {
    const willOpen = !menu.classList.contains("open");
    closeAllSessionMenus();
    if (willOpen) {
      menu.classList.add("open");
      moreBtn.classList.add("open");
    }
  }

  document.addEventListener("click", () => closeAllSessionMenus());

  async function openSession(id) {
    try {
      state.sessionId = id;
      setView("chat");
      const list = await api("/api/sessions");
      state.sessionsCache = list || [];
      const info = list.find((s) => s.id === id);
      $("sessionTitle").textContent = (info && (info.displayName || info.title)) || id;
      applyWorkspaceToChrome(effectiveWorkspace(info));
      transcript.innerHTML = "";
      transcript.appendChild(hero);
      hero.style.display = "";
      endAssistantStream();
      clearInlineInteraction();
      state.pendingInteraction = null;
      const messages = await api(`/api/sessions/${id}/messages`);
      if (messages && messages.length) {
        hideHero();
        messages.forEach((m) => {
          if (m.role === "user") addUser(m.content || "", m.id || null);
          else if (m.role === "assistant") addAssistantMarkdown(m.content || "");
          else if (m.role === "tool") {
            addToolCard({ name: "tool", output: m.content || "", ok: true }, false);
          }
        });
      }
      renderSessionList();
      await refreshUndoBar();
      scrollBottom();
    } catch (e) {
      addStatus("打开会话失败 · " + (e.message || e));
    }
  }

  async function ensureSession() {
    if (state.sessionId) return state.sessionId;
    const created = await api("/api/sessions", {
      method: "POST",
      body: JSON.stringify({ resume: true }),
    });
    await openSession(created.sessionId);
    return state.sessionId;
  }

  function setRunning(v) {
    state.running = v;
    btnSend.disabled = v && !input.value.trim() ? true : false;
    btnSend.classList.toggle("running", v);
    btnSend.innerHTML = v ? ICO.stop : ICO.send;
    btnSend.title = v ? "运行中" : "发送";
    input.disabled = v;
  }

  function clearInlineInteraction() {
    document.querySelectorAll(".interact-card").forEach((el) => el.remove());
  }

  function showInteraction(payload) {
    hideThinking();
    clearInlineInteraction();
    state.pendingInteraction = payload;
    const kind = payload.kind;
    const isAsk = kind === "ask_user";
    const card = document.createElement("div");
    card.className = "interact-card" + (isAsk ? " ask" : " approve");
    card.innerHTML = `
      <div class="interact-head">
        <span class="interact-ico">${isAsk ? ICO.help : ICO.shield}</span>
        <strong class="interact-title"></strong>
      </div>
      <div class="interact-body"></div>
      <div class="interact-options"></div>
      <input type="text" class="interact-input hidden" placeholder="输入你的回答…" />
      <div class="interact-actions">
        <button type="button" class="btn-ghost interact-cancel"></button>
        <button type="button" class="btn-primary interact-ok"></button>
      </div>`;
    card.querySelector(".interact-title").textContent = isAsk ? "需要你的回答" : "允许工具？";
    card.querySelector(".interact-body").textContent = isAsk
      ? payload.question || ""
      : `${payload.toolName || ""}\n${payload.summary || ""}`.trim();

    const opts = card.querySelector(".interact-options");
    const inputEl = card.querySelector(".interact-input");
    const okBtn = card.querySelector(".interact-ok");
    const cancelBtn = card.querySelector(".interact-cancel");

    if (isAsk) {
      inputEl.classList.remove("hidden");
      inputEl.value = "";
      (payload.options || []).forEach((o, i) => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "interact-option";
        b.textContent = `${i + 1}) ${o}`;
        b.onclick = () => {
          inputEl.value = o;
          inputEl.focus();
        };
        opts.appendChild(b);
      });
      okBtn.textContent = "发送";
      cancelBtn.textContent = "跳过";
      okBtn.onclick = () => resolveInteraction(inputEl.value || "");
      cancelBtn.onclick = () => resolveInteraction("");
      inputEl.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
          e.preventDefault();
          resolveInteraction(inputEl.value || "");
        }
      });
    } else {
      inputEl.classList.add("hidden");
      okBtn.textContent = "允许";
      cancelBtn.textContent = "拒绝";
      okBtn.onclick = () => resolveInteraction(true);
      cancelBtn.onclick = () => resolveInteraction(false);
    }

    transcript.appendChild(card);
    scrollBottom();
    if (isAsk) setTimeout(() => inputEl.focus(), 0);
  }

  async function resolveInteraction(approvedOrAnswer) {
    const p = state.pendingInteraction;
    if (!p) return;
    const card = document.querySelector(".interact-card");
    if (card) {
      card.classList.add("resolved");
      card.querySelectorAll("button, input").forEach((el) => {
        el.disabled = true;
      });
    }
    state.pendingInteraction = null;
    const body =
      p.kind === "tool_approval"
        ? { approved: !!approvedOrAnswer }
        : { answer: String(approvedOrAnswer ?? "") };
    try {
      await api(`/api/sessions/${state.sessionId}/interactions/${p.interactionId}`, {
        method: "POST",
        body: JSON.stringify(body),
      });
      if (card) {
        const note = document.createElement("div");
        note.className = "interact-resolved";
        if (p.kind === "tool_approval") {
          note.textContent = approvedOrAnswer ? "已允许" : "已拒绝";
        } else {
          const ans = String(approvedOrAnswer ?? "").trim();
          note.textContent = ans ? `已回答：${ans}` : "已跳过";
        }
        card.appendChild(note);
      }
      if (state.running) showThinking();
    } catch (e) {
      addStatus("回复失败 · " + (e.message || e));
      clearInlineInteraction();
    }
  }

  function getAtMentionContext() {
    if (!input) return null;
    const pos = input.selectionStart ?? 0;
    const before = input.value.slice(0, pos);
    const m = before.match(/(^|[\s\n\t])@([^\s@]*)$/);
    if (!m) return null;
    const query = m[2] || "";
    const start = pos - query.length - 1;
    return { start, end: pos, query };
  }

  function hideMentionPopup() {
    const pop = $("mentionPopup");
    if (pop) pop.classList.add("hidden");
    state.mention.open = false;
    state.mention.items = [];
    state.mention.index = 0;
    if (state.mention.timer) {
      clearTimeout(state.mention.timer);
      state.mention.timer = null;
    }
  }

  function renderMentionPopup() {
    const pop = $("mentionPopup");
    if (!pop) return;
    const items = state.mention.items || [];
    pop.innerHTML = "";
    if (!items.length) {
      const empty = document.createElement("div");
      empty.className = "mention-empty";
      empty.textContent = state.mention.query
        ? `没有匹配 “${state.mention.query}” 的文件`
        : "工作区暂无文件";
      pop.appendChild(empty);
    } else {
      items.forEach((path, i) => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "mention-item" + (i === state.mention.index ? " active" : "");
        btn.setAttribute("role", "option");
        btn.innerHTML = `${ICO.file}<span class="mention-item-path"></span>`;
        const ico = btn.querySelector("svg");
        if (ico) ico.classList.add("mention-item-ico");
        btn.querySelector(".mention-item-path").textContent = path;
        btn.onmousedown = (e) => {
          e.preventDefault();
          applyMention(path);
        };
        pop.appendChild(btn);
      });
    }
    pop.classList.remove("hidden");
    state.mention.open = true;
    const active = pop.querySelector(".mention-item.active");
    if (active) active.scrollIntoView({ block: "nearest" });
  }

  async function refreshMentionPopup() {
    const ctx = getAtMentionContext();
    if (!ctx) {
      hideMentionPopup();
      return;
    }
    state.mention.start = ctx.start;
    state.mention.end = ctx.end;
    state.mention.query = ctx.query;
    try {
      const q = encodeURIComponent(ctx.query || "");
      const sid = state.sessionId ? `&sessionId=${encodeURIComponent(state.sessionId)}` : "";
      const res = await api(`/api/workspace/files?q=${q}&limit=60${sid}`);
      state.mention.items = (res && res.files) || [];
      state.mention.index = 0;
      renderMentionPopup();
    } catch (e) {
      hideMentionPopup();
      addStatus("文件列表加载失败 · " + (e.message || e));
    }
  }

  function scheduleMentionPopup() {
    if (state.mention.timer) clearTimeout(state.mention.timer);
    state.mention.timer = setTimeout(() => {
      state.mention.timer = null;
      refreshMentionPopup();
    }, 80);
  }

  function applyMention(path) {
    if (!path || !input) return;
    const ctx = getAtMentionContext() || {
      start: state.mention.start,
      end: state.mention.end,
    };
    if (ctx.start < 0) return;
    const value = input.value;
    const before = value.slice(0, ctx.start);
    const after = value.slice(ctx.end);
    const insert = `@${path} `;
    input.value = before + insert + after;
    const caret = before.length + insert.length;
    input.focus();
    input.setSelectionRange(caret, caret);
    hideMentionPopup();
    input.dispatchEvent(new Event("input"));
  }

  function moveMention(delta) {
    if (!state.mention.open || !state.mention.items.length) return;
    const n = state.mention.items.length;
    state.mention.index = (state.mention.index + delta + n) % n;
    renderMentionPopup();
  }

  async function sendMessage() {
    hideMentionPopup();
    const text = input.value.trim();
    if (!text || state.running) return;
    input.value = "";
    await ensureSession();
    setUndoBar(false);
    addUser(text);
    setRunning(true);
    endAssistantStream();
    showThinking();
    try {
      const res = await fetch(`/api/sessions/${state.sessionId}/turns`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        body: JSON.stringify({ message: text }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error || res.statusText);
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let eventName = "message";
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split("\n");
        buffer = parts.pop() || "";
        for (const line of parts) {
          if (line.startsWith("event:")) {
            eventName = line.slice(6).trim();
          } else if (line.startsWith("data:")) {
            const raw = line.slice(5).trim();
            let data = {};
            try {
              data = JSON.parse(raw);
            } catch {
              data = { message: raw };
            }
            handleSse(eventName, data);
            eventName = "message";
          } else if (line.trim() === "") {
            eventName = "message";
          }
        }
      }
    } catch (e) {
      hideThinking();
      addStatus("error · " + (e.message || e));
    } finally {
      setRunning(false);
      hideThinking();
      endAssistantStream();
      refreshSessions().catch(() => {});
    }
  }

  function handleSse(type, data) {
    switch (type) {
      case "text.delta": {
        const el = ensureAssistantStream();
        state.streamingRaw += data.token || "";
        el.textContent = state.streamingRaw;
        scrollBottom();
        break;
      }
      case "tool.start":
        endAssistantStream();
        addToolCard(data, true);
        break;
      case "tool.end":
        finishToolCard(data);
        break;
      case "tool.status":
        if (data.text && String(data.text).startsWith("todos ·")) {
          addStatus(data.text);
        }
        break;
      case "interaction.required":
        hideThinking();
        showInteraction(data);
        break;
      case "user.message":
        if (data.id) bindLatestUserCheckpoint(data.id);
        break;
      case "history.prune": {
        const ids = (data && data.droppedUserIds) || [];
        if (ids.length) {
          const set = new Set(ids.map(String));
          Array.from(transcript.querySelectorAll(".msg.user")).forEach((el) => {
            if (el.dataset.eventId && set.has(el.dataset.eventId)) {
              el.remove();
            }
          });
          addStatus("已忽略先前未完成的指令");
        }
        break;
      }
      case "session.title":
        if (data.title) {
          $("sessionTitle").textContent = data.title;
          refreshSessions().catch(() => {});
        }
        break;
      case "status":
        addStatus(data.message || "");
        break;
      case "error":
        hideThinking();
        addStatus("error · " + (data.message || "failed"));
        break;
      case "turn.end":
        hideThinking();
        endAssistantStream();
        if (state.view === "trace") loadTrace().catch(() => {});
        break;
      default:
        break;
    }
  }

  $("tabChat").onclick = () => setView("chat");
  $("tabTrace").onclick = () => setView("trace");
  $("btnRefreshTrace").onclick = () => loadTrace();
  $("traceSearch").oninput = (e) => {
    state.traceFilter = e.target.value || "";
    renderTrace();
  };
  $("sessionSearch").oninput = (e) => {
    state.sessionFilter = e.target.value || "";
    renderSessionList();
  };
  $("btnSend").onclick = () => {
    if (state.running) return;
    sendMessage();
  };
  input.addEventListener("keydown", (e) => {
    if (state.mention.open) {
      if (e.key === "ArrowDown") {
        e.preventDefault();
        moveMention(1);
        return;
      }
      if (e.key === "ArrowUp") {
        e.preventDefault();
        moveMention(-1);
        return;
      }
      if (e.key === "Enter" || e.key === "Tab") {
        const pick = state.mention.items[state.mention.index];
        if (pick) {
          e.preventDefault();
          applyMention(pick);
          return;
        }
      }
      if (e.key === "Escape") {
        e.preventDefault();
        hideMentionPopup();
        return;
      }
    }
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  });
  input.addEventListener("input", () => {
    if (getAtMentionContext()) scheduleMentionPopup();
    else hideMentionPopup();
  });
  input.addEventListener("blur", () => {
    setTimeout(() => hideMentionPopup(), 150);
  });
  input.addEventListener("click", () => {
    if (getAtMentionContext()) scheduleMentionPopup();
    else hideMentionPopup();
  });
  $("btnNew").onclick = async () => {
    const created = await api("/api/sessions", { method: "POST", body: "{}" });
    await openSession(created.sessionId);
  };
  $("btnCollapse").onclick = () => document.querySelector(".app").classList.toggle("collapsed");
  $("btnSettings").onclick = () => {
    setSettingsTab("general");
    $("settingsMask").classList.remove("hidden");
    loadMeta().catch(() => {});
  };
  $("btnCloseSettings").onclick = () => $("settingsMask").classList.add("hidden");
  $("settingsMask").onclick = (e) => {
    if (e.target === $("settingsMask")) $("settingsMask").classList.add("hidden");
  };
  $("btnSaveLlm").onclick = () => saveLlm();
  $("btnAddProvider").onclick = () => addProvider();
  $("btnDeleteProvider").onclick = () => deleteProvider();
  $("btnUseProvider").onclick = () => useProvider();
  $("btnOpenLlmFile").onclick = () => onSettingsHeadAction();
  document.querySelectorAll(".settings-nav-item").forEach((btn) => {
    btn.onclick = () => setSettingsTab(btn.dataset.settingsTab);
  });
  $("settingsMode").onchange = async () => {
    try {
      const mode = $("settingsMode").value;
      const res = await api("/api/permission/mode", {
        method: "PUT",
        body: JSON.stringify({ mode }),
      });
      if (state.meta) {
        state.meta.permissionMode = res.id;
        state.meta.permissionDescription = res.description;
      }
      await loadMeta();
    } catch (e) {
      addStatus("切换权限失败 · " + (e.message || e));
    }
  };
  $("btnCloseRestore").onclick = () => hideRestoreModal();
  $("btnRestoreCancel").onclick = () => hideRestoreModal();
  $("restoreMask").onclick = (e) => {
    if (e.target === $("restoreMask")) hideRestoreModal();
  };
  $("btnRestoreContinue").onclick = async () => {
    if ($("restoreDontAsk").checked) {
      localStorage.setItem(RESTORE_SKIP_KEY, "1");
    }
    const id = state.pendingRestoreEventId;
    if (id) await doRestore(id, true);
  };
  $("btnUndoRestore").onclick = () => doUndoRestore();
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !$("restoreMask").classList.contains("hidden")) {
      hideRestoreModal();
    }
  });
  $("modelPill").onclick = (e) => {
    e.stopPropagation();
    toggleCfg("model");
  };
  $("modePill").onclick = (e) => {
    e.stopPropagation();
    toggleCfg("mode");
  };
  $("modelPopover").onclick = (e) => e.stopPropagation();
  $("modePopover").onclick = (e) => e.stopPropagation();
  document.addEventListener("click", () => closeAllCfg());
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeAllCfg();
  });

  (async () => {
    try {
      await loadMeta();
      await ensureSession();
      await refreshSessions();
    } catch (e) {
      addStatus("boot failed · " + (e.message || e));
    }
  })();
})();
