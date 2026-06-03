package com.tgwrist.app.utils

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.tgwrist.app.R
import com.tgwrist.app.TGWrist
import org.drinkless.tdlib.TdApi

// 处理和简化消息
// includeCaption: 是否包含媒体消息的文字描述（用于相册分离媒体标签和描述文字）
fun handleAllMessages(
    context: Context = TGWrist.context,
    message: TdApi.Message? = null,
    messageContext: TdApi.MessageContent? = null,
    maxText: Int = 128,
    includeCaption: Boolean = true
): AnnotatedString {
    val content: TdApi.MessageContent = messageContext ?: message?.content
    ?: return buildAnnotatedString { append(context.getString(R.string.Unknown_message)) }

    // 蓝色高亮样式（用于媒体/系统消息标签）
    val labelStyle = SpanStyle(color = Color(context.getColor(R.color.blue)))

    // 构建“蓝色标签 + 可选 caption”的通用 helper
    fun AnnotatedString.Builder.appendLabel(labelRes: Int) {
        withStyle(style = labelStyle) {
            append(context.getString(labelRes))
        }
    }

    // 截断 caption 到 maxText 长度
    fun AnnotatedString.Builder.appendCaption(caption: String) {
        val trimmed = caption.replace('\n', ' ').trim()
        if (trimmed.isNotEmpty()) {
            append(" ")
            append(if (trimmed.length > maxText) trimmed.take(maxText) + "..." else trimmed)
        }
    }

    return when (content) {
        // 文本消息
        is TdApi.MessageText -> buildAnnotatedString {
            if (includeCaption) {
                val text = content.text.text.replace('\n', ' ')
                append(if (text.length > maxText) text.take(maxText) + "..." else text)
            }
        }
        // 图片消息
        is TdApi.MessagePhoto -> buildAnnotatedString {
            appendLabel(R.string.Photo)
            if (includeCaption) appendCaption(content.caption.text)
        }
        // 视频消息
        is TdApi.MessageVideo -> buildAnnotatedString {
            appendLabel(R.string.Video)
            if (includeCaption) appendCaption(content.caption.text)
        }
        // 语音留言消息
        is TdApi.MessageVoiceNote -> buildAnnotatedString {
            appendLabel(R.string.Voice)
            if (includeCaption) appendCaption(content.caption.text)
        }
        // 音频消息（音乐、MP3 等）
        is TdApi.MessageAudio -> buildAnnotatedString {
            appendLabel(R.string.msg_type_audio)
            if (includeCaption) appendCaption(content.caption.text)
        }
        // 动画消息（GIF / 无声 MP4）
        is TdApi.MessageAnimation -> buildAnnotatedString {
            appendLabel(R.string.Animation)
            if (includeCaption) appendCaption(content.caption.text)
        }
        // 文档/文件消息
        is TdApi.MessageDocument -> buildAnnotatedString {
            appendLabel(R.string.File)
            if (includeCaption) {
                val fileName = content.document.fileName.replace('\n', ' ').trim()
                val caption = content.caption.text.replace('\n', ' ').trim()
                val combined = listOf(fileName, caption).filter { it.isNotEmpty() }.joinToString(" ")
                if (combined.isNotEmpty()) {
                    append(" ")
                    append(if (combined.length > maxText) combined.take(maxText) + "..." else combined)
                }
            }
        }
        // 视频留言（圆形视频）
        is TdApi.MessageVideoNote -> buildAnnotatedString {
            appendLabel(R.string.msg_type_video_note)
        }
        // 动态表情符号（含动画的 emoji）
        is TdApi.MessageAnimatedEmoji -> buildAnnotatedString {
            if (content.emoji.isEmpty()) append(context.getString(R.string.Unknown_message))
            else append(content.emoji)
        }
        // 贴纸
        is TdApi.MessageSticker -> buildAnnotatedString {
            if (content.sticker.emoji.isEmpty()) append(context.getString(R.string.Unknown_message))
            else append(content.sticker.emoji)
        }
        // 通话记录消息
        is TdApi.MessageCall -> buildAnnotatedString {
            val text = when (content.discardReason) {
                is TdApi.CallDiscardReasonMissed -> context.getString(R.string.Missed_call)
                is TdApi.CallDiscardReasonDeclined -> context.getString(R.string.Declined_call)
                is TdApi.CallDiscardReasonDisconnected -> context.getString(R.string.Disconnected_client)
                is TdApi.CallDiscardReasonEmpty -> context.getString(R.string.Failed_call)
                is TdApi.CallDiscardReasonHungUp -> context.getString(R.string.Hung_up)
                else -> context.getString(R.string.Call)
            }
            withStyle(style = labelStyle) { append(text) }
        }
        // 联系人名片
        is TdApi.MessageContact -> buildAnnotatedString { appendLabel(R.string.msg_type_contact) }
        // 位置（含实时位置）
        is TdApi.MessageLocation -> buildAnnotatedString { appendLabel(R.string.msg_type_location) }
        // 兴趣点/场所
        is TdApi.MessageVenue -> buildAnnotatedString { appendLabel(R.string.msg_type_venue) }
        // 互动骰子动画
        is TdApi.MessageDice -> buildAnnotatedString { appendLabel(R.string.msg_type_dice) }
        // 质押骰子（特殊骰子）
        is TdApi.MessageStakeDice -> buildAnnotatedString { appendLabel(R.string.msg_type_stake_dice) }
        // 投票/测验
        is TdApi.MessagePoll -> buildAnnotatedString { appendLabel(R.string.msg_type_poll) }
        // HTML5 游戏
        is TdApi.MessageGame -> buildAnnotatedString { appendLabel(R.string.msg_type_game) }
        // 游戏新高分
        is TdApi.MessageGameScore -> buildAnnotatedString { appendLabel(R.string.msg_type_game_score) }
        // 转发的快拍
        is TdApi.MessageStory -> buildAnnotatedString { appendLabel(R.string.msg_type_story) }
        // 待办清单
        is TdApi.MessageChecklist -> buildAnnotatedString { appendLabel(R.string.msg_type_checklist) }
        // 清单新增任务
        is TdApi.MessageChecklistTasksAdded -> buildAnnotatedString { appendLabel(R.string.msg_type_checklist_tasks_added) }
        // 清单完成任务
        is TdApi.MessageChecklistTasksDone -> buildAnnotatedString { appendLabel(R.string.msg_type_checklist_tasks_done) }
        // 账单/发票
        is TdApi.MessageInvoice -> buildAnnotatedString { appendLabel(R.string.msg_type_invoice) }
        // 付费媒体
        is TdApi.MessagePaidMedia -> buildAnnotatedString { appendLabel(R.string.msg_type_paid_media) }

        // ===== 群组/聊天系统消息 =====
        // 创建基础群组
        is TdApi.MessageBasicGroupChatCreate -> buildAnnotatedString { appendLabel(R.string.msg_type_basic_group_create) }
        // 创建超级群组/频道
        is TdApi.MessageSupergroupChatCreate -> buildAnnotatedString { appendLabel(R.string.msg_type_supergroup_chat_create) }
        // 加入群组（添加成员）
        is TdApi.MessageChatAddMembers -> buildAnnotatedString { appendLabel(R.string.Joined_the_group) }
        // 通过邀请链接加入
        is TdApi.MessageChatJoinByLink -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_join_by_link) }
        // 入群申请被批准
        is TdApi.MessageChatJoinByRequest -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_join_by_request) }
        // 成员退出/被移除
        is TdApi.MessageChatDeleteMember -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_delete_member) }
        // 群头像更换
        is TdApi.MessageChatChangePhoto -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_change_photo) }
        // 群头像删除
        is TdApi.MessageChatDeletePhoto -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_delete_photo) }
        // 群名称更改
        is TdApi.MessageChatChangeTitle -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_change_title) }
        // 内容保护切换
        is TdApi.MessageChatHasProtectedContentToggled -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_protected_toggled) }
        // 请求关闭内容保护
        is TdApi.MessageChatHasProtectedContentDisableRequested -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_protected_disable_requested) }
        // 群主变更
        is TdApi.MessageChatOwnerChanged -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_owner_changed) }
        // 群主离开
        is TdApi.MessageChatOwnerLeft -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_owner_left) }
        // 设置聊天背景
        is TdApi.MessageChatSetBackground -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_set_background) }
        // 设置自动删除时间
        is TdApi.MessageChatSetMessageAutoDeleteTime -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_set_auto_delete) }
        // 切换聊天主题
        is TdApi.MessageChatSetTheme -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_set_theme) }
        // 分享聊天
        is TdApi.MessageChatShared -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_shared) }
        // 由基础群组升级而来
        is TdApi.MessageChatUpgradeFrom -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_upgrade_from) }
        // 升级为超级群组
        is TdApi.MessageChatUpgradeTo -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_upgrade_to) }
        // 给予 boost（助力）
        is TdApi.MessageChatBoost -> buildAnnotatedString { appendLabel(R.string.msg_type_chat_boost) }
        // 置顶消息
        is TdApi.MessagePinMessage -> buildAnnotatedString { appendLabel(R.string.msg_type_pin_message) }
        // 截屏
        is TdApi.MessageScreenshotTaken -> buildAnnotatedString { appendLabel(R.string.msg_type_screenshot_taken) }
        // 联系人加入 Telegram
        is TdApi.MessageContactRegistered -> buildAnnotatedString { appendLabel(R.string.msg_type_contact_registered) }
        // 自定义服务消息
        is TdApi.MessageCustomServiceAction -> buildAnnotatedString { appendLabel(R.string.msg_type_custom_service_action) }
        // 接近距离提醒
        is TdApi.MessageProximityAlertTriggered -> buildAnnotatedString { appendLabel(R.string.msg_type_proximity_alert_triggered) }
        // 机器人写入权限已允许
        is TdApi.MessageBotWriteAccessAllowed -> buildAnnotatedString { appendLabel(R.string.msg_type_bot_write_access_allowed) }

        // ===== 论坛话题相关 =====
        // 论坛话题创建
        is TdApi.MessageForumTopicCreated -> buildAnnotatedString { appendLabel(R.string.msg_type_forum_topic_created) }
        // 论坛话题编辑
        is TdApi.MessageForumTopicEdited -> buildAnnotatedString { appendLabel(R.string.msg_type_forum_topic_edited) }
        // 论坛话题关闭/重新开启
        is TdApi.MessageForumTopicIsClosedToggled -> buildAnnotatedString { appendLabel(R.string.msg_type_forum_topic_closed_toggled) }
        // 论坛话题隐藏/显示
        is TdApi.MessageForumTopicIsHiddenToggled -> buildAnnotatedString { appendLabel(R.string.msg_type_forum_topic_hidden_toggled) }

        // ===== 阅后即焚已过期消息 =====
        // 已过期照片
        is TdApi.MessageExpiredPhoto -> buildAnnotatedString { appendLabel(R.string.msg_type_expired_photo) }
        // 已过期视频
        is TdApi.MessageExpiredVideo -> buildAnnotatedString { appendLabel(R.string.msg_type_expired_video) }
        // 已过期视频留言
        is TdApi.MessageExpiredVideoNote -> buildAnnotatedString { appendLabel(R.string.msg_type_expired_video_note) }
        // 已过期语音留言
        is TdApi.MessageExpiredVoiceNote -> buildAnnotatedString { appendLabel(R.string.msg_type_expired_voice_note) }

        // ===== 视频群组通话相关 =====
        // 群组通话
        is TdApi.MessageGroupCall -> buildAnnotatedString { appendLabel(R.string.msg_type_group_call) }
        // 邀请加入视频通话
        is TdApi.MessageInviteVideoChatParticipants -> buildAnnotatedString { appendLabel(R.string.msg_type_invite_video_chat_participants) }
        // 视频通话结束
        is TdApi.MessageVideoChatEnded -> buildAnnotatedString { appendLabel(R.string.msg_type_video_chat_ended) }
        // 视频通话计划
        is TdApi.MessageVideoChatScheduled -> buildAnnotatedString { appendLabel(R.string.msg_type_video_chat_scheduled) }
        // 视频通话开始
        is TdApi.MessageVideoChatStarted -> buildAnnotatedString { appendLabel(R.string.msg_type_video_chat_started) }

        // ===== 礼物 / 抽奖 / 付费 =====
        // 礼物
        is TdApi.MessageGift -> buildAnnotatedString { appendLabel(R.string.msg_type_gift) }
        // 赠送 Premium
        is TdApi.MessageGiftedPremium -> buildAnnotatedString { appendLabel(R.string.msg_type_gifted_premium) }
        // 赠送星星
        is TdApi.MessageGiftedStars -> buildAnnotatedString { appendLabel(R.string.msg_type_gifted_stars) }
        // 赠送 TON
        is TdApi.MessageGiftedTon -> buildAnnotatedString { appendLabel(R.string.msg_type_gifted_ton) }
        // 抽奖
        is TdApi.MessageGiveaway -> buildAnnotatedString { appendLabel(R.string.msg_type_giveaway) }
        // 抽奖完成
        is TdApi.MessageGiveawayCompleted -> buildAnnotatedString { appendLabel(R.string.msg_type_giveaway_completed) }
        // 抽奖创建
        is TdApi.MessageGiveawayCreated -> buildAnnotatedString { appendLabel(R.string.msg_type_giveaway_created) }
        // 抽奖星星奖品
        is TdApi.MessageGiveawayPrizeStars -> buildAnnotatedString { appendLabel(R.string.msg_type_giveaway_prize_stars) }
        // 抽奖中奖名单
        is TdApi.MessageGiveawayWinners -> buildAnnotatedString { appendLabel(R.string.msg_type_giveaway_winners) }
        // Premium 礼品码
        is TdApi.MessagePremiumGiftCode -> buildAnnotatedString { appendLabel(R.string.msg_type_premium_gift_code) }
        // 升级版礼物
        is TdApi.MessageUpgradedGift -> buildAnnotatedString { appendLabel(R.string.msg_type_upgraded_gift) }
        // 升级版礼物购买报价
        is TdApi.MessageUpgradedGiftPurchaseOffer -> buildAnnotatedString { appendLabel(R.string.msg_type_upgraded_gift_purchase_offer) }
        // 升级版礼物报价被拒
        is TdApi.MessageUpgradedGiftPurchaseOfferRejected -> buildAnnotatedString { appendLabel(R.string.msg_type_upgraded_gift_purchase_offer_rejected) }
        // 升级版礼物已退款
        is TdApi.MessageRefundedUpgradedGift -> buildAnnotatedString { appendLabel(R.string.msg_type_refunded_upgraded_gift) }
        // 私聊消息价格变更
        is TdApi.MessageDirectMessagePriceChanged -> buildAnnotatedString { appendLabel(R.string.msg_type_direct_message_price_changed) }
        // 付费消息价格变更
        is TdApi.MessagePaidMessagePriceChanged -> buildAnnotatedString { appendLabel(R.string.msg_type_paid_message_price_changed) }
        // 付费消息退款
        is TdApi.MessagePaidMessagesRefunded -> buildAnnotatedString { appendLabel(R.string.msg_type_paid_messages_refunded) }

        // ===== Passport / 支付 =====
        // 收到 Passport 数据
        is TdApi.MessagePassportDataReceived -> buildAnnotatedString { appendLabel(R.string.msg_type_passport_data_received) }
        // 发送 Passport 数据
        is TdApi.MessagePassportDataSent -> buildAnnotatedString { appendLabel(R.string.msg_type_passport_data_sent) }
        // 支付退款
        is TdApi.MessagePaymentRefunded -> buildAnnotatedString { appendLabel(R.string.msg_type_payment_refunded) }
        // 支付成功（用户视角）
        is TdApi.MessagePaymentSuccessful -> buildAnnotatedString { appendLabel(R.string.msg_type_payment_successful) }
        // 支付成功（机器人视角）
        is TdApi.MessagePaymentSuccessfulBot -> buildAnnotatedString { appendLabel(R.string.msg_type_payment_successful_bot) }

        // ===== 用户共享 / 头像建议 =====
        // 共享用户
        is TdApi.MessageUsersShared -> buildAnnotatedString { appendLabel(R.string.msg_type_users_shared) }
        // 建议生日
        is TdApi.MessageSuggestBirthdate -> buildAnnotatedString { appendLabel(R.string.msg_type_suggest_birthdate) }
        // 建议头像
        is TdApi.MessageSuggestProfilePhoto -> buildAnnotatedString { appendLabel(R.string.msg_type_suggest_profile_photo) }

        // ===== 频道建议帖子机制 =====
        // 建议帖审批失败
        is TdApi.MessageSuggestedPostApprovalFailed -> buildAnnotatedString { appendLabel(R.string.msg_type_suggested_post_approval_failed) }
        // 建议帖通过
        is TdApi.MessageSuggestedPostApproved -> buildAnnotatedString { appendLabel(R.string.msg_type_suggested_post_approved) }
        // 建议帖被拒
        is TdApi.MessageSuggestedPostDeclined -> buildAnnotatedString { appendLabel(R.string.msg_type_suggested_post_declined) }
        // 建议帖已支付
        is TdApi.MessageSuggestedPostPaid -> buildAnnotatedString { appendLabel(R.string.msg_type_suggested_post_paid) }
        // 建议帖已退款
        is TdApi.MessageSuggestedPostRefunded -> buildAnnotatedString { appendLabel(R.string.msg_type_suggested_post_refunded) }

        // ===== Web App / 不支持的消息 =====
        // 收到小程序数据
        is TdApi.MessageWebAppDataReceived -> buildAnnotatedString { appendLabel(R.string.msg_type_web_app_data_received) }
        // 发送了小程序数据
        is TdApi.MessageWebAppDataSent -> buildAnnotatedString { appendLabel(R.string.msg_type_web_app_data_sent) }
        // 当前客户端不支持的消息
        is TdApi.MessageUnsupported -> buildAnnotatedString { appendLabel(R.string.msg_type_unsupported) }

        else -> buildAnnotatedString { append(context.getString(R.string.Unknown_message)) }
    }
}


/*
97 个类型逐项与 handleAllMessages.kt 中已有的 is TdApi.XXX -> 分支做了对照，全部已支持。
详细对照（按你给的顺序）：
#
类型
位置
1
TdApi.MessageAnimatedEmoji
handleAllMessages.kt:95
2
TdApi.MessageAnimation
handleAllMessages.kt:73
3
TdApi.MessageAudio
handleAllMessages.kt:68
4
TdApi.MessageBasicGroupChatCreate
handleAllMessages.kt:147
5
TdApi.MessageBotWriteAccessAllowed
handleAllMessages.kt:197
6
TdApi.MessageCall
handleAllMessages.kt:105
7
TdApi.MessageChatAddMembers
handleAllMessages.kt:151
8
TdApi.MessageChatBoost
handleAllMessages.kt:185
9
TdApi.MessageChatChangePhoto
handleAllMessages.kt:159
10
TdApi.MessageChatChangeTitle
handleAllMessages.kt:163
11
TdApi.MessageChatDeleteMember
handleAllMessages.kt:157
12
TdApi.MessageChatDeletePhoto
handleAllMessages.kt:161
13
TdApi.MessageChatHasProtectedContentDisableRequested
handleAllMessages.kt:167
14
TdApi.MessageChatHasProtectedContentToggled
handleAllMessages.kt:165
15
TdApi.MessageChatJoinByLink
handleAllMessages.kt:153
16
TdApi.MessageChatJoinByRequest
handleAllMessages.kt:155
17
TdApi.MessageChatOwnerChanged
handleAllMessages.kt:169
18
TdApi.MessageChatOwnerLeft
handleAllMessages.kt:171
19
TdApi.MessageChatSetBackground
handleAllMessages.kt:173
20
TdApi.MessageChatSetMessageAutoDeleteTime
handleAllMessages.kt:175
21
TdApi.MessageChatSetTheme
handleAllMessages.kt:177
22
TdApi.MessageChatShared
handleAllMessages.kt:179
23
TdApi.MessageChatUpgradeFrom
handleAllMessages.kt:181
24
TdApi.MessageChatUpgradeTo
handleAllMessages.kt:183
25
TdApi.MessageChecklist
handleAllMessages.kt:135
26
TdApi.MessageChecklistTasksAdded
handleAllMessages.kt:137
27
TdApi.MessageChecklistTasksDone
handleAllMessages.kt:139
28
TdApi.MessageContact
handleAllMessages.kt:117
29
TdApi.MessageContactRegistered
handleAllMessages.kt:191
30
TdApi.MessageCustomServiceAction
handleAllMessages.kt:193
31
TdApi.MessageDice
handleAllMessages.kt:123
32
TdApi.MessageDirectMessagePriceChanged
handleAllMessages.kt:261
33
TdApi.MessageDocument
handleAllMessages.kt:78
34
TdApi.MessageExpiredPhoto
handleAllMessages.kt:211
35
TdApi.MessageExpiredVideo
handleAllMessages.kt:213
36
TdApi.MessageExpiredVideoNote
handleAllMessages.kt:215
37
TdApi.MessageExpiredVoiceNote
handleAllMessages.kt:217
38
TdApi.MessageForumTopicCreated
handleAllMessages.kt:201
39
TdApi.MessageForumTopicEdited
handleAllMessages.kt:203
40
TdApi.MessageForumTopicIsClosedToggled
handleAllMessages.kt:205
41
TdApi.MessageForumTopicIsHiddenToggled
handleAllMessages.kt:207
42
TdApi.MessageGame
handleAllMessages.kt:129
43
TdApi.MessageGameScore
handleAllMessages.kt:131
44
TdApi.MessageGift
handleAllMessages.kt:233
45
TdApi.MessageGiftedPremium
handleAllMessages.kt:235
46
TdApi.MessageGiftedStars
handleAllMessages.kt:237
47
TdApi.MessageGiftedTon
handleAllMessages.kt:239
48
TdApi.MessageGiveaway
handleAllMessages.kt:241
49
TdApi.MessageGiveawayCompleted
handleAllMessages.kt:243
50
TdApi.MessageGiveawayCreated
handleAllMessages.kt:245
51
TdApi.MessageGiveawayPrizeStars
handleAllMessages.kt:247
52
TdApi.MessageGiveawayWinners
handleAllMessages.kt:249
53
TdApi.MessageGroupCall
handleAllMessages.kt:221
54
TdApi.MessageInviteVideoChatParticipants
handleAllMessages.kt:223
55
TdApi.MessageInvoice
handleAllMessages.kt:141
56
TdApi.MessageLocation
handleAllMessages.kt:119
57
TdApi.MessagePaidMedia
handleAllMessages.kt:143
58
TdApi.MessagePaidMessagePriceChanged
handleAllMessages.kt:263
59
TdApi.MessagePaidMessagesRefunded
handleAllMessages.kt:265
60
TdApi.MessagePassportDataReceived
handleAllMessages.kt:269
61
TdApi.MessagePassportDataSent
handleAllMessages.kt:271
62
TdApi.MessagePaymentRefunded
handleAllMessages.kt:273
63
TdApi.MessagePaymentSuccessful
handleAllMessages.kt:275
64
TdApi.MessagePaymentSuccessfulBot
handleAllMessages.kt:277
65
TdApi.MessagePhoto
handleAllMessages.kt:53
66
TdApi.MessagePinMessage
handleAllMessages.kt:187
67
TdApi.MessagePoll
handleAllMessages.kt:127
68
TdApi.MessagePremiumGiftCode
handleAllMessages.kt:251
69
TdApi.MessageProximityAlertTriggered
handleAllMessages.kt:195
70
TdApi.MessageRefundedUpgradedGift
handleAllMessages.kt:259
71
TdApi.MessageScreenshotTaken
handleAllMessages.kt:189
72
TdApi.MessageStakeDice
handleAllMessages.kt:125
73
TdApi.MessageSticker
handleAllMessages.kt:100
74
TdApi.MessageStory
handleAllMessages.kt:133
75
TdApi.MessageSuggestBirthdate
handleAllMessages.kt:283
76
TdApi.MessageSuggestedPostApprovalFailed
handleAllMessages.kt:289
77
TdApi.MessageSuggestedPostApproved
handleAllMessages.kt:291
78
TdApi.MessageSuggestedPostDeclined
handleAllMessages.kt:293
79
TdApi.MessageSuggestedPostPaid
handleAllMessages.kt:295
80
TdApi.MessageSuggestedPostRefunded
handleAllMessages.kt:297
81
TdApi.MessageSuggestProfilePhoto
handleAllMessages.kt:285
82
TdApi.MessageSupergroupChatCreate
handleAllMessages.kt:149
83
TdApi.MessageText
handleAllMessages.kt:46
84
TdApi.MessageUnsupported
handleAllMessages.kt:305
85
TdApi.MessageUpgradedGift
handleAllMessages.kt:253
86
TdApi.MessageUpgradedGiftPurchaseOffer
handleAllMessages.kt:255
87
TdApi.MessageUpgradedGiftPurchaseOfferRejected
handleAllMessages.kt:257
88
TdApi.MessageUsersShared
handleAllMessages.kt:281
89
TdApi.MessageVenue
handleAllMessages.kt:121
90
TdApi.MessageVideo
handleAllMessages.kt:58
91
TdApi.MessageVideoChatEnded
handleAllMessages.kt:225
92
TdApi.MessageVideoChatScheduled
handleAllMessages.kt:227
93
TdApi.MessageVideoChatStarted
handleAllMessages.kt:229
94
TdApi.MessageVideoNote
handleAllMessages.kt:91
95
TdApi.MessageVoiceNote
handleAllMessages.kt:63
96
TdApi.MessageWebAppDataReceived
handleAllMessages.kt:301
97
TdApi.MessageWebAppDataSent
handleAllMessages.kt:303
*/
