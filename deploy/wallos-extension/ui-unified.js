/* Qingheng · Shared navigation and page-level information architecture. */
(() => {
  "use strict";
  function ready() {
    const file = location.pathname.split("/").pop() || "index.php";
    const page = file.replace("-preview.php", ".php");
    const main = document.querySelector("section.contain");
    if (!main) return;
    main.classList.add("om-unified-page");

    // Keep preview navigation within the preview pages; no changes to production routes.
    if (file.endsWith("-preview.php") || file === "dashboard-preview.php") {
      const aliases = new Map([
        ["index.php","dashboard-preview.php"],["","dashboard-preview.php"],
        ["stats.php","stats-preview.php"],["calendar.php","calendar-preview.php"],
        ["expenses.php","expenses-preview.php"],["subscriptions.php","subscriptions-preview.php"],
        ["settings.php","settings-preview.php"],["profile.php","profile-preview.php"],
        ["about.php","about-preview.php"]
      ]);
      document.querySelectorAll("a[href]").forEach(link => {
        const url = link.getAttribute("href");
        if (!url || url.startsWith("#") || url.startsWith("http") || url.startsWith("javascript:")) return;
        const [path, query] = url.split("?");
        if (aliases.has(path)) link.setAttribute("href", aliases.get(path) + (query ? "?" + query : ""));
        else if (path === ".") link.setAttribute("href", "dashboard-preview.php");
      });
    }

    if (page === "settings.php") setupSettings(main);
    else if (page === "profile.php") setupProfile(main);
    else if (page === "about.php") setupAbout(main);
    else if (page === "subscriptions.php") setupSubscriptions(main);
    else if (page === "calendar.php") setupCalendar(main);
    else if (page === "stats.php") setupStatistics(main);
  }

  function heading(text, root, before) {
    const title = document.createElement("h1");
    title.className = "om-page-title";
    title.textContent = text;
    root.insertBefore(title, before || root.firstElementChild);
    return title;
  }

  function setupSubscriptions(root) {
    root.classList.add("om-subscriptions-page");
    heading("订阅管理", root, root.querySelector("#main-actions") || root.firstElementChild);
  }

  function setupProfile(root) {
    root.classList.add("om-profile-page");
    heading("账户与安全", root);
  }

  function setupAbout(root) {
    root.classList.add("om-about-page");
    heading("关于清衡", root);
    // The introduction and both upstream credits are authored in about.php.
    // Preserve the Wallos component section and all original library credits.
  }

  function setupSettings(root) {
    root.classList.add("om-settings-page");
    const sections = [...root.children].filter(child => child.classList.contains("account-section"));
    if (!sections.length) return;
    heading("设置", root);
    const groups = [
      { id: "finance", title: "财务管理", items: [0, 1, 2, 4, 5, 9] },
      { id: "notifications", title: "提醒通知", items: [3] },
      { id: "appearance", title: "外观体验", items: [10, 11] },
      { id: "advanced", title: "高级设置", items: [6, 7, 8, 12] }
    ];
    const tabbar = document.createElement("div");
    tabbar.className = "om-settings-tabs";
    tabbar.setAttribute("role", "tablist");
    tabbar.setAttribute("aria-label", "设置分类");
    const views = new Map();
    for (const group of groups) {
      const tab = document.createElement("button");
      tab.type = "button";
      tab.className = "om-settings-tab";
      tab.id = "om-tab-" + group.id;
      tab.setAttribute("role", "tab");
      tab.setAttribute("aria-controls", "om-panel-" + group.id);
      tab.textContent = group.title;
      tab.dataset.omTab = group.id;
      tabbar.append(tab);
      const view = document.createElement("div");
      view.className = "om-settings-panel";
      view.id = "om-panel-" + group.id;
      view.setAttribute("role", "tabpanel");
      view.setAttribute("aria-labelledby", tab.id);
      for (const index of group.items) {
        if (sections[index]) {
          if ([2,3,4,5,9,10,11,12].includes(index)) {
            sections[index].classList.add("om-settings-wide");
          }
          view.append(sections[index]);
        }
      }
      root.append(view);
      views.set(group.id, { tab, view });
    }
    const assigned = new Set(groups.flatMap(group => group.items));
    for (let i = 0; i < sections.length; i++) {
      if (!assigned.has(i)) views.get("advanced").view.append(sections[i]);
    }
    const title = root.querySelector(".om-page-title");
    title.insertAdjacentElement("afterend", tabbar);
    const oldLimit = root.querySelector("#upcomingpaymentslimit");
    if (oldLimit) {
      const group = oldLimit.closest(".form-group-inline,.form-group,.settings-option");
      if (group) group.remove();
      else oldLimit.remove();
    }
    const activate = id => {
      for (const [key, elements] of views) {
        const selected = key === id;
        elements.tab.setAttribute("aria-selected", String(selected));
        elements.tab.tabIndex = selected ? 0 : -1;
        elements.view.hidden = !selected;
      }
    };
    for (const [key, {tab}] of views) {
      tab.addEventListener("click", () => activate(key));
    }
    tabbar.addEventListener("keydown", (event) => {
      if (!["ArrowLeft","ArrowRight","Home","End"].includes(event.key)) return;
      event.preventDefault();
      const tabs = [...tabbar.querySelectorAll('[role="tab"]')];
      const pos = tabs.indexOf(document.activeElement);
      const next = event.key === "Home" ? 0 : event.key === "End" ? tabs.length-1 :
        (pos + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
      tabs[next].focus();
      activate(tabs[next].dataset.omTab);
    });
    activate("finance");
  }

  function setupCalendar(root) {
    root.classList.add("om-calendar-page");
    const toolbar = root.querySelector(".split-header");
    const finance = root.querySelector('.finance-native[data-finance-view="calendar"]');
    if (toolbar) {
      heading("财务日历", root, finance || toolbar);
      if (finance) root.insertBefore(toolbar, finance);
    }
    if (!finance) return;
    const grid = finance.querySelector(".finance-metric-grid");
    const stats = root.querySelectorAll(".calendar-monthly-stats .statistic");
    if (!grid || stats.length < 3) return;
    root.classList.add("om-finance-ready");
    [[0, "本月续费次数"], [2, "待付款金额"]].forEach(([idx, name]) => {
      const value = stats[idx].querySelector("span")?.textContent?.trim();
      if (value === undefined || value === null) return;
      const card = document.createElement("div");
      card.className = "finance-metric";
      const label = document.createElement("small");
      label.textContent = name;
      const number = document.createElement("strong");
      number.textContent = value;
      card.append(label, number);
      grid.append(card);
    });
  }

  function setupStatistics(root) {
    root.classList.add("om-stat-page");
    const extension = root.querySelector("[data-om-stat-extra]");
    const finance = root.querySelector('.finance-native[data-finance-view="stats"]');
    if (!finance) {
      heading("财务分析", root, extension || root.firstElementChild);
      if (extension) extension.hidden = false;
      return;
    }
    heading("财务分析", root, finance);
    if (!extension) return;
    const set = tab => {
      const visible = tab === "subscriptions";
      extension.hidden = !visible;
      if (visible) requestAnimationFrame(() => {
        window.dispatchEvent(new Event("resize"));
        window.setTimeout(() => window.dispatchEvent(new Event("resize")), 80);
      });
    };
    finance.addEventListener("click", event => {
      const tab = event.target.closest("[data-finance-tab]");
      if (tab) set(tab.dataset.financeTab);
    });
    set("overview");
  }
  if (document.readyState === "loading")
    document.addEventListener("DOMContentLoaded", ready, { once: true });
  else ready();
})();
