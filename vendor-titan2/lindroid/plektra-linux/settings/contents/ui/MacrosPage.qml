// SPDX-License-Identifier: GPL-3.0-or-later
// Macros (Linux): Sym+letter or Fn+Sym+letter -> text, a key combination or a command.
import QtQuick
import QtQuick.Layouts
import QtQuick.Controls as QQC2
import org.kde.kirigami as Kirigami
import org.kde.kirigamiaddons.formcard as FormCard

FormCard.FormCardPage {
    id: pg
    property var app
    property var stack
    property var item
    title: app.t(item.title)
    property var macros: app.rev >= 0 ? JSON.parse(JSON.stringify(app.value("macros") || {})) : ({})
    readonly property var names: Object.keys(macros).sort()
    readonly property var kinds: [
        {value: "text", en: "Insert text", ru: "Вставить текст"},
        {value: "keys", en: "Press keys", ru: "Нажать клавиши"},
        {value: "run", en: "Run command", ru: "Запустить команду"}]

    function kindOf(m) { return m.run !== undefined ? "run" : (m.keys !== undefined ? "keys" : "text") }
    function valueOf(m) {
        var k = kindOf(m)
        return k === "keys" ? (m.keys || []).join(" + ") : (m[k] || "")
    }
    function put(oldName, name, kind, val) {
        var d = JSON.parse(JSON.stringify(macros))
        if (oldName && oldName !== name) delete d[oldName]
        var m = {}
        m[kind] = kind === "keys" ? val.split("+").map(x => x.trim()).filter(x => x) : val
        d[name] = m
        app.set("macros", d)
    }

    FormCard.FormHeader {
        title: app.tr("Hold Sym (or Fn+Sym) and press a letter",
                      "Держите Sym (или Fn+Sym) и нажмите букву")
    }
    FormCard.FormCard {
        FormCard.FormTextDelegate {
            text: app.tr("Placeholders", "Подстановки")
            description: app.tr("{date} {time} {datetime}, own format after a colon: {date:%d.%m.%Y}. Keys: KEY_LEFTMETA + KEY_PRINT.",
                                "{date} {time} {datetime}, свой формат после двоеточия: {date:%d.%m.%Y}. Клавиши: KEY_LEFTMETA + KEY_PRINT.")
        }
    }

    Repeater {
        model: pg.names
        delegate: ColumnLayout {
            id: mc
            required property string modelData
            readonly property var m: pg.macros[modelData] || {}
            Layout.fillWidth: true
            spacing: 0
            FormCard.FormHeader { title: mc.modelData }
            FormCard.FormCard {
                FormCard.FormTextFieldDelegate {
                    label: app.tr("Chord (SYM+D or FN+SYM+D)", "Сочетание (SYM+D или FN+SYM+D)")
                    text: mc.modelData
                    onEditingFinished: {
                        var n = text.trim().toUpperCase().replace(/\s/g, "")
                        if (/^(FN\+)?SYM\+[A-Z]$/.test(n)) pg.put(mc.modelData, n, pg.kindOf(mc.m), pg.valueOf(mc.m))
                    }
                }
                FormCard.FormComboBoxDelegate {
                    text: app.tr("Action", "Действие")
                    textRole: "label"; valueRole: "value"
                    model: pg.kinds.map(o => ({label: app.t(o), value: o.value}))
                    currentIndex: ["text", "keys", "run"].indexOf(pg.kindOf(mc.m))
                    onActivated: (i) => pg.put(mc.modelData, mc.modelData, pg.kinds[i].value, pg.valueOf(mc.m))
                }
                FormCard.FormTextFieldDelegate {
                    label: app.tr("Value", "Значение")
                    text: pg.valueOf(mc.m)
                    onEditingFinished: pg.put(mc.modelData, mc.modelData, pg.kindOf(mc.m), text)
                }
                FormCard.FormButtonDelegate {
                    text: app.tr("Delete", "Удалить")
                    icon.name: "edit-delete"
                    onClicked: { var d = JSON.parse(JSON.stringify(pg.macros)); delete d[mc.modelData]; app.set("macros", d) }
                }
            }
        }
    }
    FormCard.FormCard {
        Layout.topMargin: Kirigami.Units.largeSpacing
        FormCard.FormButtonDelegate {
            text: app.tr("Add macro", "Добавить макрос")
            icon.name: "list-add"
            onClicked: {
                var free = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".split("").filter(c => !pg.macros["SYM+" + c])
                if (free.length) pg.put(null, "SYM+" + free[0], "text", "")
            }
        }
    }
}
