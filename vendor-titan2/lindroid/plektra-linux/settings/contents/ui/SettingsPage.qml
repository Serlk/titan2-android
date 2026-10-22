// SPDX-License-Identifier: GPL-3.0-or-later
// Settings screen built from the schema.js description (sections and items as in Plektra).
import QtQuick
import QtQuick.Layouts
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kirigamiaddons.formcard as FormCard
import "schema.js" as Schema

FormCard.FormCardPage {
    id: pg
    property var app
    property var page
    property var stack
    title: app.t(page.title)

    function open(item) {
        if (item.editor) {
            var c = Qt.createComponent(item.editor + ".qml")
            if (c.status !== Component.Ready) { console.warn(c.errorString()); return }
            stack.push(c, {app: app, stack: stack, item: item})
        } else {
            stack.push(Qt.resolvedUrl("SettingsPage.qml"),
                       {app: app, stack: stack, page: Schema.pages[item.page]})
        }
    }

    Repeater {
        model: pg.page.sections
        delegate: ColumnLayout {
            id: sec
            required property var modelData
            Layout.fillWidth: true
            spacing: 0
            FormCard.FormHeader {
                visible: !!sec.modelData.title
                title: pg.app.t(sec.modelData.title)
            }
            FormCard.FormCard {
                Repeater {
                    model: sec.modelData.items
                    delegate: Loader {
                        id: ld
                        required property var modelData
                        required property int index
                        Layout.fillWidth: true
                        property var entry: modelData
                        sourceComponent: ({switch: cSwitch, choice: cChoice, spin: cSpin,
                                           text: cText, page: cPage, info: cInfo,
                                           action: cAction})[modelData.type] || cInfo
                    }
                }
            }
            FormCard.FormSectionText {
                visible: !!sec.modelData.footer
                text: pg.app.t(sec.modelData.footer)
            }
        }
    }

    // ---- delegates
    function summary(it) {
        var s = app.t(it.summary)
        if (it.note) s += (s ? "\n" : "") + "⚠ " + app.t(it.note)
        return s
    }

    Component {
        id: cSwitch
        FormCard.FormSwitchDelegate {
            property var it: parent ? parent.entry : ({})
            text: pg.app.t(it.title)
            description: pg.summary(it)
            checked: pg.app.rev >= 0 && (it.invert ? !pg.app.value(it.key) : !!pg.app.value(it.key))
            onToggled: pg.app.set(it.key, it.invert ? !checked : checked)
        }
    }
    Component {
        id: cChoice
        FormCard.FormComboBoxDelegate {
            property var it: parent ? parent.entry : ({})
            text: pg.app.t(it.title)
            description: pg.summary(it)
            textRole: "label"
            valueRole: "value"
            model: it.options.map(o => ({label: pg.app.t(o), value: o.value}))
            currentIndex: {
                var r = pg.app.rev, v = JSON.stringify(pg.app.value(it.key))
                for (var i = 0; i < it.options.length; i++)
                    if (JSON.stringify(it.options[i].value) === v) return i
                return -1
            }
            onActivated: (i) => pg.app.set(it.key, it.options[i].value)
        }
    }
    Component {
        id: cSpin
        FormCard.FormSpinBoxDelegate {
            property var it: parent ? parent.entry : ({})
            property real scale: it.scale || 1
            label: pg.app.t(it.title) + (it.unit ? " (" + pg.app.t(it.unit) + ")" : "")
            from: Math.round(it.from * scale)
            to: Math.round(it.to * scale)
            stepSize: Math.round((it.step || 1) * scale)
            description: pg.summary(it)
            readonly property int cfgValue: pg.app.rev >= 0 ? Math.round((pg.app.value(it.key) || 0) * scale) : 0
            value: cfgValue
            onValueChanged: if (pg.app.loaded && value !== cfgValue)
                                pg.app.set(it.key, scale === 1 ? value : value / scale)
        }
    }
    Component {
        id: cText
        FormCard.FormTextFieldDelegate {
            property var it: parent ? parent.entry : ({})
            label: pg.app.t(it.title)
            placeholderText: pg.app.t(it.placeholder)
            text: {
                var r = pg.app.rev, v = pg.app.value(it.key)
                return v === null || v === undefined ? "" : (Array.isArray(v) ? v.join(" + ") : String(v))
            }
            onEditingFinished: {
                var s = text.trim(), v
                if (s === "") v = it.emptyIsNull ? null : ""
                else if (it.list) v = s.split("+").map(x => x.trim()).filter(x => x)
                else v = s
                pg.app.set(it.key, v === null ? (it.emptyIsNull ? null : "") : v)
            }
        }
    }
    Component {
        id: cPage
        FormCard.FormButtonDelegate {
            property var it: parent ? parent.entry : ({})
            text: pg.app.t(it.title)
            description: pg.summary(it)
            icon.name: it.icon || ""
            onClicked: pg.open(it)
        }
    }
    Component {
        id: cInfo
        FormCard.FormTextDelegate {
            property var it: parent ? parent.entry : ({})
            text: pg.app.t(it.title)
            description: pg.summary(it)
        }
    }
    Component {
        id: cAction
        FormCard.FormButtonDelegate {
            property var it: parent ? parent.entry : ({})
            text: pg.app.t(it.title)
            description: pg.summary(it)
            icon.name: it.icon || ""
            onClicked: {
                if (it.action === "resetAll") pg.app.resetAll()
                else if (it.action === "openFile") Qt.openUrlExternally("file://" + pg.app.userPath)
            }
        }
    }
}
