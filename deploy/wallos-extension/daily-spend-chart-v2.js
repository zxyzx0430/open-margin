/* Daily expenses, rendered using Wallos' existing ApexCharts asset.
   All amounts arrive as integer CNY cents; x positions are calendar days. */
(() => {
  "use strict";
  const host = document.querySelector("#finance-daily-chart");
  const dataEl = document.querySelector("#finance-daily-data");
  if (!host || !dataEl) return;
  const root = document.querySelector("#finance-daily-chart-section");
  const picker = document.querySelector("#finance-daily-date");
  const selected = document.querySelector("#finance-daily-selection");
  const dateLabel = document.querySelector("#finance-daily-selected-date");
  const amountLabel = document.querySelector("#finance-daily-selected-amount");
  const detailLink = document.querySelector("#finance-daily-detail-link");
  let entries;
  try {
    entries = JSON.parse(dataEl.textContent);
    if (!Array.isArray(entries) || !entries.length ||
        entries.some((p, i) => !/^\d{4}-\d{2}-\d{2}$/.test(p.day) ||
          !Number.isSafeInteger(p.cents) || p.cents < 0 ||
          (i > 0 && p.day <= entries[i - 1].day))) throw Error("Invalid daily data");
  } catch (_) {
    host.textContent = "每日统计数据格式错误，请刷新页面。";
    return;
  }
  const formatAmount = cents => "¥" + (cents / 100).toLocaleString("zh-CN", {minimumFractionDigits:2, maximumFractionDigits:2});
  const tickDay = value => {
    const n = Number(value);
    // Never print 30日 directly beside 31日 on a 31-day month.
    return (n === 1 || n === entries.length ||
      (n % 5 === 0 && entries.length - n >= 3)) ? String(n) + "日" : "";
  };
  const allZero = entries.every(p => p.cents === 0);
  // Make even subpixel bars selectable by tapping their whole date column.
  function indexFromPointer(event) {
    const grid = host.querySelector(".apexcharts-grid");
    if (!grid || !event || !Number.isFinite(event.clientX)) return -1;
    const box = grid.getBoundingClientRect();
    if (event.clientX < box.left || event.clientX > box.right ||
        event.clientY < box.top || event.clientY > box.bottom || box.width <= 0) return -1;
    return Math.min(entries.length - 1, Math.floor(
      (event.clientX - box.left) / box.width * entries.length));
  }
  host.addEventListener("click", event => selectIndex(indexFromPointer(event)));
  let chosen = -1;
  let chartReady = false;
  const barClass = "finance-daily-bar-selected";
  function paintSelection() {
    if (!chartReady || chosen < 0) return;
    if (!focusBand.isConnected) host.append(focusBand);
    const bars = host.querySelectorAll(".apexcharts-bar-area");
    host.classList.add("finance-daily-has-selection");
    bars.forEach((bar, i) => {
      bar.classList.toggle(barClass, i === chosen);
      bar.classList.remove("finance-daily-bar-pop");
    });
    const active = bars[chosen];
    if (active) {
      // Replaying the animation makes a repeat tap visibly register too.
      void active.getBoundingClientRect();
      active.classList.add("finance-daily-bar-pop");
    }
    const grid = host.querySelector(".apexcharts-grid");
    const band = host.querySelector(".finance-daily-focus-band");
    if (grid && band) {
      const r = grid.getBoundingClientRect(), h = host.getBoundingClientRect();
      const w = r.width / entries.length;
      band.style.left = (r.left - h.left + chosen * w + 1) + "px";
      band.style.top = (r.top - h.top) + "px";
      band.style.width = Math.max(1, w - 2) + "px";
      band.style.height = r.height + "px";
      band.hidden = false;
      // Selecting a distant day through the dropdown reveals its bar at once.
      const viewport = root.querySelector(".ledger-chart-shell");
      const left = parseFloat(band.style.left);
      if (viewport && Number.isFinite(left)) {
        const current = viewport.scrollLeft, right = current + viewport.clientWidth;
        if (left < current + 14 || left + w > right - 14) {
          const target = Math.max(0, Math.min(
            viewport.scrollWidth - viewport.clientWidth,
            left + w / 2 - viewport.clientWidth / 2));
          viewport.scrollLeft = target;
        }
      }
    }
  }
  // Date bands remain visible even when a ¥0 or subpixel SVG bar cannot be drawn.
  const focusBand = document.createElement("span");
  focusBand.className = "finance-daily-focus-band";
  focusBand.setAttribute("aria-hidden", "true");
  focusBand.hidden = true;
  function selectIndex(index) {
    if (!Number.isInteger(index) || index < 0 || index >= entries.length) return;
    chosen = index;
    const row = entries[index];
    dateLabel.textContent = row.day;
    amountLabel.textContent = formatAmount(row.cents);
    detailLink.href = "expenses.php?month=" + encodeURIComponent(row.day.slice(0, 7)) +
      "&day=" + encodeURIComponent(row.day);
    picker.value = row.day;
    selected.hidden = false;
    selected.classList.add("is-active");
    selected.classList.remove("finance-daily-selection-pop");
    void selected.offsetWidth;
    selected.classList.add("finance-daily-selection-pop");
    root.dataset.selectedDate = row.day;
    paintSelection();
  }
  picker.addEventListener("change", () => {
    const idx = entries.findIndex(p => p.day === picker.value);
    selectIndex(idx);
  });
  root.querySelectorAll("[data-select-day]").forEach(button => {
    button.addEventListener("click", () => selectIndex(entries.findIndex(p => p.day === button.dataset.selectDay)));
  });
  // Without the JS chart, the monthly day-by-day table remains usable.
  if (typeof window.ApexCharts !== "function") {
    host.textContent = "图表暂时无法加载，仍可使用下方日期选择器和完整数据表。";
    return;
  }
  const css = getComputedStyle(document.querySelector(".wallos-ledger") || document.body);
  const color = (key, fallback) => css.getPropertyValue(key).trim() || fallback;
  const mode = () => {
    const body = document.body;
    return body.classList.contains("dark") ||
      (body.classList.contains("automatic") && window.matchMedia("(prefers-color-scheme: dark)").matches)
        ? "dark" : "light";
  };
  const chart = new ApexCharts(host, {
    chart: {
      type: "bar", height: 248, parentHeightOffset: 0, background: "transparent",
      foreColor: color("--text-muted", "#6b7280"),
      animations: {enabled: false}, toolbar: {show: false}, zoom: {enabled: false},
      selection: {enabled: false}
      // A single host click handler resolves date from geometry BEFORE scrolling.
      // ApexCharts events would race it and re-select the wrong day on narrow screens.
    },
    series: [{name: "人民币实际支出", data: entries.map(p => p.cents / 100)}],
    plotOptions: {bar: {horizontal: false, columnWidth: "64%", borderRadius: 3}},
    colors: [color("--main-color", "#4777e7")],
    theme: {mode: mode()},
    dataLabels: {enabled: false},
    grid: {borderColor: color("--box-border-color", "#e1e5e9"), strokeDashArray: 3,
      xaxis: {lines: {show: false}}, padding: {left: 2, right: 6, top: 9, bottom: 0}},
    xaxis: {
      type: "category", categories: entries.map(p => String(Number(p.day.slice(-2)))),
      tickPlacement: "between",
      axisBorder: {show: true, color: color("--box-border-color", "#e1e5e9")},
      axisTicks: {show: false},
      labels: {rotate: 0, rotateAlways: false, hideOverlappingLabels: false,
        trim: false, minHeight: 25, maxHeight: 25,
        style: {fontSize: "11px"}, formatter: tickDay},
      tooltip: {enabled: false}
    },
    yaxis: {min: 0, forceNiceScale: true, decimalsInFloat: 2,
      labels: {minWidth: 48, maxWidth: 66, formatter: n => {
        if (!Number.isFinite(n) || (allZero && n !== 0)) return "";
        return "¥" + n.toLocaleString("zh-CN", {maximumFractionDigits: 2});
      }}},
    tooltip: {enabled: false},
    legend: {show: false},
    states: {hover: {filter: {type: "lighten", value: 0.12}}, active: {filter: {type: "none"}}},
    noData: {text: "暂无已记录消费"}
  });
  chart.render().then(() => {
    root.dataset.chartReady = "true";
    root.dataset.interactiveVersion = "v2";
    chartReady = true;
    paintSelection();
  }).catch(() => {
    host.textContent = "图表无法呈现，请使用下方日期明细表。";
  });
  new MutationObserver(() => {
    chart.updateOptions({theme: {mode: mode()},
      colors: [color("--main-color", "#4777e7")],
      chart: {foreColor: color("--text-muted", "#6b7280")}}, false, false)
      .then(() => paintSelection());
  }).observe(document.body, {attributes: true, attributeFilter: ["class"]});
  root.financeDailyChart = {selectIndex, entries};
})();
