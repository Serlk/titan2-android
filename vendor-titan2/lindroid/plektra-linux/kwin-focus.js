// SPDX-License-Identifier: GPL-3.0-or-later
// plektra-linux: tells plektra-linux-im (org.plektra.Linux on the session bus) which window is
// focused, for app profiles (terminal, games). Loaded by plektra-linux-im itself.
function plektraReport() {
    var w = workspace.activeWindow;
    callDBus("org.plektra.Linux", "/org/plektra/Linux", "org.plektra.Linux", "ActiveWindow",
             w ? String(w.resourceClass) : "", w ? String(w.caption) : "");
}
workspace.windowActivated.connect(plektraReport);
plektraReport();
