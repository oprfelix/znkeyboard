package dev.zain.znkeyboard.ime

/**
 * The toolbar's clipboard, phrases and help icons, drawn like the iOS keyboard's (SF Symbols
 * doc.on.clipboard, text.bubble and questionmark.circle) on a 24-unit grid. The same shapes are
 * res/drawable/ic_toolbar_*_24.xml, for the panels and the tools menu.
 */
internal object ToolbarIconPaths {
    const val CLIPBOARD_STROKE = "M6,7.6H13Q14.6,7.6 14.6,9.2V19.4Q14.6,21 13,21H6Q4.4,21 4.4,19.4V9.2Q4.4,7.6 6,7.6Z"
    const val CLIPBOARD_FILL = "M10.5,3H18.5Q20,3 20,4.5V15.5Q20,17 18.5,17H16.6V8.6Q16.6,5.6 13.6,5.6H10.5Z"
    const val PHRASES_STROKE = "M5.2,4H18.8Q21,4 21,6.2V14.4Q21,16.6 18.8,16.6H10.4L6.6,20V16.6H5.2Q3,16.6 3,14.4V6.2Q3,4 5.2,4Z M7.2,8.6H16.8M7.2,12.2H13.6"
    const val PHRASES_FILL = ""
    const val HELP_STROKE = "M21,12A9,9 0 1,1 3,12A9,9 0 1,1 21,12Z M9.4,9.4Q9.6,6.9 12,6.9Q14.6,6.9 14.6,9.3Q14.6,10.9 12.8,11.7Q12,12.1 12,13.4V13.9"
    const val HELP_FILL = "M13,17A1,1 0 1,1 11,17A1,1 0 1,1 13,17Z"
}
