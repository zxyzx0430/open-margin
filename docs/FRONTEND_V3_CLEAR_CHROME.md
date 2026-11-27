# Clear floating navigation material

Supersedes the P6/P8 idle backdrop recording policy. Navigation uses direct alpha compositing of a single tinted surface over live content, with no RenderEffect, blur, GraphicsLayer capture, nested-scroll observer, or idle timer. Solid/Standard/Enhanced remain manual settings (1.0/0.86/0.68 surface opacity). Enhanced is default, with a small static highlight. Old stored BLUR maps to TINTED, preserving Standard; reduced-transparency accessibility preference remains respected. There is no scroll-dependent material change. C underline selection, detail shared-element transition, visibility guard, and existing page insets are preserved.

Tests cover preference migration and fixed opacity. Actual contrast and scrolling smoothness still require device verification.
