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
    root.dataset.selectedDate = row.day;
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
      selection: {enabled: false},
      events: {
        dataPointSelection: (_event, _ctx, options) => selectIndex(options.dataPointIndex),
        click: (event, _ctx, options) => {
          if (options && options.dataPointIndex >= 0) selectIndex(options.dataPointIndex);
          else selectIndex(indexFromPointer(event));
        }
      }
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
  }).catch(() => {
    host.textContent = "图表无法呈现，请使用下方日期明细表。";
  });
  new MutationObserver(() => {
    chart.updateOptions({theme: {mode: mode()},
      colors: [color("--main-color", "#4777e7")],
      chart: {foreColor: color("--text-muted", "#6b7280")}}, false, false);
  }).observe(document.body, {attributes: true, attributeFilter: ["class"]});
  root.financeDailyChart = {selectIndex, entries};
})();
