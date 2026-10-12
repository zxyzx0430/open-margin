/* Wallos-native Finance widgets. All amounts in integer fen; no second store. */
(() => {
  "use strict";
  const roots = [...document.querySelectorAll(".finance-native[data-finance-view]")];
  if (!roots.length) return;
  // Wallos layouts can include this bundle more than once. One root must
  // never acquire duplicate charts, handlers or selected-day controls.
  if(window.__pfsFinanceNativeInitialized)return;
  window.__pfsFinanceNativeInitialized=true;
  const money = cents => cents === null || cents === undefined ? "未设置" :
    (cents < 0 ? "−" : "") + "¥" +
    (Math.abs(cents)/100).toLocaleString("zh-CN",{minimumFractionDigits:2,maximumFractionDigits:2});
  const esc = value => String(value ?? "").replace(/[&<>"']/g,
    c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const dateKey = value => /^\d{4}-\d{2}-\d{2}$/.test(value);
  const all = (root, selector) => [...root.querySelectorAll(selector)];
  const centsFromYuan = value => {
    const text = String(value).trim();
    if (!/^\d{1,9}(\.\d{1,2})?$/.test(text)) return null;
    const [whole, fraction=""] = text.split(".");
    return Number(whole)*100 + Number((fraction+"00").slice(0,2));
  };
  const state = new Map();
  // Install capture handling before any asynchronous chart construction.
  document.addEventListener("click",event=>{
    const plot=event.target.closest?.(".finance-trend-plot");
    if(!plot)return;
    const chart=plot.closest(".finance-chart");
    const entries=chart?.financeTrend?.getEntries();
    if(!entries?.length)return;
    const bar=event.target.closest?.(".apexcharts-bar-area");
    let index=-1;
    if(bar){
      const j=Number(bar.getAttribute("j"));
      index=Number.isInteger(j)&&j>=0&&j<entries.length
        ?j:[...chart.querySelectorAll(".apexcharts-bar-area")].indexOf(bar);
    }else{
      const grid=chart.querySelector(".apexcharts-grid")?.getBoundingClientRect();
      if(!grid||!grid.width||event.clientX<grid.left||event.clientX>grid.right||
         event.clientY<grid.top||event.clientY>grid.bottom)return;
      index=Math.floor((event.clientX-grid.left)/grid.width*entries.length);
    }
    if(index>=0&&index<entries.length){
      const day=entries[index].date;
      requestAnimationFrame(()=>{
        // Capture the stable container before ApexCharts potentially detaches
        // and replaces the clicked SVG subtree.
        const live=chart.isConnected?chart:document.querySelector(
          ".finance-native[data-finance-view=stats] .finance-chart");
        const active=live?.financeTrend?.getEntries()||[];
        const current=active.findIndex(item=>item.date===day);
        if(current>=0)live.financeTrend.selectIndex(current);
      });
    }
  },{capture:true});
  const csrf = document.querySelector("[data-finance-csrf]")?.value ?? "";
  const modal = document.createElement("dialog");
  modal.className = "finance-day-dialog";
  modal.setAttribute("aria-label","交易详情");
  modal.innerHTML = '<header><h3 class="finance-modal-title">交易详情</h3><button type="button" data-finance-close aria-label="关闭交易详情">关闭</button></header><div class="finance-modal-content"></div><p class="finance-feedback" role="status" aria-live="polite"></p>';
  document.body.appendChild(modal);
  modal.querySelector("[data-finance-close]").addEventListener("click",()=>modal.close());
  modal.addEventListener("click",e=>{if(e.target===modal)modal.close();});
  let currentRequest = null;
  let selectedDay = null;
  const endpoint = async url => {
    const response = await fetch(url,{credentials:"same-origin",cache:"no-store",headers:{"Accept":"application/json"}});
    const json = await response.json().catch(()=>null);
    if (!response.ok || !json?.ok) throw Error(json?.message || "网络请求失败");
    return json.data;
  };
  const mutate = async payload => {
    const response=await fetch("finance-unified-action.php",{
      method:"POST",credentials:"same-origin",
      headers:{"Content-Type":"application/json","Accept":"application/json"},
      body:JSON.stringify({...payload,csrf})
    });
    const json=await response.json().catch(()=>null);
    if(!response.ok || !json?.ok)throw Error(json?.message||"保存失败");
  };
  const feedback = (text, error=false) => {
    const element=modal.querySelector(".finance-feedback");
    element.textContent=text;
    element.style.color=error?"#cf354c":"";
  };
  async function refreshOne(root) {
    const prior=state.get(root);
    const month=prior?.model.month || root.dataset.financeMonth;
    const model=await endpoint("finance-unified-data.php?mode=summary&month="+encodeURIComponent(month));
    state.set(root,{model,chart:prior?.chart||null,range:prior?.range||30,tab:prior?.tab||"overview"});
    draw(root);
  }
  async function refreshAll() {
    await Promise.all(roots.map(refreshOne));
    window.dispatchEvent(new CustomEvent("finance:updated"));
  }
  function selectedData() {
    const first=state.get(roots[0]);
    return first?.model;
  }
  function renderList(items) {
    const categories=selectedData()?.category_labels||{};
    if(!items.length)return '<p class="finance-day-empty">没有符合筛选的交易。</p>';
    return items.map(tx=>{
      const value=(tx.amount_cents/100).toFixed(2);
      const category=String(tx.category||"OTHER");
      const opts=Object.entries(categories).map(([k,v])=>
        '<option value="'+esc(k)+'"'+(k===category?' selected':'')+'>'+esc(v)+'</option>').join("");
      const labels={confirmed:"已确认",unverified:"待核对",excluded:"不计入",
        transfer:"转账，不计入",refund:"已关联退款",possible_duplicate:"疑似重复"};
      const status=tx.review_status||"unverified";
      const statusOptions=Object.entries(labels).filter(([k])=>k!=="refund").map(([k,v])=>
        '<option value="'+esc(k)+'"'+(k===status?' selected':'')+'>'+esc(v)+'</option>').join("");
      const excluded=["excluded","transfer","refund"].includes(status)||(category==="TRANSFER"&&tx.direction==="EXPENSE");
      return '<section class="finance-day-entry" data-finance-transaction="'+Number(tx.id)+'">'+
        '<div class="finance-day-entry-title"><div class="finance-day-entry-meta"><b>'+
        esc(tx.merchant||"未填写商户")+'</b><span class="finance-entry-badge'+
        (excluded?' is-excluded':'')+'">'+esc(["excluded","transfer","refund"].includes(status)?"不计入":category==="TRANSFER"?(tx.direction==="INCOME"?"转账入账 · 已计入":"转账 · 不计入"):labels[status]||"待核对")+
        ' · '+esc(categories[category]||category)+'</span></div><strong>'+
        money(tx.amount_cents)+'</strong></div>'+
        '<details class="finance-entry-edit"><summary>编辑交易或核对状态</summary><div class="finance-entry-controls">'+
        '<form class="finance-tx-edit" data-finance-id="'+Number(tx.id)+'">'+
        '<label>金额（元）</label><input aria-label="编辑金额" inputmode="decimal" name="amount" value="'+esc(value)+'" required>'+
        '<label>分类</label><select aria-label="编辑交易分类" name="category">'+opts+'</select>'+
        '<label>商户/备注</label><input aria-label="编辑商户" name="merchant" maxlength="140" value="'+esc(tx.merchant||"")+'">'+
        '<span></span><button type="submit">保存交易</button></form>'+
        '<div class="finance-status"><label>核对状态 <select aria-label="更改核对状态" data-finance-status="'+Number(tx.id)+'">'+statusOptions+'</select></label>'+
        '<button type="button" data-finance-review="'+Number(tx.id)+'">保存状态</button></div>'+
        '</div></details></section>';
    }).join("");
  }
  async function openTransactions({day=null,category="",direction="",append=false,offset=0}={}) {
    const model=selectedData();
    if(!model)return;
    currentRequest={day,category,direction,offset};
    if(day&&!dateKey(day))return;
    selectedDay=day;
    if(!modal.open)modal.showModal();
    if(!append) {
      modal.querySelector(".finance-modal-title").textContent=day?day+" · 当日流水":
        category?"分类流水 · "+(model.category_labels[category]||category):
        direction==="INCOME"?"本月实际收入":"本月实际支出";
      modal.querySelector(".finance-modal-content").innerHTML='<p class="finance-day-empty">正在读取账本…</p>';
    }
    const params=new URLSearchParams({mode:"transactions",month:model.month,offset:String(offset)});
    if(day)params.set("day",day);
    if(category)params.set("category",category);
    if(direction)params.set("direction",direction);
    try {
      const data=await endpoint("finance-unified-data.php?"+params);
      const content=modal.querySelector(".finance-modal-content");
      const daily=model.days.find(row=>row.date===day);
      if(!append) {
        const header=daily?'<div class="finance-day-amounts">'+
          '<div><small>实际支出</small><strong>'+money(daily.expense_cents)+'</strong></div>'+
          '<div><small>本日收入</small><strong>'+money(daily.income_cents)+'</strong></div>'+
          '<div><small>已排除支出</small><strong>'+money(daily.excluded_cents)+'</strong></div></div>':'';
        content.innerHTML=header+'<div class="finance-entry-list">'+renderList(data.items)+'</div>';
        if(day&&data.subscriptions?.length) {
          const box=document.createElement("aside");
          box.className="finance-day-plan";
          box.innerHTML='<strong>订阅计划 · 未计入实际支出</strong>'+data.subscriptions.map(s=>
            '<div>'+esc(s.name)+' · '+esc(s.currency)+' '+(Number(s.cents)/100).toFixed(2)+'</div>').join("");
          content.appendChild(box);
        }
      }else{
        content.querySelector(".finance-entry-list").insertAdjacentHTML("beforeend",renderList(data.items));
      }
      const previous=content.querySelector(".finance-more");
      if(previous)previous.remove();
      if(data.next_offset!==null && data.next_offset!==undefined) {
        const more=document.createElement("button");
        more.type="button";more.className="finance-more";
        more.textContent="加载更多交易";
        more.addEventListener("click",()=>openTransactions({day,category,direction,offset:data.next_offset,append:true}));
        content.appendChild(more);
      }
      feedback("");
    }catch(e) {
      if(!append)modal.querySelector(".finance-modal-content").innerHTML='<p class="finance-unavailable">账本读取失败，可关闭后重试。</p>';
      feedback(e.message,true);
    }
  }
  modal.addEventListener("submit",async e=>{
    const form=e.target.closest(".finance-tx-edit");
    if(!form)return;
    e.preventDefault();
    const id=Number(form.dataset.financeId);
    const cents=centsFromYuan(form.elements.amount.value);
    if(cents===null || cents<=0){feedback("金额格式错误",true);return;}
    const button=form.querySelector('button[type="submit"]');
    button.disabled=true;feedback("正在保存…");
    try {
      await mutate({action:"edit",id,amount_cents:cents,
        category:form.elements.category.value,merchant:form.elements.merchant.value});
      await refreshAll();
      await openTransactions({...currentRequest,offset:0});
      feedback("已保存，相关数据已刷新");
    }catch(error){feedback(error.message,true);}
    finally{button.disabled=false;}
  });
  modal.addEventListener("click",async e=>{
    const button=e.target.closest("[data-finance-review]");
    if(!button)return;
    const id=Number(button.dataset.financeReview);
    const status=modal.querySelector('[data-finance-status="'+id+'"]').value;
    button.disabled=true;feedback("正在保存状态…");
    try {
      await mutate({action:"review",id,status});
      await refreshAll();
      await openTransactions({...currentRequest,offset:0});
      feedback("核对状态已更新");
    }catch(error){feedback(error.message,true);}
    finally{button.disabled=false;}
  });
  // Current month means the seven calendar days ending TODAY (Beijing time),
  // not the last seven entries of the 31-day array, many of which are future.
  // For historic months, end at the last day of that month.
  const beijingToday=()=>new Date().toLocaleDateString("sv-SE",{timeZone:"Asia/Shanghai"});
  const daysForRange=(model,range)=>{
    const today=beijingToday();
    const last=model.month===today.slice(0,7)?today:model.month<today.slice(0,7)
      ?model.days[model.days.length-1].date:null;
    const available=last?model.days.filter(row=>row.date<=last):[];
    return range===7?available.slice(-7):model.days;
  };
  function drawChart(root,model,range) {
    const host=root.querySelector(".finance-chart");
    if(!host)return;
    const series=daysForRange(model,range);
    const old=state.get(root).chart;
    if(old)old.destroy();
    // The date controls live outside ApexCharts to survive SVG redraws,
    // but they must be removed when switching 7 days / full month.
    host.parentElement.querySelectorAll(":scope > .finance-trend-controls").forEach(el=>el.remove());
    host.replaceChildren();
    host.removeAttribute("data-finance-selected-day");
    host.removeAttribute("data-finance-chart-ready");
    host.dataset.financeChartRange=String(range);
    if(typeof window.ApexCharts!=="function") {
      host.textContent="图表暂时不可用，可以使用日历或完整流水。";
      return;
    }
    const visual=document.createElement("div");
    visual.className="finance-trend-visual";
    const plot=document.createElement("div");
    plot.className="finance-trend-plot";
    plot.setAttribute("role","img");
    plot.setAttribute("aria-label",(range===7?"近七天":"整月")+"真实消费金额柱状图，按北京时间显示");
    const focus=document.createElement("span");
    focus.className="finance-trend-focus";
    focus.setAttribute("aria-hidden","true");
    focus.hidden=true;
    const controls=document.createElement("div");
    controls.className="finance-trend-controls";
    controls.innerHTML='<label>查看指定日期 <select class="finance-trend-date" aria-label="选择消费趋势日期">'+
      '<option value="">选择日期</option>'+series.map(row=>
      '<option value="'+esc(row.date)+'">'+esc(row.date.slice(-2))+
      ' 日 · '+money(row.expense_cents)+'</option>').join("")+'</select></label>'+
      '<div class="finance-trend-selected" role="status" aria-live="polite" hidden>'+
      '<span>已选日期 <b class="finance-trend-selected-date"></b></span>'+
      '<strong class="finance-trend-selected-amount"></strong>'+
      '<button type="button" class="finance-trend-detail">查看当日流水</button></div>';
    visual.append(plot,focus);
    host.append(visual);
    // Keep the date controls outside the ApexCharts-rendered host subtree:
    // internal chart refreshes must never reset its hidden selected-day card.
    host.insertAdjacentElement("afterend",controls);
    const picker=controls.querySelector(".finance-trend-date");
    const card=controls.querySelector(".finance-trend-selected");
    const detail=controls.querySelector(".finance-trend-detail");
    let chosen=-1;
    let ready=false;
    const noMotion=window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const numeric=(row)=>row.expense_cents/100;
    function paint() {
      if(!host.querySelector(".apexcharts-bar-area")||chosen<0||chosen>=series.length)return;
      const bars=[...host.querySelectorAll(".apexcharts-bar-area")];
      bars.forEach((bar,i)=>{
        bar.classList.toggle("finance-trend-selected-bar",i===chosen);
        bar.classList.remove("finance-trend-bar-drop");
      });
      // ApexCharts or a redraw may replace our original visual subtree.
      // Always use the live focus node currently attached to this chart.
      const liveVisual=host.querySelector(".finance-trend-visual");
      let marker=liveVisual.querySelector(".finance-trend-focus");
      if(!marker){
        marker=document.createElement("span");
        marker.className="finance-trend-focus";
        marker.setAttribute("aria-hidden","true");
        marker.hidden=true;
        liveVisual.append(marker);
      }
      marker.classList.remove("finance-trend-focus-drop");
      const active=bars[chosen];
      const box=host.querySelector(".finance-trend-visual").getBoundingClientRect();
      // The actual bar SVG is authoritative. ApexCharts may temporarily lack
      // an exposed grid node while recomputing its internal drawing groups.
      const plotRect=host.querySelector(".finance-trend-plot").getBoundingClientRect();
      const gridRect=host.querySelector(".apexcharts-grid")?.getBoundingClientRect();
      const grid=gridRect&&gridRect.width>0&&gridRect.height>0?gridRect:plotRect;
      const bar=active?.getBoundingClientRect();
      const actual=series[chosen].expense_cents>0 && bar && bar.height>0 && bar.width>0;
      if(actual){
        marker.classList.remove("finance-trend-focus-zero");
        marker.style.left=(bar.left-box.left-1)+"px";
        marker.style.top=(bar.top-box.top-1)+"px";
        marker.style.width=(bar.width+2)+"px";
        marker.style.height=(bar.height+2)+"px";
        const startScale=Math.min(8,Math.max(1.12,grid.height*.83/bar.height));
        marker.style.setProperty("--finance-trend-initial-scale",startScale);
        active.style.setProperty("--finance-trend-initial-scale",startScale);
      }else{
        marker.classList.add("finance-trend-focus-zero");
        marker.style.left=(grid.left-box.left+(chosen+.5)*grid.width/series.length-4)+"px";
        marker.style.top=(grid.bottom-box.top-5)+"px";
        marker.style.width="8px";
        marker.style.height="5px";
      }
      marker.hidden=false;
      host.dataset.debugOverlay="done-connected-"+marker.isConnected+"-hidden-"+marker.hidden+"-same-"+(focus===host.querySelector(".finance-trend-focus"));
      if(actual&&!noMotion){
        void marker.offsetWidth;
        marker.classList.add("finance-trend-focus-drop");
        active.classList.add("finance-trend-bar-drop");
      }
    }
    function reflectSelection() {
      if(chosen<0)return;
      const row=series[chosen];
      const livePicker=controls.querySelector(".finance-trend-date");
      const liveCard=controls.querySelector(".finance-trend-selected");
      if(livePicker&&livePicker.value!==row.date)livePicker.value=row.date;
      if(liveCard){
        if(liveCard.hidden)liveCard.hidden=false;
        const label=liveCard.querySelector(".finance-trend-selected-date");
        const amount=liveCard.querySelector(".finance-trend-selected-amount");
        if(label&&label.textContent!==row.date)label.textContent=row.date;
        const value=money(row.expense_cents);
        if(amount&&amount.textContent!==value)amount.textContent=value;
      }
    }
    function select(index) {
      if(!host.querySelector(".apexcharts-bar-area")||!Number.isInteger(index)||index<0||index>=series.length)return;
      chosen=index;
      host.dataset.financeSelectedDay=series[index].date;
      state.get(root).trendDay=series[index].date;
      reflectSelection();
      paint();
      requestAnimationFrame(()=>{
        if(chosen!==index)return;
        reflectSelection();
        if(host.querySelector(".finance-trend-focus")?.hidden ||
           !host.querySelector(".finance-trend-selected-bar"))paint();
      });
      setTimeout(()=>{
        if(chosen!==index)return;
        reflectSelection();
        if(host.querySelector(".finance-trend-focus")?.hidden ||
           !host.querySelector(".finance-trend-selected-bar"))paint();
      },180);
    }
    controls.addEventListener("change",event=>{
      if(event.target.matches(".finance-trend-date"))
        select(series.findIndex(x=>x.date===event.target.value));
    });
    controls.addEventListener("click",event=>{
      if(event.target.closest(".finance-trend-detail") && chosen>=0)
        openTransactions({day:series[chosen].date});
    });
    // Use original pointer coordinates against the CURRENT chart grid.
    // ApexCharts dataPointSelection and its built-in tooltip previously
    // reported "01日 ¥0" when touching a bar near October 11.
    const chart=new ApexCharts(plot,{
      chart:{type:"bar",height:214,toolbar:{show:false},
        animations:{enabled:false},selection:{enabled:false},zoom:{enabled:false}},
      series:[{name:"实际支出",data:series.map(numeric)}],
      colors:[getComputedStyle(root).getPropertyValue("--main-color").trim()||"#4777e7"],
      plotOptions:{bar:{columnWidth:"62%",borderRadius:3}},
      dataLabels:{enabled:false},
      xaxis:{categories:series.map(d=>d.date.slice(-2)+"日"),
        tooltip:{enabled:false},
        labels:{rotate:0,style:{fontSize:"10px"},formatter:v=>{
          const n=Number(String(v).slice(0,2));
          return range===7 || n===1||n%5===0||n===series.length?v:"";
        }}},
      yaxis:{min:0,labels:{formatter:v=>"¥"+Math.round(v)}},
      tooltip:{enabled:false},
      grid:{borderColor:getComputedStyle(root).getPropertyValue("--box-border-color").trim()||"#dde3eb"}
    });
    state.get(root).chart=chart;
    chart.render().then(()=>{
      if(state.get(root)?.chart!==chart)return;
      ready=true;
      host.dataset.financeChartReady="true";
      // A refresh can preserve the chosen day, but switching range must not
      // silently pick the first (zero-amount) day.
      const restored=state.get(root).trendDay;
      if(restored)select(series.findIndex(row=>row.date===restored));
    }).catch(()=>{host.textContent="趋势图暂时不可用";});
    // ApexCharts may recreate bar SVG nodes during its own pointer handling.
    // The external focus marker stays mounted in `visual`, while the active
    // SVG class is restored on the next frame following child-node changes.
    let repaintScheduled=false;
    function repaintAfterApex() {
      if(!host.querySelector(".apexcharts-bar-area")||chosen<0||repaintScheduled)return;
      repaintScheduled=true;
      requestAnimationFrame(()=>{
        repaintScheduled=false;
        if(host.querySelector(".apexcharts-bar-area")&&chosen>=0){
          reflectSelection();
          paint();
        }
      });
    }
    new MutationObserver(repaintAfterApex).observe(plot,{childList:true,subtree:true});
    // Wallos/third-party scripts may replace the controls subtree after a
    // selection; recover the visible card, without relying on stale references.

    host.financeTrend={selectIndex:index=>{
      select(index);
      if(chosen>=0)state.get(root).trendDay=series[chosen].date;
    },getEntries:()=>series};

  }
  function draw(root) {
    const entry=state.get(root);if(!entry)return;
    const {model,range}=entry;
    root.dataset.financeMonth=model.month;
    const month=root.querySelector(".finance-month");
    if(month)month.value=model.month;
    all(root,"[data-finance-value]").forEach(el=>{
      const key=el.dataset.financeValue;
      el.textContent=model[key]===null && ["planned_subscription_cents","monthly_subscription_equivalent_cents"].includes(key)
        ?"汇率缺失":money(model[key]);
    });
    const budget=root.querySelector("[data-finance-budget-caption]");
    const used=model.budget_cents>0?100*model.expense_cents/model.budget_cents:null;
    if(budget)budget.textContent=money(model.budget_cents)+" · "+(used===null?"未设预算":used.toFixed(1)+"% 已使用");
    const bar=root.querySelector("[data-finance-budget-bar]");
    if(bar)bar.style.width=(used===null?0:Math.min(100,Math.max(0,used)))+"%";
    const compareLabel=root.querySelector("[data-finance-comparison-label]");
    if(compareLabel)compareLabel.textContent=model.comparison_label||"上月";
    const actualShare=root.querySelector('[data-finance-share="actual"]');
    if(actualShare)actualShare.textContent=model.actual_subscription_share_pct===null?"当期支出为零":model.actual_subscription_share_pct+"%";
    const plannedShare=root.querySelector('[data-finance-share="planned"]');
    if(plannedShare)plannedShare.textContent=model.planned_budget_share_pct===null?"未设预算":model.planned_budget_share_pct+"%";
    all(root,"[data-finance-range]").forEach(x=>x.classList.toggle("is-current",Number(x.dataset.financeRange)===range));
    drawChart(root,model,range);
    const categories=root.querySelector(".finance-categories");
    if(categories) {
      const arr=Object.entries(model.category_totals||{}).sort((a,b)=>b[1]-a[1]);
      const max=Math.max(1,...arr.map(x=>x[1]));
      categories.innerHTML=arr.length?arr.map(([code,cents])=>
        '<button type="button" class="finance-category-row" data-finance-category="'+esc(code)+'"><span>'+
        esc(model.category_labels[code]||code)+'</span><span class="finance-category-track"><span class="finance-category-fill" style="width:'+Math.round(cents/max*100)+'%"></span></span><strong>'+money(cents)+'</strong></button>').join(""):
        '<p class="finance-day-empty">本月暂无已计入支出。</p>';
    }
    const fxLabel=root.querySelector("[data-finance-fx-status]");
    if(fxLabel) {
      const updated=model.subscription_fx_last_updated;
      const today=new Date().toLocaleDateString("sv-SE",{timeZone:"Asia/Shanghai"});
      const stale=!updated || (Date.parse(today+"T00:00:00Z")-Date.parse(updated+"T00:00:00Z")>86400000);
      fxLabel.textContent=updated
        ?"汇率来源：Wallos 每日自动更新 · 最近更新 "+updated+
         (stale?" · 汇率可能过期，换算仅供参考":" · 当日同步")
        :"Wallos 尚无汇率更新时间 · 换算仅供参考";
    }
    const monthly=root.querySelector(".finance-subscription-monthly");
    if(monthly) {
      const cycleName=(cycle,freq)=>({1:"每 "+freq+" 天",2:"每 "+freq+" 周",3:"每 "+freq+" 月",
        4:"每 "+freq+" 年",5:"一次性"})[cycle]||"周期未知";
      const rows=model.subscription_monthly_items||[];
      monthly.innerHTML=rows.length?rows.map(x=>
        '<div class="finance-subscription-monthly-row">'+
        '<div class="finance-subscription-monthly-meta"><b>'+esc(x.name)+'</b>'+
        '<small>'+esc(x.currency)+' '+(x.price_cents/100).toFixed(2)+
        ' / '+esc(cycleName(x.cycle,x.frequency))+
        (x.converted_cny_cents===null?' · 汇率缺失':' · 本期约 '+money(x.converted_cny_cents))+
        '</small></div><div class="finance-subscription-monthly-value">'+
        (x.monthly_cny_cents===null?(x.cycle===5?'不折算':'无法换算'):money(x.monthly_cny_cents)+'/月')+
        '</div></div>').join(""):
        '<p class="finance-day-empty">暂无有效订阅。</p>';
    }
    const subs=root.querySelector(".finance-subscriptions");
    if(subs) {
      const other=Object.entries(model.other_currency_plans||{});
      subs.innerHTML=(model.subscription_events||[]).slice(0,40).map(x=>
        '<div class="finance-subscription"><button type="button" data-finance-sub="'+Number(x.id)+'">'+
        esc(x.name)+'</button><span>'+esc(x.date.slice(5))+' · '+esc(x.currency)+' '+
        (Number(x.cents)/100).toFixed(2)+
        (x.cny_cents===null?' · 无法换算':' ≈ '+money(x.cny_cents))+
        '</span></div>').join("")+
        (other.length?'<p class="finance-hint">未能换算的币种：'+
          other.map(([k,v])=>esc(k)+' '+(Number(v)/100).toFixed(2)).join(' / ')+'</p>':'')+
        (!model.subscription_events?.length?'<p class="finance-day-empty">本月没有计划中的订阅到期。</p>':'');
    }
    all(document,".finance-calendar-day[data-finance-day]").forEach(cell=>{
      const row=model.days.find(x=>x.date===cell.dataset.financeDay);
      const el=cell.querySelector(".finance-calendar-amount");
      if(!el||!row)return;
      el.textContent=row.date>new Date().toLocaleDateString("sv-SE",{timeZone:"Asia/Shanghai"})?"—":money(row.expense_cents);
      el.title="实际消费 "+money(row.expense_cents)+"；不计入 "+money(row.excluded_cents);
    });
  }
  roots.forEach(root=>{
    const view=root.dataset.financeView;
    const el=document.getElementById("finance-data-"+view);
    if(!el)return;
    try {
      state.set(root,{model:JSON.parse(el.textContent),chart:null,range:30,tab:"overview"});
    }catch(_){root.insertAdjacentHTML("beforeend",'<p class="finance-unavailable">财务数据解析失败，请重新加载。</p>');return;}
    root.addEventListener("click",e=>{
      const range=e.target.closest("[data-finance-range]");
      if(range){state.get(root).range=Number(range.dataset.financeRange);state.get(root).trendDay=null;draw(root);return;}
      const tab=e.target.closest("[data-finance-tab]");
      if(tab){
        const active=tab.dataset.financeTab;
        all(root,"[data-finance-tab]").forEach(x=>x.classList.toggle("is-current",x===tab));
        all(root,"[data-finance-panel]").forEach(x=>x.hidden=x.dataset.financePanel!==active);
        return;
      }
      const category=e.target.closest("[data-finance-category]");
      if(category){openTransactions({category:category.dataset.financeCategory,direction:"EXPENSE"});return;}
      const metric=e.target.closest("[data-finance-detail]");
      if(metric){openTransactions({direction:metric.dataset.financeDetail==="income"?"INCOME":"EXPENSE"});return;}
      const sub=e.target.closest("[data-finance-sub]");
      if(sub) {
        if(typeof window.showSubscriptionDetails==="function" && document.getElementById("subscription-details"))window.showSubscriptionDetails(e,Number(sub.dataset.financeSub));
        else window.location.href="subscriptions.php";
      }
    });
    root.querySelector(".finance-month")?.addEventListener("change",e=>{
      const month=e.target.value;
      if(!/^\d{4}-\d{2}$/.test(month))return;
      if(view==="calendar") {
        const [year,mon]=month.split("-");
        location.href="calendar.php?month="+Number(mon)+"&year="+Number(year);
      }else location.href=location.pathname+"?finance_month="+encodeURIComponent(month);
    });
    root.querySelector("[data-finance-budget-form]")?.addEventListener("submit",async e=>{
      e.preventDefault();
      const form=e.currentTarget;
      const cents=centsFromYuan(form.elements.budget.value||"0");
      if(cents===null){alert("请输入有效预算");return;}
      const button=form.querySelector('button[type="submit"]');
      button.disabled=true;
      try {await mutate({action:"budget",month:state.get(root).model.month,amount_cents:cents});
        await refreshAll();button.textContent="已保存";
      } catch(err){alert(err.message);}
      finally{setTimeout(()=>{button.disabled=false;button.textContent="保存";},850);}
    });
    draw(root);
  });
  document.addEventListener("keydown",e=>{
    const day=e.target.closest(".finance-calendar-day[data-finance-day]");
    if(day && (e.key==="Enter" || e.key===" ") &&
       !e.target.closest(".calendar-event, a, button")) {
      e.preventDefault();
      openTransactions({day:day.dataset.financeDay});
    }
  });
  document.addEventListener("click",e=>{
    const date=e.target.closest(".finance-calendar-day[data-finance-day]");
    if(date && !e.target.closest(".calendar-event, a, button")) {
      all(document,".finance-calendar-day").forEach(x=>x.classList.remove("finance-calendar-picked"));
      date.classList.add("finance-calendar-picked");
      openTransactions({day:date.dataset.financeDay});
    }
    const button=e.target.closest("[data-finance-open-day]");
    if(button){openTransactions({day:button.dataset.financeOpenDay});}
  });
})();
