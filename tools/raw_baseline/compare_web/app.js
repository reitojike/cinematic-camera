"use strict";

// Pure coordinate functions are also exported for node verification.
const CompareMath = (() => {
  const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
  function geometry(iw, ih, vw, vh, mode, zoom, cx, cy) {
    const fit = Math.min(vw / iw, vh / ih);
    const scale = (mode === "actual" ? 1 : fit) * zoom;
    const width = iw * scale, height = ih * scale;
    const left = width <= vw ? (vw - width) / 2 : clamp(vw / 2 - cx * width, vw - width, 0);
    const top = height <= vh ? (vh - height) / 2 : clamp(vh / 2 - cy * height, vh - height, 0);
    return {scale, width, height, left, top, fit};
  }
  function anchoredCenter(g, vw, vh, x, y, factor) {
    const u = (x - g.left) / g.width, v = (y - g.top) / g.height;
    return {cx: clamp(u - (x - vw / 2) / (g.width * factor), 0, 1), cy: clamp(v - (y - vh / 2) / (g.height * factor), 0, 1)};
  }
  function focusZoom(iw, ih, vw, vh, box) {
    const fit = Math.min(vw / iw, vh / ih);
    return .86 * Math.min(vw / (iw * (box[2] - box[0])), vh / (ih * (box[3] - box[1]))) / fit;
  }
  return {clamp, geometry, anchoredCenter, focusZoom};
})();
if (typeof module !== "undefined" && module.exports) module.exports = CompareMath;

if (typeof document !== "undefined") (() => {
  const data = window.COMPARISON;
  const byId = id => document.getElementById(id);
  const status = byId("status");
  if (!data || !Array.isArray(data.scenes) || !data.scenes.length) {
    status.textContent = "比較データを読み込めません。data.jsとassetsが同じフォルダーにあることを確認してください。";
    return;
  }
  const {clamp, geometry, anchoredCenter, focusZoom} = CompareMath;
  const state = {scene: 0, mode: "fit", zoom: 1, cx: .5, cy: .5};
  const names = {baseline: "旧固定基準", foundation: "共通明度土台", edited: "本人JPEG参照"};
  const descriptions = {
    baseline: "露出固定・カメラWB・profile toneなし。通常コンバーターの標準表示とは異なります。",
    foundation: "共通の明示露出補正のみ。4案の局所・色調整を分けて確認します。",
    edited: "ICCでsRGBへ変換した本人JPEG。構図・crop差があり、画素単位の正解ではありません。"
  };
  const title = key => data.looks[key] ? data.looks[key].name : names[key];
  const scene = () => data.scenes[state.scene];
  const panels = ["left", "right"].map(side => ({side, select: byId(side), view: byId(side + "-view"), image: byId(side + "-image"), footer: byId(side + "-scale"), loading: byId(side + "-view").querySelector(".load-state"), failed: false, ready: false, token: 0}));
  const cards = [];
  let activePointer = null;
  function panelGeometry(panel) {
    const entry = scene().images[panel.select.value];
    return geometry(entry.width, entry.height, panel.view.clientWidth, panel.view.clientHeight, state.mode, state.zoom, state.cx, state.cy);
  }
  function render() {
    for (const p of panels) {
      const g = panelGeometry(p), entry = scene().images[p.select.value];
      p.image.style.width = g.width + "px"; p.image.style.height = g.height + "px";
      p.image.style.left = g.left + "px"; p.image.style.top = g.top + "px";
      p.footer.textContent = `${entry.width} × ${entry.height} px · ${(g.scale * 100).toFixed(1)}% · sRGB`;
    }
    const failures = panels.filter(p => p.failed).length;
    status.textContent = failures ? "画像を読み込めません。assetsの欠落を確認してください。" : panels.every(p => p.ready) ? (state.mode === "actual" && state.zoom === 1 ? "CSS 1:1 · 同期表示" : "位置・倍率を同期中") : "画像を読み込み中…";
    for (const card of cards) card.button.setAttribute("aria-pressed", String(card.key === panels[1].select.value));
    byId("crop-note").hidden = !panels.some(p => p.select.value === "edited");
    byId("fit").setAttribute("aria-pressed", String(state.mode === "fit" && state.zoom === 1));
    byId("actual").setAttribute("aria-pressed", String(state.mode === "actual" && state.zoom === 1));
  }
  function loadPanel(p) {
    const key = p.select.value, entry = scene().images[key], token = ++p.token;
    p.ready = false; p.failed = false; p.loading.hidden = false; p.loading.textContent = "読み込み中…";
    byId(p.side + "-description").textContent = data.looks[key] ? data.looks[key].detail : descriptions[key];
    p.image.alt = `代表${scene().id.toUpperCase()}：${title(key)}`;
    p.image.onload = () => {if (token !== p.token) return; p.ready = true; p.loading.hidden = true; render();};
    p.image.onerror = () => {if (token !== p.token) return; p.failed = true; p.loading.textContent = "画像を読み込めません"; render();};
    p.image.src = entry.path;
    render();
  }
  function setScene(index) {
    state.scene = index; state.mode = "fit"; state.zoom = 1; state.cx = state.cy = .5;
    const current = scene();
    byId("foundation-note").textContent = `共通暗部ゲイン上限 ${current.base_ev >= 0 ? "+" : ""}${current.base_ev.toFixed(2)} EV / 領域追加 +${current.skin_ev.toFixed(2)} EV · 手動設定 / SIGMA fp`;
    byId("scene-note").textContent = current.note || "";
    for (const p of panels) {
      const old = p.select.value || (p.side === "left" ? "baseline" : "clear");
      p.select.replaceChildren();
      for (const key of ["baseline", "foundation", ...Object.keys(data.looks), "edited"]) {
        if (!current.images[key]) continue;
        const option = document.createElement("option"); option.value = key; option.textContent = title(key); p.select.append(option);
      }
      p.select.value = current.images[old] ? old : "clear";
    }
    panels.forEach(loadPanel);
    for (const card of cards) {
      card.image.src = current.images[card.key].path.replace(/\.jpg$/i, "-thumb.jpg");
      card.image.alt = `代表${current.id.toUpperCase()}：${title(card.key)}の全体`;
    }
    render();
  }
  function full() {state.mode = "fit"; state.zoom = 1; state.cx = state.cy = .5; render();}
  function actual() {state.mode = "actual"; state.zoom = 1; render();}
  function zoomAt(p, factor, x, y) {
    const next = clamp(state.zoom * factor, .1, 64), effective = next / state.zoom;
    const center = anchoredCenter(panelGeometry(p), p.view.clientWidth, p.view.clientHeight, x, y, effective);
    state.zoom = next; state.cx = center.cx; state.cy = center.cy; render();
  }
  byId("fit").addEventListener("click", full); byId("actual").addEventListener("click", actual);
  byId("center").addEventListener("click", () => {state.cx = state.cy = .5; render();});
  byId("plus").addEventListener("click", () => zoomAt(panels[0], 1.3, panels[0].view.clientWidth / 2, panels[0].view.clientHeight / 2));
  byId("minus").addEventListener("click", () => zoomAt(panels[0], 1 / 1.3, panels[0].view.clientWidth / 2, panels[0].view.clientHeight / 2));
  document.querySelectorAll("[data-focus]").forEach(button => button.addEventListener("click", () => {
    const box = scene().focus[button.dataset.focus], entry = scene().images[panels[0].select.value];
    state.mode = "fit"; state.zoom = clamp(focusZoom(entry.width, entry.height, panels[0].view.clientWidth, panels[0].view.clientHeight, box), .1, 64);
    state.cx = (box[0] + box[2]) / 2; state.cy = (box[1] + box[3]) / 2; render();
  }));
  for (const p of panels) {
    p.select.addEventListener("change", () => loadPanel(p));
    p.view.addEventListener("wheel", event => {
      event.preventDefault(); const rect = p.view.getBoundingClientRect();
      zoomAt(p, Math.exp(-clamp(event.deltaY, -200, 200) * .002), event.clientX - rect.left, event.clientY - rect.top);
    }, {passive: false});
    p.view.addEventListener("pointerdown", event => {
      if (!event.isPrimary || event.button !== 0) return;
      p.view.focus({preventScroll: true}); p.view.setPointerCapture(event.pointerId);
      activePointer = {id: event.pointerId, panel: p, x: event.clientX, y: event.clientY};
    });
    p.view.addEventListener("pointermove", event => {
      if (!activePointer || activePointer.id !== event.pointerId || activePointer.panel !== p) return;
      const g = panelGeometry(p);
      // Use actual clamped displayed center to prevent edge overscroll dead zones.
      state.cx = clamp((p.view.clientWidth / 2 - g.left - (event.clientX - activePointer.x)) / g.width, 0, 1);
      state.cy = clamp((p.view.clientHeight / 2 - g.top - (event.clientY - activePointer.y)) / g.height, 0, 1);
      activePointer.x = event.clientX; activePointer.y = event.clientY; render();
    });
    for (const name of ["pointerup", "pointercancel", "lostpointercapture"]) p.view.addEventListener(name, event => {if (activePointer && event.pointerId === activePointer.id) activePointer = null;});
    p.view.addEventListener("keydown", event => {
      if (event.key === "Home") {event.preventDefault(); full(); return;}
      if (event.key === "1") {event.preventDefault(); actual(); return;}
      if (["+", "=", "-", "_"].includes(event.key)) {event.preventDefault(); zoomAt(p, ["+", "="].includes(event.key) ? 1.3 : 1 / 1.3, p.view.clientWidth / 2, p.view.clientHeight / 2); return;}
      const arrows = {ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1]};
      if (!arrows[event.key]) return;
      event.preventDefault(); const g = panelGeometry(p), delta = arrows[event.key], amount = event.shiftKey ? 120 : 40;
      state.cx = clamp((p.view.clientWidth / 2 - g.left + delta[0] * amount) / g.width, 0, 1);
      state.cy = clamp((p.view.clientHeight / 2 - g.top + delta[1] * amount) / g.height, 0, 1); render();
    });
  }
  for (const [index, item] of data.scenes.entries()) {
    const option = document.createElement("option"); option.value = String(index); option.textContent = `代表 ${item.id.toUpperCase()}`; byId("scene").append(option);
  }
  byId("scene").addEventListener("change", event => setScene(Number(event.target.value)));
  for (const [key, look] of Object.entries(data.looks)) {
    const button = document.createElement("button"), image = document.createElement("img"), copy = document.createElement("span"), strong = document.createElement("strong"), small = document.createElement("small");
    button.type = "button"; button.className = "look-card"; button.setAttribute("aria-pressed", "false");
    copy.className = "card-copy"; strong.textContent = look.name; small.textContent = look.detail; image.loading = "lazy";
    copy.append(strong, small); button.append(image, copy); byId("looks").append(button);
    button.addEventListener("click", () => {panels[1].select.value = key; loadPanel(panels[1]);});
    cards.push({key, button, image});
  }
  const wrap = document.createElement("div"), table = document.createElement("table"), caption = document.createElement("caption");
  wrap.className = "table-wrap"; caption.textContent = "保存した4案の処理パラメータ（共通露出補正は代表ごとに別指定）"; table.append(caption);
  for (const [key, look] of Object.entries(data.looks)) {
    const row = document.createElement("tr"), heading = document.createElement("th"), cell = document.createElement("td");
    heading.scope = "row"; heading.textContent = look.name;
    cell.textContent = `追加EV ${look.ev >= 0 ? "+" : ""}${look.ev} / 局所EV +${look.local_ev} / RGB ${look.rgb.join(", ")} / 彩度 ${look.saturation} / 肌彩度 ${look.skin_saturation} / コントラスト ${look.contrast}`;
    row.append(heading, cell); table.append(row);
  }
  wrap.append(table); byId("parameters").append(wrap);
  if (typeof ResizeObserver !== "undefined") {const observer = new ResizeObserver(render); panels.forEach(p => observer.observe(p.view));}
  else window.addEventListener("resize", render);
  setScene(0);
})();
