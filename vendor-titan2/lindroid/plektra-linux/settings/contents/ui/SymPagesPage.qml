// SPDX-License-Identifier: GPL-3.0-or-later
// "Configure SYM layers" (Plektra SymCustomizationScreen): page order, inclusion in the cycle,
// layer editing, auto-close.
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

    readonly property var all: ["device", "emoji", "symbols"]
    readonly property var info: ({
        device: {t: {en: "Device SYM Layer", ru: "Слой Device SYM"}, file: "device_sym_key_mappings.json"},
        emoji: {t: {en: "Emoji Layer", ru: "Слой эмодзи"}, file: "sym_key_mappings.json"},
        symbols: {t: {en: "Symbols Layer", ru: "Слой символов"}, file: "sym_key_mappings_page2.json"}})
    // order: enabled (as in the setting) + disabled
    property var enabled: app.rev >= 0 ? (app.value("sym_pages") || []) : []
    // cycle order: the saved sym_page_order; pages missing there go to the end
    property var order: {
        var r = app.rev, o = []
        ;(app.value("sym_page_order") || enabled).concat(enabled, all).forEach(p => {
            if (all.indexOf(p) >= 0 && o.indexOf(p) < 0) o.push(p)
        })
        return o
    }

    function save(order, enabled) {
        app.setMany({sym_pages: order.filter(p => enabled.indexOf(p) >= 0), sym_page_order: order})
    }

    FormCard.FormHeader { title: app.tr("Arrange SYM pages order", "Порядок страниц SYM") }
    FormCard.FormCard {
        FormCard.FormTextDelegate {
            text: app.tr("Use the arrows to set the cycle order. The switch only controls whether an item appears in the cycle.",
                         "Стрелками задайте порядок цикла. Переключатель лишь включает страницу в цикл.")
        }
        Repeater {
            model: pg.order
            delegate: RowLayout {
                id: row
                required property string modelData
                required property int index
                Layout.fillWidth: true
                Layout.margins: Kirigami.Units.smallSpacing
                QQC2.Label {
                    text: app.t(pg.info[row.modelData].t)
                    Layout.fillWidth: true
                    Layout.leftMargin: Kirigami.Units.largeSpacing
                }
                QQC2.ToolButton {
                    icon.name: "go-up"; enabled: row.index > 0
                    QQC2.ToolTip.text: app.tr("Move up", "Выше"); QQC2.ToolTip.visible: hovered
                    onClicked: { var o = pg.order.slice(); o.splice(row.index - 1, 0, o.splice(row.index, 1)[0]); pg.save(o, pg.enabled) }
                }
                QQC2.ToolButton {
                    icon.name: "go-down"; enabled: row.index < pg.order.length - 1
                    QQC2.ToolTip.text: app.tr("Move down", "Ниже"); QQC2.ToolTip.visible: hovered
                    onClicked: { var o = pg.order.slice(); o.splice(row.index + 1, 0, o.splice(row.index, 1)[0]); pg.save(o, pg.enabled) }
                }
                QQC2.ToolButton {
                    icon.name: "document-edit"
                    QQC2.ToolTip.text: app.tr("Edit layer", "Изменить слой"); QQC2.ToolTip.visible: hovered
                    onClicked: pg.stack.push(Qt.resolvedUrl("MapEditor.qml"), {app: app, stack: pg.stack,
                        item: {title: pg.info[row.modelData].t, file: pg.info[row.modelData].file}})
                }
                QQC2.Switch {
                    checked: pg.enabled.indexOf(row.modelData) >= 0
                    onToggled: {
                        var e = pg.enabled.filter(p => p !== row.modelData)
                        if (checked) e.push(row.modelData)
                        pg.save(pg.order, e)
                    }
                }
            }
        }
        FormCard.FormTextDelegate {
            text: app.tr("Clipboard and Emoji Picker panels", "Панели «Буфер обмена» и «Выбор эмодзи»")
            description: app.tr("Not available in Linux: use Klipper and the Plasma emoji selector (Meta+.).",
                                "В Linux нет: используйте Klipper и выбор эмодзи Plasma (Meta+.).")
        }
    }

    FormCard.FormHeader { title: app.tr("SYM behaviour and display", "Поведение и вид SYM") }
    FormCard.FormCard {
        FormCard.FormSwitchDelegate {
            text: app.tr("Auto-Close SYM Layout", "Автозакрытие раскладки SYM")
            description: app.tr("Automatically close SYM layout after inserting a character, pressing Alt, or pressing Enter. Does not close with Control or Shift.",
                                "Автоматически закрывать раскладку SYM после вставки символа, нажатия Alt или нажатия Enter. Не закрывается на CTRL или Shift.")
            checked: app.rev >= 0 && !!app.value("sym_auto_close")
            onToggled: app.set("sym_auto_close", checked)
        }
        FormCard.FormButtonDelegate {
            text: app.tr("Alt modifier binding", "Привязка модификатора Alt")
            description: app.tr("Choose which SYM layer Alt uses in Modifier settings.", "Какой слой SYM использует Alt — в настройках модификаторов.")
            onClicked: pg.stack.pop()
        }
    }
}
