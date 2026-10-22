// SPDX-License-Identifier: GPL-3.0-or-later
// App profiles (Linux): the focused window (resourceClass from KWin) -> its own setting values.
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
    property var profiles: app.rev >= 0 ? JSON.parse(JSON.stringify(app.value("profiles") || [])) : []

    readonly property var fnOptions: [
        {value: null, en: "Like the main settings", ru: "Как в общих настройках"},
        {value: "auto", en: "Auto: in a text field Plektra Ctrl map, elsewhere Ctrl",
         ru: "Авто: в поле — Ctrl-раскладка Plektra, вне поля — Ctrl"},
        {value: "map", en: "Always the Plektra Ctrl map", ru: "Всегда Ctrl-раскладка Plektra"},
        {value: "ctrl", en: "Always real Ctrl (Fn, C = Ctrl+C)", ru: "Всегда настоящий Ctrl (Fn, C = Ctrl+C)"}]

    function save(list) { app.set("profiles", list) }
    function upd(i, f) {
        var l = JSON.parse(JSON.stringify(profiles))
        f(l[i])
        save(l)
    }
    function ov(p, k) { return p.overrides ? p.overrides[k] : undefined }
    function setOv(i, k, v) {
        upd(i, function (p) {
            p.overrides = p.overrides || {}
            if (v === null || v === undefined || v === "") delete p.overrides[k]
            else p.overrides[k] = v
        })
    }

    FormCard.FormHeader {
        title: app.tr("The window in focus selects the profile (KWin resourceClass)",
                      "Профиль выбирается по окну в фокусе (resourceClass KWin)")
    }

    Repeater {
        model: pg.profiles
        delegate: ColumnLayout {
            id: pr
            required property var modelData
            required property int index
            Layout.fillWidth: true
            spacing: 0
            FormCard.FormHeader { title: pr.modelData.name || app.tr("Profile", "Профиль") }
            FormCard.FormCard {
                FormCard.FormTextFieldDelegate {
                    label: app.tr("Name", "Название")
                    text: pr.modelData.name || ""
                    onEditingFinished: pg.upd(pr.index, p => p.name = text)
                }
                FormCard.FormTextFieldDelegate {
                    label: app.tr("Applications (comma separated)", "Приложения (через запятую)")
                    text: (pr.modelData.apps || []).join(", ")
                    onEditingFinished: pg.upd(pr.index, p => p.apps = text.split(",").map(x => x.trim()).filter(x => x))
                }
                FormCard.FormComboBoxDelegate {
                    text: app.tr("Fn single press", "Одиночное нажатие Fn")
                    textRole: "label"; valueRole: "value"
                    model: pg.fnOptions.map(o => ({label: app.t(o), value: o.value}))
                    currentIndex: {
                        var v = pg.ov(pr.modelData, "fn_tap")
                        for (var i = 0; i < pg.fnOptions.length; i++)
                            if (pg.fnOptions[i].value === (v === undefined ? null : v)) return i
                        return 0
                    }
                    onActivated: (i) => pg.setOv(pr.index, "fn_tap", pg.fnOptions[i].value)
                }
                FormCard.FormTextFieldDelegate {
                    label: app.tr("Sym press sends key (empty: SYM pages)", "Sym отправляет клавишу (пусто — страницы SYM)")
                    placeholderText: "KEY_ESC"
                    text: pg.ov(pr.modelData, "sym_tap") || ""
                    onEditingFinished: pg.setOv(pr.index, "sym_tap", text.trim())
                }
                FormCard.FormTextFieldDelegate {
                    label: app.tr("Alt+Space sends key (empty: space)", "Alt+Space отправляет клавишу (пусто — пробел)")
                    placeholderText: "KEY_TAB"
                    text: pg.ov(pr.modelData, "alt_space") || ""
                    onEditingFinished: pg.setOv(pr.index, "alt_space", text.trim())
                }
                FormCard.FormTextFieldDelegate {
                    label: app.tr("Replace keys (e.g. FN=KEY_TAB, SYM=KEY_ESC)", "Замена клавиш (напр. FN=KEY_TAB, SYM=KEY_ESC)")
                    text: {
                        var k = pg.ov(pr.modelData, "keys") || {}
                        return Object.keys(k).map(x => x + "=" + k[x]).join(", ")
                    }
                    onEditingFinished: {
                        var m = {}
                        text.split(",").forEach(function (e) {
                            var kv = e.split("=")
                            if (kv.length === 2 && kv[0].trim()) m[kv[0].trim().toUpperCase()] = kv[1].trim()
                        })
                        pg.setOv(pr.index, "keys", Object.keys(m).length ? m : null)
                    }
                }
                FormCard.FormSwitchDelegate {
                    text: app.tr("Double space to period", "Двойной пробел — точка")
                    checked: pg.ov(pr.modelData, "double_space_period") !== false
                    onToggled: pg.setOv(pr.index, "double_space_period", checked ? null : false)
                }
                FormCard.FormSwitchDelegate {
                    text: app.tr("Auto-capitalize after period", "Заглавная после точки")
                    checked: pg.ov(pr.modelData, "autocap_after_period") !== false
                    onToggled: pg.setOv(pr.index, "autocap_after_period", checked ? null : false)
                }
                FormCard.FormButtonDelegate {
                    text: app.tr("Delete profile", "Удалить профиль")
                    icon.name: "edit-delete"
                    onClicked: { var l = JSON.parse(JSON.stringify(pg.profiles)); l.splice(pr.index, 1); pg.save(l) }
                }
            }
        }
    }

    FormCard.FormCard {
        Layout.topMargin: Kirigami.Units.largeSpacing
        FormCard.FormButtonDelegate {
            text: app.tr("Add profile", "Добавить профиль")
            icon.name: "list-add"
            onClicked: { var l = JSON.parse(JSON.stringify(pg.profiles)); l.push({name: app.tr("New profile", "Новый профиль"), apps: [], overrides: {}}); pg.save(l) }
        }
        FormCard.FormButtonDelegate {
            text: app.tr("Restore built-in profiles (terminal)", "Вернуть встроенные профили (терминал)")
            icon.name: "edit-reset"
            onClicked: app.set("profiles", null)
        }
    }
}
