package io.github.lhd980820.coup.multiplayer

import android.content.Context

/**
 * 사용자가 마지막으로 들어가 있던 방(기기에 저장). 앱이 꺼졌다가 다시 켜지면 그 방으로 돌아가고, 방이 진행 중인 게임을 이어 준다(ADR 0012).
 * 계정마다 따로 기억한다. 다른 기기에서 이어 하기는 하지 않는다.
 */
internal interface ActiveRoomStore {
    fun get(uid: String): String?
    fun set(uid: String, roomId: String?)
}

internal class InMemoryActiveRoomStore : ActiveRoomStore {
    private val rooms = mutableMapOf<String, String>()
    override fun get(uid: String): String? = synchronized(rooms) { rooms[uid] }
    override fun set(uid: String, roomId: String?) {
        synchronized(rooms) { if (roomId == null) rooms.remove(uid) else rooms[uid] = roomId }
    }
}

internal class PrefsActiveRoomStore(context: Context) : ActiveRoomStore {
    private val prefs = context.getSharedPreferences("active_room", Context.MODE_PRIVATE)
    override fun get(uid: String): String? = prefs.getString(uid, null)
    override fun set(uid: String, roomId: String?) {
        prefs.edit().apply { if (roomId == null) remove(uid) else putString(uid, roomId) }.apply()
    }
}
