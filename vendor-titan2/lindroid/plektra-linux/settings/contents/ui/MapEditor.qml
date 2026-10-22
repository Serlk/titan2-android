// SPDX-License-Identifier: GPL-3.0-or-later
// SYM page / Alt layer editor (like the Plektra SymCustomizationScreen): key -> character.
// Writes ~/.config/plektra-linux/mappings/<file> in the Plektra format; "Reset" deletes it.
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
    readonly property var rows: ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"]
    title: app.t(item.title)

    function load() {
        app.run("getmap " + item.file, function (out) {
            try {
                var d = JSON.parse(out)
                var m = {}
                for (var k in d.mappings) m[k.replace("KEYCODE_", "")] = d.mappings[k]
                map = m
                fromUser = d.user
            } catch (e) { console.warn(e, out) }
        })
    }
    function save() {
        var out = {}
        for (var k in map) if (map[k] !== "") out["KEYCODE_" + k] = map[k]
        app.run("setmaphex " + item.file + " " + app.hex(JSON.stringify(out)),
                function () { fromUser = true })
    }
    Component.onCompleted: load()

    FormCard.FormHeader {
        title: app.tr("Tap a key cell and type the character(s) it should insert",
                      "Нажмите на клавишу и введите символ(ы), которые она будет вставлять")
    }
    FormCard.FormCard {
        GridLayout {
            Layout.fillWidth: true
            Layout.margins: Kirigami.Units.largeSpacing
            columns: 10
            columnSpacing: Kirigami.Units.smallSpacing
            rowSpacing: Kirigami.Units.smallSpacing
            Repeater {
                model: 30
                delegate: QQC2.TextField {
                    id: cell
                    required property int index
                    readonly property string letter: {
                        var r = Math.floor(index / 10), c = index % 10
                        return c < pg.rows[r].length ? pg.rows[r][c] : ""
                    }
                    opacity: letter !== "" ? 1 : 0
                    enabled: letter !== ""
                    Layout.fillWidth: true
                    Layout.preferredHeight: Kirigami.Units.gridUnit * 2.6
                    horizontalAlignment: TextInput.AlignHCenter
                    font.pixelSize: Kirigami.Units.gridUnit * 1.1
                    text: pg.map[letter] || ""
                    onEditingFinished: {
                        if ((pg.map[letter] || "") === text) return
                        var m = Object.assign({}, pg.map)
                        m[letter] = text
                        pg.map = m
                        pg.save()
                    }
                    QQC2.Label {
                        anchors.right: parent.right
                        anchors.bottom: parent.bottom
                        anchors.margins: 2
                        text: cell.letter
                        opacity: 0.6
                        font.pixelSize: Kirigami.Units.gridUnit * 0.6
                    }
                }
            }
        }
    }
    FormCard.FormCard {
        Layout.topMargin: Kirigami.Units.largeSpacing
        FormCard.FormTextDelegate {
            text: pg.fromUser ? app.tr("Your table", "Своя таблица")
                              : app.tr("Plektra default table", "Таблица Plektra по умолчанию")
            description: app.tr("Changes apply immediately.", "Изменения применяются сразу.")
        }
        FormCard.FormButtonDelegate {
            text: app.tr("Reset to default", "Сбросить к умолчанию")
            icon.name: "edit-reset"
            enabled: pg.fromUser
            onClicked: app.run("resetmap " + item.file, function () { pg.load() })
        }
    }
}
