// SPDX-License-Identifier: GPL-3.0-or-later
// Plektra (Titan 2 keyboard): the plektra-linux settings app. Screen structure and
// wording as in the Plektra settings (Android), plus items for Linux.
// Run: plasmawindowed org.plektra.settings (the Plasma 6 rootfs has no separate qml6 runner,
// and no compiler either). Settings are read and written by plektra_config.py (the Plasma5Support
// "executable" engine), the service and the bar apply them at once.
import QtQuick
import QtQuick.Layouts
import QtQuick.Controls as QQC2
import org.kde.plasma.plasmoid
import org.kde.kirigami as Kirigami
import org.kde.kirigamiaddons.formcard as FormCard
import org.kde.plasma.plasma5support as P5
import "schema.js" as Schema

PlasmoidItem {
    id: root
    preferredRepresentation: fullRepresentation
    // plasmawindowed takes the window size from the root Layout
    Layout.preferredWidth: Kirigami.Units.gridUnit * 30
    Layout.preferredHeight: Kirigami.Units.gridUnit * 40
    Layout.minimumWidth: Kirigami.Units.gridUnit * 20
    Layout.minimumHeight: Kirigami.Units.gridUnit * 20
    Plasmoid.title: tr("Plektra (Titan 2 keyboard)", "Plektra (клавиатура Titan 2)")

    readonly property string helper: "/usr/bin/python3 -I -B /usr/local/lib/plektra-linux/plektra_config.py"
    readonly property bool ru: Qt.locale().name.indexOf("ru") === 0
    property var defaults: ({})
    property var userCfg: ({})
    property var eff: ({})
    property string userPath: ""
    property bool loaded: false
    property int rev: 0                 // incremented on every settings update
    property var pending: ({})          // command -> reply handler

    function tr(en, ruText) { return (ru && ruText) ? ruText : en }
    function t(o) { return o ? (ru && o.ru ? o.ru : o.en) : "" }

    function hex(s) {
        var u = unescape(encodeURIComponent(s)), h = ""
        for (var i = 0; i < u.length; i++) {
            var c = u.charCodeAt(i).toString(16)
            h += c.length < 2 ? "0" + c : c
        }
        return h
    }

    function run(args, cb) {
        // source uniqueness: the executable engine does not rerun a command while it is
        // connected, so a counter is appended as a comment
        var cmd = helper + " " + args + " # " + (++runSeq)
        pending[cmd] = cb
        exe.connectSource(cmd)
    }
    property int runSeq: 0

    function reload() {
        run("get", function (out) {
            try {
                var d = JSON.parse(out)
                defaults = d.defaults; userCfg = d.user; eff = d.effective; userPath = d.paths.user
                loaded = true; rev++
            } catch (e) { console.warn("plektra: get", e, out) }
        })
    }

    // key value (the path "touchpad.mode" is inside a dictionary)
    function value(key) {
        var p = key.split("."), v = eff
        for (var i = 0; i < p.length && v !== undefined && v !== null; i++) v = v[p[i]]
        return v
    }
    function defaultOf(key) {
        var p = key.split("."), v = defaults
        for (var i = 0; i < p.length && v !== undefined && v !== null; i++) v = v[p[i]]
        return v
    }
    function isUser(key) {
        var p = key.split("."), v = userCfg
        for (var i = 0; i < p.length && v !== undefined && v !== null; i++) v = v[p[i]]
        return v !== undefined
    }
    function set(key, v) {
        var p = key.split("."), patch = {}
        if (p.length === 2) { patch[p[0]] = {}; patch[p[0]][p[1]] = v }
        else patch[key] = v
        // show it at once, without waiting for the file
        var e = JSON.parse(JSON.stringify(eff))
        if (p.length === 2) { e[p[0]] = e[p[0]] || {}; e[p[0]][p[1]] = (v === null ? defaultOf(key) : v) }
        else e[key] = (v === null ? defaultOf(key) : v)
        eff = e; rev++
        run("sethex " + hex(JSON.stringify(patch)), function () { reload() })
    }
    function setMany(patch) {
        var e = JSON.parse(JSON.stringify(eff))
        for (var k in patch) e[k] = patch[k] === null ? defaultOf(k) : patch[k]
        eff = e; rev++
        run("sethex " + hex(JSON.stringify(patch)), function () { reload() })
    }
    function resetAll() { run("reset", function () { reload() }) }

    P5.DataSource {
        id: exe
        engine: "executable"
        onNewData: (src, data) => {
            var cb = root.pending[src]
            delete root.pending[src]
            disconnectSource(src)
            if (data["exit code"] !== 0 && data["stderr"])
                console.warn("plektra:", src, data["stderr"])
            if (cb) cb(data["stdout"])
        }
    }

    Component.onCompleted: reload()

    fullRepresentation: Kirigami.ApplicationItem {
        id: win
        implicitWidth: Kirigami.Units.gridUnit * 30
        implicitHeight: Kirigami.Units.gridUnit * 40
        Layout.preferredWidth: Kirigami.Units.gridUnit * 30
        Layout.preferredHeight: Kirigami.Units.gridUnit * 40
        Layout.minimumWidth: Kirigami.Units.gridUnit * 22
        Layout.minimumHeight: Kirigami.Units.gridUnit * 24
        pageStack.globalToolBar.style: Kirigami.ApplicationHeaderStyle.ToolBar
        pageStack.globalToolBar.showNavigationButtons: Kirigami.ApplicationHeaderStyle.ShowBackButton
        pageStack.defaultColumnWidth: width
        pageStack.initialPage: mainPage

        // check on a PC: QML_XHR_ALLOW_FILE_READ=1 and the file /tmp/plk-ui-open with the path "page[/item]"
        Timer {
            interval: 1500; running: true
            onTriggered: {
                var x = new XMLHttpRequest()
                x.open("GET", "file:///tmp/plk-ui-open", false)
                try { x.send() } catch (e) { return }
                var path = (x.responseText || "").trim()
                if (!path) return
                if (path.indexOf("set:") === 0) {          // set:key=JSON: a write check
                    var kv = path.substring(4).split("=")
                    root.set(kv[0], JSON.parse(kv.slice(1).join("=")))
                    return
                }
                path.split("/").forEach(function (p) {
                    var cur = win.pageStack.currentItem
                    var pageDef = Schema.pages[p]
                    if (pageDef) win.pageStack.push(Qt.resolvedUrl("SettingsPage.qml"), {app: root, stack: win.pageStack, page: pageDef})
                    else if (cur && cur.open) cur.open({editor: p.split(":")[0], file: p.split(":")[1], title: {en: p}})
                })
            }
        }

        Component {
            id: mainPage
            SettingsPage { app: root; page: Schema.pages.main; stack: win.pageStack }
        }
    }
}
