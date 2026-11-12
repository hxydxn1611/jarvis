package com.assistant.core

enum class ActionType {
    OPEN_APP,
    CLICK_TEXT,
    CLICK_ID,
    TYPE_TEXT,
    SCROLL_DOWN,
    SCROLL_UP,
    GLOBAL_HOME,
    GLOBAL_BACK,
    GLOBAL_RECENTS,
    GLOBAL_NOTIFICATIONS,
    TAKE_SCREENSHOT,
    FLASHLIGHT,
    VOLUME_UP,
    VOLUME_DOWN,
    SPEAK
}

data class AssistantAction(
    val type: ActionType,
    val target: String? = null,
    val value: String? = null
)

