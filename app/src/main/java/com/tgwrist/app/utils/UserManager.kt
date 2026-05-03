package com.tgwrist.app.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tgwrist.app.data.UserInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import com.tgwrist.app.data.UserInfoEvent

/**
 * UserManager 是一个单例对象，用于管理应用中的用户信息。
 * 它提供用户的增删改查操作，并支持多用户切换。
 * 用户数据会保存在 SharedPreferences 中，使用 Gson 进行序列化和反序列化。
 */
const val ActiveUserSwitch = "ActiveUserSwitch"
object UserManager {

    // SharedPreferences 文件名
    private const val PREFS_NAME = "user_prefs"
    // 存储用户列表的 key
    private const val KEY_USERS = "users"

    // SharedPreferences 实例，用于读写用户数据
    private lateinit var prefs: SharedPreferences
    // Gson 实例，用于对象与 JSON 的转换
    private val gson = Gson()

    // 内存中保存的用户列表
    private val _users = MutableStateFlow<List<UserInfo>>(emptyList())
    val users = _users.asStateFlow()

    /**
     * 初始化 UserManager，必须在使用前调用。
     * @param context 应用上下文
     */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadUsers()
    }

    /**
     * 从 SharedPreferences 中加载用户列表到内存。
     * 如果没有存储过用户，则初始化为空列表。
     */
    private fun loadUsers() {
        val json = prefs.getString(KEY_USERS, "[]")
        val type = object : TypeToken<List<UserInfo>>() {}.type
        _users.value = gson.fromJson<List<UserInfo>>(json, type) ?: emptyList()
    }

    /**
     * 将内存中的用户列表保存到 SharedPreferences。
     */
    private fun saveUsers() {
        prefs.edit {putString(KEY_USERS, gson.toJson(_users.value)) }
    }

    /**
     * 添加新用户并保存。
     * @param userId 用户唯一 ID
     * @param userName 用户名称
     * @return 创建的 UserInfo 对象
     */
    fun addUser(userId: Long, userName: String): UserInfo {
        val existing = _users.value.find { it.userId == userId }
        if (existing != null) {
            // 覆盖原有用户名
            _users.value = _users.value.map {
                if (it.userId == userId) it.copy(userName = userName) else it
            }
            saveUsers()
            return existing.copy(userName = userName)
        }

        val shouldActive = _users.value.isEmpty()
        val newUser = UserInfo(userId, userName, shouldActive)

        _users.value += newUser
        saveUsers()
        return newUser
    }

    /**
     * 更新指定用户的用户名。
     * @param userId 用户唯一 ID
     * @param newName 新的用户名称
     * @return 更新后的 UserInfo，如果用户不存在则返回 null
     */
    fun updateUserName(userId: Long, newName: String): UserInfo? {
        val existing = _users.value.find { it.userId == userId } ?: return null
        _users.value = _users.value.map {
            if (it.userId == userId) it.copy(userName = newName) else it
        }
        saveUsers()
        return existing.copy(userName = newName)
    }

    /**
     * 获取所有用户列表（只读）。
     */
    fun getUsers(): List<UserInfo> = _users.value

    /**
     * 获取当前激活的用户。
     * @return 如果没有激活用户，返回 null
     */
    fun getActiveUser(): UserInfo? = _users.value.find { it.isActive }

    /**
     * 切换当前激活用户。
     * 遍历所有用户，将指定 userId 的用户设为激活，其余设为非激活。
     * @param userId 要激活的用户 ID
     */
    fun switchActiveUser(userId: Long) {
        val targetUser = _users.value.find { it.userId == userId } ?: return

        _users.value = _users.value.map {
            it.copy(isActive = it.userId == userId)
        }

        // 发送用户切换事件
        GlobalEventBus.send(UserInfoEvent(targetUser.copy(isActive = true), ActiveUserSwitch))

        saveUsers()
    }

    /**
     * 删除指定用户。
     * @param userId 要删除的用户 ID
     */
    fun removeUser(userId: Long) {
        val newList = _users.value.filterNot { it.userId == userId }

        _users.value = if (newList.isNotEmpty() && newList.none { it.isActive }) {
            newList.mapIndexed { index, user ->
                user.copy(isActive = index == 0)
            }
        } else {
            newList
        }

        saveUsers()
    }

    /**
     * 更新指定用户的 pushReceiverId。
     * 如果 userId 为 null，则自动使用当前激活用户。
     *
     * @param userId 用户唯一 ID，可为 null
     * @param pushReceiverId 新的 pushReceiverId
     * @return 更新后的 UserInfo，如果找不到目标用户则返回 null
     */
    fun updatePushReceiverId(userId: Long? = null, pushReceiverId: Long): UserInfo? {
        val targetUserId = userId ?: getActiveUser()?.userId ?: return null
        val existing = _users.value.find { it.userId == targetUserId } ?: return null

        val updated = existing.copy(pushReceiverId = pushReceiverId)

        _users.value = _users.value.map {
            if (it.userId == targetUserId) updated else it
        }
        saveUsers()

        return updated
    }

    /**
     * 获取所有用户的 userId 数组。
     * @return LongArray
     */
    fun getAllUserIds(): LongArray {
        return _users.value.map { it.userId }.toLongArray()
    }
}
