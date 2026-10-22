// SPDX-License-Identifier: GPL-3.0-or-later
// Navigation mode assignments (Plektra NavModeSettingsScreen): Fn(Ctrl)+key ->
// key code / action / real Ctrl / nothing. Writes its own ctrl_key_mappings.json.
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
    property var map: ({})
    property bool fromUser: false
    title: app.t(item.title)
    readonly property string letters: "QWERTYUIOPASDFGHJKLZXCVBNM"

    // options as in the Plektra "Configure %1$s" dialog (type: code / action / Native Ctrl / nothing)
    readonly property var options: [
        {v: {type: "keycode", keycode: "DPAD_UP"}, en: "↑", ru: "↑"},
        {v: {type: "keycode", keycode: "DPAD_DOWN"}, en: "↓", ru: "↓"},
        {v: {type: "keycode", keycode: "DPAD_LEFT"}, en: "←", ru: "←"},
        {v: {type: "keycode", keycode: "DPAD_RIGHT"}, en: "→", ru: "→"},
        {v: {type: "keycode", keycode: "DPAD_CENTER"}, en: "○ (Enter)", ru: "○ (Enter)"},
        {v: {type: "keycode", keycode: "MOVE_HOME"}, en: "Home", ru: "Home"},
        {v: {type: "keycode", keycode: "MOVE_END"}, en: "End", ru: "End"},
        {v: {type: "keycode", keycode: "PAGE_UP"}, en: "PgUp", ru: "PgUp"},
        {v: {type: "keycode", keycode: "PAGE_DOWN"}, en: "PgDn", ru: "PgDn"},
        {v: {type: "keycode", keycode: "ESCAPE"}, en: "Esc", ru: "Esc"},
        {v: {type: "keycode", keycode: "TAB"}, en: "Tab", ru: "Tab"},
        {v: {type: "keycode", keycode: "FORWARD_DEL"}, en: "Del→", ru: "Del→"},
        {v: {type: "action", action: "copy"}, en: "Copy", ru: "Копировать"},
        {v: {type: "action", action: "paste"}, en: "Paste", ru: "Вставить"},
        {v: {type: "action", action: "cut"}, en: "Cut", ru: "Вырезать"},
        {v: {type: "action", action: "undo"}, en: "Undo", ru: "Отмена"},
        {v: {type: "action", action: "select_all"}, en: "SelAll", ru: "Выд.Всё"},
        {v: {type: "action", action: "expand_selection_left"}, en: "←Sel", ru: "←Выб"},
        {v: {type: "action", action: "expand_selection_right"}, en: "→Sel", ru: "→Выб"},
        {v: {type: "action", action: "move_word_left"}, en: "←Word", ru: "←Слово"},
        {v: {type: "action", action: "move_word_right"}, en: "Word→", ru: "Слово→"},
        {v: {type: "action", action: "expand_selection_word_left"}, en: "←WordSel", ru: "←ВыдСлово"},
        {v: {type: "action", action: "expand_selection_word_right"}, en: "WordSel→", ru: "ВыдСлово→"},
        {v: {type: "action", action: "page_start"}, en: "DocStart", ru: "Начало документа"},
        {v: {type: "action", action: "page_end"}, en: "DocEnd", ru: "Конец документа"},
        {v: {type: "action", action: "media_play_pause"}, en: "Play/Pause", ru: "Воспроизведение/Пауза"},
        {v: {type: "action", action: "media_previous"}, en: "Media Previous", ru: "Предыдущее медиа"},
        {v: {type: "action", action: "media_next"}, en: "Media Next", ru: "Следующее медиа"},
        {v: {type: "native_ctrl"}, en: "Native Ctrl", ru: "Native Ctrl"},
        {v: {type: "none"}, en: "None", ru: "Ничего"}]

    function same(a, b) {
        return a && b && a.type === b.type && (a.keycode || "") === (b.keycode || "") && (a.action || "") === (b.action || "")
    }
    function load() {
        app.run("getmap ctrl_key_mappings.json", function (out) {
            try { var d = JSON.parse(out); map = d.mappings; fromUser = d.user } catch (e) { console.warn(e, out) }
        })
    }
    function save() {
        app.run("setmaphex ctrl_key_mappings.json " + app.hex(JSON.stringify(map)), function () { fromUser = true })
    }
    Component.onCompleted: load()

    FormCard.FormHeader {
        title: app.tr("Fn (Ctrl) + key in Nav Mode and after a single Fn press in a text field",
                      "Fn (Ctrl) + клавиша в режиме навигации и после одиночного Fn в поле")
    }
    FormCard.FormCard {
        Repeater {
            model: pg.letters.length
            delegate: FormCard.FormComboBoxDelegate {
                required property int index
                readonly property string letter: pg.letters[index]
                text: letter
                textRole: "label"
                valueRole: "idx"
                model: pg.options.map((o, i) => ({label: app.t(o), idx: i}))
                currentIndex: {
                    var m = pg.map["KEYCODE_" + letter]
                    for (var i = 0; i < pg.options.length; i++) if (pg.same(pg.options[i].v, m)) return i
                    return pg.options.length - 1
                }
                onActivated: (i) => {
                    var m = Object.assign({}, pg.map)
                    m["KEYCODE_" + letter] = pg.options[i].v
                    pg.map = m
                    pg.save()
                }
            }
        }
    }
    FormCard.FormCard {
        Layout.topMargin: Kirigami.Units.largeSpacing
        FormCard.FormButtonDelegate {
            text: app.tr("Revert to Default", "Сбросить по умолчанию")
            icon.name: "edit-reset"
            enabled: pg.fromUser
            onClicked: app.run("resetmap ctrl_key_mappings.json", function () { pg.load() })
        }
    }
}
