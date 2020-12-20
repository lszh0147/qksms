/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.interactor

import android.os.Environment
import android.telephony.SmsMessage
import android.util.Log
import com.moez.QKSMS.blocking.BlockingClient
import com.moez.QKSMS.extensions.mapNotNull
import com.moez.QKSMS.manager.NotificationManager
import com.moez.QKSMS.manager.ShortcutManager
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.repository.MessageRepository
import com.moez.QKSMS.util.Preferences
import io.reactivex.Flowable
import timber.log.Timber
import java.io.*
import java.util.regex.Matcher
import java.util.regex.Pattern
import javax.inject.Inject

var blockList: String? = null
var blockListNumber: String? = null

class ReceiveSms @Inject constructor(
        private val conversationRepo: ConversationRepository,
        private val blockingClient: BlockingClient,
        private val prefs: Preferences,
        private val messageRepo: MessageRepository,
        private val notificationManager: NotificationManager,
        private val updateBadge: UpdateBadge,
        private val shortcutManager: ShortcutManager
) : Interactor<ReceiveSms.Params>() {

    init {
        if (blockList == null) {
            try {
                var file = File(Environment.getExternalStorageDirectory().absolutePath + "/Android/AppData/QKSMS/blockList.txt")
                Log.d("拦截", "读取 关键字 配置表：file=" + file);
                if (file.exists() && file.canRead()) {
                    var bufferedReader = BufferedReader(FileReader(file))
                    var line: String? = null
                    var totalLine = StringBuffer()
                    while (bufferedReader.readLine().also { line = it } != null) {
                        totalLine.append(line)
                    }
                    bufferedReader.close()

                    blockList = totalLine.toString()
                    if (blockList != null) {
                        Log.d("拦截", "关键字 配置文件内容= " + blockList);
                    } else {
                        Log.d("拦截", "关键字 配置文件 Android/AppData/QKSMS/blockList.txt 为空！");
                    }


                } else {
                    file.parentFile.mkdirs()
                    file.createNewFile()
                    Log.d("拦截", "关键字 配置文件 Android/AppData/QKSMS/blockList.txt 不存在！");
                    Log.d("拦截", "--------创建Android/AppData/QKSMS/blockList.txt 成功！-------");
                }

            } catch (e: Exception) {
                Log.d("拦截", "读取Android/AppData/QKSMS/blockList.txt 出错=" + e);
            }
        }
        if (blockListNumber == null) {
            try {
                var file = File(Environment.getExternalStorageDirectory().absolutePath + "/Android/AppData/QKSMS/blockListNumber.txt")
                Log.d("拦截", "读取 号码 配置表：file=" + file);
                if (file.exists() && file.canRead()) {
                    var bufferedReader = BufferedReader(FileReader(file))
                    var line: String? = null
                    var totalLine = StringBuffer()
                    while (bufferedReader.readLine().also { line = it } != null) {
                        totalLine.append(line)
                    }
                    bufferedReader.close()

                    blockListNumber = totalLine.toString()
                    if (blockListNumber != null) {
                        Log.d("拦截", "号码 配置文件内容= " + blockListNumber);
                    } else {
                        Log.d("拦截", "号码 配置文件为空！");
                    }


                } else {
                    file.parentFile.mkdirs()
                    file.createNewFile()
                    Log.d("拦截", "号码  配置文件不存在 或不可读");
                    Log.d("拦截", "--------创建 Android/AppData/QKSMS/blockListNumber.txt 配置文件成功！-------");
                }

            } catch (e: Exception) {
                Log.d("拦截", "读取 号码   配置出错=" + e);
            }
        }
    }

    class Params(val subId: Int, val messages: Array<SmsMessage>)

    override fun buildObservable(params: Params): Flowable<*> {
        return Flowable.just(params)
                .filter { it.messages.isNotEmpty() }
                .mapNotNull {
                    // Don't continue if the sender is blocked
                    val messages = it.messages
                    val address = messages[0].displayOriginatingAddress
                    val body: String = messages
                            .mapNotNull { message -> message.displayMessageBody }
                            .reduce { body, new -> body + new }

                    Log.d("拦截", "收到消息：address=" + address);

                    val action = blockingClient.getAction(address).blockingGet()
                    val shouldDrop = prefs.drop.get()
                    Timber.v("block=$action, drop=$shouldDrop")

                    // If we should drop the message, don't even save it
                    if (action is BlockingClient.Action.Block && shouldDrop) {
                        return@mapNotNull null
                    }

                    val time = messages[0].timestampMillis
                    // Add the message to the db
                    val message = messageRepo.insertReceivedSms(it.subId, address, body, time)
                    when (action) {
                        is BlockingClient.Action.Block -> {
                            Log.d("拦截", "号码拦截： 成功")
                            messageRepo.markRead(message.threadId)
                            conversationRepo.markBlocked(listOf(message.threadId), prefs.blockingManager.get(), action.reason)
                        }
                        is BlockingClient.Action.Unblock -> {
                            Log.d("拦截", "号码拦截： 失败，无匹配，进行关键词拦截")
                            conversationRepo.markUnblocked(message.threadId)
                        }
                        else -> Unit
                    }

                    if (action is BlockingClient.Action.Unblock) {
                        var shuldBlockNumber = true
                        if (blockList != null && blockList!!.length > 0) {
                            val pattern: Pattern = Pattern.compile(blockList!!)
                            val matcher: Matcher = pattern.matcher(body)
                            if (matcher.find()) {
                                Log.d("拦截", "关键字拦截：成功");
                                shuldBlockNumber = false
                                messageRepo.markRead(message.threadId)
                                conversationRepo.markBlocked(listOf(message.threadId), prefs.blockingManager.get(), "关键字")
                            } else {
                                Log.d("拦截", "关键字拦截：失败，无匹配");
                            }
                        } else {
                            Log.d("拦截", "关键字拦截：失败，配置文件内容为空");
                        }

                        var shouldWirteToLog = false
                        var failNumber = "null"
                        if (shuldBlockNumber) {
                            Log.d("拦截", "关键字拦截： 失败，进行开头号码拦截")
                            if (blockListNumber != null && blockListNumber!!.length > 0) {
                                var list = blockListNumber!!.split("|")
                                Log.d("拦截", "开头号码拦截： list.size)=" + list.size)
                                for (start: String in list) {
                                    Log.d("拦截", "开头号码拦截： 号码=" + address + " , 开头=" + start)
                                    if (address.startsWith(start)) {
                                        Log.d("拦截", "开头号码拦截：成功");
                                        messageRepo.markRead(message.threadId)
                                        conversationRepo.markBlocked(listOf(message.threadId), prefs.blockingManager.get(), "关键字")
                                        shouldWirteToLog = false
                                        break;
                                    } else {
                                        shouldWirteToLog = true
                                        failNumber = address
                                    }
                                }
                            } else {
                                shouldWirteToLog = false
                                Log.d("拦截", "开头号码拦截：失败，配置文件内容为空");
                            }
                        }

                        if (shouldWirteToLog){
                            Log.d("拦截", "开头号码拦截：失败 无匹配 写入log");
                            try {
                                var file = File(Environment.getExternalStorageDirectory().absolutePath + "/Android/AppData/QKSMS/numberCompareLog.txt")
                                if (!file.exists()) {
                                    file.parentFile.mkdirs()
                                    file.createNewFile()
                                }
                                var bufferedWirter = BufferedWriter(FileWriter(file, true))
                                bufferedWirter.append(failNumber + "\n")
                                bufferedWirter.flush()
                                bufferedWirter.close()
                            } catch (e: Exception) {
                                Log.d("拦截", "写入Log文件出错=" + e);
                            }
                        }


                    }
                    message
                }
                .doOnNext { message ->
                    conversationRepo.updateConversations(message.threadId) // Update the conversation
                }
                .mapNotNull { message ->
                    conversationRepo.getOrCreateConversation(message.threadId) // Map message to conversation
                }
                .filter { conversation -> !conversation.blocked } // Don't notify for blocked conversations
                .doOnNext { conversation ->
                    // Unarchive conversation if necessary
                    if (conversation.archived) conversationRepo.markUnarchived(conversation.id)
                }
                .map { conversation -> conversation.id } // Map to the id because [delay] will put us on the wrong thread
                .doOnNext { threadId -> notificationManager.update(threadId) } // Update the notification
                .doOnNext { shortcutManager.updateShortcuts() } // Update shortcuts
                .flatMap { updateBadge.buildObservable(Unit) } // Update the badge and widget
    }

}
