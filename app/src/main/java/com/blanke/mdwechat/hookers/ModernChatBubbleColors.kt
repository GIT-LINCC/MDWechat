package com.blanke.mdwechat.hookers

import com.blanke.mdwechat.config.HookConfig
import com.blanke.mdwechat.util.ChatBubbleStylePolicy
import com.blanke.mdwechat.util.ChatBubbleStylePolicy.Side

object ModernChatBubbleColors {
    fun palette(side: Side): ChatBubbleStylePolicy.BubblePalette {
        return ChatBubbleStylePolicy.bubblePalette(
            side,
            ChatBubbleStylePolicy.BubbleColorConfig(
                useCustomBubbleTint = HookConfig.is_hook_bubble_tint,
                leftBubbleTint = HookConfig.get_hook_bubble_tint_left,
                rightBubbleTint = HookConfig.get_hook_bubble_tint_right,
                useCustomTextColor = true,
                leftTextColor = HookConfig.get_hook_chat_text_color_left,
                rightTextColor = HookConfig.get_hook_chat_text_color_right
            )
        )
    }
}
