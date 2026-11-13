/* Qingheng: four visible payment cards and bidirectional circular navigation. */
(() => {
  "use strict";
  const CLONES = 4;
  const DURATION = 320;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  function columns() {
    if (window.matchMedia("(max-width: 520px)").matches) return 1;
    if (window.matchMedia("(max-width: 900px)").matches) return 2;
    return 4;
  }

  function initCarousel(root) {
    const viewport = root.querySelector("[data-om-carousel-viewport]");
    const track = root.querySelector("[data-om-carousel-track]");
    const controls = root.parentElement.querySelector("[data-om-carousel-controls]");
    const counter = controls.querySelector("[data-om-carousel-position]");
    const previous = controls.querySelector("[data-om-prev]");
    const next = controls.querySelector("[data-om-next]");
    const originals = [...track.children];
    const count = originals.length;
    let index = CLONES;
    let step = 0;
    let looping = false;
    let busy = false;
    let drag = null;
    let muteClick = false;

    function offset() {
      return -index * step;
    }
    function translate(px) {
      track.style.transform = "translate3d(" + px + "px, 0, 0)";
    }
    function measure() {
      if (!originals[0]) return;
      const gap = Number.parseFloat(getComputedStyle(track).columnGap) || 0;
      step = originals[0].getBoundingClientRect().width + gap;
    }
    function label() {
      if (!looping) return;
      const first = ((index - CLONES) % count + count) % count;
      counter.textContent = (first + 1) + " / " + count;
    }
    function resetPosition() {
      track.style.transition = "none";
      measure();
      translate(offset());
      label();
    }
    function copyCard(original) {
      const clone = original.cloneNode(true);
      clone.setAttribute("aria-hidden", "true");
      clone.tabIndex = -1;
      clone.dataset.omClone = "true";
      return clone;
    }
    function configure() {
      const visible = columns();
      looping = count > visible;
      busy = false;
      drag = null;
      root.classList.remove("is-dragging");
      root.classList.toggle("is-looping", looping);
      controls.hidden = !looping;
      if (!looping) {
        track.replaceChildren(...originals);
        track.style.transition = "none";
        translate(0);
        viewport.removeAttribute("tabindex");
        return;
      }
      const prefix = Array.from({ length: CLONES }, (_, i) =>
        copyCard(originals[((count - CLONES + i) % count + count) % count]));
      const suffix = Array.from({ length: CLONES }, (_, i) =>
        copyCard(originals[i % count]));
      track.replaceChildren(...prefix, ...originals, ...suffix);
      index = CLONES;
      viewport.tabIndex = 0;
      resetPosition();
    }
    function settle() {
      if (!looping) return;
      if (index < CLONES) index += count;
      if (index >= CLONES + count) index -= count;
      resetPosition();
      busy = false;
    }
    function slide(direction) {
      if (!looping || busy || direction === 0) return;
      busy = true;
      index += direction;
      track.style.transition = reducedMotion.matches
        ? "none" : "transform " + DURATION + "ms cubic-bezier(.22,.72,.18,1)";
      translate(offset());
      label();
      if (reducedMotion.matches) settle();
    }
    track.addEventListener("transitionend", (event) => {
      if (event.target === track && event.propertyName === "transform" && busy) settle();
    });
    previous.addEventListener("click", () => slide(-1));
    next.addEventListener("click", () => slide(1));
    viewport.addEventListener("keydown", (event) => {
      if (!looping || event.target !== viewport) return;
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        slide(event.key === "ArrowRight" ? 1 : -1);
      }
    });

    viewport.addEventListener("pointerdown", (event) => {
      if (!looping || busy || (event.pointerType === "mouse" && event.button !== 0)) return;
      drag = { id: event.pointerId, x: event.clientX, y: event.clientY, moved: false };
    });
    viewport.addEventListener("pointermove", (event) => {
      if (!drag || drag.id !== event.pointerId || busy) return;
      const dx = event.clientX - drag.x;
      const dy = event.clientY - drag.y;
      if (!drag.moved && Math.abs(dx) > 10 && Math.abs(dx) > Math.abs(dy) * 1.1) {
        drag.moved = true;
        root.classList.add("is-dragging");
        if (viewport.setPointerCapture) viewport.setPointerCapture(event.pointerId);
      }
      if (drag.moved) {
        event.preventDefault();
        track.style.transition = "none";
        translate(offset() + dx);
      }
    });
    function endDrag(event) {
      if (!drag || drag.id !== event.pointerId) return;
      const dx = event.clientX - drag.x;
      if (drag.moved) {
        muteClick = true;
        window.setTimeout(() => { muteClick = false; }, 400);
        root.classList.remove("is-dragging");
        const threshold = Math.max(35, Math.min(step * .2, 95));
        if (Math.abs(dx) >= threshold) {
          slide(dx < 0 ? 1 : -1);
        } else {
          track.style.transition = reducedMotion.matches ? "none" :
            "transform " + DURATION + "ms cubic-bezier(.22,.72,.18,1)";
          translate(offset());
        }
      }
      drag = null;
    }
    viewport.addEventListener("pointerup", endDrag);
    viewport.addEventListener("pointercancel", (event) => {
      if (!drag || drag.id !== event.pointerId) return;
      if (drag.moved) {
        root.classList.remove("is-dragging");
        resetPosition();
      }
      drag = null;
    });
    viewport.addEventListener("click", (event) => {
      if (muteClick) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    }, true);

    viewport.addEventListener("wheel", (event) => {
      if (!looping || Math.abs(event.deltaX) < 8 ||
          Math.abs(event.deltaX) <= Math.abs(event.deltaY)) return;
      event.preventDefault();
      slide(Math.sign(event.deltaX));
    }, { passive: false });

    let previousColumns = columns();
    window.addEventListener("resize", () => {
      const currentColumns = columns();
      if (currentColumns !== previousColumns) {
        previousColumns = currentColumns;
        configure();
      } else if (looping && !drag) {
        resetPosition();
      }
    });
    configure();
  }

  function ready() {
    document.querySelectorAll("[data-om-carousel]").forEach(initCarousel);
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", ready, { once: true });
  } else {
    ready();
  }
})();
